package app.ftl.extension.mxplayerad;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class ModSettings {
    private static final String PREFS = "ftl_mod_settings";
    private static final String FLAG_PREFIX = "ftl_mod_";
    private static final String FALLBACK_PACKAGE = "com.mxtech.videoplayer.ad";

    private static final Entry[] ENTRIES = {
        new Entry(
            "speedup_no_ui",
            "SpeedUp overlay: No UI",
            "On: the long-press SpeedUp overlay never shows. Off: 2x UI.",
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
    }

    public static void showDialog(Context host) {
        AlertDialog.Builder builder = new AlertDialog.Builder(host);
        Context dc = builder.getContext();

        LinearLayout list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(dc, 24), dp(dc, 8), dp(dc, 24), dp(dc, 8));

        int shown = 0;
        for (final Entry entry : ENTRIES) {
            if (!isPatched(dc, entry.key)) continue;
            shown++;

            Switch toggle = new Switch(dc);
            toggle.setText(entry.title);
            toggle.setChecked(get(entry.key));
            toggle.setPadding(0, dp(dc, 12), 0, dp(dc, 4));
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
            .show();
    }

    private static Entry find(String key) {
        for (Entry entry : ENTRIES) {
            if (entry.key.equals(key)) return entry;
        }
        return null;
    }

    private static boolean isPatched(Context context, String key) {
        Resources resources = context.getResources();
        String name = FLAG_PREFIX + key;
        return resources.getIdentifier(name, "bool", context.getPackageName()) != 0
            || resources.getIdentifier(name, "bool", FALLBACK_PACKAGE) != 0;
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
        final String key;
        final String title;
        final String summary;
        final boolean def;

        Entry(String key, String title, String summary, boolean def) {
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
        }
    }
}
