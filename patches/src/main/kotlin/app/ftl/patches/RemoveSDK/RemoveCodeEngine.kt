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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

/*
 * Port of SmaliScissors' [REMOVE_CODE] engine onto dexlib2/Morphe.
 * Given a list of type folder prefixes (e.g. "Lcom/google/android/gms/ads/"):
 *
 *   1. Mark every class under those prefixes for deletion.
 *   2. Iteratively scan every surviving class for references to target types.
 *   3. Surgical edits: void invokes, field reads (replaced with zero), field writes,
 *      check-cast (replaced with null), instance-of (replaced with false).
 *      These edits are only applied if the target instruction is NOT inside a protected try block.
 *   4. Fallback: If surgical editing isn't possible (e.g. non-void invoke, <init>, or target
 *      inside a try/catch block), stub the WHOLE containing method.
 *   5. Drop declared fields whose type is a target.
 *   6. Rewrite extends/implements for classes whose superclass/interface is a target.
 *   7. Remove now-empty <clinit> bodies.
 *   8. Delete every class marked in step 1 directly from the dex (classMap reflection,
 *      forcing BytecodeMode.FULL so STRIP modes can't silently keep it).
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

@Suppress("UNCHECKED_CAST")
private fun clearTryBlocks(implementation: MutableMethodImplementation) {
    val field = MutableMethodImplementation::class.java.getDeclaredField("tryBlocks")
        .apply { isAccessible = true }
    (field.get(implementation) as MutableList<*>).clear()
}

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
                    continue
                }

                val impl = mutableMethod.implementation ?: continue
                val insns = impl.instructions.toList()

                val targetIndices = mutableListOf<Int>()
                var allSurgical = true
                val surgicalEdits = ArrayList<Pair<Int, String?>>()

                for ((index, insn) in insns.withIndex()) {
                    if (!insn.referencesTarget(prefixes)) continue
                    targetIndices.add(index)

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
                        else -> {
                            allSurgical = false
                        }
                    }
                }

                // Precision Try/Catch: only disable surgical if target is inside a protected range
                if (allSurgical && impl.tryBlocks.isNotEmpty()) {
                    val targetAddresses = targetIndices.map { insns[it].codeAddress }
                    val isInsideTry = impl.tryBlocks.any { tb ->
                        val start = tb.startAddress
                        val end = start + tb.codeUnitCount
                        targetAddresses.any { addr -> addr >= start && addr < end }
                    }
                    if (isInsideTry) {
                        allSurgical = false
                    }
                }

                if (allSurgical && surgicalEdits.isNotEmpty()) {
                    surgicalEdits.sortedByDescending { it.first }.forEach { (index, smali) ->
                        if (smali == null) mutableMethod.removeInstruction(index)
                        else mutableMethod.replaceInstruction(index, smali)
                    }
                } else {
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
    }

    forceFullBytecodeMode()
    val classMap = internalClassMap()
    var removed = 0
    deletedClasses.forEach { if (classMap.remove(it) != null) removed++ }
    logger.info("removed $removed classes matching \"$tag\"")
}
