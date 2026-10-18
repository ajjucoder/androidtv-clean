package com.parents.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home screen. One screen, no scrolling: a photo for the time of day, a greeting with the
 * weather, five big tiles for what the family watches, and a pill bar for the TV box,
 * the Play Store and everything else. Everything else opens as a sheet over the photo.
 * Focus is moved by hand so the D-pad always goes one row / one item at a time.
 */
public class MainActivity extends Activity {

    static final String LIVE_TV = "live-tv";
    /** Big tiles, in order. Missing apps are skipped. */
    static final String[] DOCK = {
            LIVE_TV,
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
    };
    static final String STORE = "com.android.vending";
    /** The HDMI socket the set-top box is on. */
    static final String BOX_SOCKET = "HDMI 4";
    /** Never shown as app tiles. */
    static final List<String> HIDDEN = Arrays.asList(
            "com.parents.tvhome",
            "com.android.tv.settings",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
            "com.sony.dtv.tvxlauncher.programguide",
            "com.sony.dtv.tvx",
            STORE);

    static final float SIDE = 64, GAP = 20;
    static final float HERO_TOP = 136, DOCK_TOP = 276, BAR_GAP = 54;
    static final int COLS = 6;
    static final float CELL_GAP = 18, SHEET_TOP = 112;

    /** Focus moves happen dozens of times a session: keep them quick. */
    static final int FOCUS_MS = 150;
    static final float PRESS_SCALE = 0.96f;
    static final Interpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    static final Interpolator EASE_DRAWER = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    static final int WHITE_SOFT = 0xE6FFFFFF, GREY = 0x99FFFFFF;

    static class App {
        String pkg, label;
        ComponentName component;
        Drawable banner, icon;
    }

    static final int T_TILE = 0, T_PILL = 1, T_BUTTON = 2;

    /** Anything that can take focus. */
    static class Item {
        int type;
        View view, card, gloss, label;
        TextView title;
        Glyph glyph;
        String key;
        float scale = 1.08f;
        Runnable action, longAction;
    }

    static class Row {
        final List<Item> items = new ArrayList<>();
        float top, height; // dp, in its page's coordinates
    }

    float dp;
    Typeface regular, medium, semibold;
    FrameLayout root, content, sheet, sheetPage;
    ImageView photoA, photoB;
    String photoNow;
    View dimmer, hero, topBar;
    TextView greeting, time, dateText, weatherText;
    LinearLayout weatherChip;
    Glyph weatherGlyph;
    final List<View> built = new ArrayList<>();
    final List<View> entrance = new ArrayList<>();
    final Map<String, App> cache = new HashMap<>();
    Item settingsItem;

    /** Focus rows of the home screen (settings, tiles, pills) and of the apps sheet. */
    final List<Row> homeRows = new ArrayList<>(), sheetRows = new ArrayList<>();
    List<Row> rows = homeRows;
    boolean sheetOpen;
    static final int DOCK_ROW = 1;
    int row = DOCK_ROW, col = 0, lastDockCol = 0, homeRow, homeCol;
    Item focused;

    /** Rearranging the big tiles: long-press OK, move with left/right, OK to finish. */
    boolean moving, moveOkDown, okHeld;
    float dockW;
    View pillBar, moveHint;
    /** What the phone remote can open, in tile order: key -> name. */
    volatile Map<String, String> remoteTargets = new LinkedHashMap<>();
    String boxTarget;
    RemoteServer remote;
    Sounds sounds;

    final Handler main = new Handler(Looper.getMainLooper());
    TvInputManager tim;

    final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (pkg != null) cache.remove(pkg);
            rebuild();
        }
    };
    final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String id) { rebuild(); }
        @Override public void onInputRemoved(String id) { rebuild(); }
        @Override public void onInputUpdated(String id) { rebuild(); }
    };
    final Runnable weatherLoop = new Runnable() {
        @Override public void run() {
            fetchWeather();
            main.postDelayed(this, 30 * 60 * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        regular = Typeface.createFromAsset(getAssets(), "fonts/Geist-Regular.ttf");
        medium = Typeface.createFromAsset(getAssets(), "fonts/Geist-Medium.ttf");
        semibold = Typeface.createFromAsset(getAssets(), "fonts/Geist-SemiBold.ttf");
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        buildShell();
        updateClock();
        rebuild();
        playEntrance();

        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, tf);
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        if (tim != null) tim.registerCallback(inputWatch, main);
        main.post(weatherLoop);
        remote = new RemoteServer(this);
        remote.start();
        sounds = new Sounds(this);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(tick);
        unregisterReceiver(packages);
        if (tim != null) tim.unregisterCallback(inputWatch);
        main.removeCallbacksAndMessages(null);
        if (remote != null) remote.stop();
        if (sounds != null) sounds.release();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        if (focused != null) focused.view.requestFocus();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        playReturn();
    }

    @Override
    protected void onStop() {
        // Coming back from an app always lands on the home screen, not the sheet.
        if (moving) endMove();
        if (sheetOpen) closeSheet(false);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the big tiles.
        if (moving) endMove();
        if (sheetOpen) closeSheet(true);
        if (row != DOCK_ROW) moveTo(DOCK_ROW, lastDockCol);
    }

    // ---------------------------------------------------------------- helpers

    int px(float v) { return Math.round(v * dp); }

    TextView text(Typeface tf, float sp, int color, float tracking) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLetterSpacing(tracking);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    FrameLayout.LayoutParams at(float w, float h, float left, float top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w < 0 ? (int) w : px(w), h < 0 ? (int) h : px(h), Gravity.TOP | Gravity.START);
        lp.leftMargin = px(left);
        lp.topMargin = px(top);
        return lp;
    }

    GradientDrawable shape(float radius, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radius));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }

    void roundClip(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    float centreX(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] + v.getWidth() / 2f;
    }

    // ---------------------------------------------------------------- shell

    void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        // The photo, already graded (darker top edge, left side and bottom) so no overlays
        // are drawn on top of it every frame. Two layers so the time-of-day change can cross-fade.
        photoA = photoLayer();
        photoB = photoLayer();
        root.addView(photoA, new FrameLayout.LayoutParams(-1, -1));
        root.addView(photoB, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Top right: time and Settings.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        time = text(medium, 18, Color.WHITE, -0.01f);
        bar.addView(time);
        settingsItem = roundButton(Glyph.GEAR);
        settingsItem.action = () -> launch(new Intent(Settings.ACTION_SETTINGS));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(40), px(40));
        blp.leftMargin = px(16);
        bar.addView(settingsItem.view, blp);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        barLp.topMargin = px(34);
        barLp.rightMargin = px(SIDE);
        content.addView(bar, barLp);
        topBar = bar;

        // Hero: greeting, then quiet facts in pills.
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        greeting = text(semibold, 40, Color.WHITE, -0.035f);
        // Tight tracking makes the measured width a hair short: never ellipsize the greeting.
        greeting.setEllipsize(null);
        greeting.setPadding(0, 0, px(8), 0);
        h.addView(greeting);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, px(30));
        clp.topMargin = px(16);
        h.addView(chips, clp);
        LinearLayout dateChip = chip(Glyph.CALENDAR);
        dateText = (TextView) dateChip.getChildAt(1);
        chips.addView(dateChip, new LinearLayout.LayoutParams(-2, -1));
        weatherChip = chip(Glyph.CLOUD);
        weatherGlyph = (Glyph) ((ImageView) weatherChip.getChildAt(0)).getDrawable();
        weatherText = (TextView) weatherChip.getChildAt(1);
        weatherChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -1);
        wlp.leftMargin = px(8);
        chips.addView(weatherChip, wlp);
        content.addView(h, at(-1, -2, SIDE - 2, HERO_TOP));
        hero = h;

        // The apps sheet: the photo sinks back, the apps come up over it.
        dimmer = new View(this);
        dimmer.setBackgroundColor(Color.BLACK);
        dimmer.setAlpha(0f);
        dimmer.setVisibility(View.GONE);
        root.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));
        sheet = new FrameLayout(this);
        sheet.setClipChildren(false);
        sheet.setVisibility(View.GONE);
        sheetPage = new FrameLayout(this);
        sheetPage.setClipChildren(false);
        sheet.addView(sheetPage, new FrameLayout.LayoutParams(-1, px(2400)));
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    ImageView photoLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    /** Small dark-glass pill holding a glyph and a fact. */
    LinearLayout chip(int glyph) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(11), 0, px(13), 0);
        c.setBackground(shape(100, 0x52000000, 0x26FFFFFF));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Glyph(glyph, WHITE_SOFT));
        c.addView(ic, new LinearLayout.LayoutParams(px(15), px(15)));
        TextView t = text(medium, 13, WHITE_SOFT, 0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(7);
        c.addView(t, tlp);
        return c;
    }

    /** Round icon button with a hairline ring. */
    Item roundButton(int glyph) {
        Item it = new Item();
        it.type = T_BUTTON;
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setBackground(shape(100, 0x33000000, 0x33FFFFFF));
        ImageView ic = new ImageView(this);
        it.glyph = new Glyph(glyph, Color.WHITE);
        ic.setImageDrawable(it.glyph);
        f.addView(ic, new FrameLayout.LayoutParams(px(19), px(19), Gravity.CENTER));
        it.view = f;
        it.card = f;
        it.scale = 1.1f;
        f.setTag(it);
        f.setOnClickListener(v -> { if (it.action != null) it.action.run(); });
        return it;
    }

    // ---------------------------------------------------------------- time, photo, weather

    void updateClock() {
        Date now = new Date();
        time.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", now));
        dateText.setText(DateFormat.format("EEEE d MMMM", now));
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hr >= 5 && hr < 12 ? "Good morning" : hr >= 12 && hr < 17 ? "Good afternoon" : "Good evening");
        String p = hr >= 5 && hr < 11 ? "morning" : hr >= 11 && hr < 17 ? "day" : hr >= 17 && hr < 21 ? "evening" : "night";
        if (!p.equals(photoNow)) showPhoto(p, photoNow != null);
    }

    /** Cross-fades to the photo for this part of the day. Happens four times a day, so it can be slow. */
    void showPhoto(String name, boolean animate) {
        Bitmap bm;
        try (InputStream in = getAssets().open("bg/" + name + ".jpg")) {
            bm = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return;
        }
        photoNow = name;
        int ms = animate ? 2000 : 700;
        if (photoB.getAlpha() < 0.5f) {
            // New photo on the top layer: fade it in over the old one.
            photoB.setImageBitmap(bm);
            photoB.animate().alpha(1f).setDuration(ms).setInterpolator(EASE_OUT)
                    .withEndAction(() -> photoA.setImageDrawable(null)).start();
        } else {
            // New photo on the bottom layer: show it, then fade the top one away.
            photoA.setImageBitmap(bm);
            photoA.setAlpha(1f);
            photoB.animate().alpha(0f).setDuration(ms).setInterpolator(EASE_OUT)
                    .withEndAction(() -> photoB.setImageDrawable(null)).start();
        }
    }

    void fetchWeather() {
        new Thread(() -> {
            try {
                SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
                if (System.currentTimeMillis() - sp.getLong("locAt", 0) > 24L * 3600 * 1000) {
                    JSONObject loc = new JSONObject(get("https://ipapi.co/json/"));
                    sp.edit().putFloat("lat", (float) loc.getDouble("latitude"))
                            .putFloat("lon", (float) loc.getDouble("longitude"))
                            .putLong("locAt", System.currentTimeMillis()).apply();
                }
                float lat = sp.getFloat("lat", 0), lon = sp.getFloat("lon", 0);
                if (lat == 0 && lon == 0) return;
                JSONObject cur = new JSONObject(get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                + "&current=temperature_2m,weather_code,is_day", lat, lon)))
                        .getJSONObject("current");
                final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                final int code = cur.getInt("weather_code");
                final boolean day = cur.optInt("is_day", 1) == 1;
                main.post(() -> showWeather(temp, code, day));
            } catch (Throwable ignored) {
                // No network or service down: the chip just stays hidden.
            }
        }).start();
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tvhome/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes, in plain words. */
    void showWeather(int temp, int code, boolean day) {
        String what;
        int g;
        if (code == 0) { what = "Clear"; g = day ? Glyph.SUN : Glyph.MOON; }
        else if (code <= 2) { what = "Partly cloudy"; g = Glyph.CLOUD; }
        else if (code == 3) { what = "Cloudy"; g = Glyph.CLOUD; }
        else if (code <= 48) { what = "Fog"; g = Glyph.CLOUD; }
        else if (code <= 67 || (code >= 80 && code <= 82)) { what = "Rain"; g = Glyph.RAIN; }
        else if (code <= 86) { what = "Snow"; g = Glyph.RAIN; }
        else { what = "Storms"; g = Glyph.RAIN; }
        weatherGlyph.kind = g;
        weatherGlyph.invalidateSelf();
        weatherText.setText(temp + "°  " + what);
        if (weatherChip.getVisibility() != View.VISIBLE) {
            weatherChip.setAlpha(0f);
            weatherChip.setVisibility(View.VISIBLE);
            weatherChip.animate().alpha(1f).setDuration(300).setInterpolator(EASE_OUT).start();
        }
    }

    // ---------------------------------------------------------------- home

    void rebuild() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!found.containsKey(p)) found.put(p, ri);
            }
        }
        List<App> rest = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet())
            if (!HIDDEN.contains(e.getKey()) && !Arrays.asList(DOCK).contains(e.getKey()))
                rest.add(app(e.getValue()));
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));

        for (View v : built) content.removeView(v);
        built.clear();
        entrance.clear();
        homeRows.clear();

        Row top = new Row();
        top.items.add(settingsItem);
        homeRows.add(top);

        // Big tiles across the full width.
        float w = (960 - SIDE * 2 - GAP * (DOCK.length - 1)) / DOCK.length;
        float h = w * 9f / 16f;
        dockW = w;
        Row dock = new Row();
        dock.top = DOCK_TOP;
        dock.height = h;
        float x = SIDE;
        Map<String, String> targets = new LinkedHashMap<>();
        for (String p : dockOrder()) {
            Item t;
            if (p.equals(LIVE_TV)) t = liveTvTile(w, h);
            else if (found.containsKey(p)) t = tile(app(found.get(p)), w, h, true);
            else continue;
            t.key = p;
            t.longAction = this::startMove;
            targets.put(p, p.equals(LIVE_TV) ? "Live TV" : app(found.get(p)).label);
            add(content, t.view, at(w, h + 40, x, DOCK_TOP));
            entrance.add(t.view);
            dock.items.add(t);
            x += w + GAP;
        }
        homeRows.add(dock);

        // Pill bar: the TV box, the Play Store, all other apps.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(px(4), px(4), px(4), px(4));
        bar.setBackground(shape(24, 0x59000000, 0x1FFFFFFF));
        Row pills = new Row();
        pills.top = DOCK_TOP + h + BAR_GAP;
        pills.height = 48;
        Item box = boxPill();
        if (box != null) {
            pills.items.add(box);
            targets.put("box", box.title.getText().toString());
        }
        remoteTargets = targets;
        if (found.containsKey(STORE)) {
            App store = app(found.get(STORE));
            Item s = pill(null, store.icon, "Play Store");
            s.action = () -> launch(store);
            pills.items.add(s);
        }
        if (!rest.isEmpty()) {
            Item all = pill(new Glyph(Glyph.GRID, Color.WHITE), null, "All apps");
            all.action = () -> openSheet(rest);
            pills.items.add(all);
        }
        for (int i = 0; i < pills.items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(40));
            if (i > 0) lp.leftMargin = px(2);
            bar.addView(pills.items.get(i).view, lp);
        }
        if (!pills.items.isEmpty()) {
            add(content, bar, at(-2, 48, SIDE, pills.top));
            entrance.add(bar);
            homeRows.add(pills);
        }
        pillBar = bar;

        // Shown in place of the bar while tiles are being moved.
        TextView hint = text(medium, 15, WHITE_SOFT, -0.005f);
        hint.setText("Move with  \u2039  \u203A    \u00B7    Press OK when done");
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(px(20), 0, px(20), 0);
        hint.setBackground(shape(24, 0x59000000, 0x1FFFFFFF));
        hint.setAlpha(0f);
        add(content, hint, at(-2, 48, SIDE, pills.top));
        moveHint = hint;

        if (!sheetOpen) {
            rows = homeRows;
            focused = null;
            moveTo(Math.max(0, Math.min(row, rows.size() - 1)), col, false);
        }
    }

    void add(FrameLayout parent, View v, FrameLayout.LayoutParams lp) {
        parent.addView(v, lp);
        if (parent == content) built.add(v);
    }

    App app(ResolveInfo ri) {
        ActivityInfo ai = ri.activityInfo;
        App cached = cache.get(ai.packageName);
        if (cached != null) return cached;
        PackageManager pm = getPackageManager();
        App a = new App();
        a.pkg = ai.packageName;
        a.component = new ComponentName(ai.packageName, ai.name);
        a.label = String.valueOf(ri.loadLabel(pm));
        a.banner = ai.loadBanner(pm);
        if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
        a.icon = ri.loadIcon(pm);
        cache.put(a.pkg, a);
        return a;
    }

    /** The frame every big and small tile shares: rounded, 1px light edge, gloss and label on focus. */
    Item frame(View art, String name, float w, float h, boolean big) {
        Item it = new Item();
        it.type = T_TILE;
        FrameLayout f = new FrameLayout(this);
        f.setClipChildren(false);
        f.setFocusable(true);

        FrameLayout card = new FrameLayout(this);
        card.setPivotX(px(w) / 2f);
        card.setPivotY(px(h) / 2f);
        float radius = big ? 14 : 11;
        roundClip(card, radius);
        card.setOutlineAmbientShadowColor(Color.BLACK);
        card.setOutlineSpotShadowColor(Color.BLACK);
        card.setBackgroundColor(0xFF1C1C1E);
        card.setForeground(shape(radius, Color.TRANSPARENT, 0x1AFFFFFF));
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        // Light catching the tile on focus, like a tvOS icon.
        View gloss = new View(this);
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x24FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        gloss.setAlpha(0f);
        card.addView(gloss, new FrameLayout.LayoutParams(-1, -1));
        f.addView(card, new FrameLayout.LayoutParams(px(w), px(h)));

        TextView label = text(medium, big ? 15 : 13, Color.WHITE, -0.005f);
        label.setText(name);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        llp.topMargin = px(h + (big ? 16 : 12));
        f.addView(label, llp);

        it.view = f;
        it.card = card;
        it.gloss = gloss;
        it.label = label;
        it.scale = big ? 1.08f : 1.1f;
        f.setTag(it);
        f.setOnClickListener(v -> { if (it.action != null) it.action.run(); });
        f.setOnLongClickListener(v -> {
            if (it.longAction == null) return false;
            it.longAction.run();
            return true;
        });
        return it;
    }

    /** An app's banner, or its icon and name on graphite when it has none. */
    Item tile(App a, float w, float h, boolean big) {
        View art;
        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            art = iv;
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF2C2C2E, 0xFF1C1C1E}));
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            int s = px(big ? 44 : 34);
            box.addView(iv, new LinearLayout.LayoutParams(s, s));
            art = box;
        }
        Item it = frame(art, a.label, w, h, big);
        it.action = () -> launch(a);
        it.longAction = () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg)));
        return it;
    }

    /** Live TV has no banner of its own worth showing, so it gets one in the same family as the others. */
    Item liveTvTile(float w, float h) {
        LinearLayout art = new LinearLayout(this);
        art.setOrientation(LinearLayout.HORIZONTAL);
        art.setGravity(Gravity.CENTER);
        art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2C2C2E, 0xFF161618}));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Glyph(Glyph.TV, Color.WHITE));
        art.addView(ic, new LinearLayout.LayoutParams(px(28), px(28)));
        TextView t = text(semibold, 20, Color.WHITE, -0.02f);
        t.setText("Live TV");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(10);
        art.addView(t, tlp);
        Item it = frame(art, "Live TV", w, h, true);
        it.action = () -> launch(new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI));
        return it;
    }

    /** Pill in the bar: glyph or app icon, then a name. Focus turns it white. */
    Item pill(Glyph glyph, Drawable icon, String name) {
        Item it = new Item();
        it.type = T_PILL;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(true);
        p.setPadding(px(14), 0, px(18), 0);
        p.setBackground(shape(20, Color.TRANSPARENT, 0));
        ImageView ic = new ImageView(this);
        if (glyph != null) ic.setImageDrawable(glyph); else ic.setImageDrawable(icon);
        p.addView(ic, new LinearLayout.LayoutParams(px(18), px(18)));
        TextView t = text(medium, 15, Color.WHITE, -0.005f);
        t.setText(name);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(9);
        p.addView(t, tlp);
        it.view = p;
        it.card = p;
        it.title = t;
        it.glyph = glyph;
        it.scale = 1f;
        p.setTag(it);
        p.setOnClickListener(v -> { if (it.action != null) it.action.run(); });
        return it;
    }

    /** The set-top box on HDMI 4, under the name it gives over HDMI-CEC when it has one. */
    Item boxPill() {
        if (tim == null) return null;
        TvInputInfo socket = null, device = null;
        List<TvInputInfo> all = tim.getTvInputList();
        for (TvInputInfo info : all)
            if (info.isPassthroughInput() && info.getParentId() == null
                    && name(info).replace("/ARC", "").trim().equalsIgnoreCase(BOX_SOCKET)) socket = info;
        if (socket == null) return null;
        for (TvInputInfo info : all)
            if (socket.getId().equals(info.getParentId())) device = info;
        final String target = (device != null ? device : socket).getId();
        boxTarget = target;
        Item it = pill(new Glyph(Glyph.HDMI, Color.WHITE), null, device != null ? name(device) : BOX_SOCKET);
        it.action = () -> launch(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(target)));
        return it;
    }

    String name(TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(this);
        return String.valueOf(custom != null && custom.length() > 0 ? custom : info.loadLabel(this));
    }

    // ---------------------------------------------------------------- apps sheet

    void openSheet(List<App> apps) {
        homeRow = row;
        homeCol = col;
        sheetPage.removeAllViews();
        sheetPage.setTranslationY(0);
        sheetRows.clear();

        TextView title = text(semibold, 28, Color.WHITE, -0.025f);
        title.setText("All apps");
        sheetPage.addView(title, at(-2, 34, SIDE - 1, 48));
        TextView hint = text(regular, 14, GREY, 0f);
        hint.setText("Hold OK on an app for its settings");
        sheetPage.addView(hint, at(-2, 20, SIDE, 86));

        float w = (960 - SIDE * 2 - CELL_GAP * (COLS - 1)) / COLS, h = w * 9f / 16f;
        Row r = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                r = new Row();
                r.top = SHEET_TOP + 26 + (i / COLS) * (h + 44);
                r.height = h;
                sheetRows.add(r);
            }
            Item t = tile(apps.get(i), w, h, false);
            sheetPage.addView(t.view, at(w, h + 34, SIDE + (i % COLS) * (w + CELL_GAP), r.top));
            r.items.add(t);
        }

        sheetOpen = true;
        if (focused != null) setFocusLook(focused, false, false);
        focused = null;
        rows = sheetRows;
        moveTo(0, 0, false);

        // Occasional, so it gets a short entrance: photo dims, sheet rises 12dp and fades in.
        dimmer.setVisibility(View.VISIBLE);
        dimmer.animate().alpha(0.72f).setDuration(250).setInterpolator(EASE_OUT).start();
        sheet.setVisibility(View.VISIBLE);
        sheet.setAlpha(0f);
        sheet.setTranslationY(px(12));
        sheet.animate().alpha(1f).translationY(0).setDuration(250).setInterpolator(EASE_OUT).start();
        content.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT).start();
    }

    void closeSheet(boolean animate) {
        sheetOpen = false;
        int ms = animate ? 200 : 0;
        dimmer.animate().alpha(0f).setDuration(ms).setInterpolator(EASE_OUT)
                .withEndAction(() -> dimmer.setVisibility(View.GONE)).start();
        sheet.animate().alpha(0f).translationY(px(8)).setDuration(ms).setInterpolator(EASE_OUT)
                .withEndAction(() -> { sheet.setVisibility(View.GONE); sheetPage.removeAllViews(); }).start();
        content.animate().alpha(1f).setDuration(ms).setInterpolator(EASE_OUT).start();
        focused = null;
        rows = homeRows;
        moveTo(Math.min(homeRow, rows.size() - 1), homeCol, false);
    }

    // ---------------------------------------------------------------- focus

    int rowSize(int r) { return r < 0 || r >= rows.size() ? 0 : rows.get(r).items.size(); }

    /** The item in the target row whose centre is nearest the current one. */
    int nearest(int fromRow, int fromCol, int toRow) {
        float cx = centreX(rows.get(fromRow).items.get(fromCol).view);
        int best = 0;
        float bestD = Float.MAX_VALUE;
        List<Item> to = rows.get(toRow).items;
        for (int i = 0; i < to.size(); i++) {
            float d = Math.abs(centreX(to.get(i).view) - cx);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    void moveTo(int r, int c) { moveTo(r, c, true); }

    void moveTo(int r, int c, boolean animate) {
        if (rowSize(r) == 0) { r = sheetOpen ? 0 : DOCK_ROW; c = 0; }
        if (rowSize(r) == 0) return;
        c = Math.max(0, Math.min(c, rowSize(r) - 1));
        Item next = rows.get(r).items.get(c);
        if (focused != null && focused != next) {
            setFocusLook(focused, false, animate);
            if (animate && sounds != null) sounds.tick();
        }
        row = r;
        col = c;
        if (!sheetOpen && r == DOCK_ROW) lastDockCol = c;
        focused = next;
        setFocusLook(next, true, animate);
        next.view.requestFocus();
        if (sheetOpen) scrollSheet(r, animate);
    }

    void setFocusLook(Item it, boolean on, boolean animate) {
        int ms = animate ? FOCUS_MS : 0;
        if (it.type == T_PILL) {
            // Colour only: the pill sits inside the bar, growing would break its edge.
            it.view.setBackground(shape(20, on ? Color.WHITE : Color.TRANSPARENT, 0));
            it.title.setTextColor(on ? Color.BLACK : Color.WHITE);
            if (it.glyph != null) it.glyph.setColor(on ? Color.BLACK : Color.WHITE);
            return;
        }
        float s = on ? it.scale : 1f;
        if (it.type == T_BUTTON) {
            it.view.setBackground(on ? shape(100, Color.WHITE, 0) : shape(100, 0x33000000, 0x33FFFFFF));
            it.glyph.setColor(on ? Color.BLACK : Color.WHITE);
        }
        it.card.animate().scaleX(s).scaleY(s).translationZ(on && it.type == T_TILE ? px(16) : 0)
                .setDuration(ms).setInterpolator(EASE_OUT).start();
        if (it.gloss != null) it.gloss.animate().alpha(on ? 1f : 0f).setDuration(ms).start();
        if (it.label != null) it.label.animate().alpha(on ? 1f : 0f).setDuration(ms).start();
    }

    /** Select pressed: the focused item gives a little under the thumb. */
    void pressLook(boolean down) {
        if (focused == null) return;
        float s = down ? focused.scale * PRESS_SCALE : focused.scale;
        focused.card.animate().scaleX(s).scaleY(s).setDuration(down ? 100 : 160).setInterpolator(EASE_OUT).start();
    }

    /** The sheet scrolls only when the focused row would fall off the bottom. */
    void scrollSheet(int r, boolean animate) {
        Row target = rows.get(r);
        float y = -Math.max(0, target.top + target.height + 40 - 500);
        if (Math.abs(sheetPage.getTranslationY() - px(y)) > 1)
            sheetPage.animate().translationY(px(y)).setDuration(animate ? 300 : 0)
                    .setInterpolator(EASE_DRAWER).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (moving) {
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) shift(-1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) shift(1);
                else if (ok) moveOkDown = true;
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (ok && !moveOkDown) { okHeld = false; return true; } // the hold that started it
                if (ok || k == KeyEvent.KEYCODE_BACK) { sounds.select(); endMove(); }
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (sheetOpen) { sounds.back(); closeSheet(true); }
                else if (row != DOCK_ROW) moveTo(DOCK_ROW, lastDockCol);
            }
            return true; // home never exits
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            // OK is handled here, not by the views: a tap runs the action, holding runs the long action.
            if (focused == null) return true;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okHeld = false;
                    pressLook(true);
                } else if (!okHeld && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= 500)) {
                    okHeld = true;
                    pressLook(false);
                    if (focused.longAction != null) { sounds.select(); focused.longAction.run(); }
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (okHeld) {
                    okHeld = false;
                } else {
                    pressLook(false);
                    if (focused.action != null) { sounds.select(); focused.action.run(); }
                }
            }
            return true;
        }
        boolean dpad = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!dpad) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (col > 0) moveTo(row, col - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (col < rowSize(row) - 1) moveTo(row, col + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!sheetOpen && row == DOCK_ROW) moveTo(0, 0);
                else if (row > 0) moveTo(row - 1, nearest(row, col, row - 1));
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!sheetOpen && row == 0) moveTo(DOCK_ROW, lastDockCol);
                else if (row < rows.size() - 1) moveTo(row + 1, nearest(row, col, row + 1));
                break;
        }
        return true;
    }

    // ---------------------------------------------------------------- rearranging

    List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        String saved = getSharedPreferences("home", MODE_PRIVATE).getString("dock", "");
        for (String k : saved.split(","))
            if (Arrays.asList(DOCK).contains(k) && !order.contains(k)) order.add(k);
        for (String k : DOCK) if (!order.contains(k)) order.add(k);
        return order;
    }

    /** The focused big tile lifts, the others step back, the bar gives way to a hint. */
    void startMove() {
        if (sheetOpen || row != DOCK_ROW || focused == null) return;
        moving = true;
        moveOkDown = false;
        focused.view.setTranslationZ(px(2));
        focused.label.animate().alpha(0f).setDuration(100).start();
        focused.card.animate().scaleX(1.14f).scaleY(1.14f).translationZ(px(28))
                .setDuration(200).setInterpolator(EASE_OUT).start();
        pillBar.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT).start();
        moveHint.setTranslationY(px(6));
        moveHint.animate().alpha(1f).translationY(0).setDuration(200).setInterpolator(EASE_OUT).start();
    }

    /** Swap the lifted tile with its neighbour; every tile slides to its new slot. */
    void shift(int dir) {
        List<Item> items = homeRows.get(DOCK_ROW).items;
        int j = col + dir;
        if (j < 0 || j >= items.size()) return;
        Collections.swap(items, col, j);
        sounds.tick();
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i).view;
            float base = ((FrameLayout.LayoutParams) v.getLayoutParams()).leftMargin;
            v.animate().translationX(px(SIDE + i * (dockW + GAP)) - base)
                    .setDuration(220).setInterpolator(EASE_OUT).start();
        }
        col = j;
        lastDockCol = j;
    }

    void endMove() {
        moving = false;
        StringBuilder order = new StringBuilder();
        for (Item it : homeRows.get(DOCK_ROW).items) {
            if (order.length() > 0) order.append(',');
            order.append(it.key);
        }
        getSharedPreferences("home", MODE_PRIVATE).edit().putString("dock", order.toString()).apply();
        View hint = moveHint, bar = pillBar;
        hint.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT).start();
        bar.animate().alpha(1f).setDuration(200).setInterpolator(EASE_OUT).start();
        setFocusLook(focused, true, true);
        // Lay the tiles out for real once they have settled in their new slots.
        main.postDelayed(() -> { if (!moving) rebuild(); }, 240);
    }

    // ---------------------------------------------------------------- phone remote

    /** Opens a tile, the TV box or this screen. Called from the remote's thread. */
    void remoteOpen(String key) {
        main.post(() -> {
            if ("home".equals(key)) {
                launch(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } else if ("box".equals(key) && boxTarget != null) {
                launch(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(boxTarget)));
            } else if (LIVE_TV.equals(key)) {
                launch(new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI));
            } else if (cache.containsKey(key) && remoteTargets.containsKey(key)) {
                launch(cache.get(key));
            }
        });
    }

    // ---------------------------------------------------------------- actions

    void launch(App a) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        launch(i);
    }

    void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Once, at boot: greeting, then the tiles one by one, then the bar. */
    void playEntrance() {
        List<View> seq = new ArrayList<>();
        seq.add(hero);
        seq.add(topBar);
        seq.addAll(entrance);
        for (int i = 0; i < seq.size(); i++) {
            View v = seq.get(i);
            v.setAlpha(0f);
            v.setTranslationY(px(8));
            v.animate().alpha(1f).translationY(0).setStartDelay(150 + i * 50L).setDuration(400)
                    .setInterpolator(EASE_OUT).start();
        }
    }

    /**
     * Back from an app: the photo settles from slightly close up while the screen comes into
     * focus, like stepping back out of the app you were in. Short, as it happens every time.
     */
    void playReturn() {
        for (View p : new View[]{photoA, photoB}) {
            p.animate().cancel();
            p.setScaleX(1.06f);
            p.setScaleY(1.06f);
            p.animate().scaleX(1f).scaleY(1f).setDuration(650).setInterpolator(EASE_DRAWER).start();
        }
        content.animate().cancel();
        content.setAlpha(0f);
        content.setScaleX(1.03f);
        content.setScaleY(1.03f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(EASE_OUT).start();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    // ---------------------------------------------------------------- glyphs

    /** Small line icons drawn on a 24-unit grid, one stroke weight, recoloured per state. */
    static class Glyph extends Drawable {
        static final int TV = 0, HDMI = 1, GEAR = 3, SUN = 4, MOON = 5, CLOUD = 6,
                RAIN = 7, CALENDAR = 8, GRID = 9;
        int kind;
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glyph(int kind, int color) {
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(1.9f);
            setColor(color);
        }

        void setColor(int c) {
            stroke.setColor(c);
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.save();
            c.translate(b.left, b.top);
            c.scale(b.width() / 24f, b.height() / 24f);
            switch (kind) {
                case TV:
                    c.drawRoundRect(new RectF(2.5f, 4.5f, 21.5f, 16.5f), 2.5f, 2.5f, stroke);
                    c.drawLine(8, 20.5f, 16, 20.5f, stroke);
                    break;
                case HDMI: {
                    Path p = new Path();
                    p.moveTo(3, 8);
                    p.lineTo(21, 8);
                    p.lineTo(21, 12.5f);
                    p.lineTo(18, 16);
                    p.lineTo(6, 16);
                    p.lineTo(3, 12.5f);
                    p.close();
                    c.drawPath(p, stroke);
                    c.drawLine(8, 12, 16, 12, stroke);
                    break;
                }
                case GRID:
                    for (int i = 0; i < 4; i++) {
                        float x = i % 2 == 0 ? 3.5f : 13.5f, y = i < 2 ? 3.5f : 13.5f;
                        c.drawRoundRect(new RectF(x, y, x + 7, y + 7), 2, 2, stroke);
                    }
                    break;
                case GEAR: {
                    c.drawCircle(12, 12, 3, stroke);
                    Path p = new Path();
                    int n = 16; // 8 teeth: alternating outer and inner arcs
                    for (int i = 0; i < n; i++) {
                        double a0 = Math.PI * 2 * i / n - Math.PI / n;
                        double a1 = Math.PI * 2 * (i + 1) / n - Math.PI / n;
                        float r = i % 2 == 0 ? 9.5f : 7.3f;
                        float x0 = 12 + (float) Math.cos(a0) * r, y0 = 12 + (float) Math.sin(a0) * r;
                        float x1 = 12 + (float) Math.cos(a1) * r, y1 = 12 + (float) Math.sin(a1) * r;
                        if (i == 0) p.moveTo(x0, y0); else p.lineTo(x0, y0);
                        p.lineTo(x1, y1);
                    }
                    p.close();
                    c.drawPath(p, stroke);
                    break;
                }
                case SUN:
                    c.drawCircle(12, 12, 4, stroke);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * 2 * i / 8;
                        c.drawLine(12 + (float) Math.cos(a) * 7, 12 + (float) Math.sin(a) * 7,
                                12 + (float) Math.cos(a) * 9.5f, 12 + (float) Math.sin(a) * 9.5f, stroke);
                    }
                    break;
                case MOON: {
                    Path p = new Path();
                    p.addCircle(12, 12, 8, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(16.5f, 8.5f, 7, Path.Direction.CW);
                    p.op(cut, Path.Op.DIFFERENCE);
                    c.drawPath(p, stroke);
                    break;
                }
                case CALENDAR:
                    c.drawRoundRect(new RectF(3.5f, 5, 20.5f, 20.5f), 3, 3, stroke);
                    c.drawLine(3.5f, 10, 20.5f, 10, stroke);
                    c.drawLine(8, 3, 8, 6.5f, stroke);
                    c.drawLine(16, 3, 16, 6.5f, stroke);
                    break;
                case CLOUD:
                case RAIN: {
                    float dy = kind == RAIN ? -2.5f : 0;
                    // Two puffs and a base, merged so the outline reads as one cloud.
                    Path a = new Path(), b2 = new Path(), r = new Path();
                    a.addCircle(9, 13 + dy, 4.5f, Path.Direction.CW);
                    b2.addCircle(14.5f, 11 + dy, 5.5f, Path.Direction.CW);
                    r.addRoundRect(new RectF(3, 13 + dy, 21, 18.5f + dy), 2.75f, 2.75f, Path.Direction.CW);
                    a.op(b2, Path.Op.UNION);
                    a.op(r, Path.Op.UNION);
                    c.drawPath(a, stroke);
                    if (kind == RAIN) {
                        c.drawLine(8, 19, 7, 22, stroke);
                        c.drawLine(12.5f, 19, 11.5f, 22, stroke);
                        c.drawLine(17, 19, 16, 22, stroke);
                    }
                    break;
                }
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { stroke.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
