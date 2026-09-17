package app.ftl.patches.RemoveSDK

import app.morphe.patcher.patch.bytecodePatch
import app.ftl.patches.RemoveSDK.targets.ANALYTICS_TARGETS

@Suppress("unused")
val removeAnalyticsCodePatch = bytecodePatch(
    name = "Remove analytics code",
    description = "Deletes bundled analytics/tracking SDK classes and scrubs every remaining reference to them.",
    default = true,
) {
    execute {
        removeCodeByPrefix("analytics", ANALYTICS_TARGETS)
    }
}
