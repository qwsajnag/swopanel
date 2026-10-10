package com.dsh.ba4dkeepalive;

import android.content.Context;

/**
 * In-process health checker.
 *
 * It runs as a daemon thread inside the target app process and restarts the overlay
 * service whenever the service is gone while the process is still alive. Covering the
 * "whole process is dead" case is deliberately left to the Magisk daemon, which is the
 * only thing that can act from outside a dead process.
 */
public final class Watchdog {

    private static final String TAG = "Watchdog";
    private static final long INTERVAL_MS = 3000L;

    /**
     * If the service has not reported "foreground" for this long, treat it as stopped.
     * The value has to be comfortably larger than a normal service start.
     */
    private static final long STALE_MS = 20_000L;

    private static final Object LOCK = new Object();
    private static volatile boolean running;
    private static volatile long lastStatusWrite;

    private Watchdog() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context ctx) {
        synchronized (LOCK) {
            if (running) {
                return;
            }
            if (!ControlFile.getBoolean(ControlFile.KEY_WATCHDOG, true)) {
                KLog.i(TAG, "watchdog disabled by config");
                return;
            }
            final Context app = ctx == null ? null
                    : (ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx);
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop(app);
                }
            }, "ba4d-keepalive-watchdog");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread th, Throwable ex) {
                    KLog.e(TAG, "watchdog crashed", ex);
                }
            });
            t.start();
            KLog.i(TAG, "watchdog started, interval=" + INTERVAL_MS + "ms");
        }
    }

    private static void loop(Context app) {
        int tick = 0;
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            tick++;
            try {
                if (!ControlFile.getBoolean(ControlFile.KEY_ENABLED, true)) {
                    continue;
                }
                RestartController.setAppContext(app);

                // 1. put the overlay service back if it went away
                if (ControlFile.getBoolean(ControlFile.KEY_RESTART_SERVICE, true)
                        && !RestartController.isServiceAlive()) {
                    long last = RestartController.lastForegroundAt();
                    boolean everRan = last > 0;
                    boolean stale = !everRan || (System.currentTimeMillis() - last) > STALE_MS;
                    if (stale) {
                        KLog.w(TAG, "overlay service looks stopped (" + RestartController.serviceState()
                                + "); restarting");
                        RestartController.restartServiceNow("watchdog");
                    }
                }

                // 2. keep the accessibility service registered (best effort, app UID)
                if (tick % 10 == 0) {
                    if (ControlFile.getBoolean(ControlFile.KEY_RESTORE_A11Y, true)
                            && !RestartController.isAccessibilityEnabled()) {
                        RestartController.restoreAccessibility();
                    }
                }

                // 3. publish liveness for the UI and the Magisk daemon
                if (tick % 5 == 0) {
                    writeStatus();
                }
            } catch (Throwable t) {
                KLog.e(TAG, "watchdog tick failed", t);
            }
        }
    }

    private static void writeStatus() {
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite < 4000L) {
            return;
        }
        lastStatusWrite = now;
        try {
            RestartController.writeStatus();
        } catch (Throwable ignored) {
        }
    }
}
