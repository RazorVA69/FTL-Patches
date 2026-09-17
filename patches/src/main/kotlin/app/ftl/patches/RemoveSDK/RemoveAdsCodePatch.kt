package app.ftl.patches.RemoveSDK

import app.morphe.patcher.patch.bytecodePatch
import app.ftl.patches.RemoveSDK.targets.ADS_TARGETS

@Suppress("unused")
val removeAdsCodePatch = bytecodePatch(
    name = "Remove ads code",
    description = "Deletes bundled ad-network SDK classes and scrubs every remaining reference to them. " +
        "Expert: do not combine with Remove Ads / Remove Analytics or per-app patches; " +
        "apps that inflate ad views from XML or Class.forName() the SDK will crash.",
    default = false,
) {
    execute {
        removeCodeByPrefix("ads", ADS_TARGETS)
    }
}
