package app.ftl.patches.mxplayerad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

internal val ftlSettingsToggles = mutableListOf(
    "noUi" to "SpeedUp overlay: No UI",
)

private const val FTL_PREFS_FILE = "ftl_settings"
private const val FTL_CLICK_HANDLER = "openFtlSettings"
private const val MULTI_CHOICE_LISTENER = "Landroid/content/DialogInterface${'$'}OnMultiChoiceClickListener;"

private object FtlSettingsHostFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/view/Menu;"),
    filters = listOf(
        methodCall(smali = "Lcom/mxtech/app/Apps;->j(Landroid/view/Menu;IZ)V"),
        methodCall(
            definingClass = "this",
            parameters = listOf("Landroid/view/Menu;"),
            returnType = "V",
        ),
        opcode(Opcode.RETURN, location = MatchAfterImmediately()),
    ),
)

internal val addFtlSettingsRowResourcePatch = resourcePatch(
    name = null,
    description = "Clones the Status Saver row into an FTL Settings row on the Me tab.",
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    execute {
        document("res/layout/layout_local_me_page_fragment.xml").use { doc ->
            val original = doc.documentElement.findById("whatsapp_status_saver")
                ?: error("whatsapp_status_saver row not found in layout_local_me_page_fragment.xml")
            val parent = original.parentNode

            val clone = original.cloneNode(true) as Element
            clone.setAttribute("android:id", "@+id/ftl_settings_row")
            clone.setAttribute("android:onClick", FTL_CLICK_HANDLER)

            val cloneDescendants = clone.getElementsByTagName("*")
            for (i in 0 until cloneDescendants.length) {
                (cloneDescendants.item(i) as Element).removeAttribute("android:id")
            }

            (listOf(clone) + (0 until cloneDescendants.length)
                .map { cloneDescendants.item(it) as Element })
                .firstOrNull { it.hasAttribute("android:text") }
                ?.setAttribute("android:text", "FTL Settings")

            parent.insertBefore(clone, original.nextSibling)
        }
    }
}

val addFtlSettingsEntryPatch = bytecodePatch(
    name = "Add FTL Settings Entry",
    description = "Adds an FTL Settings row to the Me tab opening an in-app toggle dialog.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)
    dependsOn(addFtlSettingsRowResourcePatch)

    execute {
        val host = mutableClassDefBy(FtlSettingsHostFingerprint.classDef)
        if (MULTI_CHOICE_LISTENER !in host.interfaces) host.interfaces.add(MULTI_CHOICE_LISTENER)

        if (host.methods.none { it.name == FTL_CLICK_HANDLER }) {
            val handler = ImmutableMethod(
                host.type,
                FTL_CLICK_HANDLER,
                listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(12), // 10 v-registers + 2 params
            ).toMutable()
            handler.addInstructions(0, buildDialogSmali())
            host.methods.add(handler)
        }

        if (host.methods.none { it.name == "onClick" && it.parameters.size == 3 }) {
            val onClickItem = ImmutableMethod(
                host.type,
                "onClick",
                listOf(
                    ImmutableMethodParameter("Landroid/content/DialogInterface;", null, null),
                    ImmutableMethodParameter("I", null, null),
                    ImmutableMethodParameter("Z", null, null),
                ),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(8), // 4 v-registers + 4 params
            ).toMutable()
            onClickItem.addInstructions(0, persistToggleSmali())
            host.methods.add(onClickItem)
        }
    }
}

// p0 = this (host), p1 = View
private fun buildDialogSmali(): String = buildString {
    appendLine("invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;")
    appendLine("move-result-object v0")
    appendLine("const-string v3, \"$FTL_PREFS_FILE\"")
    appendLine("const/4 v7, 0x0")
    appendLine("invoke-virtual {v0, v3, v7}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;")
    appendLine("move-result-object v1")
    appendLine("new-instance v2, Landroid/app/AlertDialog\$Builder;")
    appendLine("invoke-direct {v2, v0}, Landroid/app/AlertDialog\$Builder;-><init>(Landroid/content/Context;)V")
    appendLine("const-string v3, \"FTL Settings\"")
    appendLine("invoke-virtual {v2, v3}, Landroid/app/AlertDialog\$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog\$Builder;")
    appendLine("const/16 v6, 0x${ftlSettingsToggles.size.toString(16)}")
    appendLine("new-array v4, v6, [Ljava/lang/String;")
    ftlSettingsToggles.forEachIndexed { i, (_, label) ->
        appendLine("const-string v3, \"$label\"")
        appendLine("const/16 v7, 0x${i.toString(16)}")
        appendLine("aput-object v3, v4, v7")
    }
    appendLine("new-array v5, v6, [Z")
    ftlSettingsToggles.forEachIndexed { i, (key, _) ->
        appendLine("const-string v3, \"$key\"")
        appendLine("const/4 v8, 0x0")
        appendLine("invoke-interface {v1, v3, v8}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z")
        appendLine("move-result v8") // v8 is strictly boolean now
        appendLine("const/16 v7, 0x${i.toString(16)}")
        appendLine("aput-boolean v8, v5, v7") // FIXED: using strictly boolean v8
    }
    appendLine("invoke-virtual {v2, v4, v5, p0}, Landroid/app/AlertDialog\$Builder;->setMultiChoiceItems([Ljava/lang/CharSequence;[ZLandroid/content/DialogInterface\$OnMultiChoiceClickListener;)Landroid/app/AlertDialog\$Builder;")
    appendLine("const-string v3, \"Close\"")
    appendLine("const/4 v4, 0x0")
    appendLine("invoke-virtual {v2, v3, v4}, Landroid/app/AlertDialog\$Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface\$OnClickListener;)Landroid/app/AlertDialog\$Builder;")
    appendLine("invoke-virtual {v2}, Landroid/app/AlertDialog\$Builder;->show()Landroid/app/AlertDialog;")
    appendLine("return-void")
}.trimIndent()

// p0 = this, p1 = DialogInterface, p2 = which (int), p3 = isChecked (boolean)
private fun persistToggleSmali(): String = buildString {
    appendLine("invoke-static {}, Landroid/app/ActivityThread;->currentApplication()Landroid/app/Application;")
    appendLine("move-result-object v0")
    appendLine("const-string v1, \"$FTL_PREFS_FILE\"")
    appendLine("const/4 v2, 0x0")
    appendLine("invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;")
    appendLine("move-result-object v0")
    appendLine("invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences\$Editor;")
    appendLine("move-result-object v1")
    ftlSettingsToggles.forEachIndexed { i, (key, _) ->
        appendLine("const/16 v2, 0x${i.toString(16)}")
        appendLine("if-ne p2, v2, :cond_ftl_toggle_$i") // FIXED: p2 is the index (int)
        appendLine("const-string v3, \"$key\"")
        appendLine("invoke-interface {v1, v3, p3}, Landroid/content/SharedPreferences\$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences\$Editor;") // FIXED: p3 is the boolean
        appendLine(":cond_ftl_toggle_$i")
    }
    appendLine("invoke-interface {v1}, Landroid/content/SharedPreferences\$Editor;->apply()V")
    appendLine("return-void")
}.trimIndent()
