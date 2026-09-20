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
 * simply unreachable. That is dead-code elimination, not cascade (cascade cleans callers
 * of a deleted class; this removes callees that nothing reachable still needs), and it is
 * handled as a distinct sweep phase over an explicit SWEEP_ROOTS list, run only after every
 * round above has converged. It is mark-and-sweep from roots, not a bottom-up "is anything
 * still mentioning me" check - the latter can never resolve a cluster of SWEEP_ROOTS classes
 * that only reference each other (a manager and its listeners, a factory and what it builds),
 * which internal SDK implementation packages are full of; reachability from every permanent
 * survivor correctly identifies such a cluster as dead as a whole even with zero individual
 * class ever showing a reference count of exactly zero. Sweep roots must never be able to
 * overlap with TARGETS entries that the app itself calls directly (e.g. the public
 * com/google/android/gms/ads/ API surface) - those have to stay hard targets, cleaned via
 * the cascade above, precisely because the app DOES hold live references to them and a
 * root can never legitimately need a sweep root kept alive by construction.
 *
 * Deliberate simplification vs. SmaliScissors: it tracks per-opcode register dataflow to
 * surgically excise a target reference from a method that also does unrelated work,
 * keeping the rest of that method intact. That is not replicated here - matching, unlike
 * SmaliScissors' raw substring search over smali text, is reference-type-exact, so a string
 * literal that happens to contain a target package name is never a false match, but the two
 * safe shapes below (a discardable invoke, a field read/write) are the only edits actually
 * proven to preserve behavior. A method with a target reference outside those two shapes -
 * or with a pre-existing try/catch at all (shrinking a try range around a removed/replaced
 * instruction relies on dexlib2's label tracking, which does not recover cleanly from a
 * fully wiped body - it can leave a stale try_item with a collapsed
 * startAddr=0/endAddr=0 range instead of dropping it, a hard VerifyError at class-load
 * time) - used to fall back to replacing the WHOLE method body with a trivial stub. That
 * caused two confirmed startup crashes on MX Player: an R8-merged lambda dispatcher
 * (packed-switch fanning out to a dozen unrelated call sites) had every case wiped because
 * ONE of them referenced an ad SDK type, and a lifecycle override that called super() before
 * a few hundred lines of ad-SDK init had that super() call deleted along with the ad code,
 * skipping base-class setup the rest of the app depended on. There is no way to tell
 * "essential" from "ad code" at the instruction level without the full register-dataflow
 * tracking this file deliberately doesn't replicate, so instead of guessing, an
 * unsurgical method is now handled only via strategies that are structurally provable safe
 * regardless of content: a switch-bearing method is left completely alone (see the switch
 * check in runRound - this is what fixes the lambda-dispatcher case); a static initializer
 * is deleted outright (never inherited, so this is equivalent to it having run and done
 * nothing); a constructor is reduced to just its super call plus return (every constructor
 * must structurally do that much anyway, so this isn't a guess about content the way an
 * arbitrary stub would be); any other method is deleted only if the superclass already
 * defines the identical signature, so the vtable falls through to that implementation
 * naturally (this is what fixes the lifecycle-override case - App.t()/A() calling super()
 * is exactly this shape) - and failing all of those, the method is left completely
 * untouched rather than stubbed. A method left this way may still hold a live reference to
 * a class that gets deleted elsewhere; that is a real residual risk, but a conditional one
 * (it only matters if that specific reference is ever reached at runtime), unlike a blanket
 * stub which unconditionally destroys whatever else the method did on every single call.
 *
 * A class can also be a live entry point with zero bytecode references anywhere - the
 * classic case is an SDK's auto-init ContentProvider (e.g. Google Mobile Ads'
 * MobileAdsInitProvider, AppLovin's AppLovinInitProvider), declared in AndroidManifest.xml
 * and instantiated by the OS via reflection at process start, which no dex-reference scan
 * can ever see. Both TARGETS and SWEEP_ROOTS deletion defer to [manifestProtectedClasses]
 * (see ManifestProtectedClasses.kt) before ever adding a class to the deleted set - same
 * role SmaliScissors' SmaliKeeper plays.
 *
 * A protected class's own methods still get processed like any other surviving class, but
 * NOT surgically safe splits into two different outcomes depending on whether the class is
 * app code or vendor code, confirmed against two different real crashes. App code that
 * merely CALLS an SDK (com.mxtech.videoplayer.ad.App, which doesn't itself match any target
 * prefix) gets the conservative treatment above - leave alone if nothing else applies -
 * because there's real unrelated logic in there worth protecting. A class that IS the SDK's
 * own code (com.applovin.sdk.AppLovinInitProvider, which matches Lcom/applovin/ directly)
 * only survives at all because manifestProtectedClasses is keeping it alive for the OS; its
 * lifecycle callback runs unconditionally at process start regardless of any reference this
 * pass can see, so leaving a leftover call into another now-deleted SDK class in there
 * guarantees the exact same crash "leave alone" was meant to prevent, just one call deeper -
 * confirmed against AppLovinInitProvider.onCreate() still calling into a deleted
 * com.applovin.impl class after being left untouched. There's also no legitimate unrelated
 * logic to lose here - the whole reason the class exists is the SDK being removed - so for
 * this narrow case specifically (manifest-protected AND itself matching a target prefix)
 * stubbing is the safe choice, not the risky one, and the fallback chain includes it.
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

/** const/16, not const/4: SGET/IGET's own register field is 8-bit (v0-255), but const/4's
 *  is 4-bit (v0-15) - replacing a high-numbered destination with const/4 would hit the same
 *  register-range failure as the invoke-direct/p0 case above. const/16 covers the same
 *  range SGET/IGET can ever actually produce. */
private fun zeroLoadFor(reg: Int, type: String): String = when (type) {
    "J", "D" -> "const-wide/16 v$reg, 0x0"
    else -> "const/16 v$reg, 0x0"
}

/** One instruction per list element, inserted before the old body is removed (see the
 *  vendor-SDK-stub call site for why). Always v0, never a register derived from the
 *  method's own register count - so unlike the old general stub this reintroduces, there's
 *  no v15-ceiling risk to worry about here at all. */
private fun minimalReturnFor(returnType: String): List<String> = when (returnType) {
    "V" -> listOf("return-void")
    "Z", "B", "C", "S", "I", "F" -> listOf("const/4 v0, 0x0", "return v0")
    "J", "D" -> listOf("const-wide/16 v0, 0x0", "return-wide v0")
    else -> listOf("const/4 v0, 0x0", "return-object v0")
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
    var reducedConstructors = 0
    var stubbedVendorMethods = 0
    var leftUntouched = 0
    var failedMethods = 0
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

            // A packed-switch/sparse-switch is R8's fingerprint for a merged lambda
            // dispatcher: one method fanning out to N unrelated call sites bundled
            // together purely because they compiled to identical bytecode shape. Confirmed
            // against a real crash: stubbing such a method (even just the one case that
            // happened to reference a target) silently destroyed every other case's
            // unrelated functionality along with it. Leave the whole method exactly as-is -
            // whatever target reference lives inside one case stays, same residual risk as
            // any other reference this pass doesn't reach, but every unrelated case survives.
            if (insns.any { it.opcode.name.contains("SWITCH") }) {
                result.leftUntouched++
                continue
            }

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
                            // Safe to drop outright if there's nothing to preserve: a void
                            // call, or a non-void call whose result the very next
                            // instruction doesn't consume via move-result (including when
                            // this is the method's last instruction) - the return value was
                            // going to be thrown away either way, so removing the call
                            // changes nothing observable.
                            val resultDiscarded = ref.returnType == "V" ||
                                    insns.getOrNull(index + 1)?.opcode?.name?.startsWith("MOVE_RESULT") != true
                            if (ref.name != "<init>" && resultDiscarded) {
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
                continue
            }

            // Not surgically safe. A blanket whole-method stub used to sit here - it's what
            // caused two confirmed startup crashes on MX Player: com.mxtech.videoplayer.ad
            // .App.t()/.A() are overrides that call super() before ~500 lines of ad-SDK
            // init, and stubbing them to a bare return-void deleted that super() call along
            // with the ad code, skipping essential base-class setup the rest of the app
            // depends on. There is no way to tell "this instruction is essential" from
            // "this instruction is ad code" at this level without full register-dataflow
            // tracking (the real SmaliScissors approach, not replicated here - see the file
            // header). So instead of guessing, handle only the cases where removing the
            // WHOLE method is structurally provable to be safe, and leave everything else
            // completely alone.
            when (mutableMethod.name) {
                "<clinit>" -> {
                    // A static initializer is never inherited or overridden - deleting it
                    // outright is equivalent to it having run and done nothing, not a
                    // fallthrough to anything else. If its only job was setting up
                    // ad-related statics, that's exactly the outcome removal wants anyway.
                    mutableClass.methods.remove(mutableMethod)
                    result.deletedMethods++
                    logger.fine("delete <clinit> (not surgically safe): ${classDef.type}")
                }

                "<init>" -> {
                    // Every constructor must structurally call some super constructor -
                    // reducing it to just that call plus return is a narrow, well-defined
                    // simplification, not a guess: unlike an arbitrary method there is no
                    // ambiguity about what a constructor's minimum valid body looks like.
                    // This is deliberately kept distinct from the removed general stub,
                    // which invented a fake return value for a method that could have been
                    // doing anything.
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
                    // Deleting the override is only safe if (a) the superclass already
                    // defines this exact signature, so the vtable falls through to that
                    // implementation naturally, AND (b) the override already calls that
                    // super method itself somewhere - proving it was structured as
                    // "delegate to base behavior plus extra work", not a full replacement
                    // of it. Without (b), an override that never calls super could be
                    // providing entirely different, load-bearing behavior; deleting it
                    // would silently swap in whatever the base class does instead, which
                    // is a bigger behavioral change than removing ad code. If nothing up
                    // the chain provides (a) at all - a genuinely new method, or one
                    // satisfying an interface contract, where deleting produces an
                    // AbstractMethodError or a verification failure, not a safe fallthrough
                    // - or (b) doesn't hold, leave the method exactly as it was.
                    val superType = classDef.superclass
                    val superHasSameMethod = superType?.let {
                        classDefByOrNull(it)?.methods?.any { sm ->
                            sm.name == mutableMethod.name &&
                                    sm.returnType == mutableMethod.returnType &&
                                    sm.parameterTypes.map { p -> p.toString() } ==
                                    mutableMethod.parameterTypes.map { p -> p.toString() }
                        }
                    } == true
                    val callsSuper = superType != null && insns.any { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        insn.opcode.name.startsWith("INVOKE_SUPER") &&
                                ref?.name == mutableMethod.name && ref.definingClass == superType
                    }

                    // TEMPORARY diagnostic (remove once the App.A()/CleverTapAPI mystery is
                    // resolved): for a known false-negative case (superHasSameMethod=true,
                    // callsSuper=false, yet the real smali plainly contains a matching
                    // invoke-super), dump every instruction that even LOOKS like an
                    // invoke-super, with dexlib2's raw values, next to what this comparison
                    // expected - so a mismatch (opcode name, ref.name, ref.definingClass) is
                    // visible directly instead of inferred from a single aggregate boolean.
                    if (superType != null && superHasSameMethod && !callsSuper) {
                        val candidates = insns.filter { it.opcode.name.contains("SUPER") }
                        if (candidates.isEmpty()) {
                            logger.info(
                                "no SUPER-opcode instructions found at all in " +
                                        "${classDef.type}->${mutableMethod.name} (insns.size=${insns.size})"
                            )
                        } else {
                            candidates.forEach { insn ->
                                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                                logger.info(
                                    "SUPER-opcode candidate in ${classDef.type}->${mutableMethod.name}: " +
                                            "opcode=${insn.opcode.name} refName=${ref?.name} " +
                                            "refDefiningClass=${ref?.definingClass} refReturnType=${ref?.returnType} " +
                                            "-- expected name=${mutableMethod.name} expected definingClass=$superType"
                                )
                            }
                        }
                    }

                    // TEMPORARY diagnostic (remove once the App.A()/CleverTapAPI mystery is
                    // resolved): scoped to only methods where at least one of the two
                    // signals is already true, so this doesn't flood the log with every
                    // interface-implementation method that has no super-relationship at all.
                    if (superType != null && (superHasSameMethod || callsSuper)) {
                        logger.info(
                            "override-check ${classDef.type}->${mutableMethod.name}" +
                                    "(${mutableMethod.parameterTypes.joinToString(",")})${mutableMethod.returnType}: " +
                                    "superType=$superType superHasSameMethod=$superHasSameMethod " +
                                    "callsSuper=$callsSuper"
                        )
                    }

                    if (superHasSameMethod && callsSuper) {
                        mutableClass.methods.remove(mutableMethod)
                        result.deletedMethods++
                        logger.fine(
                            "delete override, superclass provides fallback: " +
                                    "${classDef.type}->${mutableMethod.name}"
                        )
                    } else if (classDef.type.isTarget(prefixes)) {
                        // The containing CLASS itself matches a target prefix - this isn't
                        // app code that happens to call an SDK, it IS the SDK's own code
                        // (e.g. com.applovin.sdk.AppLovinInitProvider, an auto-init
                        // ContentProvider AppLovin's own manifest merges in). It only
                        // reached this branch instead of being deleted outright because
                        // manifestProtectedClasses is keeping it alive - the OS instantiates
                        // it directly and unconditionally at process start regardless of any
                        // dex reference. "Leave untouched" is not the safe choice here the
                        // way it is for app code: onCreate() runs immediately no matter
                        // what, so a leftover reference to another now-deleted SDK class
                        // guarantees the exact same NoClassDefFoundError crash "leave alone"
                        // was supposed to avoid, just one call deeper. Unlike the app-code
                        // case, there's no legitimate unrelated logic here to lose - the
                        // entire reason this class exists is the SDK being removed - so
                        // stubbing it is the actually-safe choice, not the risky one.
                        clearTryBlocks(impl)
                        val originalCount = mutableMethod.instructions.size
                        val stubBody = minimalReturnFor(mutableMethod.returnType)
                        try {
                            stubBody.forEachIndexed { offset, line -> mutableMethod.addInstructions(offset, line) }
                            mutableMethod.removeInstructions(stubBody.size, originalCount)
                            result.stubbedVendorMethods++
                            logger.fine(
                                "stub vendor-SDK method (manifest-protected, not surgically safe): " +
                                        "${classDef.type}->${mutableMethod.name}"
                            )
                        } catch (e: Exception) {
                            result.failedMethods++
                            logger.severe(
                                "FAILED to stub vendor-SDK method ${classDef.type}->${mutableMethod.name}: " +
                                        "${e.javaClass.name}: ${e.message}"
                            )
                        }
                    } else {
                        result.leftUntouched++
                        logger.fine(
                            "leave untouched, no safe removal strategy: " +
                                    "${classDef.type}->${mutableMethod.name}"
                        )
                    }
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
                    // Same v15 register-argument ceiling as the whole-method stub's
                    // super-call above - "p0" resolves to registerCount - paramWidth, which
                    // is only ever addressable as a plain "vN" once it's within v0-v15.
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
        if (!hasRemainingBody && (superIsTarget || targetInterfaces.isNotEmpty()) &&
            classDef.type !in manifestProtectedClasses
        ) {
            result.orphanedClasses += classDef.type
        }
    }

    return result
}

/** Same two provably-safe edits as runRound's surgical path - a non-&lt;init&gt; void invoke
 *  removed, a field read zeroed, a field write removed - but for SWEEP_ROOTS references
 *  found in classes runRound never even looks at (it only scans against the hard TARGETS
 *  list). This is the actual reason a class like internal/ads/zzXXX stayed "referenced"
 *  and unsweepable: some surviving, non-target class held a field or call typed to it, and
 *  nothing ever cleaned that up. Deliberately no whole-method stub fallback and no method
 *  deletion here - unlike a hard target, a sweep candidate isn't guaranteed to be deleted at
 *  all, so forcing a stub on the strength of a reference that might not even end up
 *  mattering would be destructive for no guaranteed benefit. Anything not one of the two
 *  safe shapes (or inside a try/catch, same VerifyError risk as runRound) is left alone -
 *  that reference legitimately keeps the candidate alive and the sweep correctly won't
 *  remove it. Declared fields typed to a sweep candidate are dropped outright once their
 *  own accesses are scrubbed, same as runRound's hasTargetField handling.
 *
 *  Deliberately skips classes that are THEMSELVES a sweep candidate: internal SDK packages
 *  are typically a densely interconnected web (hundreds of classes calling each other), and
 *  a reference from one sweep candidate to another needs no editing at all - the mark-and-
 *  sweep reachability pass below already ignores edges between sweep candidates that no
 *  root ever reaches, cycles included. Editing those internal cross-references anyway was
 *  pure waste: most aren't one of the two safe shapes regardless (real construction/usage,
 *  not void calls or bare field access), so it bloated the diff against surviving classes
 *  for no gain in what actually got removed. */
private fun BytecodePatchContext.scrubSweepReferences(
    sweepPrefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
) {
    classDefForEach classLoop@{ classDef ->
        if (classDef.type in deletedClasses) return@classLoop
        if (classDef.type.isTarget(sweepPrefixes)) return@classLoop

        val hasSweepField = classDef.fields.any { it.type.isTarget(sweepPrefixes) }
        val methodsNeedingWork = classDef.methods.filter { method ->
            method.implementation?.instructions?.any { it.referencesTarget(sweepPrefixes) } == true
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
                if (!insn.referencesTarget(sweepPrefixes)) continue
                val opName = insn.opcode.name
                when {
                    opName.startsWith("INVOKE_") -> {
                        val ref = (insn as ReferenceInstruction).reference as MethodReference
                        val resultDiscarded = ref.returnType == "V" ||
                                insns.getOrNull(index + 1)?.opcode?.name?.startsWith("MOVE_RESULT") != true
                        if (ref.name != "<init>" && resultDiscarded) edits += index to null
                    }

                    opName.startsWith("SGET") || opName.startsWith("IGET") -> {
                        val fieldType = ((insn as ReferenceInstruction).reference as FieldReference).type
                        val reg = (insn as OneRegisterInstruction).registerA
                        edits += index to zeroLoadFor(reg, fieldType)
                    }

                    opName.startsWith("SPUT") || opName.startsWith("IPUT") -> edits += index to null
                    // Anything else (non-void invoke, new-instance, check-cast...) is left
                    // exactly as-is - not one of the safe shapes, so this reference stands.
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
            mutableClass.fields.removeAll { it.type.isTarget(sweepPrefixes) }
        }
    }
}

/** Every type-like reference a class makes anywhere: superclass, interfaces, field types,
 *  method signatures, every instruction's referenced type/method/field, and catch types. */
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

/** Mark-and-sweep, run only after the round loop above has converged, not a reference-count
 *  check. Every surviving class that ISN'T itself a sweep candidate is a root - permanently
 *  alive regardless of what references it. Anything a root references, directly or through
 *  a chain of other sweep candidates, is transitively alive too. Any sweep candidate never
 *  reached this way is genuinely dead and gets removed - including an entire cluster of
 *  sweep candidates that only reference each other with no root ever pointing into it.
 *
 *  This matters specifically because internal SDK implementation packages are typically
 *  full of exactly that shape - a manager holding a list of listener/callback objects that
 *  hold a back-reference to the manager, factories instantiating helpers that reference the
 *  factory back, and so on. A bottom-up "is anything still mentioning me" check can never
 *  resolve a cycle like that: each member is always "referenced" by another member of the
 *  same dead cluster, no matter how many rounds it re-checks. Reachability from roots is
 *  the only correct way to identify a self-referential dead cluster as dead as a whole.
 *
 *  Runs [scrubSweepReferences] first so a root's own safely-removable reference into a
 *  sweep candidate doesn't needlessly keep that candidate (and everything reachable from
 *  it) alive. Deliberately never applied to TARGETS - only to SWEEP_ROOTS, which by
 *  construction the caller has picked because nothing outside the SDK's own internals ever
 *  names them, so a root can never legitimately need one kept alive by design. */
private fun BytecodePatchContext.sweepUnreferenced(
    sweepPrefixes: Collection<String>,
    deletedClasses: MutableSet<String>,
): Int {
    if (sweepPrefixes.isEmpty()) return 0

    scrubSweepReferences(sweepPrefixes, deletedClasses)

    val referencesOf = HashMap<String, Set<String>>()
    val sweepCandidates = HashSet<String>()

    classDefForEach { classDef ->
        if (classDef.type in deletedClasses) return@classDefForEach
        referencesOf[classDef.type] = referencedTypesOf(classDef)
        if (classDef.type.isTarget(sweepPrefixes) && classDef.type !in manifestProtectedClasses) {
            sweepCandidates += classDef.type
        }
    }

    val reachable = HashSet<String>()
    val queue = ArrayDeque<String>()

    referencesOf.forEach { (owner, refs) ->
        if (owner in sweepCandidates) return@forEach   // roots seed the queue; candidates only propagate below
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
    var reducedConstructors = 0
    var stubbedVendorMethods = 0
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
        stubbedVendorMethods += result.stubbedVendorMethods
        leftUntouched += result.leftUntouched
        failedMethods += result.failedMethods

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
                "deleted $deletedMethods methods (signature/clinit/override-fallback), " +
                "reduced $reducedConstructors constructors to super-call-only, " +
                "stubbed $stubbedVendorMethods manifest-protected vendor-SDK methods, " +
                "left $leftUntouched app-code methods completely untouched (no safe removal strategy - " +
                "may retain a reference to a deleted class), $failedMethods method(s) FAILED to " +
                "reduce/stub (see Level.SEVERE above if >0) (set Level.FINE on logger \"RemoveCode:$tag\" " +
                "for the per-method list)"
    )
}
