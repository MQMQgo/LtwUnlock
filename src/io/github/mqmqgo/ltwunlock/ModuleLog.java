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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * LtwUnlock logging.
 * <ul>
 *   <li>Every line → android.util.Log (tag LtwUnlock / LtwUnlock-Img / LtwUnlock-SS).</li>
 *   <li>Hook side → always {@link XposedInterface#log} with tag {@link #TAG} (LSPosed search);
 *       channel is prefixed in the message ([SS]/[Img]/…).</li>
 *   <li>Hook side → also {@link LogRelay} → module's {@link LogProvider}.</li>
 * </ul>
 */
public final class ModuleLog {
    public static final String TAG = "LtwUnlock";
    public static final String TAG_SS = "LtwUnlock-SS";
    public static final String TAG_IMG = "LtwUnlock-Img";
    public static final String LOCAL_NAME = "ltwunlock.log";
    private static final long MAX_LOCAL = 256L * 1024L;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile boolean sSystemServer;
    private static volatile boolean sHookProcess;
    private static volatile XposedInterface sXp;
    private static volatile String sProcLabel = "?";

    // Soft client-side ceiling so a hot path cannot wedge LSPosed; INFO+ always attempts.
    private static final Object RL = new Object();
    private static long sWinStart;
    private static int sWinCount;
    private static int sDropped;
    private static final int WIN_MAX = 800;

    private ModuleLog() {}

    /** Called from ModernInit in hooked processes. */
    public static void attach(XposedInterface xp, boolean systemServer) {
        sXp = xp;
        sHookProcess = true;
        if (systemServer) sSystemServer = true;
        try {
            sProcLabel = (systemServer ? "system_server" : "app")
                    + " pid=" + Process.myPid() + " uid=" + Process.myUid();
        } catch (Throwable ignored) {
            sProcLabel = systemServer ? "system_server" : "app";
        }
        LogRelay.ensureStarted();
        // Bootstrap line: proves attach + xp.log + relay path are wired.
        i(TAG, "ModuleLog.attach ok (" + sProcLabel + ")");
    }

    public static void setSystemServer(boolean ss) {
        sSystemServer = ss;
    }

    public static boolean isSystemServer() {
        return sSystemServer || Process.myUid() == Process.SYSTEM_UID;
    }

    public static void d(String msg) { log(Log.DEBUG, null, msg, null); }
    public static void d(String tag, String msg) { log(Log.DEBUG, tag, msg, null); }
    public static void i(String msg) { log(Log.INFO, null, msg, null); }
    public static void i(String tag, String msg) { log(Log.INFO, tag, msg, null); }
    public static void w(String msg) { log(Log.WARN, null, msg, null); }
    public static void w(String tag, String msg) { log(Log.WARN, tag, msg, null); }
    public static void w(String msg, Throwable t) { log(Log.WARN, null, msg, t); }
    public static void w(String tag, String msg, Throwable t) { log(Log.WARN, tag, msg, t); }
    public static void e(String msg) { log(Log.ERROR, null, msg, null); }
    public static void e(String tag, String msg) { log(Log.ERROR, tag, msg, null); }
    public static void e(String msg, Throwable t) { log(Log.ERROR, null, msg, t); }
    public static void e(String tag, String msg, Throwable t) { log(Log.ERROR, tag, msg, t); }

    /** UI-process events: logcat + app-private file. */
    public static void ui(Context ctx, String msg) {
        try {
            Log.i(TAG, msg == null ? "" : msg);
        } catch (Throwable ignored) {
        }
        if (ctx != null) {
            appendLocal(localFile(ctx), formatLine("I", TAG, msg, null));
        }
    }

    /** Core path. Never throws. Every hook-process line → logcat + xp.log + LogRelay. */
    static void log(int priority, String tag, String msg, Throwable t) {
        try {
            final boolean ss = isSystemServer();
            if (tag == null || (ss && TAG.equals(tag))) {
                tag = ss ? TAG_SS : TAG;
            }
            if (msg == null) msg = "";

            // 1) logcat (keeps original channel tag)
            try {
                if (t != null) {
                    Log.println(priority, tag, msg + '\n' + Log.getStackTraceString(t));
                } else {
                    Log.println(priority, tag, msg);
                }
            } catch (Throwable ignored) {
            }

            if (!sHookProcess) {
                return;
            }

            // 2) libxposed / LSPosed manager — always tag=LtwUnlock so search finds every line;
            //    channel encoded in message. Prefer 3-arg (4-arg has been observed to drop).
            xposedLog(priority, tag, msg, t);

            // 3) batch relay → module LogProvider → LogActivity
            LogRelay.enqueue(formatLine(level(priority), tag, msg, t));
        } catch (Throwable ignored) {
        }
    }

    private static void xposedLog(int priority, String channelTag, String msg, Throwable t) {
        XposedInterface xp = sXp;
        if (xp == null) return;
        if (priority <= Log.DEBUG && !allowXposedLog()) {
            // DEBUG only is soft-limited; INFO+ always goes through.
            return;
        }
        String prefix = channelPrefix(channelTag);
        String body = prefix + (msg == null ? "" : msg);
        if (t != null) {
            String st = Log.getStackTraceString(t);
            if (st.length() > 3500) st = st.substring(0, 3500) + "...";
            body = body + '\n' + st;
        }
        try {
            xp.log(priority, TAG, body);
            return;
        } catch (Throwable ignored) {
        }
        // Fallback: 4-arg, then bare message
        try {
            if (t != null) xp.log(priority, TAG, prefix + msg, t);
            else xp.log(priority, TAG, body);
        } catch (Throwable ignored) {
        }
    }

    private static String channelPrefix(String channelTag) {
        if (channelTag == null || TAG.equals(channelTag)) return "";
        if (TAG_SS.equals(channelTag)) return "[SS] ";
        if (TAG_IMG.equals(channelTag)) return "[Img] ";
        if (channelTag.startsWith("LtwUnlock")) {
            return "[" + channelTag.substring("LtwUnlock".length()).replaceFirst("^-?", "") + "] ";
        }
        return "[" + channelTag + "] ";
    }

    private static boolean allowXposedLog() {
        synchronized (RL) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - sWinStart > 10_000L) {
                int dropped = sDropped;
                sWinStart = now;
                sWinCount = 0;
                sDropped = 0;
                if (dropped > 0) {
                    XposedInterface xp = sXp;
                    try {
                        if (xp != null) {
                            xp.log(Log.WARN, TAG, "(debug rate-limit: dropped " + dropped + " lines)");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (sWinCount >= WIN_MAX) {
                sDropped++;
                return false;
            }
            sWinCount++;
            return true;
        }
    }

    private static String level(int p) {
        switch (p) {
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            default: return "V";
        }
    }

    static String formatLine(String level, String tag, String msg, Throwable t) {
        String ts;
        synchronized (FMT) {
            ts = FMT.format(new Date());
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(ts).append(' ').append(level).append('/').append(tag).append(": ");
        sb.append(msg == null ? "" : msg);
        if (t != null) {
            String st = Log.getStackTraceString(t);
            if (st.length() > 4000) st = st.substring(0, 4000) + "...";
            sb.append('\n').append(st);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static synchronized void appendLocal(File file, String line) {
        try {
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(line);
            }
            if (file.length() > MAX_LOCAL) {
                LogProvider.trimTail(file, MAX_LOCAL / 2);
            }
        } catch (Throwable ignored) {
        }
    }

    public static File localFile(Context ctx) {
        return new File(ctx.getFilesDir(), LOCAL_NAME);
    }

    /** Hook relay file (written by LogProvider) + local UI log + provider diagnostics. */
    public static String readCombined(Context ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            sb.append(LogProvider.diagText(ctx));
        }
        String hook = ctx != null ? readFile(LogProvider.hookFile(ctx)) : "";
        sb.append("===== hook 日志 (连接到 Windows + 系统框架, 经 LogProvider 中转) =====\n");
        if (hook.isEmpty()) {
            sb.append("(暂无 hook 日志)\n")
                    .append("· 确认 LSPosed 中已启用本模块，作用域勾选 系统框架 + 连接到 Windows，并已重启\n")
                    .append("· 打开一次「连接到 Windows」后点刷新；看上方「诊断」计数是否增加\n")
                    .append("· 也可在 LSPosed 管理器 → 日志 搜索 LtwUnlock（含 [SS]/[Img] 前缀）\n");
        } else {
            sb.append(hook);
            if (!hook.endsWith("\n")) sb.append('\n');
        }
        String local = ctx != null ? readFile(localFile(ctx)) : "";
        if (!local.isEmpty()) {
            sb.append("===== 模块界面日志 =====\n").append(local);
            if (!local.endsWith("\n")) sb.append('\n');
        }
        return sb.toString();
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        try {
            LogProvider.clear(ctx);
        } catch (Throwable ignored) {
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            localFile(ctx).delete();
        } catch (Throwable ignored) {
        }
        ui(ctx, "UI: logs cleared by user");
    }

    static String readFile(File file) {
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
