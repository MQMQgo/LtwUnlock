package io.github.mqmqgo.ltwunlock;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.libxposed.api.XposedInterface;

/** Adapter matching flyme_purify Installer shape on modern libxposed. */
final class HookBridge {
    private static final String TAG = "LtwUnlock";

    interface Hooker {
        Object invoke(XposedInterface.Chain chain) throws Throwable;
    }

    interface Installer {
        void hook(String className, String methodName, Hooker hooker, Class<?>... parameters);

        /** Hook first declared method with this name and matching arity (soft-fail). */
        void hookByName(String className, String methodName, int arity, Hooker hooker);
    }

    static Installer installer(final XposedInterface xp, final ClassLoader loader) {
        return new Installer() {
            @Override
            public void hook(String className, String methodName, final Hooker hooker, Class<?>... parameters) {
                try {
                    Method target = Class.forName(className, false, loader)
                            .getDeclaredMethod(methodName, parameters);
                    install(target, hooker);
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "Cannot hook " + className + "#" + methodName
                            + (parameters == null ? "" : Arrays.toString(parameters)), t);
                }
            }

            @Override
            public void hookByName(String className, String methodName, int arity, final Hooker hooker) {
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    Method found = null;
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(methodName) && m.getParameterTypes().length == arity) {
                            found = m;
                            break;
                        }
                    }
                    if (found == null) {
                        throw new NoSuchMethodException(className + "#" + methodName + "/" + arity);
                    }
                    install(found, hooker);
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "Cannot hookByName " + className + "#" + methodName + "/" + arity, t);
                }
            }

            private void install(Method target, final Hooker hooker) {
                xp.hook(target).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return hooker.invoke(chain);
                    }
                });
                ModuleLog.i(TAG, "Hooked " + target.getDeclaringClass().getName() + "#" + target.getName());
            }
        };
    }

    private HookBridge() {}
}
