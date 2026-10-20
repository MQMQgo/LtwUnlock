package io.github.mqmqgo.ltwunlock;

import android.content.ClipboardManager;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Force Link to Windows organic clipboard path on non-OEM devices (Meizu).
 * Capability flags for CLIPBOARD (and all features) are handled by FeatureUnlockHooks.
 */
final class ClipboardHooks {
    private static final String TAG = "LtwUnlock";
    private static final String BASE_BROKER =
            "com.microsoft.deviceExperiences.clipboard.BaseClipboardManagerBroker";
    private static final String EXTGENERIC_BROKER =
            "com.microsoft.appmanager.extgeneric.clipboard.ExtGenericClipboardManagerBroker";
    private static final String CLIPBOARD_CAP_PROVIDER =
            "com.microsoft.appmanager.clipboard.ClipboardCapabilityProvider";

    void install(ClassLoader loader, HookBridge.Installer installer) {
        hookIsClipListenerSupported(installer);
        hookPrimaryClipListeners(installer);
        hookClipboardCapabilityProvider(installer);
        ModuleLog.i(TAG, "ClipboardHooks install complete");
    }

    private void hookIsClipListenerSupported(HookBridge.Installer installer) {
        installer.hook(BASE_BROKER, "isClipListenerSupported",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "isClipListenerSupported → true (Base)");
                        return Boolean.TRUE;
                    }
                });

        installer.hook(EXTGENERIC_BROKER, "isClipListenerSupported",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "isClipListenerSupported → true (ExtGeneric)");
                        return Boolean.TRUE;
                    }
                });
    }

    private void hookPrimaryClipListeners(HookBridge.Installer installer) {
        final Class<?> listenerClass = ClipboardManager.OnPrimaryClipChangedListener.class;

        HookBridge.Hooker addHook = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) throws Throwable {
                Object thiz = chain.getThisObject();
                ClipboardManager.OnPrimaryClipChangedListener listener = argListener(chain);
                if (listener == null) {
                    return null;
                }
                ClipboardManager cm = clipboardOf(thiz);
                if (cm == null) {
                    ModuleLog.w(TAG, "addPrimaryClipListener: no ClipboardManager, proceeding");
                    return chain.proceed();
                }
                cm.addPrimaryClipChangedListener(listener);
                ModuleLog.i(TAG, "addPrimaryClipListener → ClipboardManager registered ("
                        + thiz.getClass().getSimpleName() + ")");
                return null;
            }
        };

        HookBridge.Hooker removeHook = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) throws Throwable {
                Object thiz = chain.getThisObject();
                ClipboardManager.OnPrimaryClipChangedListener listener = argListener(chain);
                if (listener == null) {
                    return null;
                }
                ClipboardManager cm = clipboardOf(thiz);
                if (cm != null) {
                    try {
                        cm.removePrimaryClipChangedListener(listener);
                        ModuleLog.i(TAG, "removePrimaryClipListener → ClipboardManager unregistered ("
                                + thiz.getClass().getSimpleName() + ")");
                    } catch (Throwable t) {
                        ModuleLog.w(TAG, "removePrimaryClipListener failed", t);
                    }
                }
                try {
                    return chain.proceed();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        };

        installer.hook(BASE_BROKER, "addPrimaryClipListener", addHook, listenerClass);
        installer.hook(BASE_BROKER, "removePrimaryClipListener", removeHook, listenerClass);
        installer.hook(EXTGENERIC_BROKER, "addPrimaryClipListener", addHook, listenerClass);
        installer.hook(EXTGENERIC_BROKER, "removePrimaryClipListener", removeHook, listenerClass);
    }

    private void hookClipboardCapabilityProvider(HookBridge.Installer installer) {
        installer.hookByName(CLIPBOARD_CAP_PROVIDER, "isFeatureCapableOnCrossDevice", 2,
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "ClipboardCapabilityProvider.isFeatureCapableOnCrossDevice → true");
                        return Boolean.TRUE;
                    }
                });
        installer.hookByName(CLIPBOARD_CAP_PROVIDER, "isFeatureEnabledOnCrossDevice", 1,
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "ClipboardCapabilityProvider.isFeatureEnabledOnCrossDevice(id) → true");
                        return Boolean.TRUE;
                    }
                });
        installer.hookByName(CLIPBOARD_CAP_PROVIDER, "isFeatureEnabledOnCrossDevice", 0,
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "ClipboardCapabilityProvider.isFeatureEnabledOnCrossDevice() → true");
                        return Boolean.TRUE;
                    }
                });
    }

    private static ClipboardManager.OnPrimaryClipChangedListener argListener(
            XposedInterface.Chain chain) {
        try {
            Object a = chain.getArg(0);
            if (a instanceof ClipboardManager.OnPrimaryClipChangedListener) {
                return (ClipboardManager.OnPrimaryClipChangedListener) a;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static ClipboardManager clipboardOf(Object broker) {
        Context ctx = contextOf(broker);
        if (ctx == null) {
            return null;
        }
        try {
            Object svc = ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            return svc instanceof ClipboardManager ? (ClipboardManager) svc : null;
        } catch (Throwable t) {
            ModuleLog.w(TAG, "getSystemService(clipboard) failed", t);
            return null;
        }
    }

    private static Context contextOf(Object broker) {
        if (broker == null) {
            return null;
        }
        Class<?> c = broker.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("context");
                f.setAccessible(true);
                Object v = f.get(broker);
                if (v instanceof Context) {
                    return (Context) v;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                ModuleLog.w(TAG, "read context field failed", t);
            }
            try {
                Method g = c.getDeclaredMethod("getContext");
                g.setAccessible(true);
                Object v = g.invoke(broker);
                if (v instanceof Context) {
                    return (Context) v;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                ModuleLog.w(TAG, "getContext() failed", t);
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
