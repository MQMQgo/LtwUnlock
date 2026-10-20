package io.github.mqmqgo.ltwunlock;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent log buffer for LtwUnlock.
 * <ul>
 *   <li>App-process hooks → logcat {@code LtwUnlock} + file
 *       {@link #HOOK_LOG_PATH} (and host filesDir fallback).</li>
 *   <li>system_server → logcat {@code LtwUnlock-SS} + {@code /data/local/tmp/ltwunlock-ss.log}.</li>
 *   <li>UI → app-private {@link #LOCAL_NAME}; export merges hook file +
 *       local + best-effort logcat dump.</li>
 * </ul>
 */
public final class ModuleLog {
    public static final String TAG = "LtwUnlock";
    public static final String TAG_SS = "LtwUnlock-SS";
    /** Shared hook-side log (world-readable on many devices). */
    public static final String HOOK_LOG_PATH = "/data/local/tmp/ltwunlock.log";
    /** Secondary path often writable from app processes on rooted devices. */
    public static final String HOOK_LOG_CACHE = "/cache/ltwunlock.log";
    public static final String LOCAL_NAME = "ltwunlock.log";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile boolean sSystemServer;
    private static volatile File sHostFilesLog;

    private ModuleLog() {}

    /** Mark current process as system_server (no file writes). */
    public static void setSystemServer(boolean ss) {
        sSystemServer = ss;
    }

    public static boolean isSystemServer() {
        return sSystemServer || Process.myUid() == Process.SYSTEM_UID;
    }

    public static void d(String msg) {
        log(Log.DEBUG, msg, null);
    }

    public static void d(String tag, String msg) {
        log(Log.DEBUG, msg, null);
    }

    public static void i(String msg) {
        log(Log.INFO, msg, null);
    }

    public static void i(String tag, String msg) {
        log(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        log(Log.WARN, msg, null);
    }

    public static void w(String tag, String msg) {
        log(Log.WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        log(Log.WARN, msg, t);
    }

    public static void w(String tag, String msg, Throwable t) {
        log(Log.WARN, msg, t);
    }

    public static void e(String msg) {
        log(Log.ERROR, msg, null);
    }

    public static void e(String tag, String msg) {
        log(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        log(Log.ERROR, msg, t);
    }

    public static void e(String tag, String msg, Throwable t) {
        log(Log.ERROR, msg, t);
    }

    /** UI-process events: also append under filesDir. */
    public static void ui(Context ctx, String msg) {
        log(Log.INFO, msg, null);
        if (ctx != null) {
            appendToFile(localFile(ctx), formatLine("I", TAG, msg, null));
        }
    }

    private static void log(int priority, String msg, Throwable t) {
        final boolean ss = isSystemServer();
        final String tag = ss ? TAG_SS : TAG;
        if (t != null) {
            if (priority >= Log.ERROR) {
                Log.e(tag, msg, t);
            } else {
                Log.w(tag, msg, t);
            }
        } else {
            Log.println(priority, tag, msg == null ? "" : msg);
        }
        // system_server: logcat only
        if (ss) {
            return;
        }
        String level;
        switch (priority) {
            case Log.DEBUG:
                level = "D";
                break;
            case Log.INFO:
                level = "I";
                break;
            case Log.WARN:
                level = "W";
                break;
            case Log.ERROR:
                level = "E";
                break;
            default:
                level = "V";
                break;
        }
        String line = formatLine(level, tag, msg, t);
        appendToFile(new File(HOOK_LOG_PATH), line);
        appendToFile(new File(HOOK_LOG_CACHE), line);
        ensureHostFilesLog();
        if (sHostFilesLog != null) {
            appendToFile(sHostFilesLog, line);
        }
    }

    /**
     * Resolve hooked-app filesDir/ltwunlock.log via ActivityThread so install/hit
     * lines survive even when /data/local/tmp is SELinux-blocked. LogActivity
     * still may not read this path without root; logcat dump covers that.
     */
    private static void ensureHostFilesLog() {
        if (sHostFilesLog != null) {
            return;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return;
            }
            Object filesDir = app.getClass().getMethod("getFilesDir").invoke(app);
            if (filesDir instanceof File) {
                sHostFilesLog = new File((File) filesDir, LOCAL_NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String formatLine(String level, String tag, String msg, Throwable t) {
        String ts;
        synchronized (FMT) {
            ts = FMT.format(new Date());
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(ts).append(' ').append(level).append('/').append(tag).append(": ");
        sb.append(msg == null ? "" : msg);
        if (t != null) {
            sb.append('\n').append(Log.getStackTraceString(t));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void appendToFile(File file, String line) {
        if (file == null || line == null) {
            return;
        }
        LOCK.lock();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            boolean created = !file.exists();
            try (FileOutputStream fos = new FileOutputStream(file, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(line);
                w.flush();
            }
            if (created) {
                tryMakeWorldReadable(file);
            }
            truncateIfNeeded(file);
        } catch (Throwable ignored) {
            // Hook/UI must never crash on logging.
        } finally {
            LOCK.unlock();
        }
    }

    private static void tryMakeWorldReadable(File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            file.setWritable(true, false);
        } catch (Throwable ignored) {
        }
        try {
            Runtime.getRuntime().exec(new String[]{"chmod", "666", file.getAbsolutePath()})
                    .waitFor();
        } catch (Throwable ignored) {
        }
    }

    private static void truncateIfNeeded(File file) {
        try {
            long len = file.length();
            if (len <= MAX_BYTES) {
                return;
            }
            long keep = MAX_BYTES / 2;
            byte[] buf = new byte[(int) keep];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(len - keep);
                int n = raf.read(buf);
                if (n <= 0) {
                    return;
                }
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        start = i + 1;
                        break;
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(file, false)) {
                    fos.write("--- truncated ---\n".getBytes(StandardCharsets.UTF_8));
                    if (start < n) {
                        fos.write(buf, start, n - start);
                    }
                }
            }
            tryMakeWorldReadable(file);
        } catch (Throwable ignored) {
        }
    }

    public static File localFile(Context ctx) {
        return new File(ctx.getFilesDir(), LOCAL_NAME);
    }

    /** Combined hook tmp log + local UI log + logcat dump (hook first). */
    public static String readCombined(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String hook = readFile(new File(HOOK_LOG_PATH));
        if (hook == null || hook.isEmpty()) {
            // Rooted devices: su cat of hook log / host files log
            hook = readViaSu("cat " + HOOK_LOG_PATH + " 2>/dev/null");
        }
        String cacheHook = readFile(new File(HOOK_LOG_CACHE));
        String local = ctx != null ? readFile(localFile(ctx)) : "";

        if (hook != null && !hook.isEmpty()) {
            sb.append("===== hook (/data/local/tmp/ltwunlock.log) =====\n");
            sb.append(hook);
            if (!hook.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("===== hook log empty or unreadable =====\n");
            sb.append("(app hooks write here; if SELinux blocks, see logcat below)\n");
        }
        if (cacheHook != null && !cacheHook.isEmpty()) {
            sb.append("===== hook (/cache/ltwunlock.log) =====\n");
            sb.append(cacheHook);
            if (!cacheHook.endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (local != null && !local.isEmpty()) {
            sb.append("===== app (filesDir/ltwunlock.log) =====\n");
            sb.append(local);
            if (!local.endsWith("\n")) {
                sb.append('\n');
            }
        }

        sb.append(readSsSection());

        String logcat = dumpLogcat();
        sb.append("===== logcat (LtwUnlock / LtwUnlock-SS) =====\n");
        if (logcat != null && !logcat.isEmpty()) {
            sb.append(logcat);
            if (!logcat.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("(logcat dump empty — need READ_LOGS or: adb logcat -s LtwUnlock:D LtwUnlock-SS:D)\n");
            sb.append("system_server hooks only appear under tag LtwUnlock-SS\n");
        }

        if (sb.length() == 0) {
            return "(no logs yet)\n";
        }
        return sb.toString();
    }

    public static final String SS_LOG_PATH = "/data/local/tmp/ltwunlock-ss.log";
    public static final String SS_LOG_ALT = "/data/system/ltwunlock-ss.log";

    /** system_server status file: direct read first, then su -c cat (stderr captured). */
    public static String readSsSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== system_server (" + SS_LOG_PATH + ") =====\n");
        String direct = null;
        String directErr = null;
        try {
            File f = new File(SS_LOG_PATH);
            if (!f.exists()) {
                directErr = "not visible to app (missing or SELinux)";
            } else if (!f.canRead()) {
                directErr = "exists but not readable";
            } else {
                direct = readFile(f);
            }
        } catch (Throwable t) {
            directErr = "direct read threw: " + t;
        }
        if (direct != null && !direct.isEmpty()) {
            sb.append("[source: direct]\n").append(direct);
        } else {
            sb.append("[direct read: ").append(directErr == null ? "empty" : directErr).append("]\n");
            String su = readProcess(new String[]{"su", "-c",
                    "cat " + SS_LOG_PATH + " 2>&1; echo \"--- alt " + SS_LOG_ALT + " ---\"; cat "
                            + SS_LOG_ALT + " 2>&1"}, 10000);
            if (su != null && !su.isEmpty()) {
                sb.append("[source: su -c cat, stderr included]\n").append(su);
            } else {
                sb.append("[su -c cat produced no output: no root / su denied]\n");
                sb.append("If empty after reboot: 系统框架 scope not active or SS hooks never ran.\n");
            }
        }
        if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Best-effort: plain logcat (own UID on modern Android), then {@code su -c}.
     */
    public static String dumpLogcat() {
        String cmd = "logcat -d -t 400 -s LtwUnlock:D LtwUnlock-SS:D LtwUnlock:V LtwUnlock-SS:V";
        String out = readProcess(new String[]{"sh", "-c", cmd}, 8000);
        if (out != null && out.trim().length() > 0 && !looksLikeLogcatDenied(out)) {
            return out;
        }
        String viaSu = readViaSu(cmd);
        if (viaSu != null && !viaSu.isEmpty()) {
            return viaSu;
        }
        return out == null ? "" : out;
    }

    private static boolean looksLikeLogcatDenied(String out) {
        String l = out.toLowerCase(Locale.US);
        return l.contains("not permitted") || l.contains("permission denial")
                || l.contains("unable to open");
    }

    private static String readViaSu(String shellCmd) {
        String out = readProcess(new String[]{"su", "-c", shellCmd}, 10000);
        if (out != null && !out.isEmpty()) {
            return out;
        }
        // Magisk / KSU variants
        out = readProcess(new String[]{"su", "0", "sh", "-c", shellCmd}, 10000);
        return out == null ? "" : out;
    }

    private static String readProcess(String[] cmd, int timeoutMs) {
        java.lang.Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final StringBuilder sb = new StringBuilder();
            final java.lang.Process proc = p;
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                        if (sb.length() > 400_000) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "ltw-logcat");
            t.start();
            t.join(timeoutMs);
            try {
                proc.exitValue();
            } catch (IllegalThreadStateException pending) {
                proc.destroy();
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void clear(Context ctx) {
        LOCK.lock();
        try {
            deleteQuiet(new File(HOOK_LOG_PATH));
            deleteQuiet(new File(HOOK_LOG_CACHE));
            if (ctx != null) {
                deleteQuiet(localFile(ctx));
            }
        } finally {
            LOCK.unlock();
        }
        // Best-effort clear via su
        readViaSu("rm -f " + HOOK_LOG_PATH + " " + HOOK_LOG_CACHE + " " + SS_LOG_PATH + " " + SS_LOG_ALT);
        i("logs cleared");
        if (ctx != null) {
            try {
                appendToFile(localFile(ctx),
                        formatLine("I", TAG, "UI: logs cleared by user", null));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void deleteQuiet(File f) {
        try {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            return "(read failed: " + t.getMessage() + ")\n";
        }
        return sb.toString();
    }
}
