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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
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
 *   2. For every surviving class, scan every method for a reference to a target type
 *      (invoke definingClass/params/return, field definingClass/type, new-instance,
 *      check-cast, instance-of, new-array, filled-new-array, const-class, catch types):
 *        - method's OWN return/parameter type is a target -> delete the method outright.
 *          Any caller shares that exact signature in its invoke reference, so it gets
 *          caught and cleaned by this same pass - never left as a dangling call.
 *        - otherwise, any method with a pre-existing try/catch block (target-related or
 *          not) -> whole-method stub, try blocks cleared first (see below for why).
 *        - otherwise, every target-touching instruction is tentatively marked removable,
 *          plus the move-result immediately following an invoke that's marked removable.
 *          A register-escape check then asks, for every register a removable instruction
 *          writes: is that register read by anything NOT in the removable set? If every
 *          write stays fully contained inside the removable set, the whole block is
 *          excised and the rest of the method is untouched - this is what reproduces
 *          SmaliScissors' actual behaviour (e.g. a `new AdRequest.Builder().build()` then
 *          `.loadAd(...)` chain disappears as a unit, an unrelated caller of the same
 *          method keeps working). If any write escapes, or a target-typed constructor
 *          call's own new-instance isn't itself removable, the method falls back to a
 *          whole-method stub instead - a method mixing an SDK call with real app logic
 *          loses that unrelated logic when this fallback triggers, which is the one
 *          remaining gap versus a full dataflow port.
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
 * Matching: a folder-style prefix like "Lcom/google/android/gms/ads/AdView/" also matches
 * the bare leaf class itself (Lcom/google/android/gms/ads/AdView;) and its inner/synthetic
 * classes (Lcom/google/android/gms/ads/AdView$1;) - dex uses "$" for nesting, not "/", so a
 * literal-prefix-with-trailing-slash match alone silently misses both. This was verified
 * against a real APK: it's the reason AdView/AdRequest/AdRequest$Builder references were
 * being left completely untouched even though those exact folders are in the target lists.
 *
 * Any method with a pre-existing try/catch block (regardless of whether it references a
 * target) is routed straight to the whole-method stub, and that path clears the method's
 * try blocks before wiping its instructions. Shrinking a try range around one removed
 * instruction relies on dexlib2's label tracking, and wiping a method's entire body is
 * exactly the case that tracking does not recover from cleanly - it can leave a stale
 * try_item with a collapsed startAddr=0/endAddr=0 range instead of dropping it, which is a
 * hard VerifyError ("bad exception entry") at class-load time. A stubbed body has nothing
 * left to catch anyway.
 */

private fun String.isTarget(prefixes: List<String>): Boolean {
    for (raw in prefixes) {
        if (startsWith(raw)) return true
        if (raw.endsWith("/")) {
            val base = raw.substring(0, raw.length - 1)
            if (this == "$base;" || startsWith("$base\$")) return true
        }
    }
    return false
}

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

/** Every register this instruction touches, read or write alike - used only for the
 *  escape check, so a coarse read+write union is fine (and safer: treating a coincidental
 *  write to the same register number as a "use" only makes the check MORE conservative,
 *  never less). Formats not covered here (no register operand at all - goto, return-void,
 *  nop, ...) correctly contribute nothing. */
private fun Instruction.allRegisters(): Set<Int> {
    val regs = HashSet<Int>()
    when (this) {
        is RegisterRangeInstruction -> for (r in startRegister until startRegister + registerCount) regs += r
        is FiveRegisterInstruction -> {
            val c = registerCount
            if (c >= 1) regs += registerC
            if (c >= 2) regs += registerD
            if (c >= 3) regs += registerE
            if (c >= 4) regs += registerF
            if (c >= 5) regs += registerG
        }
        is ThreeRegisterInstruction -> { regs += registerA; regs += registerB; regs += registerC }
        is TwoRegisterInstruction -> { regs += registerA; regs += registerB }
        is OneRegisterInstruction -> regs += registerA
    }
    return regs
}

/** The register this instruction freshly defines, for the specific shapes that appear as
 *  seeds in the removable set. Plain invoke/iput/sput never define a numbered register
 *  directly (an invoke's result, if any, is captured by a separate move-result*), so they
 *  correctly return null - they contribute nothing to escape-check, which is right: they
 *  have nothing downstream could depend on other than via that separate move-result. */
private fun Instruction.definedRegister(): Int? = when {
    opcode.name.startsWith("MOVE_RESULT") -> (this as OneRegisterInstruction).registerA
    opcode == Opcode.NEW_INSTANCE || opcode == Opcode.CONST_CLASS || opcode == Opcode.CHECK_CAST ->
        (this as OneRegisterInstruction).registerA
    opcode == Opcode.INSTANCE_OF || opcode == Opcode.NEW_ARRAY -> (this as TwoRegisterInstruction).registerA
    else -> null
}

/** The register an invoke's own object argument sits in (its arg0/"this") - used only to
 *  trace an <init> call back to the new-instance that created the object being initialized. */
private fun Instruction.firstArgRegister(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> if (registerCount >= 1) registerC else null
    else -> null
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

private fun stubWholeMethod(
    mutableMethod: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
    impl: MutableMethodImplementation,
    effectiveSuper: String,
) {
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

fun BytecodePatchContext.removeCodeByPrefix(tag: String, prefixes: List<String>) {
    val logger = Logger.getLogger("RemoveCode:$tag")
    val deletedClasses = HashSet<String>()
    var deletedMethods = 0
    var stubbedMethods = 0
    var editedMethods = 0

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

            // ANY pre-existing try/catch - target-related or not - goes straight to the
            // whole-method stub; see the file header for why.
            if (impl.tryBlocks.isNotEmpty()) {
                stubbedMethods++
                logger.fine("stub method (has try/catch): ${classDef.type}->${mutableMethod.name}")
                stubWholeMethod(mutableMethod, impl, effectiveSuper)
                continue
            }

            val insns = impl.instructions.toList()
            val removable = BooleanArray(insns.size)
            val fieldReadEdits = HashMap<Int, String>()

            for ((index, insn) in insns.withIndex()) {
                if (!insn.referencesTarget(prefixes)) continue
                val opName = insn.opcode.name
                when {
                    opName.startsWith("INVOKE_") -> {
                        removable[index] = true
                        val next = insns.getOrNull(index + 1)
                        if (next != null && next.opcode.name.startsWith("MOVE_RESULT")) {
                            removable[index + 1] = true
                        }
                    }

                    opName.startsWith("SGET") || opName.startsWith("IGET") -> {
                        val fieldType = ((insn as ReferenceInstruction).reference as FieldReference).type
                        val reg = (insn as OneRegisterInstruction).registerA
                        fieldReadEdits[index] = zeroLoadFor(reg, fieldType)
                    }

                    opName.startsWith("SPUT") || opName.startsWith("IPUT") -> removable[index] = true

                    else -> removable[index] = true   // new-instance/check-cast/instance-of/
                    // new-array/filled-new-array/const-class
                }
            }

            if (!removable.any { it } && fieldReadEdits.isEmpty()) continue

            // Constructor safety: an <init> invoke may only be removed if the object it
            // initializes (its first argument register) was itself created by a new-instance
            // that is ALSO removable in this same pass. Otherwise removing just the <init>
            // call would leave a live, permanently-uninitialized reference behind for
            // whatever legitimately still uses it - a hard VerifyError. This only bites the
            // rare case of a non-target class whose constructor merely happens to take a
            // target-typed parameter; the ordinary case (both new-instance and <init> refer
            // to the same target class) is already self-consistent since both independently
            // match referencesTarget().
            var safe = true
            for ((index, insn) in insns.withIndex()) {
                if (!removable[index]) continue
                if (insn.opcode.name.startsWith("INVOKE_") &&
                    (insn as ReferenceInstruction).reference.let { it is MethodReference && it.name == "<init>" }
                ) {
                    val selfReg = insn.firstArgRegister()
                    val ownerIdx = selfReg?.let { reg ->
                        insns.take(index).indexOfLast {
                            it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == reg
                        }
                    } ?: -1
                    if (ownerIdx == -1 || !removable[ownerIdx]) {
                        safe = false
                        break
                    }
                }
            }

            // Escape check: for every register a removable instruction defines, is it read
            // by anything NOT in the removable set? If any write escapes, the whole block
            // can't be excised without risking a dangling/uninitialized value for whatever
            // still reads it outside - fall back to the whole-method stub.
            if (safe) {
                outer@ for ((index, insn) in insns.withIndex()) {
                    if (!removable[index]) continue
                    val written = insn.definedRegister() ?: continue
                    for ((j, other) in insns.withIndex()) {
                        if (removable[j]) continue
                        if (written in other.allRegisters()) {
                            safe = false
                            break@outer
                        }
                    }
                }
            }

            if (safe) {
                editedMethods++
                val ops = ArrayList<Pair<Int, String?>>()
                fieldReadEdits.forEach { (index, smali) -> ops += index to smali }
                removable.forEachIndexed { index, r -> if (r) ops += index to null }
                ops.sortedByDescending { it.first }.forEach { (index, smali) ->
                    if (smali == null) mutableMethod.removeInstruction(index)
                    else mutableMethod.replaceInstruction(index, smali)
                }
            } else {
                stubbedMethods++
                logger.fine("stub method (unsafe instruction): ${classDef.type}->${mutableMethod.name}")
                stubWholeMethod(mutableMethod, impl, effectiveSuper)
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
                "edited $editedMethods methods (surgical), stubbed $stubbedMethods methods " +
                "(set Level.FINE on logger \"RemoveCode:$tag\" for the per-method list)"
    )
}
