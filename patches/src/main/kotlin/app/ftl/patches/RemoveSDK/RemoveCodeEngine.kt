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
 * Port of SmaliScissors' [REMOVE_CODE] engine onto dexlib2/Morphe. Given a list of type
 * folder prefixes (e.g. "Lcom/google/android/gms/ads/"):
 *
 *   1. Mark every class under those prefixes for deletion.
 *   2. For every surviving class, scan every method for a reference to a target type
 *      (invoke definingClass/params/return, field definingClass/type, new-instance,
 *      check-cast, instance-of, new-array, filled-new-array, const-class, catch types):
 *        - method's OWN return/parameter type is a target -> delete the method outright.
 *          Any caller shares that exact signature in its invoke reference, so it gets
 *          caught and cleaned by this same pass - never left as a dangling call.
 *        - otherwise, if every target-referencing instruction in the method is one of
 *          the two provably-safe shapes (a non-<init> void invoke of a target method,
 *          or a read/write of a target-typed field) -> surgical single-instruction edit.
 *          Field reads are REPLACED with a same-width zero/null constant (not removed -
 *          the destination register may still be read later); field writes and void
 *          calls are removed outright (nothing downstream depends on them).
 *        - otherwise (non-void invoke, <init> invoke, new-instance/check-cast/
 *          instance-of/new-array/filled-new-array/const-class of a target, a catch
 *          block on a target exception type, or the method having ANY pre-existing
 *          try/catch block at all, target-related or not) -> whole-method stub, with
 *          the method's try blocks explicitly cleared first. Constructors keep a
 *          minimal super-call so the class stays verifiable.
 *   3. Drop declared fields whose type is a target - safe once every class has passed
 *      through step 2, since every read/write of such a field was already neutralized
 *      regardless of which class declared it.
 *   4. Rewrite extends/implements for classes whose superclass/interface is a target,
 *      fixing the affected <init> super-call to Ljava/lang/Object;-><init>()V.
 *   5. Remove now-empty <clinit> bodies.
 *   6. Delete every class marked in step 1 directly from the dex (classMap reflection,
 *      forcing BytecodeMode.FULL so STRIP modes can't silently keep it) - actually gone,
 *      not just gutted.
 *
 * Deliberate simplification vs. SmaliScissors: it tracks per-opcode register dataflow to
 * surgically excise a target reference from a method that also does unrelated work,
 * keeping the rest of that method intact. That is not replicated here - any instruction
 * outside the two provably-safe shapes above stubs the WHOLE containing method instead.
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

private fun String.isTarget(prefixes: List<String>) = prefixes.any { startsWith(it) }

private fun Instruction.referencesTarget(prefixes: List<String>): Boolean {
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

fun BytecodePatchContext.removeCodeByPrefix(tag: String, prefixes: List<String>) {
    val logger = Logger.getLogger("RemoveCode:$tag")
    val deletedClasses = HashSet<String>()
    var deletedMethods = 0
    var stubbedMethods = 0

    classDefForEach { classDef ->
        if (classDef.type.isTarget(prefixes)) deletedClasses += classDef.type
    }
    if (deletedClasses.isEmpty()) {
        logger.info("no matching classes")
        return
    }

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
                deletedMethods++
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
                stubbedMethods++
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
    }

    forceFullBytecodeMode()
    val classMap = internalClassMap()
    var removed = 0
    deletedClasses.forEach { if (classMap.remove(it) != null) removed++ }
    logger.info(
        "\"$tag\": removed $removed classes, deleted $deletedMethods methods (signature match), " +
                "stubbed $stubbedMethods methods (set Level.FINE on logger \"RemoveCode:$tag\" for the per-method list)"
    )
}
