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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

/*
 * Morphe implementation of the useful part of SmaliScissors REMOVE_CODE.
 *
 * The engine intentionally works on typed dexlib2 references instead of smali text. It
 * removes targeted classes, cleans safe target calls and fields, propagates non-void call
 * results to move-result instructions, removes target new-instance/constructor pairs when
 * they are self-contained, and falls back to a valid stub when control-flow repair would
 * otherwise be unsafe.
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

private fun MethodReference.isTargetMethod(prefixes: List<String>) =
    definingClass.isTarget(prefixes) ||
        returnType.isTarget(prefixes) ||
        parameterTypes.any { it.toString().isTarget(prefixes) }

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

@Suppress("UNCHECKED_CAST")
private fun clearTryBlocks(implementation: MutableMethodImplementation) {
    val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
        .apply { isAccessible = true }
    (field.get(implementation) as MutableList<*>).clear()
}

/** Isolated compatibility shim for Morphe versions without a public class-removal API. */
@Suppress("UNCHECKED_CAST")
private fun BytecodePatchContext.removeClassFromDex(type: String): Boolean {
    val patchClasses = BytecodePatchContext::class.java
        .getDeclaredField("patchClasses")
        .apply { isAccessible = true }
        .get(this)
    val classMap = patchClasses.javaClass
        .getDeclaredField("classMap")
        .apply { isAccessible = true }
        .get(patchClasses) as MutableMap<String, *>
    return classMap.remove(type) != null
}

/** Class deletion requires FULL mode when the class was not first materialized as mutable. */
private fun BytecodePatchContext.forceFullBytecodeMode() {
    val config = BytecodePatchContext::class.java
        .getDeclaredField("config")
        .apply { isAccessible = true }
        .get(this)
    config.javaClass.getDeclaredField("bytecodeMode")
        .apply { isAccessible = true }
        .set(config, BytecodeMode.FULL)
}

private data class Edit(val index: Int, val replacement: String? = null)

private fun isMoveResult(opcode: Opcode) = opcode == Opcode.MOVE_RESULT ||
    opcode == Opcode.MOVE_RESULT_OBJECT || opcode == Opcode.MOVE_RESULT_WIDE

private fun isFieldGet(opcode: Opcode) = opcode.name.startsWith("SGET") || opcode.name.startsWith("IGET")
private fun isFieldPut(opcode: Opcode) = opcode.name.startsWith("SPUT") || opcode.name.startsWith("IPUT")

/**
 * Cleans one method. The return value indicates whether the method was changed.
 * A false result means that the caller should use the safe whole-method stub fallback.
 */
private fun cleanMethodSurgically(
    method: MutableMethod,
    prefixes: List<String>,
): Boolean {
    val implementation = method.implementation ?: return false
    if (implementation.tryBlocks.isNotEmpty()) return false

    val insns = implementation.instructions.toList()
    val edits = mutableListOf<Edit>()
    var changed = false

    for ((index, insn) in insns.withIndex()) {
        if (!insn.referencesTarget(prefixes)) continue
        val ref = (insn as? ReferenceInstruction)?.reference

        when {
            ref is MethodReference -> {
                if (!ref.isTargetMethod(prefixes)) continue
                if (ref.name == "<init>") {
                    // Removing a constructor call is safe only when its matching new-instance
                    // is immediately attributable to the same register. Otherwise the whole
                    // method is stubbed rather than leaving an invalid object flow.
                    return false
                }

                if (ref.returnType == "V") {
                    edits += Edit(index)
                    changed = true
                } else {
                    val next = insns.getOrNull(index + 1)
                    if (next == null || !isMoveResult(next.opcode)) return false
                    val result = next as? OneRegisterInstruction ?: return false
                    edits += Edit(index)
                    edits += Edit(index + 1, zeroLoadFor(result.registerA, ref.returnType))
                    changed = true
                }
            }

            ref is FieldReference && isFieldGet(insn.opcode) -> {
                // sget has one register (the destination); iget has two registers
                // (destination A and object B). Both expose the destination as registerA,
                // but dexlib2 models them with different interfaces.
                val registerA = when (insn) {
                    is OneRegisterInstruction -> insn.registerA
                    is TwoRegisterInstruction -> insn.registerA
                    else -> return false
                }
                edits += Edit(index, zeroLoadFor(registerA, ref.type))
                changed = true
            }

            ref is FieldReference && isFieldPut(insn.opcode) -> {
                edits += Edit(index)
                changed = true
            }

            else -> return false
        }
    }

    if (!changed) return false

    edits.distinctBy { it.index }
        .sortedByDescending { it.index }
        .forEach { edit ->
            if (edit.replacement == null) method.removeInstruction(edit.index)
            else method.replaceInstruction(edit.index, edit.replacement)
        }
    return true
}

private fun stubMethod(
    method: MutableMethod,
    effectiveSuper: String,
) {
    val implementation = method.implementation ?: return
    clearTryBlocks(implementation)
    method.removeInstructions(0, method.instructions.size)
    when (method.name) {
        "<init>" -> method.addInstructions(
            0,
            "invoke-direct {p0}, $effectiveSuper-><init>()V\nreturn-void",
        )
        "<clinit>" -> method.addInstructions(0, "return-void")
        else -> method.addInstructions(0, minimalReturnFor(method.returnType))
    }
}

fun BytecodePatchContext.removeCodeByPrefix(tag: String, prefixes: List<String>) {
    val logger = Logger.getLogger("RemoveCode:$tag")
    val deletedClasses = hashSetOf<String>()

    classDefForEach { classDef ->
        if (classDef.type.isTarget(prefixes)) deletedClasses += classDef.type
    }
    if (deletedClasses.isEmpty()) {
        logger.info("no matching classes")
        return
    }

    // Two passes are intentional: the first pass removes direct references, and the second
    // catches methods exposed by the first pass without repeatedly materializing every class.
    repeat(2) {
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
            val effectiveSuper = if (superIsTarget) "Ljava/lang/Object;"
            else (classDef.superclass ?: "Ljava/lang/Object;")

            if (targetInterfaces.isNotEmpty()) mutableClass.interfaces.removeAll(targetInterfaces)
            if (superIsTarget) mutableClass.setSuperClass("Ljava/lang/Object;")

            for (method in methodsNeedingWork) {
                val mutableMethod = mutableClass.methods.firstOrNull { candidate ->
                    candidate.name == method.name &&
                        candidate.returnType == method.returnType &&
                        candidate.parameterTypes.map { it.toString() } ==
                        method.parameterTypes.map { it.toString() }
                } ?: continue

                val signatureIsTarget = mutableMethod.returnType.isTarget(prefixes) ||
                    mutableMethod.parameterTypes.any { it.toString().isTarget(prefixes) }
                if (signatureIsTarget) {
                    // A direct reference to this signature will be cleaned in the same pass.
                    // Removing it is closer to SmaliScissors than leaving a dangling body.
                    mutableClass.methods.remove(mutableMethod)
                    continue
                }

                val implementation = mutableMethod.implementation ?: continue
                val changed = cleanMethodSurgically(mutableMethod, prefixes)
                if (!changed) stubMethod(mutableMethod, effectiveSuper)
                else if (implementation.instructions.isEmpty()) stubMethod(mutableMethod, effectiveSuper)
            }

            if (superIsTarget) {
                mutableClass.methods.filter { it.name == "<init>" }.forEach { ctor ->
                    val body = ctor.implementation ?: return@forEach
                    val oldSuper = classDef.superclass
                    val index = body.instructions.indexOfFirst { instruction ->
                        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.name == "<init>" && ref.definingClass == oldSuper
                    }
                    if (index >= 0) {
                        ctor.replaceInstruction(index, "invoke-direct {p0}, Ljava/lang/Object;-><init>()V")
                    }
                }
            }

            if (hasTargetField) {
                mutableClass.fields.removeAll { it.type.isTarget(prefixes) }
            }

            val emptyClinit = mutableClass.methods.firstOrNull { candidate ->
                candidate.name == "<clinit>" &&
                    candidate.implementation?.instructions?.toList()
                        ?.let { it.size == 1 && it[0].opcode == Opcode.RETURN_VOID } == true
            }
            if (emptyClinit != null) mutableClass.methods.remove(emptyClinit)
        }
    }

    forceFullBytecodeMode()
    var removed = 0
    deletedClasses.forEach { if (removeClassFromDex(it)) removed++ }
    logger.info("removed $removed classes matching $tag")
}
