package com.parents.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/**
 * Soft clicks for moving and selecting, in place of the stock Android beep
 * (System sounds is switched off on the TV). Kept quiet so they sit under the room, not on top of it.
 */
class Sounds {
    static final float TICK = 0.22f, SELECT = 0.3f, BACK = 0.28f;

    final SoundPool pool;
    final int tick, select, back;
    long lastTick;

    Sounds(Context c) {
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        tick = load(c, "tick");
        select = load(c, "select");
        back = load(c, "back");
    }

    int load(Context c, String name) {
        try {
            return pool.load(c.getAssets().openFd("sounds/" + name + ".wav"), 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Holding an arrow repeats fast; skip ticks closer than 45 ms so it purrs instead of buzzing. */
    void tick() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTick < 45) return;
        lastTick = now;
        play(tick, TICK);
    }

    void select() { play(select, SELECT); }

    void back() { play(back, BACK); }

    void play(int id, float vol) {
        if (id != 0) pool.play(id, vol, vol, 1, 0, 1f);
    }

    void release() { pool.release(); }
}
