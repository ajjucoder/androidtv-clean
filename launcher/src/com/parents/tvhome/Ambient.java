package com.parents.tvhome;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Calendar;
import java.util.Date;

/**
 * Screensaver: the home photos, one at a time, drifting slowly, with the time in the corner.
 * Any button wakes the TV straight back to whatever was on.
 */
public class Ambient extends DreamService {
    static final String[] PHOTOS = {"morning", "day", "evening", "night"};
    static final long HOLD_MS = 40_000, FADE_MS = 2400;
    static final PathInterpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    final Handler main = new Handler(Looper.getMainLooper());
    ImageView front, back;
    TextView time, date;
    int index;
    boolean flip;
    float dp;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        back = photo();
        front = photo();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        time = text("fonts/Geist-SemiBold.ttf", 76, -0.05f, Color.WHITE);
        date = text("fonts/Geist-Medium.ttf", 20, -0.01f, 0xCCFFFFFF);
        date.setPadding(px(3), 0, 0, 0);
        corner.addView(time);
        corner.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = px(64);
        lp.bottomMargin = px(52);
        root.addView(corner, lp);
        setContentView(root);

        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        index = hr >= 5 && hr < 11 ? 0 : hr >= 11 && hr < 17 ? 1 : hr >= 17 && hr < 21 ? 2 : 3;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        clock.run();
        show(true);
    }

    @Override
    public void onDreamingStopped() {
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    final Runnable clock = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time.setText(DateFormat.format(DateFormat.is24HourFormat(Ambient.this) ? "H:mm" : "h:mm", now));
            date.setText(DateFormat.format("EEEE d MMMM", now));
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    /** Brings the next photo up over the last one; it drifts the whole time it is on screen. */
    void show(boolean first) {
        ImageView in = front, out = back;
        try (InputStream s = getAssets().open("bg/" + PHOTOS[index] + ".jpg")) {
            in.setImageBitmap(BitmapFactory.decodeStream(s));
        } catch (Exception e) {
            return;
        }
        in.bringToFront();
        ((android.view.View) time.getParent()).bringToFront();
        flip = !flip;
        float dx = (flip ? 1 : -1) * 14 * dp;
        in.setScaleX(1.02f);
        in.setScaleY(1.02f);
        in.setTranslationX(-dx);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(first ? 1200 : FADE_MS).setInterpolator(EASE)
                .withEndAction(() -> out.setImageDrawable(null)).start();
        // Drift and fade run as separate animators so the slow move is never cut short by the fade.
        in.postOnAnimation(() -> in.animate().scaleX(1.08f).scaleY(1.08f).translationX(dx)
                .setDuration(HOLD_MS + FADE_MS * 2).setInterpolator(new LinearInterpolator()).start());
        front = out;
        back = in;
        index = (index + 1) % PHOTOS.length;
        main.postDelayed(() -> show(false), HOLD_MS);
    }

    ImageView photo() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    TextView text(String font, int size, float tracking, int color) {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.createFromAsset(getAssets(), font));
        t.setTextSize(size);
        t.setLetterSpacing(tracking);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(24 * dp, 0, 0, 0x40000000);
        return t;
    }

    int px(float v) { return Math.round(v * dp); }
}
