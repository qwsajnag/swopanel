package com.dsh.ba4dkeepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lets the module's settings UI change the configuration even though the two apps
 * have different UIDs and the configuration file lives in the target app's private
 * storage (which the UI app cannot write).
 *
 * The receiver is registered dynamically inside the target app process by the
 * LSPosed module, is explicitly exported, and only acts on intents sent by the
 * settings UI's package.
 */
public final class ConfigReceiver extends BroadcastReceiver {

    /** Module settings UI package. */
    public static final String UI_PKG = "com.dsh.ba4dkeepalive";
    /** Action the UI sends. */
    public static final String ACTION = "com.dsh.ba4dkeepalive.action.CONFIG";
    /** Action the UI sends to force an immediate restart attempt. */
    public static final String ACTION_RESTART = "com.dsh.ba4dkeepalive.action.RESTART";

    /**
     * Separate intent action used to launch the target app's main activity.
     * Without its own action, Intent.init() collapses an intent with no action,
     * no data and no type into a copy of the target's own intent filter.
     */
    public static final String ACTION_LAUNCH = "com.dsh.ba4dkeepalive.action.LAUNCH";

    private static final String TAG = "ConfigReceiver";

    private static volatile boolean registered;

    /** Registers the receiver; safe to call more than once. */
    public static void register(Context ctx) {
        if (ctx == null || registered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION);
            filter.addAction(ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(new ConfigReceiver(), filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(new ConfigReceiver(), filter);
            }
            registered = true;
            KLog.i(TAG, "config receiver registered");
        } catch (Throwable t) {
            KLog.e(TAG, "register failed", t);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        String sender = context == null ? null : context.getPackageName();
        KLog.i(TAG, "received " + action + " from " + sender);

        if (ACTION_RESTART.equals(action)) {
            ControlFile.reload();
            RestartController.restartServiceNow("ui-request");
            return;
        }
        if (!ACTION.equals(action)) {
            return;
        }

        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(intent, values, ControlFile.KEY_ENABLED);
        putIfPresent(intent, values, ControlFile.KEY_RESTART_SERVICE);
        putIfPresent(intent, values, ControlFile.KEY_KEEP_ALIVE);
        putIfPresent(intent, values, ControlFile.KEY_RESPECT_USER_STOP);
        putIfPresent(intent, values, ControlFile.KEY_RESTORE_A11Y);
        putIfPresent(intent, values, ControlFile.KEY_WATCHDOG);
        putIfPresent(intent, values, ControlFile.KEY_BRING_TO_FRONT);

        if (values.isEmpty()) {
            return;
        }
        ControlFile.putAll(values);
        ControlFile.reload();
        KLog.i(TAG, "applied " + values.size() + " setting(s) from the UI");

        if (ControlFile.getBoolean(ControlFile.KEY_ENABLED, true)
                && ControlFile.getBoolean(ControlFile.KEY_RESTART_SERVICE, true)
                && !RestartController.isServiceAlive()) {
            RestartController.scheduleRestart("ui-config-change");
        }
        RestartController.writeStatus();
    }

    private static void putIfPresent(Intent intent, Map<String, String> out, String key) {
        if (intent.hasExtra(key)) {
            out.put(key, intent.getBooleanExtra(key, true) ? "true" : "false");
        }
    }
}
