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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.logging.Logger

/*
 * Port of SmaliScissors' [REMOVE_CODE] engine onto dexlib2/Morphe.
 * 
 * CRASH FIXES APPLIED:
 * 1. Hard-skip for Kotlin Functions/Lambdas to prevent gutting shared dispatchers.
 * 2. Safe override fallback: Do not delete override methods just because they call super.
 * 3. Enhanced surgical path: Zero out destination registers for non-void invokes.
 * 4. Superclass Chain Resolution: Walk up the inheritance chain to find the nearest surviving
 *    superclass when the direct superclass is deleted. Prevents ClassCastException for manifest
 *    components whose intermediate SDK base classes were removed.
 * 5. Aggressive Stubbing for Manifest-Protected SDK Classes: If an SDK class is kept solely
 *    because the OS requires it (e.g., AppLovinInitProvider), aggressively stub ALL its methods
 *    to return default values. Prevents ClassNotFoundException when onCreate() tries to
 *    reference deleted SDK dependencies.
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

        // FIX: Skip Kotlin merged lambdas and function interfaces entirely.
        val isKotlinLambda = classDef.superclass == "Lkotlin/jvm/internal/Lambda;" ||
                classDef.interfaces.any { it.startsWith("Lkotlin/jvm/functions/Function") }
        if (isKotlinLambda) {
            return@classLoop
        }

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
        
        // FIX: Superclass Chain Resolution
        // Walk up the inheritance chain to find the nearest surviving superclass.
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

        // FIX: Aggressive Stubbing for Manifest-Protected SDK Classes
        // If this class is an SDK class (matches prefixes) but is kept because the OS requires it
        // (e.g., AppLovinInitProvider in manifest), we must ensure it does absolutely nothing.
        val isProtectedTarget = classDef.type in manifestProtectedClasses && classDef.type.isTarget(prefixes)
        if (isProtectedTarget) {
            for (method in mutableClass.methods) {
                val impl = method.implementation ?: continue
                if (impl.instructions.isEmpty()) continue
                
                clearTryBlocks(impl)
                val originalCount = impl.instructions.size
                val returnType = method.returnType
                val stubInstructions = mutableListOf<String>()
                
                when (returnType) {
                    "V" -> stubInstructions.add("return-void")
                    "Z", "B", "S", "C", "I" -> {
                        stubInstructions.add("const/4 v0, 0x0")
                        stubInstructions.add("return v0")
                    }
                    "J" -> {
                        stubInstructions.add("const-wide/16 v0, 0x0")
                        stubInstructions.add("return-wide v0")
                    }
                    "F" -> {
                        stubInstructions.add("const/4 v0, 0x0")
                        stubInstructions.add("return v0")
                    }
                    "D" -> {
                        stubInstructions.add("const-wide/16 v0, 0x0")
                        stubInstructions.add("return-wide v0")
                    }
                    else -> {
                        stubInstructions.add("const/4 v0, 0x0")
                        stubInstructions.add("return-object v0")
                    }
                }
                
                try {
                    stubInstructions.forEachIndexed { offset, line ->
                        method.addInstructions(offset, line)
                    }
                    method.removeInstructions(stubInstructions.size, originalCount)
                    result.stubbedMethods++
                } catch (e: Exception) {
                    logger.severe("FAILED to stub protected method ${classDef.type}->${method.name}: ${e.message}")
                    result.failedMethods++
                }
            }
            if (hasTargetField) {
                mutableClass.fields.removeAll { it.type.isTarget(prefixes) || it.type in deletedClasses }
            }
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
                logger.fine("delete method (signature): ${classDef.type}->${method.name}")
                continue
            }

            val impl = mutableMethod.implementation ?: continue
            val insns = impl.instructions.toList()

            if (insns.any { it.opcode.name.contains("SWITCH") }) continue

            var allSurgical = impl.tryBlocks.isEmpty()
            val surgicalEdits = ArrayList<Pair<Int, String?>>()
            if (allSurgical) {
                for ((index, insn) in insns.withIndex()) {
                    if (!allSurgical) break
                    if (!insn.referencesTarget(prefixes)) continue
                    val opName = insn.opcode.name
                    when {
                        opName.startsWith("INVOKE_") -> {
                            val ref = (insn as ReferenceInstruction).reference as MethodReference
                            val nextInsn = insns.getOrNull(index + 1)
                            val isMoveResult = nextInsn?.opcode?.name?.startsWith("MOVE_RESULT") == true
                            
                            if (ref.name != "<init>") {
                                if (ref.returnType == "V" || !isMoveResult) {
                                    surgicalEdits += index to null
                                } else {
                                    val destReg = (nextInsn as OneRegisterInstruction).registerA
                                    val zeroLoad = zeroLoadFor(destReg, ref.returnType)
                                    surgicalEdits += index to zeroLoad
                                    surgicalEdits += (index + 1) to null
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
                continue
            }

            when (mutableMethod.name) {
                "<clinit>" -> {
                    mutableClass.methods.remove(mutableMethod)
                    result.deletedMethods++
                    logger.fine("delete <clinit> (not surgically safe): ${classDef.type}")
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
                        listOf(superCall, "return-void").forEachIndexed { offset, line ->
                            mutableMethod.addInstructions(offset, line)
                        }
                        mutableMethod.removeInstructions(2, originalCount)
                        result.reducedConstructors++
                    } catch (e: Exception) {
                        result.failedMethods++
                        logger.severe(
                            "FAILED to reduce constructor ${classDef.type}-><init>: attempted " +
                                    "super call=$superCall registerCount=${impl.registerCount} - " +
                                    "${e.javaClass.name}: ${e.message}"
                        )
                    }
                }

                else -> {
                    // FIX: Safe Override Fallback
                    result.leftUntouched++
                    logger.fine(
                        "leave untouched, not surgically safe: " +
                                "${classDef.type}->${mutableMethod.name}"
                    )
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
                        "invoke-direct {v$thisReg}, $effectiveSuper-><init>()V"
                    } else {
                        "invoke-direct/range {v$thisReg .. v$thisReg}, $effectiveSuper-><init>()V"
                    }
                    ctor.replaceInstruction(superCallIdx, superCall)
                }
            }
        }

        if (hasTargetField) {
            mutableClass.fields.removeAll { it.type.isTarget(prefixes) || it.type in deletedClasses }
        }

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
        if (isKotlinLambda) {
            return@classLoop
        }

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
            if (impl.tryBlocks.isNotEmpty()) continue
            val insns = impl.instructions.toList()
            if (insns.any { it.opcode.name.contains("SWITCH") }) continue

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
                                edits += index to null
                            } else {
                                val destReg = (nextInsn as OneRegisterInstruction).registerA
                                val zeroLoad = zeroLoadFor(destReg, ref.returnType)
                                edits += index to zeroLoad
                                edits += (index + 1) to null
                            }
                        }
                    }

                    opName.startsWith("SGET") || opName.startsWith("IGET") -> {
                        val fieldType = ((insn as ReferenceInstruction).reference as FieldReference).type
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += index to zeroLoadFor(reg, fieldType)
                    }

                    opName.startsWith("SPUT") || opName.startsWith("IPUT") -> edits += index to null
                }
            }
            if (edits.isNotEmpty()) {
                edits.sortedByDescending { it.first }.forEach { (index, smali) ->
                    if (smali == null) mutableMethod.removeInstruction(index)
                    else mutableMethod.replaceInstruction(index, smali)
                }
            }
        }

        if (hasSweepField) {
            mutableClass.fields.removeAll { it.type.isTarget(sweep_prefixes) }
        }
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
        logger.fine("round $round: cascading ${newlyOrphaned.size} now-empty wrapper class(es)")
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
