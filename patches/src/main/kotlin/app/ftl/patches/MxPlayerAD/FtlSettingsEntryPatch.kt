package app.ftl.patches.mxplayerad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

// Pinned like IV_ME_TOOLBAR_ID / ME_TOOLBAR_ACTION_ID - the cloned row must keep a
// stable id across builds or the bytecode half's findViewById() silently returns null.
// 0x7f0b7ffe/0x7f0b7fff are taken by the Me toolbar action, so take the slot below them.
internal const val FTL_SETTINGS_ROW_ID = 0x7f0b7ffd

// Single source of truth for the in-app dialog. Every patch that gets converted off
// Morphe options appends its (prefKey -> label) pair here; the dialog smali below is
// generated from this list at patch time, and the converted patches read the same
// keys from the same file (see ConfigureSpeedUpOverlayPatch).
internal val ftlSettingsToggles = mutableListOf(
    "noUi" to "SpeedUp overlay: No UI",
)

private const val FTL_PREFS_FILE = "ftl_settings"

private const val VIEW_ONCLICK_LISTENER = "Landroid/view/View${'$'}OnClickListener;"
private const val MULTI_CHOICE_LISTENER = "Landroid/content/DialogInterface${'$'}OnMultiChoiceClickListener;"

/**
 * Matches the method that wires the Me tab's "Status Saver" row: sget of the real,
 * unobfuscated R$id field, findViewById() on the inflated root, then
 * setOnClickListener(). That method is guaranteed to hold a live reference to the
 * Me tab view hierarchy, which is exactly what the cloned FTL Settings row needs -
 * no separate inflate-point fingerprint required.
 */
private object FtlSettingsRowClickFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(name = "whatsapp_status_saver", opcode = Opcode.SGET),
        methodCall(
            smali = "Landroid/view/View;->findViewById(I)Landroid/view/View;",
            location = MatchAfterWithin(3),
        ),
        methodCall(
            smali = "Landroid/view/View;->setOnClickListener(Landroid/view/View${'$'}OnClickListener;)V",
            location = MatchAfterWithin(3),
        ),
    ),
)

// name = null - addFtlSettingsEntryPatch pulls it in via dependsOn.
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

            // Deep clone keeps the row's card styling, paddings, icon slot and arrow
            // without us having to know any of them. The clone is a sibling inserted
            // right after the original, so it lands in the same card group.
            val clone = original.cloneNode(true) as Element
            clone.setAttribute("android:id", "@+id/ftl_settings_row")

            // Duplicate @+id/... in one layout file is an aapt2 error, and stale ids on
            // the clone would also steal findViewById() hits from the real rows - the
            // original's children keep their ids, the clone's lose them.
            val cloneDescendants = clone.getElementsByTagName("*")
            for (i in 0 until cloneDescendants.length) {
                (cloneDescendants.item(i) as Element).removeAttribute("android:id")
            }

            // Relabel: first node in the clone (row itself or its TextView child) that
            // carries an android:text gets the literal title. Literal, not a new
            // <string>, so strings.xml stays untouched.
            val labeled = (listOf(clone) + (0 until cloneDescendants.length)
                .map { cloneDescendants.item(it) as Element })
                .firstOrNull { it.hasAttribute("android:text") }
            labeled?.setAttribute("android:text", "FTL Settings")

            parent.insertBefore(clone, original.nextSibling)
        }

        // Pin the new row id, same rationale as AddMeTabMenuResourcePatch.
        document("res/values/public.xml").use { doc ->
            val entry = doc.createElement("public")
            entry.setAttribute("type", "id")
            entry.setAttribute("name", "ftl_settings_row")
            entry.setAttribute("id", "0x%08x".format(FTL_SETTINGS_ROW_ID))
            doc.documentElement.appendChild(entry)
        }
    }
}

val addFtlSettingsEntryPatch = bytecodePatch(
    name = "Add FTL Settings Entry",
    description = "Adds an FTL Settings row to the Me tab opening an in-app toggle dialog " +
        "backed by the \"$FTL_PREFS_FILE\" SharedPreferences. Converted patches read their " +
        "toggles from there instead of Morphe options.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)
    dependsOn(addFtlSettingsRowResourcePatch)

    execute {
        val matches = FtlSettingsRowClickFingerprint.instructionMatches
        val method = FtlSettingsRowClickFingerprint.method

        // Reuse the matched findViewById()'s own registers: registerC is the live root
        // view (never clobbered), registerD is the id temp, dead after the call. No
        // fresh registers, no liveness risk.
        val findView = matches[1].instruction as FiveRegisterInstruction
        val rootReg = findView.registerC
        val scratchReg = findView.registerD
        val rowIdLiteral = "0x%08x".format(FTL_SETTINGS_ROW_ID)

        method.addInstructions(
            matches[2].index + 1,
            """
                const v$scratchReg, $rowIdLiteral
                invoke-virtual {v$rootReg, v$scratchReg}, Landroid/view/View;->findViewById(I)Landroid/view/View;
                move-result-object v$scratchReg
                if-eqz v$scratchReg, :cond_ftl_row_skip
                invoke-virtual {v$scratchReg, p0}, Landroid/view/View;->setOnClickListener(Landroid/view/View${'$'}OnClickListener;)V
                :cond_ftl_row_skip
            """.trimIndent(),
        )

        // The host becomes its own listener for both the row click and the dialog's
        // multi-choice callbacks - same self-as-listener trick as
        // DisableBottomBarAndAddMeTabPatch, no synthetic listener class needed.
        val host = mutableClassDefBy(FtlSettingsRowClickFingerprint.classDef)
        for (iface in listOf(VIEW_ONCLICK_LISTENER, MULTI_CHOICE_LISTENER)) {
            if (iface !in host.interfaces) host.interfaces.add(iface)
        }

        if (host.methods.none { it.name == "onClick" && it.parameters.size == 1 && it.parameters[0].type == "Landroid/view/View;" }) {
            val onClickView = ImmutableMethod(
                host.type,
                "onClick",
                listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(8),
            ).toMutable()
            onClickView.addInstructions(0, buildDialogSmali())
            host.methods.add(onClickView)
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
                MutableMethodImplementation(4),
            ).toMutable()
            onClickItem.addInstructions(0, persistToggleSmali())
            host.methods.add(onClickItem)
        }
    }
}

// onClick(View): builds the FTL Settings dialog. Context comes from the clicked row
// view (its context IS the host Activity), never from p0 - the host is a fragment-ish
// class, not a Context. Framework AlertDialog on purpose: this build's androidx
// AlertDialog is obfuscated (see RemoveRecycleBinPatch), android.app.* never is.
private fun buildDialogSmali(): String = buildString {
    appendLine("invoke-virtual {p1}, Landroid/view/View;->getContext()Landroid/content/Context;")
    appendLine("move-result-object v0")
    appendLine("const-string v1, \"$FTL_PREFS_FILE\"")
    appendLine("const/4 v2, 0x0")
    appendLine("invoke-virtual {v0, v1, v2}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;")
    appendLine("move-result-object v1")
    appendLine("new-instance v2, Landroid/app/AlertDialog\$Builder;")
    appendLine("invoke-direct {v2, v0}, Landroid/app/AlertDialog\$Builder;-><init>(Landroid/content/Context;)V")
    appendLine("const-string v3, \"FTL Settings\"")
    appendLine("invoke-virtual {v2, v3}, Landroid/app/AlertDialog\$Builder;->setTitle(Ljava/lang/CharSequence;)Landroid/app/AlertDialog\$Builder;")
    appendLine("const/16 v6, 0x${ftlSettingsToggles.size.toString(16)}")
    appendLine("new-array v4, v6, [Ljava/lang/String;")
    ftlSettingsToggles.forEachIndexed { i, (_, label) ->
        appendLine("const-string v3, \"$label\"")
        appendLine("const/16 v5, 0x${i.toString(16)}")
        appendLine("aput-object v3, v4, v5")
    }
    appendLine("new-array v7, v6, [Z")
    ftlSettingsToggles.forEachIndexed { i, (key, _) ->
        appendLine("const-string v3, \"$key\"")
        appendLine("const/4 v5, 0x0")
        appendLine("invoke-interface {v1, v3, v5}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z")
        appendLine("move-result v3")
        appendLine("const/16 v5, 0x${i.toString(16)}")
        appendLine("aput v3, v7, v5")
    }
    appendLine("invoke-virtual {v2, v4, v7, p0}, Landroid/app/AlertDialog\$Builder;->setMultiChoiceItems([Ljava/lang/CharSequence;[ZLandroid/content/DialogInterface\$OnMultiChoiceClickListener;)Landroid/app/AlertDialog\$Builder;")
    appendLine("const-string v3, \"Close\"")
    appendLine("const/4 v4, 0x0")
    appendLine("invoke-virtual {v2, v3, v4}, Landroid/app/AlertDialog\$Builder;->setNegativeButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface\$OnClickListener;)Landroid/app/AlertDialog\$Builder;")
    appendLine("invoke-virtual {v2}, Landroid/app/AlertDialog\$Builder;->show()Landroid/app/AlertDialog;")
    appendLine("return-void")
}.trimIndent()

// onClick(DialogInterface, int, boolean): persists each toggle the moment it flips,
// so converted patches pick the change up on their next run without a restart.
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
        appendLine("if-ne p1, v2, :cond_ftl_toggle_$i")
        appendLine("const-string v3, \"$key\"")
        appendLine("invoke-interface {v1, v3, p2}, Landroid/content/SharedPreferences\$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences\$Editor;")
        appendLine(":cond_ftl_toggle_$i")
    }
    appendLine("invoke-interface {v1}, Landroid/content/SharedPreferences\$Editor;->apply()V")
    appendLine("return-void")
}.trimIndent()
