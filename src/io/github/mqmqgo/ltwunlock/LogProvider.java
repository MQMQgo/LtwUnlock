package io.github.mqmqgo.ltwunlock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Exported call()-only sink for hook log lines. Accepts system_server (uid 1000)
 * and com.microsoft.appmanager; stores lines in files/hook.log. Records diagnostics
 * for LogActivity (receive/reject counters, last caller).
 */
public final class LogProvider extends ContentProvider {
    static final String HOOK_NAME = "hook.log";
    static final String METHOD_STATS = "stats";
    static final String METHOD_PROBE = "probe";
    private static final String TARGET = "com.microsoft.appmanager";
    private static final long MAX_BYTES = 512L * 1024L;
    private static final Object FILE_LOCK = new Object();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private volatile int mTargetAppId = -2;

    // Diagnostics (process-local; survive while provider process is alive)
    private static volatile long sRecvOk;
    private static volatile long sRecvReject;
    private static volatile long sRecvBad;
    private static volatile int sLastCallerUid = -1;
    private static volatile String sLastCallerPkg = "-";
    private static volatile String sLastProc = "-";
    private static volatile String sLastRejectReason = "-";
    private static volatile int sLastRejectUid = -1;
    private static volatile String sLastOkAt = "-";
    private static volatile String sLastRejectAt = "-";
    private static volatile String sLastError = "-";

    static File hookFile(Context ctx) {
        return new File(ctx.getFilesDir(), HOOK_NAME);
    }

    static void clear(Context ctx) {
        synchronized (FILE_LOCK) {
            //noinspection ResultOfMethodCallIgnored
            hookFile(ctx).delete();
        }
        sRecvOk = sRecvReject = sRecvBad = 0;
        sLastError = "-";
    }

    /** Human-readable diagnostics block for LogActivity. */
    static String diagText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 诊断 (LogProvider) =====\n");
        // Live in-process stats (may be 0 if provider process was killed)
        sb.append("in-process: ok=").append(sRecvOk)
                .append(" reject=").append(sRecvReject)
                .append(" bad=").append(sRecvBad).append('\n');
        sb.append("lastCaller uid=").append(sLastCallerUid)
                .append(" pkg=").append(sLastCallerPkg)
                .append(" proc=").append(sLastProc)
                .append(" at=").append(sLastOkAt).append('\n');
        sb.append("lastReject uid=").append(sLastRejectUid)
                .append(" reason=").append(sLastRejectReason)
                .append(" at=").append(sLastRejectAt).append('\n');
        sb.append("lastError=").append(sLastError).append('\n');
        // Persisted stats via call (works even after process death if we also write file header)
        if (ctx != null) {
            try {
                Bundle r = ctx.getContentResolver().call(LogRelay.AUTHORITY, METHOD_STATS, null, null);
                if (r != null) {
                    sb.append("provider-call: ok=").append(r.getLong("ok", -1))
                            .append(" reject=").append(r.getLong("reject", -1))
                            .append(" lastUid=").append(r.getInt("lastUid", -1))
                            .append(" lastPkg=").append(r.getString("lastPkg", "-"))
                            .append('\n');
                } else {
                    sb.append("provider-call: stats returned null\n");
                }
            } catch (Throwable t) {
                sb.append("provider-call: ").append(t.getClass().getSimpleName())
                        .append(": ").append(t.getMessage()).append('\n');
            }
            try {
                File f = hookFile(ctx);
                sb.append("hook.log exists=").append(f.exists())
                        .append(" len=").append(f.exists() ? f.length() : 0)
                        .append(" path=").append(f.getAbsolutePath()).append('\n');
            } catch (Throwable ignored) {
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static String now() {
        synchronized (FMT) {
            return FMT.format(new Date());
        }
    }

    private boolean callerAllowed(int uid) {
        int appId = uid % 100000;
        if (appId == Process.SYSTEM_UID || uid == Process.myUid()) return true;
        // Some OEMs: system_server may present as AID_SYSTEM via different path
        if (appId == 1000) return true;
        int t = mTargetAppId;
        if (t == -2) {
            try {
                t = getContext().getPackageManager().getPackageUid(TARGET, 0) % 100000;
            } catch (Throwable e) {
                t = -1;
            }
            mTargetAppId = t;
        }
        if (t >= 0 && appId == t) return true;
        try {
            String[] pk = getContext().getPackageManager().getPackagesForUid(uid);
            if (pk != null) {
                for (String p : pk) {
                    if (TARGET.equals(p)) {
                        mTargetAppId = appId;
                        return true;
                    }
                    if ("android".equals(p)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String pkgOf(int uid) {
        try {
            String[] pk = getContext().getPackageManager().getPackagesForUid(uid);
            if (pk != null && pk.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < pk.length && i < 3; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(pk[i]);
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return call(method, arg, extras);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle r = new Bundle();
        int uid = Binder.getCallingUid();
        if (METHOD_STATS.equals(method)) {
            // App UI only
            if (uid != Process.myUid()) {
                r.putBoolean("ok", false);
                return r;
            }
            r.putBoolean("ok", true);
            r.putLong("ok", sRecvOk);
            r.putLong("reject", sRecvReject);
            r.putLong("bad", sRecvBad);
            r.putInt("lastUid", sLastCallerUid);
            r.putString("lastPkg", sLastCallerPkg);
            r.putString("lastProc", sLastProc);
            r.putString("lastOkAt", sLastOkAt);
            r.putString("lastRejectReason", sLastRejectReason);
            r.putInt("lastRejectUid", sLastRejectUid);
            r.putString("lastError", sLastError);
            return r;
        }
        if (METHOD_PROBE.equals(method)) {
            if (uid != Process.myUid()) {
                r.putBoolean("ok", false);
                return r;
            }
            try {
                String line = now() + " I/LtwUnlock: (probe) LogActivity self-test via LogProvider\n";
                appendLines(singleton(line), "probe");
                sRecvOk++;
                sLastCallerUid = uid;
                sLastCallerPkg = getContext().getPackageName();
                sLastProc = "probe";
                sLastOkAt = now();
                r.putBoolean("ok", true);
            } catch (Throwable t) {
                sLastError = t.getMessage();
                r.putBoolean("ok", false);
            }
            return r;
        }
        if (!LogRelay.METHOD_APPEND.equals(method) || extras == null) {
            sRecvBad++;
            r.putBoolean("ok", false);
            r.putString("reason", "bad-method");
            return r;
        }
        if (!callerAllowed(uid)) {
            sRecvReject++;
            sLastRejectUid = uid;
            sLastRejectReason = "uid not allowed appId=" + (uid % 100000) + " pkg=" + pkgOf(uid);
            sLastRejectAt = now();
            r.putBoolean("ok", false);
            r.putString("reason", sLastRejectReason);
            // Soft-fail (no SecurityException): some resolvers treat throw as hard failure
            // and never retry; diagnostics still recorded.
            return r;
        }
        try {
            ArrayList<String> lines = extras.getStringArrayList(LogRelay.KEY_LINES);
            String proc = extras.getString(LogRelay.KEY_PROC, "?");
            if (lines == null || lines.isEmpty()) {
                r.putBoolean("ok", true);
                return r;
            }
            appendLines(lines, proc);
            sRecvOk++;
            sLastCallerUid = uid;
            sLastCallerPkg = pkgOf(uid);
            sLastProc = proc;
            sLastOkAt = now();
            r.putBoolean("ok", true);
            r.putInt("n", lines.size());
        } catch (Throwable t) {
            sLastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            r.putBoolean("ok", false);
            r.putString("reason", sLastError);
        }
        return r;
    }

    private static ArrayList<String> singleton(String s) {
        ArrayList<String> a = new ArrayList<>(1);
        a.add(s);
        return a;
    }

    private void appendLines(ArrayList<String> lines, String proc) throws Exception {
        int total = 0;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (l == null) continue;
            if (l.length() > 8000) l = l.substring(0, 8000) + "...\n";
            total += l.length();
            if (total > 400_000) break;
            sb.append(l);
            if (!l.endsWith("\n")) sb.append('\n');
        }
        File f = hookFile(getContext());
        synchronized (FILE_LOCK) {
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            if (f.length() > MAX_BYTES) trimTail(f, MAX_BYTES / 2);
        }
    }

    static void trimTail(File file, long keep) {
        try {
            long len = file.length();
            if (len <= keep) return;
            byte[] buf = new byte[(int) keep];
            int n;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(len - keep);
                n = raf.read(buf);
            }
            if (n <= 0) return;
            int start = 0;
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    start = i + 1;
                    break;
                }
            }
            try (FileOutputStream fos = new FileOutputStream(file, false)) {
                fos.write("--- truncated ---\n".getBytes(StandardCharsets.UTF_8));
                if (start < n) fos.write(buf, start, n - start);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
