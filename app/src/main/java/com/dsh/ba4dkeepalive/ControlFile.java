package com.dsh.ba4dkeepalive;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Shared configuration and status for three very different callers:
 *   - the settings UI (this module's own process, a different UID),
 *   - the hooked target app process (where this class is injected),
 *   - the Magisk shell daemon and plain `adb shell`.
 *
 * Because those three do not share writable storage on modern Android, the file
 * location is resolved at runtime:
 *
 *   1. /data/local/tmp/ba4d_keepalive  - visible to adb/shell and the Magisk daemon,
 *      but on Android 10+ SELinux denies writes from an app UID.
 *   2. /data/data/com.miradesktop.ba4d/files/ba4d_keepalive - always writable by the
 *      injected module, reachable by root, not by the settings UI.
 *
 * Whichever location is actually writable wins; the Magisk daemon writes a small
 * "selection" file so the settings UI knows which one to edit.
 */
public final class ControlFile {

    /** Preferred location: readable and writable by adb/shell and by the Magisk daemon. */
    public static final String DIR = "/data/local/tmp/ba4d_keepalive";

    /** Fallback for app UIDs that SELinux forbids under /data/local/tmp. */
    public static final String PRIVATE_DIR = "/data/data/com.miradesktop.ba4d/files/ba4d_keepalive";

    public static final String CONF = DIR + "/config.conf";
    public static final String STATUS = DIR + "/status";
    public static final String CONF_FALLBACK = PRIVATE_DIR + "/config.conf";
    public static final String STATUS_FALLBACK = PRIVATE_DIR + "/status";

    /** Written by the Magisk daemon, read by the settings UI, to point at the live config. */
    public static final String SELECTION = DIR + "/selection";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_RESTART_SERVICE = "restart_service";
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    public static final String KEY_RESTORE_A11Y = "restore_accessibility";
    public static final String KEY_WATCHDOG = "watchdog";
    public static final String KEY_BRING_TO_FRONT = "bring_to_front";
    public static final String KEY_RESPECT_USER_STOP = "respect_user_stop";
    public static final String KEY_RESTART_DELAY_MS = "restart_delay_ms";
    public static final String KEY_MAX_RESTARTS = "max_restarts_per_minute";

    /** Lazily resolved, then cached for the lifetime of the process. */
    private static volatile String activeConf;
    private static volatile String activeStatus;

    private static final Object LOCK = new Object();
    private static volatile Properties cache = new Properties();
    private static volatile long cacheMtime = -1L;
    private static volatile boolean cacheLoaded = false;
    private static volatile boolean cacheFromFallback = false;

    private ControlFile() {
    }

    // ------------------------------------------------------------ path picking

    private static boolean ensureWritableDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            if (!dir.isDirectory()) {
                return false;
            }
            File probe = new File(dir, ".writable");
            if (!probe.exists() && !probe.createNewFile()) {
                // A concurrent creator may have won; fall through to the checks.
                if (!probe.exists()) {
                    return false;
                }
            }
            boolean ok = probe.isFile() && probe.canWrite();
            //noinspection ResultOfMethodCallIgnored
            probe.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dir.setExecutable(true, false);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves where configuration lives.
     *
     * The private directory always wins when it already holds a config file: the
     * injected module owns it, the Magisk daemon reaches it as root, and it is the
     * only location that works on ROMs where SELinux forbids app UIDs from writing
     * under /data/local/tmp. The shared directory is the fallback and its file is
     * still honoured when the private one does not exist yet.
     */
    private static String confPath(boolean forWriting) {
        String cached = activeConf;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (activeConf != null) {
                return activeConf;
            }
            File privConf = new File(CONF_FALLBACK);
            File tmpConf = new File(CONF);

            // An explicit selection file lets an advanced user redirect the config.
            String selected = readSelection();
            if (selected != null && !selected.isEmpty()) {
                File candidate = new File(selected);
                boolean usable = forWriting
                        ? ensureWritableDir(candidate.getParentFile())
                        : candidate.isFile() && candidate.canRead();
                if (usable) {
                    cacheFromFallback = candidate.getAbsolutePath().startsWith(PRIVATE_DIR);
                    activeConf = candidate.getAbsolutePath();
                    return activeConf;
                }
            }

            if (privConf.isFile() && privConf.canRead()) {
                cacheFromFallback = true;
                activeConf = privConf.getAbsolutePath();
                return activeConf;
            }
            if (tmpConf.isFile() && tmpConf.canRead()) {
                cacheFromFallback = false;
                activeConf = tmpConf.getAbsolutePath();
                return activeConf;
            }

            // Nothing exists yet: prefer whichever directory we can actually write.
            if (ensureWritableDir(new File(PRIVATE_DIR))) {
                cacheFromFallback = true;
                activeConf = privConf.getAbsolutePath();
                return activeConf;
            }
            if (ensureWritableDir(new File(DIR))) {
                cacheFromFallback = false;
                activeConf = tmpConf.getAbsolutePath();
                return activeConf;
            }

            // Last resort: report the private path; the module can normally create it.
            cacheFromFallback = true;
            activeConf = privConf.getAbsolutePath();
            return activeConf;
        }
    }

    private static String statusPath() {
        String cached = activeStatus;
        if (cached != null) {
            return cached;
        }
        // Status is always written by the module, so follow the config location first
        // and only fall back to the private dir when nothing is writable.
        String conf = confPath(false);
        String derived = conf.endsWith("config.conf")
                ? conf.substring(0, conf.length() - "config.conf".length()) + "status"
                : STATUS;
        activeStatus = derived;
        return activeStatus;
    }

    public static boolean isUsingFallbackDir() {
        confPath(false);
        return cacheFromFallback;
    }

    public static String activeConfPath() {
        return confPath(false);
    }

    public static String activeStatusPath() {
        return statusPath();
    }

    private static String readSelection() {
        try {
            File f = new File(SELECTION);
            if (!f.isFile()) {
                return null;
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line = r.readLine();
                return line == null ? null : line.trim();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------- reading

    /** Reads config.conf from disk, reusing the cached copy while it is unchanged. */
    public static Properties load() {
        synchronized (LOCK) {
            try {
                File f = new File(confPath(false));
                long mt = f.isFile() ? f.lastModified() : -1L;
                if (!cacheLoaded || mt != cacheMtime) {
                    Properties p = new Properties();
                    if (f.isFile() && f.length() > 0) {
                        FileInputStream in = new FileInputStream(f);
                        try {
                            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                        } finally {
                            in.close();
                        }
                    }
                    cache = p;
                    cacheMtime = mt;
                    cacheLoaded = true;
                }
            } catch (Throwable t) {
                KLog.w("ControlFile", "load failed: " + KLog.describe(t));
            }
            return cache;
        }
    }

    public static void reload() {
        synchronized (LOCK) {
            cacheMtime = -1L;
            cacheLoaded = false;
        }
        load();
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = load().getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    public static int getInt(String key, int def) {
        String v = load().getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static String get(String key, String def) {
        String v = load().getProperty(key);
        return v == null ? def : v.trim();
    }

    // -------------------------------------------------------------- writing

    /** Writes a single key, preserving everything else in the file. */
    public static void put(String key, String value) {
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            map.put(key, value);
            writeAll(map);
        }
    }

    public static void putAll(Map<String, String> values) {
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            map.putAll(values);
            writeAll(map);
        }
    }

    /** Ensures every known key exists, filling in defaults without clobbering user values. */
    public static void ensureDefaults() {
        synchronized (LOCK) {
            Map<String, String> map = readAll();
            boolean changed = false;
            changed |= putIfAbsent(map, KEY_ENABLED, "true");
            changed |= putIfAbsent(map, KEY_RESTART_SERVICE, "true");
            changed |= putIfAbsent(map, KEY_KEEP_ALIVE, "true");
            changed |= putIfAbsent(map, KEY_RESTORE_A11Y, "true");
            changed |= putIfAbsent(map, KEY_WATCHDOG, "true");
            changed |= putIfAbsent(map, KEY_BRING_TO_FRONT, "true");
            changed |= putIfAbsent(map, KEY_RESPECT_USER_STOP, "true");
            changed |= putIfAbsent(map, KEY_RESTART_DELAY_MS, "800");
            changed |= putIfAbsent(map, KEY_MAX_RESTARTS, "6");
            if (changed || !new File(confPath(false)).isFile()) {
                writeAll(map);
            }
        }
    }

    private static boolean putIfAbsent(Map<String, String> map, String k, String v) {
        if (!map.containsKey(k)) {
            map.put(k, v);
            return true;
        }
        return false;
    }

    private static Map<String, String> readAll() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            File f = new File(confPath(true));
            if (f.isFile()) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) {
                            continue;
                        }
                        int eq = s.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        map.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                    }
                } finally {
                    r.close();
                }
            }
        } catch (Throwable t) {
            KLog.w("ControlFile", "readAll failed: " + KLog.describe(t));
        }
        return map;
    }

    private static void writeAll(Map<String, String> map) {
        String path = confPath(true);
        try {
            File dst = new File(path);
            File dir = dst.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# BA4D KeepAlive configuration\n");
            sb.append("# edited by the module; safe to edit by hand\n");
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }

            File tmp = new File(path + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(dst)) {
                FileOutputStream direct = new FileOutputStream(dst);
                try {
                    direct.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                } finally {
                    direct.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            dst.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dst.setWritable(true, false);
            cacheMtime = -1L;
            cacheLoaded = false;
        } catch (Throwable t) {
            KLog.w("ControlFile", "writeAll failed: " + KLog.describe(t));
        }
    }

    /** Status line written by the injected module so the UI and shell can see liveness. */
    public static void writeStatus(String text) {
        String body = "time=" + (System.currentTimeMillis() / 1000L) + "\n" + text;
        // Primary location next to the config file; a copy goes to the shared directory
        // when that is possible, because the settings UI cannot read app-private storage.
        writeTextFile(statusPath(), body);
        String primary = statusPath();
        if (!primary.equals(STATUS)) {
            writeTextFile(STATUS, body);
        }
    }

    private static void writeTextFile(String path, String body) {
        try {
            File f = new File(path);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    public static String readStatus() {
        for (String path : new String[]{statusPath(), STATUS_FALLBACK, STATUS}) {
            String text = readAllText(path);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String readAllText(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                r.close();
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
