package io.github.mqmqgo.ltwunlock;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModernInit extends XposedModule {
    private static final String TAG = "LtwUnlock";
    private static final String TARGET = "com.microsoft.appmanager";
    private static final String ANDROID = "android";

    private static volatile boolean sSsInstalled;
    private static volatile boolean sKeepAliveInstalled;
    /** True only in the real system_server process (set by onModuleLoaded/onSystemServerStarting). */
    private static volatile boolean sInSystemServer;

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        ModuleLog.attach(this, param.isSystemServer());
        if (param.isSystemServer()) {
            sInSystemServer = true;
            ModuleLog.setSystemServer(true);
            ModuleLog.i(TAG, "onModuleLoaded(system_server)");
        } else {
            ModuleLog.i(TAG, "onModuleLoaded(app) pkgHint ss=" + param.isSystemServer());
        }
    }

    @Override
    public void onSystemServerStarting(
            @NonNull XposedModuleInterface.SystemServerStartingParam param) {
        sInSystemServer = true;
        ModuleLog.setSystemServer(true);
        installSystemServer("onSystemServerStarting", param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        if (ANDROID.equals(param.getPackageName())) {
            if (!sInSystemServer) return; // other uid-1000 process (e.g. settings/:ui) — stay quiet
            installSystemServer("onPackageLoaded(android)", param.getDefaultClassLoader());
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getClassLoader();

        if (ANDROID.equals(pkg)) {
            if (!sInSystemServer) return; // not system_server — skip SS hooks quietly
            installSystemServer("onPackageReady(android)", loader);
            return;
        }

        if (!TARGET.equals(pkg)) {
            return;
        }
        ModuleLog.setSystemServer(false);
        HookBridge.Installer installer = HookBridge.installer(this, loader);
        ModuleLog.i(TAG, "ModernInit onPackageReady for " + pkg);

        // Before any LogRelay send: ensure LTW can resolve our LogProvider authority
        try {
            ProviderVisibilityHooks.install(this, loader);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ProviderVisibilityHooks failed: " + t);
            ModuleLog.e(TAG, "ProviderVisibilityHooks failed", t);
        }

        try {
            new ClipboardHooks().install(loader, installer);
            ModuleLog.i(TAG, "ClipboardHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ClipboardHooks failed: " + t);
            ModuleLog.e(TAG, "ClipboardHooks failed", t);
        }
        try {
            new ImageClipboardHooks().install(loader, installer);
            ModuleLog.i(TAG, "ImageClipboardHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ImageClipboardHooks failed: " + t);
            ModuleLog.e(TAG, "ImageClipboardHooks failed", t);
        }
        try {
            ClipboardRequestDedupeHooks.install(installer);
            ModuleLog.i(TAG, "ClipboardRequestDedupeHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "ClipboardRequestDedupeHooks failed", t);
        }
        try {
            new FeatureUnlockHooks().install(loader, installer);
            ModuleLog.i(TAG, "FeatureUnlockHooks installed in " + pkg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "FeatureUnlockHooks failed: " + t);
            ModuleLog.e(TAG, "FeatureUnlockHooks failed", t);
        }
    }

    private void installSystemServer(String via, ClassLoader loader) {
        if (sSsInstalled && sKeepAliveInstalled) {
            ModuleLog.i(TAG, "SS hooks already installed; skip via=" + via);
            return;
        }
        try {
            PackageVisibilityHooks.install(this, loader);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "PackageVisibilityHooks failed: " + t);
            ModuleLog.e(TAG, "PackageVisibilityHooks failed", t);
        }
        if (!sSsInstalled) try {
            int n = SystemServerClipboardHooks.install(this, loader, via);
            sSsInstalled = n > 0;
            String msg = "SS ClipboardService hooks via=" + via + " count=" + n
                    + " installed=" + sSsInstalled;
            ModuleLog.i(TAG, msg);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "SS install failed via=" + via + ": " + t);
            ModuleLog.e(TAG, "SS install failed via=" + via, t);
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
        } catch (Throwable t) {
            ModuleLog.e(TAG, "SS KeepAlive failed via=" + via + ": " + t);
            ModuleLog.e(TAG, "SS install failed via=" + via, t);
        }
    }
}
