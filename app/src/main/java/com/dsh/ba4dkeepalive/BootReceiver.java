package com.dsh.ba4dkeepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs at boot and after the module is updated.
 *
 * The real configuration file lives in the target app's private storage and is
 * written by the module, so this receiver mainly makes sure the shared directory
 * used by the Magisk daemon exists. The settings UI remembers the user's choices in
 * its own preferences and re-sends them to the module whenever the module is live.
 */
public class BootReceiver extends BroadcastReceiver {

    /** Shared with SettingsActivity so both sides agree on where choices are remembered. */
    public static final String PREFS = "ba4d_keepalive_ui";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            KLog.i("BootReceiver", "received " + action);
            // Creates /data/local/tmp/ba4d_keepalive (and a default config there) so the
            // Magisk daemon has something to read before the module has run once.
            ControlFile.ensureDefaults();
            ControlFile.reload();
        } catch (Throwable t) {
            KLog.e("BootReceiver", "onReceive failed", t);
        }
    }
}
