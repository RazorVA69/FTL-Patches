package app.ftl.patches.RemoveSDK

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

/*
 * Port of SmaliScissors' [REMOVE_CODE] engine onto dexlib2/Morphe.
 *
 * SmaliRemoveJob is not a single pass: it removes seed targets, cleans every remaining
 * reference to them, and if a method or class can't be cleaned without breaking, THAT
 * becomes a new target for the next round - a worklist run to a fixed point. This file
 * replicates that as a round loop: each round does exactly what the original single-pass
 * engine did, and a class that ends up with no meaningful body left because its
 * superclass/interface was a target is folded into the target set for the next round,
 * so classes that exist purely as SDK wrappers/listeners get removed too, and whatever
 * held a reference to THEM gets cleaned in turn.
 *
 * Separately, SmaliScissors' output includes classes that were never in any target list
 * at all - e.g. com.google.android.gms.internal.ads - because nothing outside the SDK's
 * own top-level classes ever calls into them; once the top-level layer is gone, they're
 * simply unreferenced. That is reference counting, not cascade (cascade cleans callers of
 * a deleted class; this removes callees of a deleted class that nothing else still needs),
 * and it is handled as a distinct sweep phase over an explicit SWEEP_ROOTS list, run only
 * after every round above has converged. Sweep roots must never be able to overlap with
 * TARGETS entries that the app itself calls directly (e.g. the public
 * com/google/android/gms/ads/ API surface) - those have to stay hard targets, cleaned via
 * the cascade above, precisely because the app DOES hold live references to them and a
 * reference-count sweep would just find those references and keep them.
 *
 * Deliberate simplification vs. SmaliScissors: it tracks per-opcode register dataflow to
 * surgically excise a target reference from a method that also does unrelated work,
 * keeping the rest of that method intact. That is not replicated here - any instruction
 * outside the two provably-safe shapes below stubs the WHOLE containing method instead.
 * In practice this is the shape of almost every SDK-init/SDK-embedding call site anyway;
 * genuinely mixed methods (SDK call interleaved with real app logic) lose that unrelated
 * logic too when stubbed. Matching, unlike SmaliScissors' raw substring search over smali
 * text, is reference-type-exact, so a string literal that happens to contain a target
 * package name is never a false match.
 *
 * Any method with a pre-existing try/catch block (regardless of whether it references a
 * target) is also routed to the whole-method stub rather than the surgical path, and that
 * path clears the method's try blocks before wiping its instructions. Shrinking a try
 * range around one removed/replaced instruction relies on dexlib2's label tracking, and
 * wiping a method's entire body is exactly the case that tracking does not recover from
 * cleanly - it can leave a stale try_item with a collapsed startAddr=0/endAddr=0 range
 * instead of dropping it, which is a hard VerifyError ("bad exception entry") at
 * class-load time. A stubbed body has nothing left to catch anyway.
 */

private fun String.isTarget(prefixes: Collection<String>) = prefixes.any { startsWith(it) }

private fun Instruction.referencesTarget(prefixes: Collection<String>): Boolean {
    val ref = (this as? ReferenceInstruction)?.reference ?: return false
    return when (ref) {
        is MethodReference -> ref.definingClass.isTarget(prefixes) ||
                ref.returnType.isTarget(prefixes) ||
                ref.parameterTypes.any { it.toString().isTarget(prefixes) }
        is FieldReference -> ref.definingClass.isTarget(prefixes) || ref.type.isTarget(prefixes)
        is TypeReference -> ref.type.isTarget(prefixes)
        else -> false
    }
}

private fun minimalReturnFor(returnType: String): String = when (returnType) {
    "V" -> "return-void"
    "Z", "B", "C", "S", "I", "F" -> "const/4 v0, 0x0\nreturn v0"
    "J", "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    else -> "const/4 v0, 0x0\nreturn-object v0"
}

private fun zeroLoadFor(reg: Int, type: String): String = when (type) {
    "J", "D" -> "const-wide/16 v$reg, 0x0"
    else -> "const/4 v$reg, 0x0"
}

/** getTryBlocks() wraps the backing list in Collections.unmodifiableList (verified against
 *  dexlib2 source - there is no public removal API, addCatch() is the only mutator and it's
 *  purely additive) - so .clear() on the getter's return value throws UnsupportedOperationException.
 *  Reflect on the private backing field directly instead; that ArrayList itself is genuinely
 *  mutable, only the accessor hides it. */
@Suppress("UNCHECKED_CAST")
private fun clearTryBlocks(implementation: MutableMethodImplementation) {
    val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
        .apply { isAccessible = true }
    (field.get(implementation) as MutableList<*>).clear()
}

/** Reflection: private classMap inside BytecodePatchContext.patchClasses. No public API for it. */
@Suppress("UNCHECKED_CAST")
private fun BytecodePatchContext.internalClassMap(): MutableMap<String, *> {
    val patchClasses = BytecodePatchContext::class.java
        .getDeclaredField("patchClasses")
        .apply { isAccessible = true }
        .get(this)
    return patchClasses.javaClass
        .getDeclaredField("classMap")
        .apply { isAccessible = true }
        .get(patchClasses) as MutableMap<String, *>
}

/** Reflection: STRIP modes only strip descriptors tracked as "modified" - a class dropped
 *  purely from classMap without ever going through mutableClassDefBy would survive into
 *  the output dex in those modes. Force FULL so removed types never get re-emitted. */
private fun BytecodePatchContext.forceFullBytecodeMode() {
    val config = BytecodePatchContext::class.java
        .getDeclaredField("config")
        .apply { isAccessible = true }
        .get(this)
    config.javaClass.getDeclaredField("bytecodeMode")
        .apply { isAccessible = true }
        .set(config, BytecodeMode.FULL)
}

private class RoundResult {
    var deletedMethods = 0
    var stubbedMethods = 0
    val orphanedClasses = HashSet<String>()
}

/** One full sweep over every surviving class - identical to the original single-pass
 *  engine - except a class that ends up with no meaningful body left, and only had one
 *  because its superclass/interface was a target, is reported back as newly orphaned so
 *  the caller can fold it into next round's target set (SmaliScissors cascade). */
private fun BytecodePatchContext.runRound(
    prefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
    logger: Logger,
): RoundResult {
    val result = RoundResult()

    classDefForEach classLoop@{ classDef ->
        if (classDef.type in deletedClasses) return@classLoop

        val superIsTarget = classDef.superclass?.isTarget(prefixes) == true
        val targetInterfaces = classDef.interfaces.filter { it.isTarget(prefixes) }
        val hasTargetField = classDef.fields.any { it.type.isTarget(prefixes) }
        val methodsNeedingWork = classDef.methods.filter { method ->
            method.returnType.isTarget(prefixes) ||
                    method.parameterTypes.any { it.toString().isTarget(prefixes) } ||
                    method.implementation?.let { impl ->
                        impl.instructions.any { it.referencesTarget(prefixes) } ||
                                impl.tryBlocks.any { tb ->
                                    tb.exceptionHandlers.any { it.exceptionType?.isTarget(prefixes) == true }
                                }
                    } == true
        }

        if (!superIsTarget && targetInterfaces.isEmpty() && !hasTargetField && methodsNeedingWork.isEmpty()) {
            return@classLoop
        }

        val mutableClass = mutableClassDefBy(classDef.type)
        val effectiveSuper = if (superIsTarget) "Ljava/lang/Object;" else (classDef.superclass ?: "Ljava/lang/Object;")

        if (targetInterfaces.isNotEmpty()) mutableClass.interfaces.removeAll(targetInterfaces)
        if (superIsTarget) mutableClass.setSuperClass("Ljava/lang/Object;")

        for (method in methodsNeedingWork) {
            val mutableMethod = mutableClass.methods.firstOrNull { m ->
                m.name == method.name && m.returnType == method.returnType &&
                        m.parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }
            } ?: continue

            val sigIsTarget = mutableMethod.returnType.isTarget(prefixes) ||
                    mutableMethod.parameterTypes.any { it.toString().isTarget(prefixes) }
            if (sigIsTarget) {
                mutableClass.methods.remove(mutableMethod)
                result.deletedMethods++
                logger.fine("delete method (signature): ${classDef.type}->${method.name}")
                continue
            }

            val impl = mutableMethod.implementation ?: continue
            val insns = impl.instructions.toList()

            // ANY pre-existing try/catch - target-related or not - disqualifies the
            // surgical path entirely; see the file header for why.
            var allSurgical = impl.tryBlocks.isEmpty()
            val surgicalEdits = ArrayList<Pair<Int, String?>>()   // index -> replacement smali, null = remove
            if (allSurgical) {
                for ((index, insn) in insns.withIndex()) {
                    if (!allSurgical) break
                    if (!insn.referencesTarget(prefixes)) continue
                    val opName = insn.opcode.name
                    when {
                        opName.startsWith("INVOKE_") -> {
                            val ref = (insn as ReferenceInstruction).reference as MethodReference
                            if (ref.returnType == "V" && ref.name != "<init>") {
                                surgicalEdits += index to null
                            } else {
                                allSurgical = false
                            }
                        }

                        opName.startsWith("SGET") || opName.startsWith("IGET") -> {
                            val fieldType = ((insn as ReferenceInstruction).reference as FieldReference).type
                            val reg = (insn as OneRegisterInstruction).registerA
                            surgicalEdits += index to zeroLoadFor(reg, fieldType)
                        }

                        opName.startsWith("SPUT") || opName.startsWith("IPUT") -> {
                            surgicalEdits += index to null
                        }

                        else -> allSurgical = false
                    }
                }
            }

            if (allSurgical && surgicalEdits.isNotEmpty()) {
                surgicalEdits.sortedByDescending { it.first }.forEach { (index, smali) ->
                    if (smali == null) mutableMethod.removeInstruction(index)
                    else mutableMethod.replaceInstruction(index, smali)
                }
            } else {
                result.stubbedMethods++
                logger.fine(
                    "stub method (${if (impl.tryBlocks.isEmpty()) "unsafe instruction" else "has try/catch"}): " +
                            "${classDef.type}->${mutableMethod.name}"
                )
                clearTryBlocks(impl)
                val count = mutableMethod.instructions.size
                mutableMethod.removeInstructions(0, count)
                when (mutableMethod.name) {
                    "<init>" -> mutableMethod.addInstructions(
                        0, "invoke-direct {p0}, $effectiveSuper-><init>()V\nreturn-void"
                    )

                    "<clinit>" -> mutableMethod.addInstructions(0, "return-void")
                    else -> mutableMethod.addInstructions(0, minimalReturnFor(mutableMethod.returnType))
                }
            }
        }

        if (superIsTarget) {
            mutableClass.methods.filter { it.name == "<init>" }.forEach { ctor ->
                val body = ctor.implementation ?: return@forEach
                val insns = body.instructions.toList()
                val superCallIdx = insns.indexOfFirst { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.name == "<init>" && ref.definingClass == classDef.superclass
                }
                if (superCallIdx != -1) {
                    ctor.replaceInstruction(superCallIdx, "invoke-direct {p0}, Ljava/lang/Object;-><init>()V")
                }
            }
        }

        if (hasTargetField) {
            mutableClass.fields.removeAll { it.type.isTarget(prefixes) }
        }

        val emptyClinit = mutableClass.methods.firstOrNull { m ->
            m.name == "<clinit>" && m.implementation?.instructions?.toList()
                ?.let { it.size == 1 && it[0].opcode == Opcode.RETURN_VOID } == true
        }
        if (emptyClinit != null) mutableClass.methods.remove(emptyClinit)

        // Nothing left but constructors, and the only reason this class existed at all was
        // to extend/implement a target - it's a pure SDK wrapper/listener shell now. Delete
        // it outright and hand its own type back as a new target: whatever still holds a
        // reference to it (a field, a listener registration, a new-instance call) gets
        // cleaned by the next round exactly like a reference to the original SDK class would.
        val hasRemainingBody = mutableClass.methods.any { it.name !in setOf("<init>", "<clinit>") } ||
                mutableClass.fields.isNotEmpty()
        if (!hasRemainingBody && (superIsTarget || targetInterfaces.isNotEmpty())) {
            result.orphanedClasses += classDef.type
        }
    }

    return result
}

/** Reference-count sweep, run only after the round loop above has converged. A class under
 *  one of [sweepPrefixes] is deleted once a full scan finds no surviving class (anywhere,
 *  not just among sweep candidates) still mentioning it - as a superclass, interface, field
 *  type, method signature type, or any instruction reference. Repeated to a fixed point:
 *  deleting one orphan can orphan another (e.g. a zzB that only zzA used, once zzA itself
 *  was just swept). Deliberately never applied to TARGETS - only to SWEEP_ROOTS, which by
 *  construction the caller has picked because nothing outside the SDK's own internals ever
 *  names them, so this can't accidentally eat a class the app (or the manifest) still needs. */
private fun BytecodePatchContext.sweepUnreferenced(
    sweepPrefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
): Int {
    if (sweepPrefixes.isEmpty()) return 0
    var totalSwept = 0

    while (true) {
        val stillReferenced = HashSet<String>()

        classDefForEach { classDef ->
            if (classDef.type in deletedClasses) return@classDefForEach
            classDef.superclass?.let { stillReferenced += it }
            stillReferenced += classDef.interfaces
            classDef.fields.forEach { stillReferenced += it.type }
            classDef.methods.forEach { method ->
                stillReferenced += method.returnType
                method.parameterTypes.forEach { stillReferenced += it.toString() }
                method.implementation?.instructions?.forEach { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference ?: return@forEach
                    when (ref) {
                        is MethodReference -> {
                            stillReferenced += ref.definingClass
                            stillReferenced += ref.returnType
                            ref.parameterTypes.forEach { stillReferenced += it.toString() }
                        }

                        is FieldReference -> {
                            stillReferenced += ref.definingClass
                            stillReferenced += ref.type
                        }

                        is TypeReference -> stillReferenced += ref.type
                    }
                }
                method.implementation?.tryBlocks?.forEach { tb ->
                    tb.exceptionHandlers.forEach { it.exceptionType?.let { t -> stillReferenced += t } }
                }
            }
        }

        val orphaned = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.type in deletedClasses) return@classDefForEach
            if (classDef.type.isTarget(sweepPrefixes) && classDef.type !in stillReferenced) {
                orphaned += classDef.type
            }
        }

        if (orphaned.isEmpty()) return totalSwept
        deletedClasses += orphaned
        totalSwept += orphaned.size
    }
}

/**
 * @param prefixes Hard targets: deleted unconditionally, every remaining reference to them
 *   cleaned (or the referencing method/class deleted in turn) over as many rounds as it
 *   takes to converge. Use for anything the app itself calls directly.
 * @param sweepPrefixes Soft targets: deleted only once nothing outside the already-deleted
 *   set still references them, checked after the round loop above has converged. Use only
 *   for SDK-internal implementation packages nothing else ever names directly - never for
 *   anything the app's own code, or the manifest, could plausibly reference.
 */
fun BytecodePatchContext.removeCodeByPrefix(
    tag: String,
    prefixes: List<String>,
    sweepPrefixes: List<String> = emptyList(),
) {
    val logger = Logger.getLogger("RemoveCode:$tag")
    val deletedClasses = HashSet<String>()
    val currentPrefixes = prefixes.toMutableSet()
    var deletedMethods = 0
    var stubbedMethods = 0
    var round = 0

    while (true) {
        round++

        classDefForEach { classDef ->
            if (classDef.type.isTarget(currentPrefixes)) deletedClasses += classDef.type
        }
        if (deletedClasses.isEmpty()) {
            logger.info("no matching classes")
            return
        }

        val result = runRound(currentPrefixes, deletedClasses, logger)
        deletedMethods += result.deletedMethods
        stubbedMethods += result.stubbedMethods

        val newlyOrphaned = result.orphanedClasses.filterNot { it in deletedClasses }
        if (newlyOrphaned.isEmpty()) break
        logger.fine("round $round: cascading ${newlyOrphaned.size} now-empty wrapper class(es)")
        deletedClasses += newlyOrphaned
        currentPrefixes += newlyOrphaned
    }

    val swept = sweepUnreferenced(sweepPrefixes, deletedClasses)

    forceFullBytecodeMode()
    val classMap = internalClassMap()
    var removed = 0
    deletedClasses.forEach { if (classMap.remove(it) != null) removed++ }
    logger.info(
        "\"$tag\": removed $removed classes ($swept via reachability sweep) over $round round(s), " +
                "deleted $deletedMethods methods (signature match), stubbed $stubbedMethods methods " +
                "(set Level.FINE on logger \"RemoveCode:$tag\" for the per-method list)"
    )
}
