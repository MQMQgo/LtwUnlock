package io.github.mqmqgo.ltwunlock;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * system_server hooks (v1.4, perf cleanups v1.5): allow ONLY {@code com.microsoft.appmanager}
 * (by package arg OR by uid ownership) to pass ClipboardService focus /
 * background checks. Never relaxes checks for any other package/uid.
 * <p>
 * Logging: logcat {@code LtwUnlock-SS} AND a status file {@link #SS_LOG}
 * readable by LogActivity (direct or via {@code su -c cat}). All file writes
 * are wrapped in try/catch so a logging failure never breaks a hook.
 */
final class SystemServerClipboardHooks {
    static final String TAG = "LtwUnlock-SS";
    static final String SS_LOG = "/data/local/tmp/ltwunlock-ss.log";
    static final String SS_LOG_ALT = "/data/system/ltwunlock-ss.log";
    private static final String TARGET_PKG = "com.microsoft.appmanager";
    private static final String[] CLIPBOARD_CANDIDATES = {
            "com.android.server.clipboard.ClipboardService",
            "com.android.server.clipboard.FlymeClipboardService",
            "com.android.server.clipboard.MzClipboardService",
            "com.android.server.clipboard.ClipboardServiceExt",
            "com.android.server.ClipboardService",
    };
    /** Boolean focus / background / access helpers worth LTW-only allowing. */
    private static final Pattern HELPER_NAMES = Pattern.compile(
            "(?i).*(focus|background|accessAllowed|clipboardAccess"
                    + "|canRead|isAllowed|allowRead|checkRead).*");

    private static final Set<Executable> HOOKED = new HashSet<>();
    private static final Map<Integer, Boolean> UID_CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_ALLOW_LOG = new AtomicLong();
    private static final AtomicLong ALLOW_COUNT = new AtomicLong();
    private static final ThreadLocal<Boolean> IN_RESOLVE = new ThreadLocal<>();

    private static volatile XposedInterface sXp;
    private static volatile Context sSysCtx;

    private SystemServerClipboardHooks() {}

    /** @return total number of methods hooked so far */
    static synchronized int install(XposedInterface xp, ClassLoader loader, String via) {
        sXp = xp;
        SsLog.i("===== install callback=" + via + " pid=" + Process.myPid() + " uid="
                + Process.myUid() + " uptime=" + SystemClock.uptimeMillis()
                + "ms boot=" + new Date(System.currentTimeMillis()
                - SystemClock.elapsedRealtime()) + " alreadyHooked=" + HOOKED.size());
        int hooked = 0;
        List<Class<?>> classes = resolveClipboardClasses(loader);
        if (classes.isEmpty()) {
            SsLog.e("ClipboardService class NOT FOUND (callback=" + via
                    + ", tried " + Arrays.toString(CLIPBOARD_CANDIDATES) + ")", null);
        } else {
            for (Class<?> c : classes) {
                SsLog.i("class loaded: " + c.getName());
                hooked += hookClass(xp, c);
            }
        }
        SsLog.i("install callback=" + via + " newlyHooked=" + hooked
                + " totalHooked=" + HOOKED.size()
                + (HOOKED.isEmpty() ? "  !!! hooked=0 (check 系统框架 scope + reboot)"
                        : "  hooked>0 OK"));
        return HOOKED.size();
    }

    private static List<Class<?>> resolveClipboardClasses(ClassLoader loader) {
        List<Class<?>> out = new ArrayList<>();
        ClassLoader[] loaders = {loader, Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()};
        Set<String> seen = new HashSet<>();
        for (String name : CLIPBOARD_CANDIDATES) {
            for (ClassLoader cl : loaders) {
                if (cl == null) {
                    continue;
                }
                try {
                    Class<?> c = Class.forName(name, false, cl);
                    if (c != null && seen.add(c.getName())) {
                        out.add(c);
                    }
                    break;
                } catch (Throwable ignored) {
                    // try next loader / candidate
                }
            }
        }
        return out;
    }

    /** Hook every boolean focus/access helper (incl. inner classes). */
    private static int hookClass(XposedInterface xp, Class<?> clazz) {
        int n = 0;
        List<Class<?>> targets = new ArrayList<>();
        targets.add(clazz);
        try {
            for (Class<?> inner : clazz.getDeclaredClasses()) {
                targets.add(inner);
            }
        } catch (Throwable ignored) {
        }
        for (Class<?> t : targets) {
            Method[] methods;
            try {
                methods = t.getDeclaredMethods();
            } catch (Throwable e) {
                SsLog.e("getDeclaredMethods failed on " + t.getName(), e);
                continue;
            }
            for (Method m : methods) {
                try {
                    if (m.getName().equals("getPrimaryClip")
                            && m.getReturnType() == android.content.ClipData.class
                            && !HOOKED.contains(m)) {
                        xp.hook(m).intercept(new GrantHooker());
                        HOOKED.add(m);
                        n++;
                        SsLog.i("hooked getPrimaryClip (uri-grant fallback): " + m);
                    }
                } catch (Throwable e) {
                    SsLog.e("hook getPrimaryClip failed: " + m, e);
                }
                // Screenshot / notify diagnostics: setPrimaryClip* and listener dispatch
                try {
                    String mn = m.getName();
                    boolean setClip = mn.startsWith("setPrimaryClip")
                            || mn.equals("setPrimaryClipInternal")
                            || mn.equals("setPrimaryClipAsPackage")
                            || mn.equals("setClipboard")
                            || mn.contains("SetPrimaryClip");
                    boolean notify = mn.equalsIgnoreCase("dispatchPrimaryClipChanged")
                            || mn.equalsIgnoreCase("notifyPrimaryClipChanged")
                            || mn.equalsIgnoreCase("sendClipChangedBroadcast")
                            || mn.equalsIgnoreCase("scheduleClipChangedNotification")
                            || (mn.toLowerCase(Locale.US).contains("clipchanged")
                                && !mn.toLowerCase(Locale.US).contains("listener"));
                    if ((setClip || notify) && !HOOKED.contains(m)) {
                        xp.hook(m).intercept(new SetClipDiagHooker(setClip));
                        HOOKED.add(m);
                        n++;
                        SsLog.i("hooked diag: " + signature(m));
                    }
                } catch (Throwable e) {
                    SsLog.e("hook set/notify diag failed: " + m, e);
                }
                if (m.getReturnType() != boolean.class) {
                    continue;
                }
                String name = m.getName();
                boolean isAccess = name.equalsIgnoreCase("clipboardAccessAllowed");
                if (!isAccess && !HELPER_NAMES.matcher(name).matches()) {
                    continue;
                }
                if (HOOKED.contains(m)) {
                    continue;
                }
                try {
                    xp.hook(m).intercept(new AllowHooker());
                    HOOKED.add(m);
                    n++;
                    SsLog.i("hooked method: " + signature(m));
                } catch (Throwable e) {
                    SsLog.e("hook failed: " + signature(m), e);
                }
            }
        }
        return n;
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getSimpleName()).append('#').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ps[i].getSimpleName());
        }
        sb.append(")->boolean");
        return sb.toString();
    }

    /** Interceptor: only flips a false result to true for LTW; never relaxes others. */
    private static final class AllowHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            boolean original = (result instanceof Boolean) && (Boolean) result;
            Executable exec = chain.getExecutable();
            String mname = exec != null ? exec.getName() : "?";
            List<Object> args = null;
            try {
                args = chain.getArgs();
            } catch (Throwable ignored) {
            }
            boolean ltw = isLtwCaller(args, mname);
            if (original) {
                if (ltw) maybeLogAccess(mname, args, true, true);
                return result;
            }
            if (ltw) {
                maybeLogAccess(mname, args, false, true);
                return Boolean.TRUE;
            }
            return result;
        }
    }

    private static final AtomicLong LAST_ACCESS_LOG = new AtomicLong();

    private static void maybeLogAccess(String mname, List<Object> args, boolean original, boolean allowed) {
        long now = SystemClock.elapsedRealtime();
        long prev = LAST_ACCESS_LOG.get();
        if (now - prev < 500L && !LAST_ACCESS_LOG.compareAndSet(prev, now)) return;
        LAST_ACCESS_LOG.set(now);
        StringBuilder sb = new StringBuilder();
        sb.append("ACCESS ").append(mname).append(" ltw orig=").append(original)
                .append(" -> allow=").append(allowed).append(" args=");
        if (args != null) {
            for (Object a : args) {
                if (a instanceof String) sb.append('[').append(a).append(']');
                else if (a instanceof Integer) sb.append('{').append(a).append('}');
            }
        }
        sb.append(" callingUid=").append(Binder.getCallingUid());
        SsLog.i(sb.toString());
    }

    /** Log setPrimaryClip / notify paths — proves whether Flyme screenshot touches clipboard. */
    private static final class SetClipDiagHooker implements XposedInterface.Hooker {
        private final boolean setClip;
        private static final AtomicLong LAST = new AtomicLong();

        SetClipDiagHooker(boolean setClip) { this.setClip = setClip; }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Executable exec = chain.getExecutable();
            String mname = exec != null ? exec.getName() : "?";
            int cuid = Binder.getCallingUid();
            String pkgs = pkgsOf(cuid);
            String mime = "?";
            String sens = "?";
            try {
                for (Object a : chain.getArgs()) {
                    if (a instanceof ClipData) {
                        ClipData cd = (ClipData) a;
                        ClipDescription d = cd.getDescription();
                        if (d != null) {
                            StringBuilder mb = new StringBuilder();
                            for (int i = 0; i < d.getMimeTypeCount(); i++) {
                                if (i > 0) mb.append(',');
                                mb.append(d.getMimeType(i));
                            }
                            mime = mb.toString();
                            try {
                                android.os.PersistableBundle ex = d.getExtras();
                                if (ex != null) {
                                    sens = String.valueOf(ex.getBoolean(
                                            "android.content.extra.IS_SENSITIVE", false));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        // first uri authority
                        try {
                            if (cd.getItemCount() > 0 && cd.getItemAt(0).getUri() != null) {
                                mime = mime + " uriAuth=" + cd.getItemAt(0).getUri().getAuthority();
                            }
                        } catch (Throwable ignored) {
                        }
                        break;
                    }
                    if (a instanceof ClipDescription) {
                        ClipDescription d = (ClipDescription) a;
                        StringBuilder mb = new StringBuilder();
                        for (int i = 0; i < d.getMimeTypeCount(); i++) {
                            if (i > 0) mb.append(',');
                            mb.append(d.getMimeType(i));
                        }
                        mime = mb.toString();
                    }
                }
            } catch (Throwable ignored) {
            }
            long now = SystemClock.elapsedRealtime();
            // Always log image-ish or unknown; rate-limit text spam
            boolean interesting = setClip || (mime != null && mime.contains("image"));
            if (interesting || now - LAST.get() > 1_000L) {
                LAST.set(now);
                String msg = (setClip ? "SETCLIP " : "NOTIFY ")
                        + mname + " callingUid=" + cuid + " pkg=" + pkgs
                        + " mimes=" + mime + " sens=" + sens;
                SsLog.i(msg);
            }
            Object result = chain.proceed();
            if (setClip && interesting) {
                SsLog.i("SETCLIP done " + mname + " result=" + result);
            }
            return result;
        }
    }

    private static String pkgsOf(int uid) {
        try {
            Context ctx = systemContext();
            if (ctx == null) return "?";
            String[] pk = ctx.getPackageManager().getPackagesForUid(uid);
            if (pk == null || pk.length == 0) return "?";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pk.length && i < 3; i++) {
                if (i > 0) sb.append(',');
                sb.append(pk[i]);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static final Map<String, Long> GRANTED = new ConcurrentHashMap<>();

    /** v1.5.3-test: after getPrimaryClip for LTW, grant read URI permission on item URIs. */
    private static final class GrantHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object result = chain.proceed();
            try {
                if (result instanceof android.content.ClipData) {
                    android.content.ClipData cd = (android.content.ClipData) result;
                    boolean hasUri = false;
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        if (cd.getItemAt(i).getUri() != null) hasUri = true;
                    }
                    if (hasUri) {
                        List<Object> args = null;
                        try {
                            args = chain.getArgs();
                        } catch (Throwable ignored) {
                        }
                        int callingUid = Binder.getCallingUid();
                        if (isLtwCaller(args, "getPrimaryClip")) {
                            grantForLtw(cd, callingUid);
                        }
                    }
                }
            } catch (Throwable t) {
                SsLog.e("GRANT hook error (ignored)", t);
            }
            return result;
        }
    }

    private static void grantForLtw(android.content.ClipData cd, int ltwUid) {
        int targetUser = ltwUid / 100000;
        long ident = Binder.clearCallingIdentity();
        try {
            Context ctx = systemContext();
            Class<?> ls = Class.forName("com.android.server.LocalServices");
            Class<?> ugmiC = Class.forName("com.android.server.uri.UriGrantsManagerInternal");
            Object ugmi = ls.getMethod("getService", Class.class).invoke(null, ugmiC);
            if (ugmi == null) {
                SsLog.e("GRANT: UriGrantsManagerInternal unavailable", null);
                return;
            }
            Method check = null, grant = null;
            for (Method m : ugmiC.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("checkGrantUriPermissionFromIntent") && p.length == 4
                        && p[0] == android.content.Intent.class && p[1] == int.class
                        && p[2] == String.class && p[3] == int.class) {
                    check = m;
                } else if (m.getName().equals("grantUriPermissionUncheckedFromIntent")
                        && p.length == 2) {
                    grant = m;
                }
            }
            if (check == null || grant == null) {
                SsLog.e("GRANT: UGMI methods not found check=" + check + " grant=" + grant, null);
                return;
            }
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri uri = cd.getItemAt(i).getUri();
                if (uri == null || !"content".equals(uri.getScheme())) continue;
                String key = uri.toString();
                Long last = GRANTED.get(key);
                long now = SystemClock.elapsedRealtime();
                if (last != null && now - last < 5_000L) continue;
                GRANTED.put(key, now);
                if (GRANTED.size() > 64) GRANTED.clear();
                String auth = uri.getAuthority();
                if (auth != null && auth.contains("@")) auth = auth.substring(auth.indexOf('@') + 1);
                int srcUid = -1;
                try {
                    android.content.pm.ProviderInfo pi = ctx == null ? null
                            : ctx.getPackageManager().resolveContentProvider(auth, 0);
                    if (pi != null && pi.applicationInfo != null) srcUid = pi.applicationInfo.uid;
                } catch (Throwable t) {
                    SsLog.e("GRANT: resolveContentProvider failed auth=" + auth, t);
                }
                if (srcUid < 0) {
                    SsLog.i("GRANT skip: provider not resolved authority=" + auth);
                    continue;
                }
                try {
                    android.content.Intent in = new android.content.Intent();
                    in.setClipData(android.content.ClipData.newRawUri("ltw", uri));
                    in.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Object needed = check.invoke(ugmi, in, srcUid, TARGET_PKG, targetUser);
                    if (needed == null) {
                        SsLog.i("GRANT: not needed / not allowed authority=" + auth + " srcUid=" + srcUid);
                        continue;
                    }
                    grant.invoke(ugmi, needed, null);
                    SsLog.i("GRANT OK read uri authority=" + auth + " srcUid=" + srcUid
                            + " -> " + TARGET_PKG + " user=" + targetUser);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    SsLog.e("GRANT failed authority=" + auth + " srcUid=" + srcUid, ite.getCause());
                } catch (Throwable t) {
                    SsLog.e("GRANT failed authority=" + auth + " srcUid=" + srcUid, t);
                }
            }
        } catch (Throwable t) {
            SsLog.e("GRANT: reflective setup failed", t);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * True iff the call is on behalf of com.microsoft.appmanager, decided by:
     * (1) any String arg equal to the target package; else
     * (2) any int uid arg (or Binder.getCallingUid()) owned by the package.
     */
    private static boolean isLtwCaller(List<Object> args, String mname) {
        String pkgArg = null;
        Integer uidArg = null;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof String && pkgArg == null) {
                    pkgArg = (String) a;
                } else if (a instanceof Integer && uidArg == null) {
                    int v = (Integer) a;
                    if (v >= Process.FIRST_APPLICATION_UID) {
                        uidArg = v;
                    }
                }
            }
        }
        // (1) explicit package argument
        if (TARGET_PKG.equals(pkgArg)) {
            logAllow(mname, "package arg=" + pkgArg);
            return true;
        }
        // (2) package arg missing or not LTW -> fall back to uid ownership
        //     (still LTW-only: uid must be owned by TARGET_PKG)
        int uid = uidArg != null ? uidArg : Binder.getCallingUid();
        if (uidBelongsToTarget(uid)) {
            logAllow(mname, "uid=" + uid + " (owner=" + TARGET_PKG + ")");
            return true;
        }
        return false;
    }

    /** v1.5: ALLOW logging rate-limited to once per 60 s (first allow always logged). */
    private static void logAllow(String mname, String by) {
        long c = ALLOW_COUNT.incrementAndGet();
        long now = SystemClock.elapsedRealtime();
        long last = LAST_ALLOW_LOG.get();
        if ((c == 1 || now - last >= 60_000L) && LAST_ALLOW_LOG.compareAndSet(last, now)) {
            SsLog.i("ALLOW " + mname + " by " + by + " (total allows=" + c + ", rate-limited 60s)");
        }
    }

    private static boolean uidBelongsToTarget(int uid) {
        if (uid <= 0) {
            return false;
        }
        Boolean cached = UID_CACHE.get(uid);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(IN_RESOLVE.get())) {
            return false;
        }
        boolean belongs = false;
        IN_RESOLVE.set(Boolean.TRUE);
        try {
            Context ctx = systemContext();
            if (ctx != null) {
                PackageManager pm = ctx.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs != null) {
                    for (String p : pkgs) {
                        if (TARGET_PKG.equals(p)) {
                            belongs = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            SsLog.e("uid resolve failed uid=" + uid, e);
        } finally {
            IN_RESOLVE.remove();
        }
        UID_CACHE.put(uid, belongs);
        return belongs;
    }

    private static Context systemContext() {
        if (sSysCtx != null) {
            return sSysCtx;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object ctx = at.getMethod("getSystemContext").invoke(thread);
                if (ctx instanceof Context) {
                    sSysCtx = (Context) ctx;
                }
            }
        } catch (Throwable e) {
            SsLog.e("systemContext failed", e);
        }
        return sSysCtx;
    }

    /**
     * Status logger for system_server: logcat {@code LtwUnlock-SS} plus a
     * world-readable status file {@link #SS_LOG} (LogActivity reads it, direct
     * or via su). Every write guarded so logging can never crash a hook.
     */
    static final class SsLog {
        private static final SimpleDateFormat FMT =
                new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

        private SsLog() {}

        static void i(String msg) {
            write("I", msg, null);
        }

        static void e(String msg, Throwable t) {
            write("E", msg, t);
        }

        private static void write(String level, String msg, Throwable t) {
            // Routed via ModuleLog: logcat + LSPosed log + LogRelay → module LogActivity.
            ModuleLog.log("E".equals(level) ? Log.ERROR : Log.INFO, ModuleLog.TAG_SS, msg, t);
        }

        private static boolean append(String path, String line) {
            try {
                File f = new File(path);
                boolean created = !f.exists();
                try (OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(line);
                    w.flush();
                }
                if (created) {
                    try {
                        f.setReadable(true, false);
                        f.setWritable(true, false);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
