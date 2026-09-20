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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

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

private fun zeroLoadFor(reg: Int, type: String): String = when (type) {
    "J", "D" -> "const-wide/16 v$reg, 0x0"
    else -> "const/16 v$reg, 0x0"
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

private class RoundResult {
    var deletedMethods = 0
    var reducedConstructors = 0
    var stubbedMethods = 0
    var leftUntouched = 0
    var failedMethods = 0
    val orphanedClasses = HashSet<String>()
}

private fun BytecodePatchContext.runRound(
    prefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
    logger: Logger,
): RoundResult {
    val result = RoundResult()

    classDefForEach classLoop@{ classDef ->
        if (classDef.type in deletedClasses) return@classLoop

        val isKotlinLambda = classDef.superclass == "Lkotlin/jvm/internal/Lambda;" ||
                classDef.interfaces.any { it.startsWith("Lkotlin/jvm/functions/Function") }
        if (isKotlinLambda) return@classLoop

        val superIsTarget = classDef.superclass?.isTarget(prefixes) == true || classDef.superclass in deletedClasses
        val targetInterfaces = classDef.interfaces.filter { it.isTarget(prefixes) || it in deletedClasses }
        val hasTargetField = classDef.fields.any { it.type.isTarget(prefixes) || it.type in deletedClasses }
        val methodsNeedingWork = classDef.methods.filter { method ->
            method.returnType.isTarget(prefixes) || method.returnType in deletedClasses ||
                    method.parameterTypes.any { it.toString().isTarget(prefixes) || it.toString() in deletedClasses } ||
                    method.implementation?.let { impl ->
                        impl.instructions.any { it.referencesTarget(prefixes) } ||
                                impl.tryBlocks.any { tb ->
                                    tb.exceptionHandlers.any {
                                        val t = it.exceptionType?.toString() ?: ""
                                        t.isTarget(prefixes) || t in deletedClasses
                                    }
                                }
                    } == true
        }

        if (!superIsTarget && targetInterfaces.isEmpty() && !hasTargetField && methodsNeedingWork.isEmpty()) {
            return@classLoop
        }

        val mutableClass = mutableClassDefBy(classDef.type)

        var effectiveSuper = classDef.superclass ?: "Ljava/lang/Object;"
        if (superIsTarget) {
            var currentSuper = classDef.superclass
            while (currentSuper != null && (currentSuper.isTarget(prefixes) || currentSuper in deletedClasses)) {
                val superClassDef = classDefByOrNull(currentSuper)
                currentSuper = superClassDef?.superclass
            }
            effectiveSuper = currentSuper ?: "Ljava/lang/Object;"
            mutableClass.setSuperClass(effectiveSuper)
        }

        if (targetInterfaces.isNotEmpty()) mutableClass.interfaces.removeAll(targetInterfaces)

        val isProtectedTarget = classDef.type in manifestProtectedClasses && classDef.type.isTarget(prefixes)
        if (isProtectedTarget) {
            val constructors = mutableClass.methods.toList().filter { it.name == "<init>" }
            val hasNoArgConstructor = constructors.any { it.parameterTypes.isEmpty() }

            for (ctor in constructors) {
                val ctorParamWidth = ctor.parameterTypes.sumOf {
                    if (it.toString() == "J" || it.toString() == "D") 2 else 1
                }
                val ctorSigIsTarget = ctor.parameterTypes.any {
                    it.toString().isTarget(prefixes) || it.toString() in deletedClasses
                }

                if (ctorSigIsTarget && ctorParamWidth > 0 && hasNoArgConstructor) {
                    mutableClass.methods.remove(ctor)
                    result.deletedMethods++
                    continue
                }

                val impl = ctor.implementation ?: continue
                clearTryBlocks(impl)
                val originalCount = impl.instructions.size
                val requiredRegs = ctorParamWidth + 1
                if (impl.registerCount < requiredRegs) impl.registerCount = requiredRegs

                val thisReg = impl.registerCount - ctorParamWidth - 1
                val superCall = if (thisReg <= 15) {
                    "invoke-direct {v$thisReg}, $effectiveSuper-><init>()V"
                } else {
                    "invoke-direct/range {v$thisReg .. v$thisReg}, $effectiveSuper-><init>()V"
                }

                try {
                    ctor.addInstructions(0, superCall)
                    ctor.addInstructions(1, "return-void")
                    if (originalCount > 0) ctor.removeInstructions(2, originalCount)
                    result.reducedConstructors++
                } catch (e: Exception) {
                    result.failedMethods++
                    logger.severe("FAILED to reduce protected constructor ${classDef.type}-><init>: ${e.message}")
                }
            }

            for (method in methodsNeedingWork) {
                if (method.name == "<init>") continue
                val mutableMethod = mutableClass.methods.firstOrNull { m ->
                    m.name == method.name && m.returnType == method.returnType &&
                            m.parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }
                } ?: continue

                if (mutableMethod.name == "<clinit>") {
                    mutableClass.methods.remove(mutableMethod)
                    result.deletedMethods++
                    continue
                }

                val sigIsTarget = mutableMethod.returnType.isTarget(prefixes) ||
                        mutableMethod.returnType in deletedClasses ||
                        mutableMethod.parameterTypes.any { it.toString().isTarget(prefixes) || it.toString() in deletedClasses }

                if (sigIsTarget) {
                    mutableClass.methods.remove(mutableMethod)
                    result.deletedMethods++
                    continue
                }

                val impl = mutableMethod.implementation ?: continue
                clearTryBlocks(impl)
                val originalCount = impl.instructions.size
                val mParamWidth = mutableMethod.parameterTypes.sumOf {
                    if (it.toString() == "J" || it.toString() == "D") 2 else 1
                }
                val isStatic = (mutableMethod.accessFlags and 0x8) != 0
                val returnType = mutableMethod.returnType
                val requiredRegs = mParamWidth + (if (!isStatic) 1 else 0) +
                        when (returnType) { "V" -> 0; "J", "D" -> 2; else -> 1 }

                if (impl.registerCount < requiredRegs) impl.registerCount = requiredRegs

                val stubInstructions = when (returnType) {
                    "V" -> listOf("return-void")
                    "Z", "B", "S", "C", "I", "F" -> listOf("const/4 v0, 0x0", "return v0")
                    "J", "D" -> listOf("const-wide/16 v0, 0x0", "return-wide v0")
                    else -> listOf("const/4 v0, 0x0", "return-object v0")
                }

                try {
                    stubInstructions.forEachIndexed { offset, line -> mutableMethod.addInstructions(offset, line) }
                    if (originalCount > 0) mutableMethod.removeInstructions(stubInstructions.size, originalCount)
                    result.stubbedMethods++
                } catch (e: Exception) {
                    result.failedMethods++
                    logger.severe("FAILED to stub protected method ${classDef.type}->${mutableMethod.name}: ${e.message}")
                }
            }

            if (hasTargetField) mutableClass.fields.removeAll { it.type.isTarget(prefixes) || it.type in deletedClasses }
            val emptyClinit = mutableClass.methods.firstOrNull { m -> m.name == "<clinit>" && m.implementation?.instructions?.toList()?.let { it.size == 1 && it[0].opcode == Opcode.RETURN_VOID } == true }
            if (emptyClinit != null) mutableClass.methods.remove(emptyClinit)
            return@classLoop
        }

        for (method in methodsNeedingWork) {
            val mutableMethod = mutableClass.methods.firstOrNull { m ->
                m.name == method.name && m.returnType == method.returnType &&
                        m.parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }
            } ?: continue

            val sigIsTarget = mutableMethod.returnType.isTarget(prefixes) || mutableMethod.returnType in deletedClasses ||
                    mutableMethod.parameterTypes.any { it.toString().isTarget(prefixes) || it.toString() in deletedClasses }
            if (sigIsTarget) {
                mutableClass.methods.remove(mutableMethod)
                result.deletedMethods++
                continue
            }

            val impl = mutableMethod.implementation ?: continue
            val insns = impl.instructions.toList()

            if (insns.any { it.opcode.name.contains("SWITCH") }) continue

            var allSurgical = true
            val hadTryBlocks = impl.tryBlocks.isNotEmpty()
            val surgicalEdits = ArrayList<Pair<Int, String?>>()
            
            for ((index, insn) in insns.withIndex()) {
                if (!insn.referencesTarget(prefixes)) continue
                val opName = insn.opcode.name
                when {
                    opName.startsWith("INVOKE_") -> {
                        val ref = (insn as ReferenceInstruction).reference as MethodReference
                        val nextInsn = insns.getOrNull(index + 1)
                        val isMoveResult = nextInsn?.opcode?.name?.startsWith("MOVE_RESULT") == true
                        if (ref.name != "<init>") {
                            if (ref.returnType == "V" || !isMoveResult) {
                                surgicalEdits += index to "nop"
                            } else {
                                val destReg = (nextInsn as OneRegisterInstruction).registerA
                                val zeroLoad = zeroLoadFor(destReg, ref.returnType)
                                surgicalEdits += index to zeroLoad
                                surgicalEdits += (index + 1) to "nop"
                            }
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
                        surgicalEdits += index to "nop"
                    }
                    opName == "NEW_INSTANCE" || opName == "CONST_CLASS" -> {
                        val reg = (insn as OneRegisterInstruction).registerA
                        surgicalEdits += index to zeroLoadFor(reg, "L")
                    }
                    opName == "CHECK_CAST" -> {
                        surgicalEdits += index to "nop"
                    }
                    opName == "INSTANCE_OF" -> {
                        val destReg = (insn as TwoRegisterInstruction).registerA
                        surgicalEdits += index to zeroLoadFor(destReg, "Z")
                    }
                    else -> allSurgical = false
                }
            }

            if (allSurgical && surgicalEdits.isNotEmpty()) {
                if (hadTryBlocks) clearTryBlocks(impl)
                surgicalEdits.sortedByDescending { it.first }.forEach { (index, smali) ->
                    mutableMethod.replaceInstruction(index, smali!!)
                }
                continue
            }

            when (mutableMethod.name) {
                "<clinit>" -> {
                    mutableClass.methods.remove(mutableMethod)
                    result.deletedMethods++
                }
                "<init>" -> {
                    clearTryBlocks(impl)
                    val originalCount = mutableMethod.instructions.size
                    val paramWidth = mutableMethod.parameterTypes.sumOf {
                        if (it.toString() == "J" || it.toString() == "D") 2 else 1
                    }
                    val thisReg = impl.registerCount - paramWidth - 1
                    val superCall = if (thisReg <= 15) {
                        "invoke-direct {v$thisReg}, $effectiveSuper-><init>()V"
                    } else {
                        "invoke-direct/range {v$thisReg .. v$thisReg}, $effectiveSuper-><init>()V"
                    }
                    try {
                        listOf(superCall, "return-void").forEachIndexed { offset, line -> mutableMethod.addInstructions(offset, line) }
                        mutableMethod.removeInstructions(2, originalCount)
                        result.reducedConstructors++
                    } catch (e: Exception) {
                        result.failedMethods++
                        logger.severe("FAILED to reduce constructor ${classDef.type}-><init>: ${e.message}")
                    }
                }
                else -> {
                    val superType = classDef.superclass
                    val superHasSameMethod = superType?.let {
                        classDefByOrNull(it)?.methods?.any { sm ->
                            sm.name == mutableMethod.name && sm.returnType == mutableMethod.returnType &&
                                    sm.parameterTypes.map { p -> p.toString() } == mutableMethod.parameterTypes.map { p -> p.toString() }
                        }
                    } == true

                    val superCallInsn = insns.firstOrNull { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        insn.opcode.name.startsWith("INVOKE_SUPER") &&
                                ref?.name == mutableMethod.name && ref.definingClass == superType
                    }

                    if (mutableMethod.returnType == "V" && superHasSameMethod && superCallInsn != null) {
                        val paramWidth = mutableMethod.parameterTypes.sumOf {
                            if (it.toString() == "J" || it.toString() == "D") 2 else 1
                        }
                        val isStatic = (mutableMethod.accessFlags and 0x8) != 0
                        val firstParamReg = impl.registerCount - paramWidth - (if (!isStatic) 1 else 0)

                        var usesOnlyParams = false
                        if (superCallInsn is RegisterRangeInstruction) {
                            val startReg = superCallInsn.startRegister
                            val count = superCallInsn.registerCount
                            usesOnlyParams = (startReg >= firstParamReg) && (startReg + count <= impl.registerCount)
                        } else if (superCallInsn is FiveRegisterInstruction) {
                            val regs = listOf(superCallInsn.registerC, superCallInsn.registerD, superCallInsn.registerE, superCallInsn.registerF, superCallInsn.registerG)
                            val count = (superCallInsn as RegisterRangeInstruction).registerCount
                            usesOnlyParams = regs.take(count).all { it >= firstParamReg }
                        } else if (superCallInsn is ThreeRegisterInstruction) {
                            usesOnlyParams = listOf(superCallInsn.registerA, superCallInsn.registerB, superCallInsn.registerC).all { it >= firstParamReg }
                        } else if (superCallInsn is TwoRegisterInstruction) {
                            usesOnlyParams = listOf(superCallInsn.registerA, superCallInsn.registerB).all { it >= firstParamReg }
                        } else if (superCallInsn is OneRegisterInstruction) {
                            usesOnlyParams = superCallInsn.registerA >= firstParamReg
                        }

                        if (usesOnlyParams) {
                            clearTryBlocks(impl)
                            val originalCount = impl.instructions.size
                            val ref = (superCallInsn as ReferenceInstruction).reference as MethodReference
                            val paramsStr = ref.parameterTypes.joinToString("") { it.toString() }

                            val superCallSmali = if (superCallInsn is RegisterRangeInstruction) {
                                val startReg = superCallInsn.startRegister
                                val endReg = startReg + superCallInsn.registerCount - 1
                                "invoke-super/range {v$startReg .. v$endReg}, $superType->${ref.name}($paramsStr)${ref.returnType}"
                            } else {
                                val regs = mutableListOf<Int>()
                                when (superCallInsn) {
                                    is FiveRegisterInstruction -> regs.addAll(listOf(superCallInsn.registerC, superCallInsn.registerD, superCallInsn.registerE, superCallInsn.registerF, superCallInsn.registerG))
                                    is ThreeRegisterInstruction -> regs.addAll(listOf(superCallInsn.registerA, superCallInsn.registerB, superCallInsn.registerC))
                                    is TwoRegisterInstruction -> regs.addAll(listOf(superCallInsn.registerA, superCallInsn.registerB))
                                    is OneRegisterInstruction -> regs.add(superCallInsn.registerA)
                                }
                                val count = (superCallInsn as RegisterRangeInstruction).registerCount
                                val regsStr = regs.take(count).joinToString(", ") { "v$it" }
                                "invoke-super {$regsStr}, $superType->${ref.name}($paramsStr)${ref.returnType}"
                            }

                            try {
                                mutableMethod.removeInstructions(0, originalCount)
                                mutableMethod.addInstructions(0, listOf(superCallSmali, "return-void"))
                                result.reducedConstructors++
                                logger.fine("stubbed void override to super call only: ${classDef.type}->${mutableMethod.name}")
                            } catch (e: Exception) {
                                result.failedMethods++
                                logger.severe("FAILED to stub override ${classDef.type}->${mutableMethod.name}: ${e.message}")
                            }
                            continue
                        }
                    }

                    result.leftUntouched++
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
                    val paramWidth = ctor.parameterTypes.sumOf {
                        if (it.toString() == "J" || it.toString() == "D") 2 else 1
                    }
                    val thisReg = body.registerCount - paramWidth - 1
                    val superCall = if (thisReg <= 15) {
                        "invoke-direct {v$thisReg}, Ljava/lang/Object;-><init>()V"
                    } else {
                        "invoke-direct/range {v$thisReg .. v$thisReg}, Ljava/lang/Object;-><init>()V"
                    }
                    ctor.replaceInstruction(superCallIdx, superCall)
                }
            }
        }

        if (hasTargetField) mutableClass.fields.removeAll { it.type.isTarget(prefixes) || it.type in deletedClasses }

        val emptyClinit = mutableClass.methods.firstOrNull { m ->
            m.name == "<clinit>" && m.implementation?.instructions?.toList()
                ?.let { it.size == 1 && it[0].opcode == Opcode.RETURN_VOID } == true
        }
        if (emptyClinit != null) mutableClass.methods.remove(emptyClinit)

        val hasRemainingBody = mutableClass.methods.any { it.name !in setOf("<init>", "<clinit>") } ||
                mutableClass.fields.isNotEmpty()
        if (!hasRemainingBody && (superIsTarget || targetInterfaces.isNotEmpty()) &&
            classDef.type !in manifestProtectedClasses
        ) {
            result.orphanedClasses += classDef.type
        }
    }

    return result
}

private fun BytecodePatchContext.scrubSweepReferences(
    sweep_prefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
) {
    classDefForEach classLoop@{ classDef ->
        if (classDef.type in deletedClasses) return@classLoop
        if (classDef.type.isTarget(sweep_prefixes)) return@classLoop

        val isKotlinLambda = classDef.superclass == "Lkotlin/jvm/internal/Lambda;" ||
                classDef.interfaces.any { it.startsWith("Lkotlin/jvm/functions/Function") }
        if (isKotlinLambda) return@classLoop

        val hasSweepField = classDef.fields.any { it.type.isTarget(sweep_prefixes) }
        val methodsNeedingWork = classDef.methods.filter { method ->
            method.implementation?.instructions?.any { it.referencesTarget(sweep_prefixes) } == true
        }
        if (!hasSweepField && methodsNeedingWork.isEmpty()) return@classLoop

        val mutableClass = mutableClassDefBy(classDef.type)

        for (method in methodsNeedingWork) {
            val mutableMethod = mutableClass.methods.firstOrNull { m ->
                m.name == method.name && m.returnType == method.returnType &&
                        m.parameterTypes.map { it.toString() } == method.parameterTypes.map { it.toString() }
            } ?: continue
            val impl = mutableMethod.implementation ?: continue
            val insns = impl.instructions.toList()
            if (insns.any { it.opcode.name.contains("SWITCH") }) continue

            var allSurgical = true
            val hadTryBlocks = impl.tryBlocks.isNotEmpty()
            val edits = ArrayList<Pair<Int, String?>>()
            
            for ((index, insn) in insns.withIndex()) {
                if (!insn.referencesTarget(sweep_prefixes)) continue
                val opName = insn.opcode.name
                when {
                    opName.startsWith("INVOKE_") -> {
                        val ref = (insn as ReferenceInstruction).reference as MethodReference
                        val nextInsn = insns.getOrNull(index + 1)
                        val isMoveResult = nextInsn?.opcode?.name?.startsWith("MOVE_RESULT") == true
                        if (ref.name != "<init>") {
                            if (ref.returnType == "V" || !isMoveResult) {
                                edits += index to "nop"
                            } else {
                                val destReg = (nextInsn as OneRegisterInstruction).registerA
                                val zeroLoad = zeroLoadFor(destReg, ref.returnType)
                                edits += index to zeroLoad
                                edits += (index + 1) to "nop"
                            }
                        } else {
                            allSurgical = false
                        }
                    }
                    opName.startsWith("SGET") || opName.startsWith("IGET") -> {
                        val fieldType = ((insn as ReferenceInstruction).reference as FieldReference).type
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += index to zeroLoadFor(reg, fieldType)
                    }
                    opName.startsWith("SPUT") || opName.startsWith("IPUT") -> edits += index to "nop"
                    opName == "NEW_INSTANCE" || opName == "CONST_CLASS" -> {
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += index to zeroLoadFor(reg, "L")
                    }
                    opName == "CHECK_CAST" -> edits += index to "nop"
                    opName == "INSTANCE_OF" -> {
                        val destReg = (insn as TwoRegisterInstruction).registerA
                        edits += index to zeroLoadFor(destReg, "Z")
                    }
                    else -> allSurgical = false
                }
            }
            
            if (allSurgical && edits.isNotEmpty()) {
                if (hadTryBlocks) clearTryBlocks(impl)
                edits.sortedByDescending { it.first }.forEach { (index, smali) ->
                    mutableMethod.replaceInstruction(index, smali!!)
                }
            }
        }

        if (hasSweepField) mutableClass.fields.removeAll { it.type.isTarget(sweep_prefixes) }
    }
}

private fun referencedTypesOf(classDef: ClassDef): Set<String> {
    val refs = HashSet<String>()
    classDef.superclass?.let { refs += it }
    refs += classDef.interfaces
    classDef.fields.forEach { refs += it.type }
    classDef.methods.forEach { method ->
        refs += method.returnType
        method.parameterTypes.forEach { refs += it.toString() }
        method.implementation?.instructions?.forEach { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference ?: return@forEach
            when (ref) {
                is MethodReference -> {
                    refs += ref.definingClass
                    refs += ref.returnType
                    ref.parameterTypes.forEach { refs += it.toString() }
                }
                is FieldReference -> {
                    refs += ref.definingClass
                    refs += ref.type
                }
                is TypeReference -> refs += ref.type
            }
        }
        method.implementation?.tryBlocks?.forEach { tb ->
            tb.exceptionHandlers.forEach { it.exceptionType?.let { t -> refs += t } }
        }
    }
    return refs
}

private fun BytecodePatchContext.sweepUnreferenced(
    sweep_prefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
): Int {
    if (sweep_prefixes.isEmpty()) return 0
    scrubSweepReferences(sweep_prefixes, deletedClasses)

    val referencesOf = HashMap<String, Set<String>>()
    val sweepCandidates = HashSet<String>()

    classDefForEach { classDef ->
        if (classDef.type in deletedClasses) return@classDefForEach
        referencesOf[classDef.type] = referencedTypesOf(classDef)
        if (classDef.type.isTarget(sweep_prefixes) && classDef.type !in manifestProtectedClasses) {
            sweepCandidates += classDef.type
        }
    }

    val reachable = HashSet<String>()
    val queue = ArrayDeque<String>()

    referencesOf.forEach { (owner, refs) ->
        if (owner in sweepCandidates) return@forEach
        refs.forEach { ref -> if (ref in sweepCandidates && reachable.add(ref)) queue += ref }
    }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        referencesOf[current]?.forEach { ref ->
            if (ref in sweepCandidates && reachable.add(ref)) queue += ref
        }
    }

    val orphaned = sweepCandidates - reachable
    deletedClasses += orphaned
    return orphaned.size
}

fun BytecodePatchContext.removeCodeByPrefix(
    tag: String,
    prefixes: List<String>,
    sweep_prefixes: List<String> = emptyList(),
) {
    val logger = Logger.getLogger("RemoveCode:$tag")
    val deletedClasses = HashSet<String>()
    val currentPrefixes = prefixes.toMutableSet()
    var deletedMethods = 0
    var reducedConstructors = 0
    var stubbedMethods = 0
    var leftUntouched = 0
    var failedMethods = 0
    var round = 0

    while (true) {
        round++
        classDefForEach { classDef ->
            if (classDef.type.isTarget(currentPrefixes) && classDef.type !in manifestProtectedClasses) {
                deletedClasses += classDef.type
            }
        }
        if (deletedClasses.isEmpty()) {
            logger.info("no matching classes")
            return
        }

        val result = runRound(currentPrefixes, deletedClasses, logger)
        deletedMethods += result.deletedMethods
        reducedConstructors += result.reducedConstructors
        stubbedMethods += result.stubbedMethods
        leftUntouched += result.leftUntouched
        failedMethods += result.failedMethods

        val newlyOrphaned = result.orphanedClasses.filterNot { it in deletedClasses }
        if (newlyOrphaned.isEmpty()) break
        deletedClasses += newlyOrphaned
        currentPrefixes += newlyOrphaned
    }

    val swept = sweepUnreferenced(sweep_prefixes, deletedClasses)

    forceFullBytecodeMode()
    val classMap = internalClassMap()
    var removed = 0
    deletedClasses.forEach { if (classMap.remove(it) != null) removed++ }
    logger.info(
        "\"$tag\": removed $removed classes ($swept via reachability sweep) over $round round(s), " +
                "deleted $deletedMethods methods, stubbed $stubbedMethods protected methods, " +
                "reduced $reducedConstructors constructors, " +
                "left $leftUntouched methods untouched, $failedMethods FAILED " +
                "(set Level.FINE on logger \"RemoveCode:$tag\" for details)"
    )
}
