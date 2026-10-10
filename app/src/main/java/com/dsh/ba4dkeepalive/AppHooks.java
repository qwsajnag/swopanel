package com.dsh.ba4dkeepalive;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Entry points into the target app process.
 *
 * Two hooks matter: Application.attach gives us a Context very early (so a restart
 * can be issued from the first moment the process exists), and Application.onCreate
 * starts the in-process watchdog that notices the overlay service disappearing.
 *
 * Note on scope: the overlay service is not exported and is a mediaProjection
 * foreground service, so it can only be (re)started from inside this process.
 * Bringing a dead process back is the Magisk daemon's job.
 */
public final class AppHooks {

    private static final String TAG = "AppHooks";

    private static volatile boolean initialised;

    private AppHooks() {
    }

    /** Hooks the earliest context-carrying entry points we can rely on. */
    public static void hookEarlyContext(ClassLoader cl) {
        // ContextWrapper.attachBaseContext(Context) is public on every Android version and
        // is called by Application, Activity and Service alike: the most reliable place to
        // grab a Context before anything else in the process runs.
        try {
            Class<?> contextWrapper = XposedHelpers.findClass("android.content.ContextWrapper", cl);
            XposedBridge.hookAllMethods(contextWrapper, "attachBaseContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = extractContext(param);
                        if (ctx != null && RestartController.context() == null) {
                            RestartController.setAppContext(ctx);
                            KLog.i(TAG, "app context captured in attachBaseContext");
                        }
                    } catch (Throwable t) {
                        KLog.e(TAG, "attachBaseContext hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "ContextWrapper.attachBaseContext hook unavailable: " + KLog.describe(t));
        }

        // Application.attach(Context) exists on API 21+ and gives us the Application itself.
        try {
            Class<?> application = XposedHelpers.findClass("android.app.Application", cl);
            XposedBridge.hookAllMethods(application, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = extractContext(param);
                        if (ctx != null) {
                            RestartController.setAppContext(ctx);
                            KLog.i(TAG, "app context captured in Application.attach");
                        }
                    } catch (Throwable t) {
                        KLog.e(TAG, "attach hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "Application.attach hook unavailable: " + KLog.describe(t));
        }

        // handleBindApplication happens even before Application.attach; used only as a
        // second chance to grab a Context for extremely early restarts.
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl);
            XposedBridge.hookAllMethods(activityThread, "handleBindApplication", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = extractContext(param);
                        if (ctx != null) {
                            RestartController.setAppContext(ctx);
                            KLog.i(TAG, "app context captured in handleBindApplication");
                        }
                    } catch (Throwable t) {
                        KLog.e(TAG, "bind hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "ActivityThread.handleBindApplication hook unavailable: " + KLog.describe(t));
        }
    }

    private static Context extractContext(XC_MethodHook.MethodHookParam param) {
        if (param.args != null) {
            for (Object a : param.args) {
                if (a instanceof Context) {
                    return (Context) a;
                }
                if (a instanceof Application) {
                    return (Application) a;
                }
            }
        }
        if (param.thisObject instanceof Context) {
            return (Context) param.thisObject;
        }
        return null;
    }

    /**
     * Falls back to ActivityThread.currentActivityThread() plus reflection on
     * getApplication(), which works on every API level from 26 upwards even when the
     * constructor and attach hooks were not reached.
     */
    private static void ensureContextFromActivityThread(ClassLoader cl) {
        try {
            Class<?> activityThread;
            if (cl != null) {
                activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl);
            } else {
                activityThread = Class.forName("android.app.ActivityThread");
            }
            Object thread = XposedHelpers.callMethod(activityThread, "currentActivityThread");
            if (thread == null) {
                return;
            }
            Object app = XposedHelpers.callMethod(thread, "getApplication");
            if (app instanceof Context) {
                RestartController.setAppContext((Context) app);
                KLog.i(TAG, "app context captured through ActivityThread.getApplication()");
            }
        } catch (Throwable t) {
            KLog.w(TAG, "ActivityThread context fallback failed: " + KLog.describe(t));
        }
    }

    /** Runs once the target Application has been created. */
    public static void hookApplicationReady(ClassLoader cl) {
        try {
            Class<?> application = XposedHelpers.findClass("android.app.Application", cl);
            XposedBridge.hookAllMethods(application, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = extractContext(param);
                        if (ctx == null) {
                            ctx = RestartController.context();
                        }
                        onAppStarted(ctx);
                    } catch (Throwable t) {
                        KLog.e(TAG, "Application.onCreate hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            KLog.w(TAG, "Application.onCreate hook unavailable: " + KLog.describe(t));
        }
    }

    /** Called whenever the app process comes up (cold start, restart, or post-update). */
    public static void onAppStarted(Context ctx) {
        RestartController.setAppContext(ctx);
        // Last-resort context capture: attachBaseContext and Application.attach can both
        // fail on very early or non-standard starts, but the module is injected into the
        // app process, so ActivityThread is always reachable from here.
        if (!RestartController.hasContext()) {
            ensureContextFromActivityThread(ctx == null ? null : ctx.getClassLoader());
        }
        ControlFile.ensureDefaults();
        ControlFile.reload();
        ConfigReceiver.register(ctx);

        boolean first = !initialised;
        initialised = true;
        KLog.i(TAG, "target app process started" + (first ? " (module first init)" : " (re-init)"));
        RestartController.writeStatus();

        // The system drops an app's accessibility service when the app is replaced;
        // ask for it back on every start.
        if (ControlFile.getBoolean(ControlFile.KEY_RESTORE_A11Y, true)
                && !RestartController.isAccessibilityEnabled()) {
            RestartController.restoreAccessibility();
        }

        // If the overlay was running before this process died, put it back immediately
        // instead of waiting for the watchdog's first tick.
        if (RestartController.shouldRun()
                && !RestartController.isServiceAlive()
                && RestartController.lastForegroundAt() > 0) {
            RestartController.scheduleRestart("app-onCreate");
        }

        Watchdog.start(ctx);
    }
}
