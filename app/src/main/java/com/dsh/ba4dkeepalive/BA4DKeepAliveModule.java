package com.dsh.ba4dkeepalive;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point.
 *
 * The hooked process is com.miradesktop.ba4d only; everything the module does
 * happens inside that process, which is the only place from which the app's
 * foreground overlay service can be started.
 */
public final class BA4DKeepAliveModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "Module";
    private static final String TARGET_PKG = RestartController.TARGET_PKG;

    /**
     * Runs in zygote, so the shared config directory exists with the right
     * permissions before any app process (or the Magisk daemon) touches it.
     */
    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            File dir = new File(ControlFile.DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            //noinspection ResultOfMethodCallIgnored
            dir.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setWritable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setExecutable(true, false);
            ControlFile.ensureDefaults();
        } catch (Throwable t) {
            KLog.w(TAG, "initZygote setup failed: " + KLog.describe(t));
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            KLog.i(TAG, "loaded into " + lpparam.packageName
                    + " process=" + lpparam.processName + " pid=" + android.os.Process.myPid());
            ControlFile.ensureDefaults();

            final ClassLoader cl = lpparam.classLoader;

            AppHooks.hookEarlyContext(cl);
            RestartController.hookService(cl);
            RestartController.hookAppWide(cl);
            AppHooks.hookApplicationReady(cl);
        } catch (Throwable t) {
            KLog.e(TAG, "module init failed", t);
        }
    }
}
