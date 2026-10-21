package io.github.mqmqgo.ltwunlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * system_server: let com.microsoft.appmanager see our package (Android 11+ package
 * visibility), so the in-LTW hook can reach {@link LogProvider} via ContentResolver#call.
 */
final class PackageVisibilityHooks {
    private static final String SELF = "io.github.mqmqgo.ltwunlock";
    private static final String LTW = "com.microsoft.appmanager";
    private static final String[] CLASSES = {
            "com.android.server.pm.AppsFilterBase",
            "com.android.server.pm.AppsFilterImpl",
            "com.android.server.pm.AppsFilter",
    };
    private static final String[] METHOD_NAMES = {
            "shouldFilterApplication",
            "shouldFilterApplicationIncludingUninstalled",
            "shouldFilterApplicationInternal",
    };
    private static final ConcurrentHashMap<Class<?>, Object> NAME_ACCESS = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();
    private static volatile boolean sInstalled;
    private static volatile boolean sLoggedHit;

    private PackageVisibilityHooks() {}

    static synchronized void install(XposedInterface xp, ClassLoader cl) {
        if (sInstalled) return;
        int n = 0;
        for (String cn : CLASSES) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                String mn = m.getName();
                boolean match = false;
                for (String want : METHOD_NAMES) {
                    if (want.equals(mn)) { match = true; break; }
                }
                if (!match || m.getReturnType() != boolean.class
                        || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    // Anonymous Hooker — avoid method-ref / indy issues on some OEM ART
                    xp.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return PackageVisibilityHooks.intercept(chain);
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    ModuleLog.w(ModuleLog.TAG, "visibility hook failed " + m, t);
                }
            }
        }
        // Also filterAppAccess(Computer, int callingUid, String targetPackage, int userId) style
        for (String cn : new String[]{
                "com.android.server.pm.ComputerEngine",
                "com.android.server.pm.PackageManagerService",
        }) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!"filterAppAccess".equals(m.getName()) || m.getReturnType() != boolean.class)
                    continue;
                try {
                    xp.hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (!Boolean.TRUE.equals(r)) return r;
                            try {
                                for (Object a : chain.getArgs()) {
                                    if (SELF.equals(a)) {
                                        if (!sLoggedHit) {
                                            sLoggedHit = true;
                                            ModuleLog.i(ModuleLog.TAG,
                                                    "[SS] filterAppAccess allow → " + SELF);
                                        }
                                        return Boolean.FALSE;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return r;
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        sInstalled = n > 0;
        ModuleLog.i(ModuleLog.TAG, "[SS] PackageVisibility hooks count=" + n
                + " (LTW → module LogProvider)");
    }

    private static Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object r = chain.proceed();
        if (!Boolean.TRUE.equals(r)) return r;
        try {
            List<Object> args = chain.getArgs();
            boolean targetSelf = false;
            boolean callerLtw = false;
            for (Object a : args) {
                if (a == null) continue;
                if (a instanceof String) {
                    if (SELF.equals(a)) targetSelf = true;
                    else if (LTW.equals(a)) callerLtw = true;
                    continue;
                }
                String name = nameOf(a);
                if (SELF.equals(name)) targetSelf = true;
                else if (LTW.equals(name)) callerLtw = true;
            }
            if (targetSelf && callerLtw) {
                if (!sLoggedHit) {
                    sLoggedHit = true;
                    ModuleLog.i(ModuleLog.TAG, "[SS] PackageVisibility: allow LTW → " + SELF);
                }
                return Boolean.FALSE;
            }
            // Also: if target is self and any arg looks like LTW uid owned — handled loosely:
            if (targetSelf) {
                // callingSetting may be null for some queries; still unfilter when target is us
                // and calling uid belongs to LTW (int args).
                for (Object a : args) {
                    if (!(a instanceof Integer)) continue;
                    int uid = (Integer) a;
                    if (uid <= 0) continue;
                    // Can't resolve PM here cheaply; rely on name match above primarily.
                }
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    private static String nameOf(Object o) {
        Class<?> c = o.getClass();
        Object acc = NAME_ACCESS.get(c);
        if (acc == null) {
            acc = NONE;
            try {
                Method m = findMethod(c, "getPackageName");
                if (m != null) acc = m;
                else {
                    Field f = findField(c, "name");
                    if (f != null && f.getType() == String.class) acc = f;
                    else {
                        Field f2 = findField(c, "mName");
                        if (f2 != null && f2.getType() == String.class) acc = f2;
                    }
                }
            } catch (Throwable ignored) {
            }
            NAME_ACCESS.put(c, acc);
        }
        try {
            if (acc instanceof Method) return (String) ((Method) acc).invoke(o);
            if (acc instanceof Field) return (String) ((Field) acc).get(o);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String n) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(n);
                if (m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> c, String n) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
