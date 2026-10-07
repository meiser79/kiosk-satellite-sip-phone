// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.sip;

import java.lang.reflect.Constructor;

/** Kiosk Satellite's built-in intercom telephone ring, played on Android's notification stream. */
final class SipRingtone {
    private static final int SAMPLE_RATE = 16000;
    private static final int RING_INTERVAL_MS = 4000;
    private boolean running;
    private Object track;
    private Thread worker;

    synchronized void start() throws Exception {
        if (running) return;
        track = playRing();
        running = true;
        worker = new Thread(this::repeat, "sip-intercom-ringtone");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        stopTrack(track);
        track = null;
    }

    private void repeat() {
        while (true) {
            try {
                Thread.sleep(RING_INTERVAL_MS);
            } catch (InterruptedException done) {
                return;
            }
            synchronized (this) {
                if (!running) return;
                try {
                    stopTrack(track);
                    track = playRing();
                } catch (Exception error) {
                    running = false;
                    track = null;
                    worker = null;
                    return;
                }
            }
        }
    }

    private static Object playRing() throws Exception {
        // Same two-tone 440/480 Hz double burst used by Kiosk Satellite's intercom.
        int burst = SAMPLE_RATE * 400 / 1000;
        int gap = SAMPLE_RATE * 200 / 1000;
        short[] pcm = new short[burst * 2 + gap];
        fillBurst(pcm, 0, burst);
        fillBurst(pcm, burst + gap, burst);

        Class<?> audioTrack = Class.forName("android.media.AudioTrack");
        Class<?> audioManager = Class.forName("android.media.AudioManager");
        Class<?> format = Class.forName("android.media.AudioFormat");
        int notification = audioManager.getField("STREAM_NOTIFICATION").getInt(null);
        int mono = format.getField("CHANNEL_OUT_MONO").getInt(null);
        int pcm16 = format.getField("ENCODING_PCM_16BIT").getInt(null);
        int staticMode = audioTrack.getField("MODE_STATIC").getInt(null);
        Constructor<?> constructor = audioTrack.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        Object result = constructor.newInstance(notification, SAMPLE_RATE, mono, pcm16, pcm.length * 2, staticMode);
        int written = (Integer) audioTrack.getMethod("write", short[].class, int.class, int.class).invoke(result, pcm, 0, pcm.length);
        if (written != pcm.length) {
            release(result);
            throw new IllegalStateException("Could not load the intercom ringtone samples");
        }
        audioTrack.getMethod("play").invoke(result);
        return result;
    }

    private static void fillBurst(short[] pcm, int start, int length) {
        int fade = SAMPLE_RATE / 100;
        for (int i = 0; i < length; i++) {
            double t = (double) i / SAMPLE_RATE;
            double wave = 0.5 * Math.sin(2 * Math.PI * 440 * t) + 0.5 * Math.sin(2 * Math.PI * 480 * t);
            double envelope = i < fade ? (double) i / fade : i > length - fade ? (double) (length - i) / fade : 1.0;
            pcm[start + i] = (short) (wave * envelope * 0.6 * Short.MAX_VALUE);
        }
    }

    private static void stopTrack(Object value) {
        if (value == null) return;
        try { value.getClass().getMethod("stop").invoke(value); } catch (Exception ignored) { }
        release(value);
    }

    private static void release(Object value) {
        try { if (value != null) value.getClass().getMethod("release").invoke(value); } catch (Exception ignored) { }
    }
}
