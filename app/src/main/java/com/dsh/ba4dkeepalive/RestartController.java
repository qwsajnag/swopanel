package com.dsh.ba4dkeepalive;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Owns everything that happens after the target app's process is alive:
 *  - remembers the app Context,
 *  - restarts the overlay foreground service when it goes away,
 *  - keeps the accessibility service enabled,
 *  - reports liveness so the forked watchdog can detect a stopped service.
 */
public final class RestartController {

    public static final String TARGET_PKG = "com.miradesktop.ba4d";
    public static final String SERVICE_CLASS = TARGET_PKG + ".overlay.OverlayService";
    public static final String A11Y_CLASS = TARGET_PKG + ".overlay.OverlayAccessibilityService";
    public static final String MAIN_ACTIVITY = TARGET_PKG + ".MainActivity";

    private static final String TAG = "BA4D";

    private static final Object SVC_LOCK = new Object();
    private static volatile Object serviceInstance;
    private static volatile String serviceState = "not-created";
    private static volatile long lastForegroundAt;
    private static volatile long lastStopAt;
    private static volatile int restartAttempts;
    private static volatile long restartWindowStart;
    private static volatile boolean restartScheduled;

    private static volatile Context appContext;
    private static volatile long appContextAt;
    private static volatile boolean stoppedByUser;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RestartController() {
    }

    // ------------------------------------------------------------------ context

    public static void setAppContext(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            appContextAt = System.currentTimeMillis();
        }
    }

    public static Context context() {
        return appContext;
    }

    public static boolean hasContext() {
        return appContext != null;
    }

    // ------------------------------------------------------- service lifecycle

    public static void onServiceCreated(Object service) {
        synchronized (SVC_LOCK) {
            serviceInstance = service;
            serviceState = "created";
        }
        lastForegroundAt = System.currentTimeMillis();
        stoppedByUser = false;
        KLog.i(TAG, "OverlayService created");
        writeStatus();
    }

    public static void onServiceForeground() {
        lastForegroundAt = System.currentTimeMillis();
        synchronized (SVC_LOCK) {
            serviceState = "foreground";
        }
        writeStatus();
    }

    public static void onServiceDestroyed(String reason) {
        synchronized (SVC_LOCK) {
            serviceInstance = null;
            serviceState = "destroyed(" + reason + ")";
        }
        lastStopAt = System.currentTimeMillis();
        KLog.w(TAG, "OverlayService destroyed (" + reason + ")");
        writeStatus();
        if (shouldRun()) {
            scheduleRestart(reason);
        }
    }

    public static boolean isServiceAlive() {
        return serviceInstance != null;
    }

    public static long lastForegroundAt() {
        return lastForegroundAt;
    }

    public static String serviceState() {
        return serviceState;
    }

    public static void markStoppedByUser() {
        stoppedByUser = true;
        KLog.i(TAG, "user stop detected; respecting it");
    }

    // -------------------------------------------------------------- restarts

    /** True when the module should try to keep the overlay running. */
    public static boolean shouldRun() {
        if (!ControlFile.getBoolean(ControlFile.KEY_ENABLED, true)) {
            return false;
        }
        if (!ControlFile.getBoolean(ControlFile.KEY_RESTART_SERVICE, true)) {
            return false;
        }
        if (ControlFile.getBoolean(ControlFile.KEY_RESPECT_USER_STOP, true) && stoppedByUser) {
            return false;
        }
        return true;
    }

    public static void scheduleRestart(String reason) {
        if (!shouldRun()) {
            return;
        }
        if (restartScheduled) {
            return;
        }
        restartScheduled = true;
        int delay = Math.max(200, ControlFile.getInt(ControlFile.KEY_RESTART_DELAY_MS, 800));
        KLog.i(TAG, "scheduling service restart in " + delay + "ms (reason: " + reason + ")");
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                restartScheduled = false;
                restartServiceNow("scheduled");
            }
        }, delay);
    }

    /** Starts the overlay service from inside the target app process. */
    public static boolean restartServiceNow(String reason) {
        Context ctx = appContext;
        if (ctx == null) {
            KLog.w(TAG, "cannot restart service: no app context yet");
            return false;
        }
        if (!shouldRun()) {
            return false;
        }
        if (!allowAnotherAttempt()) {
            KLog.w(TAG, "restart rate limit reached; backing off");
            return false;
        }
        if (isServiceAlive()) {
            KLog.i(TAG, "restart skipped, service already alive");
            return true;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TARGET_PKG, SERVICE_CLASS));
            intent.setAction(TARGET_PKG + ".action.RESTART_BY_KEEPALIVE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            restartAttempts++;
            KLog.i(TAG, "startForegroundService(OverlayService) issued, reason=" + reason
                    + " attempt=" + restartAttempts);
            writeStatus();
            return true;
        } catch (Throwable t) {
            KLog.e(TAG, "startForegroundService failed", t);
            writeStatus();
            return false;
        }
    }

    private static boolean allowAnotherAttempt() {
        long now = System.currentTimeMillis();
        if (restartWindowStart == 0 || now - restartWindowStart > 60_000L) {
            restartWindowStart = now;
            restartAttempts = 0;
        }
        int max = Math.max(1, ControlFile.getInt(ControlFile.KEY_MAX_RESTARTS, 6));
        return restartAttempts < max;
    }

    // ------------------------------------------------------- accessibility

    private static android.content.ContentResolver resolver() {
        Context ctx = appContext;
        return ctx == null ? null : ctx.getContentResolver();
    }

    /** Re-adds our accessibility service to the secure setting, needs the host to be shell/root. */
    public static void restoreAccessibility() {
        if (!ControlFile.getBoolean(ControlFile.KEY_RESTORE_A11Y, true)) {
            return;
        }
        android.content.ContentResolver cr = resolver();
        if (cr == null) {
            return;
        }
        try {
            String current = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = TARGET_PKG + "/" + A11Y_CLASS;
            if (current != null && current.contains(me)) {
                return;
            }
            String next = (current == null || current.isEmpty()) ? me : current + ":" + me;
            Settings.Secure.putString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            KLog.i(TAG, "accessibility service re-enabled through Settings.Secure");
        } catch (Throwable t) {
            // WRITE_SECURE_SETTINGS is not held by the app UID: the Magisk daemon does it instead.
            KLog.w(TAG, "cannot write secure settings from app UID: " + KLog.describe(t));
        }
    }

    public static boolean isAccessibilityEnabled() {
        android.content.ContentResolver cr = resolver();
        if (cr == null) {
            return false;
        }
        try {
            String me = TARGET_PKG + "/" + A11Y_CLASS;
            String flat = Settings.Secure.getString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String acc = Settings.Secure.getString(cr, Settings.Secure.ACCESSIBILITY_ENABLED);
            if (flat != null && flat.contains(A11Y_CLASS)) {
                return "1".equals(acc) || acc == null;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ----------------------------------------------------------------- status

    public static void writeStatus() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("pkg=").append(TARGET_PKG).append('\n');
            sb.append("service=").append(serviceState).append('\n');
            sb.append("service_alive=").append(isServiceAlive() ? 1 : 0).append('\n');
            sb.append("last_foreground=").append(lastForegroundAt == 0 ? 0 : lastForegroundAt / 1000L).append('\n');
            sb.append("last_stop=").append(lastStopAt == 0 ? 0 : lastStopAt / 1000L).append('\n');
            sb.append("pid=").append(android.os.Process.myPid()).append('\n');
            sb.append("enabled=").append(ControlFile.getBoolean(ControlFile.KEY_ENABLED, true) ? 1 : 0).append('\n');
            sb.append("watchdog=").append(Watchdog.isRunning() ? 1 : 0).append('\n');
            sb.append("a11y=").append(isAccessibilityEnabled() ? 1 : 0).append('\n');
            ControlFile.writeStatus(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Brings the app UI to the front; used as a last resort restart path. */
    public static boolean bringToFront() {
        Context ctx = appContext;
        if (ctx == null) {
            return false;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TARGET_PKG, MAIN_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(intent);
            KLog.i(TAG, "MainActivity brought to front");
            return true;
        } catch (Throwable t) {
            KLog.e(TAG, "bringToFront failed", t);
            return false;
        }
    }

    /** Convenience for the settings UI: is the target process currently alive? */
    public static boolean isTargetProcessRunning(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (TARGET_PKG.equals(p.processName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ hooks

    /** Hooks the overlay service lifecycle so a stop is noticed immediately. */
    public static void hookService(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass(SERVICE_CLASS, cl);
            safeHook(svc, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onServiceCreated(param.thisObject);
                }
            });
            safeHook(svc, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onServiceDestroyed("onDestroy");
                }
            });
            safeHook(svc, "onTaskRemoved", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    KLog.i(TAG, "onTaskRemoved");
                    if (shouldRun()) {
                        scheduleRestart("onTaskRemoved");
                    }
                }
            });
            safeHook(svc, "stopSelf", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    KLog.w(TAG, "stopSelf() called by the app");
                    markStoppedByUser();
                }
            });
        } catch (Throwable t) {
            KLog.e(TAG, "hookService failed", t);
        }
    }

    /** Hooks Application/Activity entry points to catch every stop path. */
    public static void hookAppWide(ClassLoader cl) {
        try {
            Class<?> ctxImpl = XposedHelpers.findClass("android.app.ContextImpl", cl);
            safeHook(ctxImpl, "stopService", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0) {
                        return;
                    }
                    Object arg = param.args[0];
                    if (!(arg instanceof Intent)) {
                        return;
                    }
                    ComponentName cn = ((Intent) arg).getComponent();
                    if (cn != null && SERVICE_CLASS.equals(cn.getClassName())) {
                        KLog.w(TAG, "stopService(OverlayService) intercepted");
                        markStoppedByUser();
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "ContextImpl.stopService hook unavailable: " + KLog.describe(t));
        }

        try {
            Class<?> activity = XposedHelpers.findClass("android.app.Activity", cl);
            safeHook(activity, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!shouldRun()) {
                        return;
                    }
                    // only revive a service that was actually running before
                    if (!isServiceAlive() && lastForegroundAt > 0) {
                        scheduleRestart("activity-onDestroy");
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "Activity.onDestroy hook unavailable: " + KLog.describe(t));
        }

        try {
            Class<?> am = XposedHelpers.findClass("android.app.Application", cl);
            safeHook(am, "onTerminate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    KLog.i(TAG, "Application.onTerminate");
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "Application.onTerminate hook unavailable: " + KLog.describe(t));
        }
    }

    static void safeHook(Class<?> clazz, String method, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(clazz, method, callback);
        } catch (Throwable t) {
            KLog.w(TAG, "hook " + clazz.getSimpleName() + "." + method + " failed: " + KLog.describe(t));
        }
    }
}
