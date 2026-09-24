package app.ftl.extension.mxplayerad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class ModSettings {
    private static final String PREFS = "ftl_mod_settings";
    private static final String FLAG_DIR = "ftl_mod";

    private static final Entry[] ENTRIES = {
        new Entry(
            "SpeedUp overlay",
            "speedup_no_ui",
            "No UI",
            "On: the long-press SpeedUp overlay never shows. Off: 2x UI.",
            false,
            false
        ),
        new Entry(
            "Smart Enhance",
            "smart_enhance_skip_popup",
            "Skip intro popup",
            "On: the player menu item toggles Smart Enhance directly, without the popup and animation. Off: stock popup.",
            true,
            false
        ),
        new Entry(
            "Smart Enhance",
            "smart_enhance_toast",
            "Toast on enable",
            "On: toast when Smart Enhance turns on. Off: silent toggle.",
            true,
            false
        ),
        new Entry(
            "Me tab",
            "me_hide_status_saver",
            "Hide Status Saver row",
            "Collapses the WhatsApp Status Saver row.",
            true,
            false
        ),
        new Entry(
            "Me tab",
            "me_hide_legal_help",
            "Hide Legal / Help group",
            "Hides Legal, Help and Data privacy.",
            true,
            false
        ),
        new Entry(
            "Me tab",
            "me_hide_tiles_pager",
            "Hide local tiles pager",
            "Hides the local tiles pager and its indicator.",
            true,
            false
        ),
        new Entry(
            "Me tab",
            "me_hide_music_player",
            "Hide Music Player tile",
            "The app reloads when you close this dialog.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "me_hide_cloud_drive",
            "Hide Cloud Drive tile",
            "The app reloads when you close this dialog.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "me_show_network_stream",
            "Network Stream tile",
            "Replaces the Video Playlists tile with Network Stream. The app reloads when you close this dialog.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_private_folder",
            "Hide Private Folder",
            "Me tab tile (the app reloads when you close this dialog), per-file more sheet and multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_file_transfer",
            "Hide File Transfer",
            "Me tab tile (the app reloads when you close this dialog), per-file more sheet and multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_add_to_playlist",
            "Hide Add to Playlist",
            "Per-file more sheet, multi-select menu and split toolbar.",
            true,
            false
        ),
    };

    private static Context appContext;

    private ModSettings() {}

    public static boolean get(String key) {
        Entry entry = find(key);
        boolean def = entry != null && entry.def;
        SharedPreferences prefs = prefs(context());
        return prefs == null ? def : prefs.getBoolean(key, def);
    }

    public static void set(String key, boolean value) {
        SharedPreferences prefs = prefs(context());
        if (prefs != null) prefs.edit().putBoolean(key, value).apply();
        ModViewHider.refreshAll();
    }

    public static void showDialog(final Context host) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        Context dc = builder.getContext();
        final Map<String, Boolean> initial = new HashMap<String, Boolean>();

        LinearLayout list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(dc, 24), dp(dc, 8), dp(dc, 24), dp(dc, 8));

        int shown = 0;
        String lastGroup = null;
        for (final Entry entry : ENTRIES) {
            if (!isPatched(dc, entry.key)) continue;

            if (!entry.group.equals(lastGroup)) {
                lastGroup = entry.group;
                TextView header = new TextView(dc);
                header.setText(entry.group);
                header.setTextSize(14f);
                header.setTypeface(null, Typeface.BOLD);
                TypedValue accent = new TypedValue();
                if (dc.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true)) {
                    header.setTextColor(accent.data);
                }
                header.setPadding(0, dp(dc, shown == 0 ? 4 : 20), 0, dp(dc, 4));
                list.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            shown++;
            if (entry.restart) initial.put(entry.key, get(entry.key));

            Switch toggle = new Switch(dc);
            toggle.setText(entry.title);
            toggle.setChecked(get(entry.key));
            toggle.setPadding(0, dp(dc, 10), 0, dp(dc, 2));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    set(entry.key, checked);
                }
            });
            list.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            if (entry.summary != null) {
                TextView summary = new TextView(dc);
                summary.setText(entry.summary);
                summary.setTextSize(13f);
                summary.setAlpha(0.7f);
                list.addView(summary, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        if (shown == 0) {
            TextView empty = new TextView(dc);
            empty.setText("No configurable mods in this build.");
            empty.setPadding(0, dp(dc, 12), 0, dp(dc, 12));
            list.addView(empty);
        }

        ScrollView scroll = new ScrollView(dc);
        scroll.addView(list);

        builder.setTitle("Mod Settings")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    for (Map.Entry<String, Boolean> before : initial.entrySet()) {
                        if (get(before.getKey()) != before.getValue().booleanValue()) {
                            restartActivity(host);
                            return;
                        }
                    }
                }
            })
            .show();
    }

    private static void restartActivity(Context context) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing()) return;
        Intent intent = new Intent(activity.getIntent());
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.finish();
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static Entry find(String key) {
        for (Entry entry : ENTRIES) {
            if (entry.key.equals(key)) return entry;
        }
        return null;
    }

    private static boolean isPatched(Context context, String key) {
        try {
            String[] names = context.getAssets().list(FLAG_DIR);
            if (names == null) return false;
            for (String name : names) {
                if (name.equals(key)) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static Context context() {
        if (appContext != null) return appContext;
        try {
            Object app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
            if (app instanceof Context) appContext = (Context) app;
        } catch (Throwable ignored) {
        }
        return appContext;
    }

    private static SharedPreferences prefs(Context context) {
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Entry {
        final String group;
        final String key;
        final String title;
        final String summary;
        final boolean def;
        final boolean restart;

        Entry(String group, String key, String title, String summary, boolean def, boolean restart) {
            this.group = group;
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
            this.restart = restart;
        }
    }
}
