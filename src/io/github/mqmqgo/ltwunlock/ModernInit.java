package io.github.mqmqgo.ltwunlock;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModernInit extends XposedModule {
    private static final String TAG = "LtwUnlock";
    private static final String TARGET = "com.microsoft.appmanager";
    private static final String ANDROID = "android";
    private static final String SELF = "io.github.mqmqgo.ltwunlock";

    private static volatile boolean sSsInstalled;
    private static volatile boolean sKeepAliveInstalled;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            ModuleLog.setSystemServer(true);
            ModuleLog.i(TAG, "onModuleLoaded(system_server)");
            log(Log.INFO, TAG, "onModuleLoaded(system_server)");
        }
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param) {
        ModuleLog.setSystemServer(true);
        installSystemServer("onSystemServerStarting", param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        if (ANDROID.equals(param.getPackageName())) {
            ModuleLog.setSystemServer(true);
            installSystemServer("onPackageLoaded(android)", param.getDefaultClassLoader());
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getClassLoader();

        // Backup if a framework build skips onSystemServerStarting.
        if (ANDROID.equals(pkg)) {
            ModuleLog.setSystemServer(true);
            installSystemServer("onPackageReady(android)", loader);
            return;
        }

        if (SELF.equals(pkg)) {
            installSelfCheck(loader);
            return;
        }

        if (!TARGET.equals(pkg)) {
            return;
        }
        ModuleLog.setSystemServer(false);
        HookBridge.Installer installer = HookBridge.installer(this, loader);
        ModuleLog.i(TAG, "ModernInit onPackageReady for " + pkg);
        try {
            new ClipboardHooks().install(loader, installer);
            ModuleLog.i(TAG, "ClipboardHooks installed in " + pkg);
            log(Log.INFO, TAG, "ClipboardHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ClipboardHooks failed", t);
            log(Log.ERROR, TAG, "ClipboardHooks failed", t);
        }
        try {
            new FeatureUnlockHooks().install(loader, installer);
            ModuleLog.i(TAG, "FeatureUnlockHooks installed in " + pkg);
            log(Log.INFO, TAG, "FeatureUnlockHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "FeatureUnlockHooks failed", t);
            log(Log.ERROR, TAG, "FeatureUnlockHooks failed", t);
        }
    }

    private void installSelfCheck(ClassLoader loader) {
        HookBridge.Installer installer = HookBridge.installer(this, loader);
        try {
            installer.hook(ModuleStatus.class.getName(), "isActivated",
                    chain -> Boolean.TRUE);
            ModuleLog.i(TAG, "Self-check hook installed in " + SELF);
            log(Log.INFO, TAG, "Self-check hook installed in " + SELF);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "Self-check hook failed", t);
            log(Log.ERROR, TAG, "Self-check hook failed", t);
        }
    }

    private void installSystemServer(String via, ClassLoader loader) {
        if (sSsInstalled && sKeepAliveInstalled) {
            ModuleLog.i(TAG, "SS hooks already installed; skip via=" + via);
            log(Log.INFO, TAG, "SS hooks already installed; skip via=" + via);
            return;
        }
        if (!sSsInstalled) try {
            int n = SystemServerClipboardHooks.install(this, loader, via);
            sSsInstalled = n > 0;
            String msg = "SS ClipboardService hooks via=" + via + " count=" + n
                    + " installed=" + sSsInstalled;
            ModuleLog.i(TAG, msg);
            log(Log.INFO, TAG, msg);
            SystemServerClipboardHooks.SsLog.i(msg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "SS install failed via=" + via, t);
            log(Log.ERROR, TAG, "SS install failed via=" + via, t);
            SystemServerClipboardHooks.SsLog.e("install failed via=" + via, t);
        }
        if (sKeepAliveInstalled) {
            return;
        }
        try {
            int k = SystemServerKeepAliveHooks.install(this, loader, via);
            sKeepAliveInstalled = k > 0;
            String msg = "SS KeepAlive hooks via=" + via + " count=" + k
                    + " installed=" + sKeepAliveInstalled;
            ModuleLog.i(TAG, msg);
            log(Log.INFO, TAG, msg);
            SystemServerClipboardHooks.SsLog.i(msg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "SS install failed via=" + via, t);
            log(Log.ERROR, TAG, "KeepAlive install failed via=" + via, t);
            SystemServerClipboardHooks.SsLog.e("KeepAlive install failed via=" + via, t);
        }
    }
}
