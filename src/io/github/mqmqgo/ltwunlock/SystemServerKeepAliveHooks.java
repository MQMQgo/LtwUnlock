package io.github.mqmqgo.ltwunlock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * v1.5.0 system_server keep-alive hooks, LTW (com.microsoft.appmanager) ONLY:
 * <ul>
 *   <li>CachedAppOptimizer freeze entry points: skip when target ProcessRecord is LTW;
 *       if it is already frozen try the matching unfreeze method.</li>
 *   <li>Process.setProcessFrozen(pid, uid, true): skip for LTW uid (catch-all).</li>
 *   <li>AppStandbyController.setAppStandbyBucket: clamp LTW bucket to ACTIVE.</li>
 *   <li>Scan Meizu/Flyme freeze/power classes; log, hook only clear signatures.</li>
 * </ul>
 * Every other app/uid is passed through untouched.
 */
final class SystemServerKeepAliveHooks {
    private static final String TARGET_PKG = "com.microsoft.appmanager";
    private static final String CAO = "com.android.server.am.CachedAppOptimizer";
    private static final String ASC = "com.android.server.usage.AppStandbyController";
    private static final Pattern FREEZE_NAME = Pattern.compile(
            "(?i)^(force)?freeze(App|Process)[A-Za-z]*$");
    private static final Pattern VENDOR_CLS = Pattern.compile("(?i).*(meizu|flyme).*");
    private static final Pattern VENDOR_KIND = Pattern.compile("(?i).*(freez|power|hibernat).*");
    private static final Pattern VENDOR_FREEZE_M = Pattern.compile("(?i)^(?!.*unfreez).*(freez|hibernat).*");
    private static final int BUCKET_ACTIVE = 10;
    private static final int BUCKET_WORKING_SET = 20;
    private static final long SKIP_LOG_INTERVAL_MS = 60_000L;

    private static final Set<Executable> HOOKED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong LAST_SKIP_LOG = new AtomicLong();
    private static final AtomicLong SKIP_COUNT = new AtomicLong();
    private static final AtomicLong LAST_BUCKET_LOG = new AtomicLong();
    /** appId (uid % 100000) of LTW; -1 = unknown yet. */
    private static volatile int sLtwAppId = -1;
    private static final ThreadLocal<Boolean> IN_UNFREEZE = new ThreadLocal<>();

    private SystemServerKeepAliveHooks() {}

    private static void log(String m) {
        SystemServerClipboardHooks.SsLog.i("[KeepAlive] " + m);
    }

    private static void err(String m, Throwable t) {
        SystemServerClipboardHooks.SsLog.e("[KeepAlive] " + m, t);
    }

    static synchronized int install(XposedInterface xp, ClassLoader loader, String via) {
        log("===== install via=" + via + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " alreadyHooked=" + HOOKED.size());
        int n = 0;
        try { n += hookCachedAppOptimizer(xp, loader); } catch (Throwable t) { err("CAO install failed", t); }
        try { n += hookSetProcessFrozen(xp); } catch (Throwable t) { err("setProcessFrozen install failed", t); }
        try { n += hookStandby(xp, loader); } catch (Throwable t) { err("standby install failed", t); }
        try { n += scanVendor(xp, loader); } catch (Throwable t) { err("vendor scan failed", t); }
        log("install via=" + via + " newlyHooked=" + n + " totalHooked=" + HOOKED.size()
                + (HOOKED.isEmpty() ? "  !!! keepalive hooked=0" : "  OK"));
        return HOOKED.size();
    }

    // ---------------------------------------------------------------- CachedAppOptimizer
    private static int hookCachedAppOptimizer(XposedInterface xp, ClassLoader loader) {
        Class<?> cao;
        try {
            cao = Class.forName(CAO, false, loader);
        } catch (Throwable t) {
            log("CachedAppOptimizer NOT FOUND: " + t);
            return 0;
        }
        List<Class<?>> targets = new ArrayList<>();
        targets.add(cao);
        try { for (Class<?> c : cao.getDeclaredClasses()) targets.add(c); } catch (Throwable ignored) {}
        StringBuilder all = new StringBuilder();
        int n = 0;
        for (Class<?> c : targets) {
            Method[] ms;
            try { ms = c.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (Method m : ms) {
                String name = m.getName();
                if (name.toLowerCase(Locale.US).contains("freez")) {
                    all.append(sig(m)).append("; ");
                }
                if (!FREEZE_NAME.matcher(name).matches() || procIndex(m) < 0) continue;
                if (hook(xp, m, new FreezeHooker())) n++;
            }
        }
        log("CAO freeze-related methods present: " + all);
        return n;
    }

    private static int procIndex(Method m) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i].getName().equals("com.android.server.am.ProcessRecord")) return i;
        }
        return -1;
    }

    private static final class FreezeHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Method m = (Method) chain.getExecutable();
            Object proc = null;
            try {
                int idx = procIndex(m);
                if (idx >= 0) proc = chain.getArg(idx);
            } catch (Throwable ignored) {}
            if (proc == null || !isLtwProc(proc)) {
                return chain.proceed();
            }
            noteSkip(m.getName(), procDesc(proc));
            tryUnfreeze(chain.getThisObject(), proc);
            return defaultReturn(m.getReturnType());
        }
    }

    /** If LTW proc already frozen, call CachedAppOptimizer.unfreezeAppLSP / unfreezeAppInternalLSP. */
    private static void tryUnfreeze(Object self, Object proc) {
        if (Boolean.TRUE.equals(IN_UNFREEZE.get())) return;
        try {
            if (!isFrozen(proc)) return;
            Object cao = findCao(self);
            if (cao == null) return;
            IN_UNFREEZE.set(Boolean.TRUE);
            for (String name : new String[]{"unfreezeAppLSP", "unfreezeAppInternalLSP"}) {
                for (Method m : cao.getClass().getDeclaredMethods()) {
                    if (!m.getName().equals(name) || procIndex(m) != 0) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    Object[] args = new Object[ps.length];
                    args[0] = proc;
                    boolean ok = true;
                    for (int i = 1; i < ps.length; i++) {
                        if (ps[i] == int.class) args[i] = 0;
                        else if (ps[i] == boolean.class) args[i] = Boolean.TRUE; // force
                        else if (ps[i] == long.class) args[i] = 0L;
                        else { ok = false; break; }
                    }
                    if (!ok) continue;
                    m.setAccessible(true);
                    m.invoke(cao, args);
                    log("unfroze already-frozen LTW proc via " + sig(m) + " " + procDesc(proc));
                    return;
                }
            }
            log("LTW proc frozen but no usable unfreeze method " + procDesc(proc));
        } catch (Throwable t) {
            err("unfreeze failed " + procDesc(proc), t);
        } finally {
            IN_UNFREEZE.remove();
        }
    }

    private static Object findCao(Object self) {
        if (self == null) return null;
        if (self.getClass().getName().equals(CAO)) return self;
        for (Field f : self.getClass().getDeclaredFields()) {
            if (f.getType().getName().equals(CAO)) {
                try { f.setAccessible(true); return f.get(self); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean isFrozen(Object proc) {
        try {
            Object opt = readField(proc, "mOptRecord");
            if (opt != null) {
                Method m = opt.getClass().getDeclaredMethod("isFrozen");
                m.setAccessible(true);
                return Boolean.TRUE.equals(m.invoke(opt));
            }
            Object fz = readField(proc, "frozen"); // Android 11/12 early
            return Boolean.TRUE.equals(fz);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Process.setProcessFrozen
    private static int hookSetProcessFrozen(XposedInterface xp) {
        try {
            Method m = Process.class.getDeclaredMethod("setProcessFrozen",
                    int.class, int.class, boolean.class);
            return hook(xp, m, chain -> {
                Object uid = chain.getArg(1);
                Object frz = chain.getArg(2);
                if (Boolean.TRUE.equals(frz) && uid instanceof Integer && isLtwUid((Integer) uid)) {
                    noteSkip("Process.setProcessFrozen", "pid=" + chain.getArg(0) + " uid=" + uid);
                    return null;
                }
                return chain.proceed();
            }) ? 1 : 0;
        } catch (Throwable t) {
            log("Process.setProcessFrozen(int,int,boolean) not found: " + t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- App standby
    private static int hookStandby(XposedInterface xp, ClassLoader loader) {
        Class<?> c;
        try {
            c = Class.forName(ASC, false, loader);
        } catch (Throwable t) {
            log("AppStandbyController NOT FOUND: " + t);
            return 0;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals("setAppStandbyBucket")) continue;
            Class<?>[] p = m.getParameterTypes();
            // AOSP 12-15 internal: (String pkg, int userId, int newBucket, int reason, long elapsed, boolean resetTimeout)
            if (p.length == 6 && p[0] == String.class && p[1] == int.class && p[2] == int.class
                    && p[3] == int.class && p[4] == long.class && p[5] == boolean.class) {
                if (hook(xp, m, chain -> {
                    Object pkg = chain.getArg(0);
                    Object b = chain.getArg(2);
                    if (TARGET_PKG.equals(pkg) && b instanceof Integer
                            && (Integer) b > BUCKET_WORKING_SET) {
                        Object[] a = chain.getArgs().toArray();
                        a[2] = BUCKET_ACTIVE;
                        long now = System.currentTimeMillis();
                        long last = LAST_BUCKET_LOG.get();
                        if (now - last >= SKIP_LOG_INTERVAL_MS && LAST_BUCKET_LOG.compareAndSet(last, now)) {
                            log("standby bucket " + b + " -> ACTIVE for " + TARGET_PKG
                                    + " user=" + a[1] + " (rate-limited 60s)");
                        }
                        return chain.proceedWith(chain.getThisObject(), a);
                    }
                    return chain.proceed();
                })) n++;
            } else {
                log("standby: not hooking (signature unclear) " + sig(m));
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- Meizu / Flyme scan
    private static int scanVendor(XposedInterface xp, ClassLoader loader) {
        List<String> names = new ArrayList<>();
        ClassLoader[] cls = {loader, SystemServerKeepAliveHooks.class.getClassLoader().getParent()};
        for (ClassLoader cl : cls) collectDexNames(cl, names);
        int found = 0, n = 0;
        for (String name : names) {
            if (!VENDOR_CLS.matcher(name).matches() || !VENDOR_KIND.matcher(name).matches()) continue;
            Class<?> c;
            try { c = Class.forName(name, false, loader); } catch (Throwable t) { continue; }
            found++;
            StringBuilder sb = new StringBuilder();
            Method[] ms;
            try { ms = c.getDeclaredMethods(); } catch (Throwable t) { log("vendor class " + name + " (methods unreadable)"); continue; }
            for (Method m : ms) {
                if (!VENDOR_FREEZE_M.matcher(m.getName()).matches()) continue;
                sb.append(sig(m));
                if (clearVendorSignature(m)) {
                    if (hook(xp, m, new VendorHooker())) { n++; sb.append("[HOOKED]"); }
                }
                sb.append("; ");
            }
            log("vendor class found: " + name + " freezeMethods={" + sb + "}");
        }
        log("vendor scan: dexEntries=" + names.size() + " matchedClasses=" + found + " hooked=" + n);
        return n;
    }

    /** Only (uid|pkg [,boolean]) style, void/boolean return, abstract excluded. */
    private static boolean clearVendorSignature(Method m) {
        if (Modifier.isAbstract(m.getModifiers())) return false;
        Class<?> r = m.getReturnType();
        if (r != void.class && r != boolean.class) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 1 || p.length > 3) return false;
        boolean hasId = false;
        int bools = 0;
        for (Class<?> t : p) {
            if (t == int.class || t == String.class) hasId = true;
            else if (t == boolean.class) bools++;
            else return false;
        }
        return hasId && bools <= 1;
    }

    private static final class VendorHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Method m = (Method) chain.getExecutable();
            boolean ltw = false;
            Boolean flag = null;
            for (Object a : chain.getArgs()) {
                if (a instanceof String && TARGET_PKG.equals(a)) ltw = true;
                else if (a instanceof Integer && (Integer) a >= Process.FIRST_APPLICATION_UID
                        && isLtwUid((Integer) a)) ltw = true;
                else if (a instanceof Boolean) flag = (Boolean) a;
            }
            // boolean param false == "unfreeze" -> let it through
            if (!ltw || Boolean.FALSE.equals(flag)) return chain.proceed();
            noteSkip(m.getDeclaringClass().getSimpleName() + "#" + m.getName(), chain.getArgs().toString());
            return defaultReturn(m.getReturnType());
        }
    }

    @SuppressWarnings("deprecation")
    private static void collectDexNames(ClassLoader cl, List<String> out) {
        if (cl == null) return;
        try {
            Field pl = findField(cl.getClass(), "pathList");
            if (pl == null) return;
            Object pathList = pl.get(cl);
            Field de = findField(pathList.getClass(), "dexElements");
            Object[] elems = (Object[]) de.get(pathList);
            for (Object e : elems) {
                Field df = findField(e.getClass(), "dexFile");
                Object dex = df != null ? df.get(e) : null;
                if (!(dex instanceof dalvik.system.DexFile)) continue;
                Enumeration<String> en = ((dalvik.system.DexFile) dex).entries();
                while (en.hasMoreElements()) out.add(en.nextElement());
            }
        } catch (Throwable t) {
            err("dex enumerate failed on " + cl.getClass().getName(), t);
        }
    }

    // ---------------------------------------------------------------- helpers
    private static boolean hook(XposedInterface xp, Method m, XposedInterface.Hooker h) {
        if (HOOKED.contains(m)) return false;
        try {
            xp.hook(m).intercept(h);
            HOOKED.add(m);
            log("hooked " + sig(m));
            return true;
        } catch (Throwable t) {
            err("hook failed " + sig(m), t);
            return false;
        }
    }

    private static void noteSkip(String where, String what) {
        long c = SKIP_COUNT.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = LAST_SKIP_LOG.get();
        if (now - last >= SKIP_LOG_INTERVAL_MS && LAST_SKIP_LOG.compareAndSet(last, now)) {
            log("SKIP freeze for " + TARGET_PKG + " via " + where + " " + what
                    + " (total skips=" + c + ", rate-limited 60s)");
        }
    }

    static boolean isLtwProc(Object proc) {
        try {
            Object info = readField(proc, "info");
            if (info instanceof ApplicationInfo && TARGET_PKG.equals(((ApplicationInfo) info).packageName)) {
                int uid = ((ApplicationInfo) info).uid;
                if (uid > 0 && sLtwAppId < 0) sLtwAppId = uid % 100000;
                return true;
            }
            Object uid = readField(proc, "uid");
            if (uid instanceof Integer && isLtwUid((Integer) uid)) return true;
            Object pn = readField(proc, "processName");
            return pn instanceof String && (pn.equals(TARGET_PKG) || ((String) pn).startsWith(TARGET_PKG + ":"));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isLtwUid(int uid) {
        if (uid < Process.FIRST_APPLICATION_UID) return false;
        int appId = resolveLtwAppId();
        return appId > 0 && uid % 100000 == appId;
    }

    private static int resolveLtwAppId() {
        int a = sLtwAppId;
        if (a > 0) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            Context ctx = th == null ? null : (Context) at.getMethod("getSystemContext").invoke(th);
            if (ctx != null) {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(TARGET_PKG, 0);
                if (ai != null && ai.uid > 0) {
                    sLtwAppId = ai.uid % 100000;
                    log("resolved LTW uid=" + ai.uid + " appId=" + sLtwAppId);
                }
            }
        } catch (Throwable ignored) {
            // PM not ready / not installed; retry next time
        }
        return sLtwAppId;
    }

    private static Object readField(Object o, String name) throws IllegalAccessException {
        Field f = findField(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    private static final ConcurrentHashMap<String, Object> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    private static Field findField(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        Object cached = FIELD_CACHE.get(key);
        if (cached != null) return cached == NONE ? null : (Field) cached;
        Field found = null;
        for (Class<?> k = c; k != null && found == null; k = k.getSuperclass()) {
            try { found = k.getDeclaredField(name); found.setAccessible(true); } catch (Throwable ignored) {}
        }
        FIELD_CACHE.put(key, found == null ? NONE : found);
        return found;
    }

    private static Object defaultReturn(Class<?> r) {
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        return null;
    }

    private static String procDesc(Object proc) {
        try {
            return "proc=" + readField(proc, "processName") + " pid=" + readField(proc, "mPid")
                    + " uid=" + readField(proc, "uid");
        } catch (Throwable t) {
            return String.valueOf(proc);
        }
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getSimpleName()).append('#').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(")->").append(m.getReturnType().getSimpleName()).toString();
    }
}
