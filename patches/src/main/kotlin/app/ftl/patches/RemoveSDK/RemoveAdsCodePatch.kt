package app.ftl.patches.RemoveSDK

import app.morphe.patcher.patch.bytecodePatch
import app.ftl.patches.RemoveSDK.targets.ADS_SWEEP_ROOTS
import app.ftl.patches.RemoveSDK.targets.ADS_TARGETS

@Suppress("unused")
val removeAdsCodePatch = bytecodePatch(
    name = "Remove ads code",
    description = "Deletes bundled ad-network SDK classes and scrubs every remaining reference to them.",
    default = true,
) {
    execute {
        removeCodeByPrefix("ads", ADS_TARGETS, ADS_SWEEP_ROOTS)
    }
}
