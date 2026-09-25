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
import android.view.View;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
            "Home screen",
            "me_hide_tiles_pager",
            "Hide local tiles pager",
            "Hides the local tiles pager and its indicator.",
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
            "me_hide_music_player",
            "Hide Music Player tile",
            "Removes the Music Player tile from the Me tab.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "me_hide_cloud_drive",
            "Hide Cloud Drive tile",
            "Removes the Cloud Drive tile from the Me tab.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "me_show_network_stream",
            "Network Stream tile",
            "Replaces the Video Playlists tile with Network Stream.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_private_folder",
            "Hide Private Folder",
            "Hidden everywhere: Me tab tile, 3-dot menu of each file, and the multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_file_transfer",
            "Hide File Transfer",
            "Hidden everywhere: Me tab tile, 3-dot menu of each file, and the multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Hidden features",
            "hide_add_to_playlist",
            "Hide Add to Playlist",
            "Hidden everywhere: 3-dot menu of each file, the multi-select menu, and the split toolbar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_bookmark",
            "Hide Bookmark",
            "Removes the Bookmark shortcut from the player sidebar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_favourite",
            "Hide Favourite",
            "Removes the Favourite shortcut. If Add to Playlist is also hidden, hiding this hides both.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_add_to_playlist",
            "Hide Add to Playlist",
            "Removes the Add to Playlist shortcut from the player sidebar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_tutorial",
            "Hide Tutorial",
            "Removes the Tutorial shortcut from the player sidebar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_playing_queue",
            "Hide Playing Queue",
            "Removes the Playing Queue shortcut from the player sidebar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_video_display",
            "Hide Video Display row",
            "Hides the Video Display row in the player sidebar.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "sidebar_hide_help",
            "Hide More menu Help section",
            "Hides What's New, Features, FAQ, Check for Update, Bug Report and About.",
            true,
            false
        ),
        new Entry(
            "Sidebar",
            "subtitle_open_settings",
            "Open subtitle settings by default",
            "Expands Sync/Speed/Panel/Customization the next time you open the subtitle menu.",
            true,
            false
        ),
    };

    private static Context appContext;
    private static WeakReference<Object> tilesOwner;
    private static String tilesMethod;

    private ModSettings() {}

    public static boolean get(String key) {
        Entry entry = find(key);
        boolean def = entry != null && entry.def;
        SharedPreferences prefs = prefs(context());
        return prefs == null ? def : prefs.getBoolean(key, def);
    }

    public static void onTilesOwner(Object owner, String method) {
        tilesOwner = new WeakReference<Object>(owner);
        tilesMethod = method;
    }

    private static boolean refreshTiles() {
        Object owner = tilesOwner == null ? null : tilesOwner.get();
        if (owner == null || tilesMethod == null) return false;
        try {
            Method method = owner.getClass().getDeclaredMethod(tilesMethod);
            method.setAccessible(true);
            method.invoke(owner);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
        final boolean[] refreshFailed = {false};

        LinearLayout list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(dc, 24), dp(dc, 8), dp(dc, 24), dp(dc, 8));

        int shown = 0;
        Map<String, LinearLayout> groupContent = new LinkedHashMap<String, LinearLayout>();
        TypedValue accent = new TypedValue();
        final boolean hasAccent = dc.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        final int accentColor = accent.data;

        for (final Entry entry : ENTRIES) {
            if (!isPatched(dc, entry.key)) continue;
            shown++;
            if (entry.tiles) initial.put(entry.key, get(entry.key));

            LinearLayout content = groupContent.get(entry.group);
            if (content == null) {
                final LinearLayout newContent = new LinearLayout(dc);
                newContent.setOrientation(LinearLayout.VERTICAL);
                newContent.setVisibility(View.GONE);
                newContent.setPadding(0, 0, 0, dp(dc, 8));

                final String groupName = entry.group;
                final TextView header = new TextView(dc);
                header.setText("\u25B8  " + groupName);
                header.setTextSize(14f);
                header.setTypeface(null, Typeface.BOLD);
                if (hasAccent) header.setTextColor(accentColor);
                header.setPadding(0, dp(dc, groupContent.isEmpty() ? 4 : 20), 0, dp(dc, 8));
                header.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean expand = newContent.getVisibility() != View.VISIBLE;
                        newContent.setVisibility(expand ? View.VISIBLE : View.GONE);
                        header.setText((expand ? "\u25BE  " : "\u25B8  ") + groupName);
                    }
                });

                list.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                list.addView(newContent, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                groupContent.put(entry.group, newContent);
                content = newContent;
            }

            Switch toggle = new Switch(dc);
            toggle.setText(entry.title);
            toggle.setChecked(get(entry.key));
            toggle.setPadding(0, dp(dc, 10), 0, dp(dc, 2));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    set(entry.key, checked);
                    if (entry.tiles && !refreshTiles()) refreshFailed[0] = true;
                }
            });
            content.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            if (entry.summary != null) {
                TextView summary = new TextView(dc);
                summary.setText(entry.summary);
                summary.setTextSize(13f);
                summary.setAlpha(0.7f);
                content.addView(summary, new LinearLayout.LayoutParams(
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
                    if (!refreshFailed[0]) return;
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
        final boolean tiles;

        Entry(String group, String key, String title, String summary, boolean def, boolean tiles) {
            this.group = group;
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
            this.tiles = tiles;
        }
    }
}
