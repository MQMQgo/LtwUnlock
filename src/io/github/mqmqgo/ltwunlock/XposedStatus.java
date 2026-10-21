package io.github.mqmqgo.ltwunlock;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Activation status via libxposed service (same approach as QAuxiliary HookStatus):
 * LSPosed delivers an IXposedService binder through XposedProvider when the module is enabled.
 */
final class XposedStatus {
    interface Listener {
        void onChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService sService;
    private static boolean sRegistered;

    private XposedStatus() {
    }

    static synchronized void init() {
        if (sRegistered) return;
        sRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                sService = service;
                notifyChanged();
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService == service) sService = null;
                notifyChanged();
            }
        });
    }

    static XposedService service() {
        return sService;
    }

    static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void notifyChanged() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged();
        });
    }
}
