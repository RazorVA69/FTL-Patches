package app.ftl.patches.RemoveSDK

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

/*
 * Port of SmaliScissors' [REMOVE_CODE] engine onto dexlib2/Morphe. Given a list of type
 * folder prefixes (e.g. "Lcom/google/android/gms/ads/"):
 *
 *   1. Mark every class under those prefixes for deletion.
 *   2. Iteratively (cascade loop) scan every surviving class for references to target types:
 *        - method's OWN return/parameter type is a target -> delete the method outright.
 *          Callers share that exact signature in their invoke reference, so they get
 *          caught and cleaned by this same pass - never left as a dangling call.
 *        - surgical single-instruction edits for provably-safe shapes: non-<init> void
 *          invokes (removed), target-typed field reads (replaced with same-width zero/null),
 *          field writes (removed), check-cast (replaced with null), instance-of (false).
 *          These are safe EVEN INSIDE try/catch ranges: dexlib2 try blocks are label-based,
 *          so removing/replacing one instruction shrinks or shifts the range automatically.
 *        - leftover catch handlers whose exception type is a target are dropped from the
 *          try-block list without touching the method body.
 *        - anything else (non-void invoke, <init>, new-instance, const-class, new-array...)
 *          -> whole-method stub, with the method's try blocks explicitly cleared first
 *          (wiping a body is the one case label tracking does not recover from cleanly -
 *          it can leave a stale try_item with a collapsed range, a hard VerifyError at
 *          class-load time). Constructors keep a minimal super-call so the class stays
 *          verifiable.
 *   3. Drop declared fields whose type is a target - safe once every class has passed
 *      through step 2, since every read/write of such a field was already neutralized
 *      regardless of which class declared it.
 *   4. Rewrite extends/implements for classes whose superclass/interface is a target,
 *      fixing the affected <init> super-call to Ljava/lang/Object;-><init>()V.
 *   5. Remove now-empty <clinit> bodies.
 *   6. Delete every marked class directly from the dex (classMap reflection, forcing
 *      BytecodeMode.FULL so STRIP modes can't silently re-emit it) - actually gone,
 *      not just gutted.
 *
 * If a surgical edit ever throws mid-application (dexlib2 label edge case), the method
 * falls back to the whole-body stub so the dex can never be left half-edited.
 *
 * Deliberate simplification vs. SmaliScissors: it tracks per-opcode register dataflow to
 * surgically excise a target reference from a method that also does unrelated work.
 * That is not replicated here - any instruction outside the provably-safe shapes above
 * stubs the WHOLE containing method instead. Matching, unlike SmaliScissors' raw substring
 * search over smali text, is reference-type-exact, so a string literal that happens to
 * contain a target package name is never a false match.
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

/** getTryBlocks() exposes no removal API (addCatch() is purely additive), so reflect on
 *  the private backing ArrayList and drop only the blocks catching target exception types. */
@Suppress("UNCHECKED_CAST")
private fun removeTargetTryBlocks(impl: MutableMethodImplementation, prefixes: List<String>): Boolean {
    val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
        .apply { isAccessible = true }
    val backing = field.get(impl) as MutableList<Any?>
    return backing.removeAll { tb ->
        (tb as? TryBlock<*>)?.exceptionHandlers
            ?.any { it.exceptionType?.isTarget(prefixes) == true } == true
    }
}

/** Wipe a method body completely. Try blocks MUST be cleared first: a fully erased body
 *  leaves stale/collapsed try_items otherwise ("bad exception entry" VerifyError). */
@Suppress("UNCHECKED_CAST")
private fun stubMethodBody(
    method: MutableMethod,
    impl: MutableMethodImplementation,
    effectiveSuper: String,
) {
    val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
        .apply { isAccessible = true }
    (field.get(impl) as MutableList<Any?>).clear()

    val count = method.instructions.size
    method.removeInstructions(0, count)
    when (method.name) {
        "<init>" -> method.addInstructions(
            0,
            "invoke-direct {p0}, $effectiveSuper-><init>()V\nreturn-void",
        )

        "<clinit>" -> method.addInstructions(0, "return-void")
        else -> method.addInstructions(0, minimalReturnFor(method.returnType))
    }
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

/** STRIP modes only re-emit classes tracked as "modified" and pass untouched original dex
 *  files through as-is - a class dropped purely from classMap would survive into the output
 *  dex in those modes. Force FULL so removed types never get re-emitted. */
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

    classDefForEach { classDef ->
        if (classDef.type.isTarget(prefixes)) deletedClasses += classDef.type
    }
    if (deletedClasses.isEmpty()) {
        logger.info("no matching classes")
        return
    }

    var cascadeChanged = true
    while (cascadeChanged) {
        cascadeChanged = false

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

            cascadeChanged = true

            val mutableClass = mutableClassDefBy(classDef.type)
            val effectiveSuper =
                if (superIsTarget) "Ljava/lang/Object;" else (classDef.superclass ?: "Ljava/lang/Object;")

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
                    continue
                }

                val impl = mutableMethod.implementation ?: continue
                val insns = impl.instructions.toList()

                val hasTargetCatch = impl.tryBlocks.any { tb ->
                    tb.exceptionHandlers.any { it.exceptionType?.isTarget(prefixes) == true }
                }

                // Only leftover catch handlers on deleted exception types, no live target
                // references left in the body: drop just those handlers, keep the body intact.
                if (!insns.any { it.referencesTarget(prefixes) }) {
                    if (hasTargetCatch) removeTargetTryBlocks(impl, prefixes)
                    continue
                }

                var allSurgical = true
                val surgicalEdits = ArrayList<Pair<Int, String?>>() // index -> replacement smali, null = remove
                for ((index, insn) in insns.withIndex()) {
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

                        opName == "CHECK_CAST" -> {
                            val reg = (insn as OneRegisterInstruction).registerA
                            surgicalEdits += index to "const/4 v$reg, 0x0"
                        }

                        opName == "INSTANCE_OF" -> {
                            val reg = (insn as TwoRegisterInstruction).registerA
                            surgicalEdits += index to "const/4 v$reg, 0x0"
                        }

                        else -> allSurgical = false
                    }
                }

                if (allSurgical && surgicalEdits.isNotEmpty()) {
                    runCatching {
                        surgicalEdits.sortedByDescending { it.first }.forEach { (index, smali) ->
                            if (smali == null) mutableMethod.removeInstruction(index)
                            else mutableMethod.replaceInstruction(index, smali)
                        }
                    }.onFailure {
                        logger.warning(
                            "surgical edit failed in ${mutableClass.type}->${mutableMethod.name}, " +
                                "falling back to stub: $it",
                        )
                        stubMethodBody(mutableMethod, impl, effectiveSuper)
                    }
                } else {
                    stubMethodBody(mutableMethod, impl, effectiveSuper)
                }
            }

            if (superIsTarget) {
                mutableClass.methods.filter { it.name == "<init>" }.forEach { ctor ->
                    val body = ctor.implementation ?: return@forEach
                    val bodyInsns = body.instructions.toList()
                    val superCallIdx = bodyInsns.indexOfFirst { insn ->
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
    }

    forceFullBytecodeMode()
    val classMap = internalClassMap()
    var removed = 0
    deletedClasses.forEach { if (classMap.remove(it) != null) removed++ }
    logger.info("removed $removed classes matching \"$tag\"")
}
