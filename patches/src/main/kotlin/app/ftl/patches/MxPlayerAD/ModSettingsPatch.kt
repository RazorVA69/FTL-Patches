package app.ftl.patches.mxplayerad

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ME_PAGE_LAYOUT = "res/layout/layout_local_me_page_fragment.xml"
private const val MOD_SETTINGS_ROW_CLASS = "app.ftl.extension.mxplayerad.ModSettingsRow"
private const val MOD_FLAGS_DIR = "assets/ftl_mod"
private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"

internal val addModSettingsEntryPatch = resourcePatch(
    name = null,
    description = "Adds a Mod Settings row to the Me tab, right after the Legal/Help group.",
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    execute {
        document(ME_PAGE_LAYOUT).use { document ->
            val root = document.documentElement
            val anchor = root.findById("group_b")
                ?: error("@id/group_b not found in $ME_PAGE_LAYOUT")
            val parent = anchor.parentNode as? Element
                ?: error("@id/group_b has no parent element in $ME_PAGE_LAYOUT")

            val row = document.createElement(MOD_SETTINGS_ROW_CLASS)
            val parentTag = parent.tagName

            when {
                parentTag.endsWith("LinearLayout") || parentTag.endsWith("LinearLayoutCompat") -> {
                    row.setAttribute("android:layout_width", "match_parent")
                    row.setAttribute("android:layout_height", "wrap_content")
                }

                parentTag.endsWith("ConstraintLayout") -> {
                    if (root.getAttribute("xmlns:app").isEmpty()) {
                        root.setAttribute("xmlns:app", APP_NAMESPACE)
                    }
                    row.setAttribute("android:layout_width", "0dp")
                    row.setAttribute("android:layout_height", "wrap_content")
                    row.setAttribute("app:layout_constraintTop_toBottomOf", "@id/group_b")
                    row.setAttribute("app:layout_constraintStart_toStartOf", "parent")
                    row.setAttribute("app:layout_constraintEnd_toEndOf", "parent")
                }

                parentTag.endsWith("RelativeLayout") -> {
                    row.setAttribute("android:layout_width", "match_parent")
                    row.setAttribute("android:layout_height", "wrap_content")
                    row.setAttribute("android:layout_below", "@id/group_b")
                }

                else -> error("Unsupported parent <$parentTag> for @id/group_b in $ME_PAGE_LAYOUT")
            }

            parent.insertBefore(row, anchor.nextSibling)
        }
    }
}

internal val modSettingsPatch = bytecodePatch(
    name = null,
    description = "Merges the Mod Settings extension and adds its Me tab entry.",
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    dependsOn(addModSettingsEntryPatch)

    extendWith("extensions/mxplayerad.mpe")
}

internal fun modSettingFlagPatch(key: String) = resourcePatch(
    name = null,
    description = "Lists \"$key\" in Mod Settings.",
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    execute {
        get("$MOD_FLAGS_DIR/$key", false).writeText("1")
    }
}
