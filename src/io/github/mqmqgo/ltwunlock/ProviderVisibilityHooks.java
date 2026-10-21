package io.github.mqmqgo.ltwunlock;

import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * LTW process: if package visibility hides our LogProvider authority, force
 * resolveContentProvider to return a valid ProviderInfo so ContentResolver.call works.
 */
final class ProviderVisibilityHooks {
    private static final String SELF = "io.github.mqmqgo.ltwunlock";
    private static volatile boolean sInstalled;

    private ProviderVisibilityHooks() {}

    static void install(XposedInterface xp, ClassLoader loader) {
        if (sInstalled) return;
        int n = 0;
        n += hookResolve(xp, "android.app.ApplicationPackageManager", loader);
        n += hookResolve(xp, "android.app.ContextImpl$ApplicationPackageManager", loader);
        // IPackageManager stub proxies sometimes used directly
        try {
            Class<?> pm = Class.forName("android.content.pm.IPackageManager$Stub$Proxy", false, loader);
            for (Method m : pm.getDeclaredMethods()) {
                if (!"resolveContentProvider".equals(m.getName())) continue;
                try {
                    xp.hook(m).intercept(new ResolveHooker(xp));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        sInstalled = n > 0;
        ModuleLog.i(ModuleLog.TAG, "ProviderVisibilityHooks count=" + n
                + " (force-resolve " + LogRelay.AUTHORITY + ")");
    }

    private static int hookResolve(XposedInterface xp, String cn, ClassLoader loader) {
        int n = 0;
        try {
            Class<?> c = Class.forName(cn, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!"resolveContentProvider".equals(m.getName())) continue;
                try {
                    xp.hook(m).intercept(new ResolveHooker(xp));
                    n++;
                } catch (Throwable t) {
                    ModuleLog.w(ModuleLog.TAG, "ProviderVisibility hook failed " + m, t);
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static final class ResolveHooker implements XposedInterface.Hooker {
        private final XposedInterface xp;

        ResolveHooker(XposedInterface xp) { this.xp = xp; }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object r = chain.proceed();
            if (r != null) return r;
            try {
                Object a0 = chain.getArg(0);
                if (!(a0 instanceof String)) return null;
                if (!LogRelay.AUTHORITY.equals(a0)) return null;
                ProviderInfo pi = buildInfo(xp);
                if (pi != null) {
                    ModuleLog.i(ModuleLog.TAG, "ProviderVisibility: injected ProviderInfo for "
                            + LogRelay.AUTHORITY);
                }
                return pi;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static ProviderInfo buildInfo(XposedInterface xp) {
        try {
            ApplicationInfo ai = xp.getModuleApplicationInfo();
            ProviderInfo pi = new ProviderInfo();
            pi.authority = LogRelay.AUTHORITY;
            pi.packageName = SELF;
            pi.name = LogProvider.class.getName();
            pi.exported = true;
            pi.applicationInfo = ai;
            if (ai != null) {
                pi.processName = ai.processName != null ? ai.processName : SELF;
            }
            return pi;
        } catch (Throwable t) {
            ModuleLog.w(ModuleLog.TAG, "build ProviderInfo failed", t);
            return null;
        }
    }
}
