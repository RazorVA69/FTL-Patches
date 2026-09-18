package app.ftl.patches.RemoveSDK

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Populated once by [collectManifestProtectedClassesPatch]. A class in here is instantiated
 * directly by the OS from a manifest entry - a ContentProvider auto-init trick being the
 * classic case (e.g. Google Mobile Ads' MobileAdsInitProvider) - with no bytecode reference
 * anywhere in the dex for the reachability/cascade logic in RemoveCodeEngine to ever see.
 * SmaliScissors has exactly this problem and solves it the same way: SmaliKeeper reads the
 * manifest and refuses to fully delete a class it declares, no matter how "unreferenced" a
 * pure dex-reference scan would otherwise conclude it is.
 */
internal val manifestProtectedClasses: MutableSet<String> = HashSet()

private val COMPONENT_TAGS = listOf(
    "application", "activity", "activity-alias", "service", "receiver", "provider",
)

private fun String.toClassDescriptor(packageName: String): String {
    val fqcn = when {
        startsWith(".") -> packageName + this
        !contains(".") -> "$packageName.$this"
        else -> this
    }
    return "L" + fqcn.replace('.', '/') + ";"
}

@Suppress("unused")
val collectManifestProtectedClassesPatch = resourcePatch(
    name = "Collect manifest-protected classes",
    description = "Internal dependency for the ads/analytics removal patches: reads AndroidManifest.xml so a " +
            "class Android instantiates directly from a manifest component is never deleted, even when nothing " +
            "in the dex itself still references it.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val packageName = manifest.documentElement.getAttribute("package")
            COMPONENT_TAGS.forEach { tag ->
                val nodes = manifest.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as? Element ?: continue
                    element.getAttribute("android:name").takeIf { it.isNotBlank() }?.let {
                        manifestProtectedClasses += it.toClassDescriptor(packageName)
                    }
                    // activity-alias points at the real component via targetActivity - its
                    // own android:name is just the exported alias, not a class at all.
                    element.getAttribute("android:targetActivity").takeIf { it.isNotBlank() }?.let {
                        manifestProtectedClasses += it.toClassDescriptor(packageName)
                    }
                }
            }
        }
    }
}
