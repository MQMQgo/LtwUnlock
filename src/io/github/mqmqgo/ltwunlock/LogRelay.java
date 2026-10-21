package io.github.mqmqgo.ltwunlock;

import android.content.Context;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Hook-process side: bounded ring → batch ContentResolver.call to module LogProvider.
 * Never throws. Uses createPackageContext for system_server reliability.
 */
final class LogRelay {
    static final String AUTHORITY = "io.github.mqmqgo.ltwunlock.logs";
    static final String MODULE_PKG = "io.github.mqmqgo.ltwunlock";
    static final String METHOD_APPEND = "append";
    static final String KEY_LINES = "lines";
    static final String KEY_PROC = "proc";

    private static final int RING_MAX = 400;
    private static final int BATCH_MAX = 200;
    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static int sDropped;
    private static Thread sThread;
    private static volatile String sLastSendErr;
    private static volatile long sOkCount;
    private static volatile long sFailCount;

    private LogRelay() {}

    static void ensureStarted() {
        synchronized (RING) {
            if (sThread == null) {
                sThread = new Thread(new Runnable() {
                    @Override public void run() { loop(); }
                }, "LtwUnlock-LogRelay");
                sThread.setDaemon(true);
                sThread.start();
                RING.addLast(ModuleLog.formatLine("I", ModuleLog.TAG,
                        "LogRelay thread started pid=" + Process.myPid()
                                + " uid=" + Process.myUid()
                                + " ss=" + ModuleLog.isSystemServer(), null));
                RING.notifyAll();
            }
        }
    }

    static void enqueue(String line) {
        try {
            synchronized (RING) {
                if (RING.size() >= RING_MAX) {
                    RING.pollFirst();
                    sDropped++;
                }
                RING.addLast(line);
                if (sThread == null) ensureStarted();
                else RING.notifyAll();
            }
        } catch (Throwable ignored) {
        }
    }

    static String statusLine() {
        return "relay ok=" + sOkCount + " fail=" + sFailCount
                + " lastErr=" + (sLastSendErr == null ? "-" : sLastSendErr);
    }

    private static void loop() {
        long backoff = 2_000L;
        boolean reportedFail = false;
        while (true) {
            try {
                ArrayList<String> batch = new ArrayList<>();
                int dropped;
                synchronized (RING) {
                    while (RING.isEmpty()) RING.wait();
                }
                Thread.sleep(1_500L);
                if (ModuleLog.isSystemServer() && !bootCompleted()) {
                    Thread.sleep(5_000L);
                    continue;
                }
                synchronized (RING) {
                    while (!RING.isEmpty() && batch.size() < BATCH_MAX) batch.add(RING.pollFirst());
                    dropped = sDropped;
                    sDropped = 0;
                }
                if (dropped > 0) batch.add(0, "(relay ring overflow: dropped " + dropped + " lines)\n");
                if (send(batch)) {
                    backoff = 2_000L;
                    reportedFail = false;
                    sOkCount++;
                } else {
                    synchronized (RING) {
                        for (int i = batch.size() - 1; i >= 0; i--) {
                            if (RING.size() >= RING_MAX) break;
                            RING.addFirst(batch.get(i));
                        }
                    }
                    sFailCount++;
                    if (!reportedFail) {
                        reportedFail = true;
                        try {
                            Log.w(ModuleLog.isSystemServer() ? ModuleLog.TAG_SS : ModuleLog.TAG,
                                    "LogRelay: provider unavailable (" + sLastSendErr + "), will retry");
                        } catch (Throwable ignored) {
                        }
                    }
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 60_000L);
                }
            } catch (Throwable t) {
                try {
                    Thread.sleep(10_000L);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean bootCompleted() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, "sys.boot_completed");
            return "1".equals(v);
        } catch (Throwable t) {
            return SystemClock.elapsedRealtime() > 120_000L;
        }
    }

    private static Context context() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object sys = at.getMethod("getSystemContext").invoke(thread);
                if (sys instanceof Context) return (Context) sys;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Prefer a Context bound to our module package / user 0 (system_server-safe). */
    private static Context resolverContext(Context base) {
        if (base == null) return null;
        try {
            if (MODULE_PKG.equals(base.getPackageName())) return base;
        } catch (Throwable ignored) {
        }
        // createPackageContextAsUser(user 0) when available
        try {
            java.lang.reflect.Method m = Context.class.getMethod(
                    "createPackageContextAsUser", String.class, int.class, UserHandle.class);
            Object user = UserHandle.class.getField("SYSTEM").get(null);
            Object c = m.invoke(base, MODULE_PKG, 0, user);
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        try {
            Context c = base.createPackageContext(MODULE_PKG, Context.CONTEXT_IGNORE_SECURITY);
            if (c != null) return c;
        } catch (Throwable ignored) {
        }
        return base;
    }

    private static boolean send(ArrayList<String> batch) {
        if (batch.isEmpty()) return true;
        Context base = context();
        if (base == null) {
            sLastSendErr = "no Context (ActivityThread)";
            return false;
        }
        Context ctx = resolverContext(base);
        long id = Binder.clearCallingIdentity();
        try {
            Bundle b = new Bundle();
            b.putStringArrayList(KEY_LINES, batch);
            b.putString(KEY_PROC, ModuleLog.isSystemServer() ? "system_server"
                    : ("app pid=" + Process.myPid() + " uid=" + Process.myUid()));
            ContentResolver cr = ctx.getContentResolver();
            Bundle r = null;
            Throwable err = null;
            try {
                // API 29+ authority form avoids some Uri visibility filters
                r = cr.call(AUTHORITY, METHOD_APPEND, null, b);
            } catch (Throwable t) {
                err = t;
                try {
                    r = cr.call(Uri.parse("content://" + AUTHORITY), METHOD_APPEND, null, b);
                    err = null;
                } catch (Throwable t2) {
                    err = t2;
                }
            }
            if (r != null && r.getBoolean("ok", false)) {
                sLastSendErr = null;
                return true;
            }
            sLastSendErr = err != null ? err.getClass().getSimpleName() + ": " + err.getMessage()
                    : ("null/ok=false from pkgCtx=" + ctx.getPackageName());
            return false;
        } catch (Throwable t) {
            sLastSendErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }
}
