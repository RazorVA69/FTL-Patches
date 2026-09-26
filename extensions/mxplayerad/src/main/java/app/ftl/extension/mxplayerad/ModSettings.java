package app.ftl.extension.mxplayerad;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
            "Home screen",
            "home_hide_bottom_bar",
            "Hide bottom navigation bar",
            "The app reloads to the Local tab when you close this dialog. The Me tab button in " +
                "the toolbar stays either way, so this switch can never lock you out of itself.",
            true,
            false,
            true
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
            "Me tab",
            "me_hide_recycle_bin",
            "Hide Recycle Bin tile",
            "Only hides the tile - deleted files are always removed permanently while this patch " +
                "is applied, on or off.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "hide_private_folder",
            "Hide Private Folder",
            "Hidden everywhere: Me tab tile, 3-dot menu of each file, and the multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Me tab",
            "hide_file_transfer",
            "Hide File Transfer",
            "Hidden everywhere: Me tab tile, 3-dot menu of each file, and the multi-select menu.",
            true,
            true
        ),
        new Entry(
            "Me tab",
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

    /**
     * Floating Dialog with a root screen listing each group (like a settings menu) and a
     * detail screen per group holding its switches - a cheap stand-in for a real nested
     * PreferenceScreen, built only from framework widgets (no androidx.preference dependency,
     * since it isn't confirmed present in this host app at runtime).
     */
    public static void showDialog(final Context host) {
        final Map<String, Boolean> initial = new HashMap<String, Boolean>();
        final boolean[] refreshFailed = {false};

        final LinkedHashMap<String, List<Entry>> groups = new LinkedHashMap<String, List<Entry>>();
        for (Entry entry : ENTRIES) {
            if (!isPatched(host, entry.key)) continue;
            if (entry.tiles || entry.needsReload) initial.put(entry.key, get(entry.key));

            List<Entry> list = groups.get(entry.group);
            if (list == null) {
                list = new ArrayList<Entry>();
                groups.put(entry.group, list);
            }
            list.add(entry);
        }

        boolean dark = (host.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int style = dark
            ? android.R.style.Theme_DeviceDefault_Dialog
            : android.R.style.Theme_DeviceDefault_Light_Dialog;

        final Dialog dialog = new Dialog(host, style);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout content = new FrameLayout(dialog.getContext());
        dialog.setContentView(content);

        final Nav nav = new Nav(dialog, content, groups, initial, refreshFailed);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
                        && nav.currentGroup[0] != null) {
                    renderRoot(nav);
                    return true;
                }
                return false;
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (!refreshFailed[0]) return;
                for (Map.Entry<String, Boolean> before : initial.entrySet()) {
                    if (get(before.getKey()) != before.getValue().booleanValue()) {
                        restartActivity(host);
                        return;
                    }
                }
            }
        });

        renderRoot(nav);

        dialog.show();
    }

    private static void renderRoot(final Nav nav) {
        nav.currentGroup[0] = null;
        Context dc = nav.dialog.getContext();

        LinearLayout screen = new LinearLayout(dc);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(buildHeader(nav, "Mod Settings", true), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(dc, 8), 0, dp(dc, 8));

        if (nav.groups.isEmpty()) {
            TextView empty = new TextView(dc);
            empty.setText("No configurable mods in this build.");
            empty.setAlpha(0.7f);
            empty.setPadding(dp(dc, 20), dp(dc, 20), dp(dc, 20), dp(dc, 20));
            list.addView(empty);
        } else {
            boolean first = true;
            for (final Map.Entry<String, List<Entry>> group : nav.groups.entrySet()) {
                if (!first) list.addView(divider(dc));
                first = false;

                final String groupName = group.getKey();
                LinearLayout row = new LinearLayout(dc);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(dc, 56));
                row.setPadding(dp(dc, 20), dp(dc, 12), dp(dc, 20), dp(dc, 12));
                row.setClickable(true);
                row.setFocusable(true);
                applyRipple(dc, row);

                TextView title = new TextView(dc);
                title.setText(groupName);
                title.setTextSize(16f);
                row.addView(title, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView chevron = new TextView(dc);
                chevron.setText("\u203A");
                chevron.setTextSize(20f);
                chevron.setAlpha(0.5f);
                row.addView(chevron, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        renderCategory(nav, groupName);
                    }
                });

                list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }

        ScrollView scroll = new ScrollView(dc);
        scroll.addView(list, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        nav.content.removeAllViews();
        nav.content.addView(screen, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void renderCategory(final Nav nav, String groupName) {
        nav.currentGroup[0] = groupName;
        Context dc = nav.dialog.getContext();
        List<Entry> entries = nav.groups.get(groupName);

        LinearLayout screen = new LinearLayout(dc);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(buildHeader(nav, groupName, false), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(dc, 4), 0, dp(dc, 8));

        boolean first = true;
        for (final Entry entry : entries) {
            if (!first) list.addView(divider(dc));
            first = false;

            LinearLayout row = new LinearLayout(dc);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(dc, 56));
            row.setPadding(dp(dc, 20), dp(dc, 12), dp(dc, 20), dp(dc, 12));
            row.setClickable(true);
            row.setFocusable(true);
            applyRipple(dc, row);

            LinearLayout text = new LinearLayout(dc);
            text.setOrientation(LinearLayout.VERTICAL);

            TextView title = new TextView(dc);
            title.setText(entry.title);
            title.setTextSize(16f);
            text.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            if (entry.summary != null) {
                TextView summary = new TextView(dc);
                summary.setText(entry.summary);
                summary.setTextSize(13f);
                summary.setAlpha(0.65f);
                summary.setPadding(0, dp(dc, 2), 0, 0);
                text.addView(summary, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            row.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final Switch toggle = new Switch(dc);
            toggle.setChecked(get(entry.key));
            toggle.setPadding(dp(dc, 16), 0, 0, 0);
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    set(entry.key, checked);
                    if (entry.tiles && !refreshTiles()) nav.refreshFailed[0] = true;
                    if (entry.needsReload) nav.refreshFailed[0] = true;
                }
            });
            row.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle.setChecked(!toggle.isChecked());
                }
            });

            list.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(dc);
        scroll.addView(list, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        nav.content.removeAllViews();
        nav.content.addView(screen, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout buildHeader(final Nav nav, String title, final boolean isRoot) {
        Context dc = nav.dialog.getContext();

        LinearLayout wrapper = new LinearLayout(dc);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(dc);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(dc, 56));
        header.setPadding(dp(dc, 4), 0, dp(dc, 20), 0);

        TextView back = new TextView(dc);
        back.setText(isRoot ? "\u2715" : "\u2190");
        back.setTextSize(18f);
        back.setGravity(Gravity.CENTER);
        back.setClickable(true);
        back.setFocusable(true);
        applyRipple(dc, back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRoot) nav.dialog.dismiss();
                else renderRoot(nav);
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(dc, 48), dp(dc, 48)));

        TextView titleView = new TextView(dc);
        titleView.setText(title);
        titleView.setTextSize(19f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(dp(dc, 12), 0, 0, 0);
        header.addView(titleView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        wrapper.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrapper.addView(divider(dc), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dc, 1)));

        return wrapper;
    }

    private static View divider(Context dc) {
        View line = new View(dc);
        line.setBackgroundColor(0x22888888);
        return line;
    }

    private static void applyRipple(Context dc, View view) {
        TypedValue ripple = new TypedValue();
        if (dc.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            view.setBackgroundResource(ripple.resourceId);
        }
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

    /** Bundles the state a screen-render needs, so helper methods don't take six params each. */
    private static final class Nav {
        final Dialog dialog;
        final FrameLayout content;
        final String[] currentGroup = {null};
        final LinkedHashMap<String, List<Entry>> groups;
        final Map<String, Boolean> initial;
        final boolean[] refreshFailed;

        Nav(
            Dialog dialog, FrameLayout content, LinkedHashMap<String, List<Entry>> groups,
            Map<String, Boolean> initial, boolean[] refreshFailed
        ) {
            this.dialog = dialog;
            this.content = content;
            this.groups = groups;
            this.initial = initial;
            this.refreshFailed = refreshFailed;
        }
    }

    private static final class Entry {
        final String group;
        final String key;
        final String title;
        final String summary;
        final boolean def;
        final boolean tiles;
        // Never attempted live, unlike tiles: hiding the bottom bar in place can strand
        // the user if they reached this screen via the bottom bar's own tab click (no
        // back stack, no back arrow) rather than the toolbar's Me tab icon (which pushes
        // its own back-navigable screen). Always falls through to the automatic reload
        // instead, which resets to the Local tab and so can never trap anyone.
        final boolean needsReload;

        Entry(String group, String key, String title, String summary, boolean def, boolean tiles) {
            this(group, key, title, summary, def, tiles, false);
        }

        Entry(
            String group, String key, String title, String summary,
            boolean def, boolean tiles, boolean needsReload
        ) {
            this.group = group;
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
            this.tiles = tiles;
            this.needsReload = needsReload;
        }
    }
}
