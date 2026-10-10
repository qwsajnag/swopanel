package com.dsh.ba4dkeepalive;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Logging that works in every environment this module runs in: the injected target
 * app process, this module's own settings UI process, and (via the file) the Magisk
 * daemon and adb.
 *
 * XposedBridge is used reflectively so the module can be built against the
 * compileOnly Xposed API and the settings UI never trips over a missing class.
 */
public final class KLog {

    /** Preferred location: readable by adb/shell and by the Magisk daemon. */
    public static final String DIR = "/data/local/tmp/ba4d_keepalive";

    /**
     * Fallback for environments where SELinux forbids an app UID from writing under
     * /data/local/tmp. The injected module can always write its own app directory,
     * and root can read it back.
     */
    public static final String PRIVATE_DIR = "/data/data/com.miradesktop.ba4d/files/ba4d_keepalive";

    private static final String FILE_NAME = "keepalive.log";
    private static final long MAX_BYTES = 256 * 1024L;
    private static final int RING = 300;
    private static final ArrayDeque<String> RING_BUF = new ArrayDeque<>(RING);

    /** Shared-path log file; the resolved path may differ, see {@link #logPath()}. */
    public static final String FILE = DIR + "/" + FILE_NAME;

    private static volatile Method xposedLog;
    private static volatile boolean xposedLogResolved;
    private static volatile boolean fileBroken;
    private static volatile String activePath;
    private static final Object FILE_LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private KLog() {
    }

    public static void i(String tag, String msg) {
        line("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        line("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        line("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        line("E", tag, msg + " :: " + describe(t));
    }

    public static String describe(Throwable t) {
        if (t == null) {
            return "(null)";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void line(String level, String tag, String msg) {
        String text = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        synchronized (RING_BUF) {
            if (RING_BUF.size() >= RING) {
                RING_BUF.pollFirst();
            }
            RING_BUF.addLast(text);
        }
        int priority = "E".equals(level) ? Log.ERROR : ("W".equals(level) ? Log.WARN : Log.INFO);
        try {
            Log.println(priority, "BA4D-KeepAlive", tag + ": " + msg);
        } catch (Throwable ignored) {
        }
        writeXposed(text);
        writeFile(text);
    }

    private static void writeXposed(String text) {
        if (!xposedLogResolved) {
            synchronized (KLog.class) {
                if (!xposedLogResolved) {
                    try {
                        Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
                        xposedLog = bridge.getMethod("log", String.class);
                    } catch (Throwable t) {
                        xposedLog = null;
                    }
                    xposedLogResolved = true;
                }
            }
        }
        Method m = xposedLog;
        if (m != null) {
            try {
                m.invoke(null, text);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Picks the log file location on first use. The private directory goes first
     * because the injected module is guaranteed to be allowed there.
     */
    private static String resolvePath() {
        String cached = activePath;
        if (cached != null) {
            return cached;
        }
        synchronized (FILE_LOCK) {
            if (activePath != null) {
                return activePath;
            }
            for (String dir : new String[]{PRIVATE_DIR, DIR}) {
                try {
                    File d = new File(dir);
                    if (!d.exists() && !d.mkdirs() && !d.isDirectory()) {
                        continue;
                    }
                    File probe = new File(d, ".writable");
                    if (!probe.exists() && !probe.createNewFile() && !probe.isFile()) {
                        continue;
                    }
                    if (probe.isFile() && probe.canWrite()) {
                        //noinspection ResultOfMethodCallIgnored
                        probe.setReadable(true, false);
                        //noinspection ResultOfMethodCallIgnored
                        d.setReadable(true, false);
                        //noinspection ResultOfMethodCallIgnored
                        d.setExecutable(true, false);
                        activePath = new File(d, FILE_NAME).getAbsolutePath();
                        return activePath;
                    }
                } catch (Throwable ignored) {
                }
            }
            fileBroken = true;
            return null;
        }
    }

    private static void writeFile(String text) {
        if (fileBroken) {
            return;
        }
        synchronized (FILE_LOCK) {
            String path = resolvePath();
            if (path == null) {
                return;
            }
            FileWriter fw = null;
            try {
                File f = new File(path);
                if (f.exists() && f.length() > MAX_BYTES) {
                    File old = new File(path + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                fw = new FileWriter(f, true);
                fw.write(text);
                fw.write('\n');
            } catch (Throwable t) {
                fileBroken = true;
            } finally {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    public static String tail() {
        StringBuilder sb = new StringBuilder();
        synchronized (RING_BUF) {
            for (String s : RING_BUF) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    /** The path the log is actually being written to, or null when logging to file failed. */
    public static String logPath() {
        if (fileBroken) {
            return null;
        }
        return resolvePath();
    }
}
