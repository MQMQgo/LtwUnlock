package io.github.mqmqgo.ltwunlock;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Broad Phone Link / LTW feature unlock beyond clipboard.
 * Forces FeatureCapabilityProvider flags, ExtGeneric Compatibility keys,
 * notification experiment gate, and organic hotspot "supported" advertise.
 *
 * Does NOT stub OEM AIDL / media projection (would crash LTW).
 */
final class FeatureUnlockHooks {
    private static final String TAG = "LtwUnlock";

    /**
     * Spoofing DeviceBrand to HONOR/SAMSUNG can load OEM DI paths that call
     * missing ContentProviders and crash. Keep false unless debugging UI only.
     */
    private static final boolean SPOOF_BRAND_TO_HONOR = false;

    private static final String BASE_CAPABILITY =
            "com.microsoft.appmanager.capabilities.BaseCapabilityProvider";
    private static final String FEATURE_CAPABILITY =
            "com.microsoft.appmanager.capabilities.FeatureCapabilityProvider";
    private static final String COMPATIBILITY =
            "com.microsoft.appmanager.extgeneric.compatibility.Compatibility";
    private static final String NOTIF_GATE =
            "com.microsoft.appmanager.notification.DefaultGetNotificationsCapabilityProvider";
    private static final String BASE_HOTSPOT =
            "com.microsoft.deviceExperiences.BaseHotspotApi";
    private static final String EXT_HOTSPOT =
            "com.microsoft.deviceExperiences.ExtHotspotApi";
    private static final String EXTGENERIC_HOTSPOT =
            "com.microsoft.deviceExperiences.ExtGenericHotspotApi";
    private static final String DEVICE_INFO =
            "com.microsoft.appmanager.utils.DeviceInfoProvider";
    private static final String DEVICE_BRAND =
            "com.microsoft.appmanager.device.DeviceBrand";

    /** ExtGeneric Compatibility.isFeatureSupported app-level keys. */
    static final Set<String> EXTGENERIC_FEATURES = new HashSet<>(Arrays.asList(
            "copyAndPaste",
            "dragAndDrop",
            "inputInjector",
            "blackScreen",
            "permissionCache",
            "appRemote",
            "audioStream",
            "oemRfcomm",
            "proximity",
            "thermalManager"
    ));

    /**
     * Known FeatureCapabilityProvider / YPP advertise keys.
     * Returning true for unknown names is also OK; whitelist reduces surprise.
     */
    static final Set<String> KNOWN_FEATURE_KEYS = new HashSet<>(Arrays.asList(
            "CLIPBOARD",
            "FILE_TRANSFER",
            "SEND_MESSAGE",
            "REMOTE_CONTROL",
            "REMOTE_CONTROL_V2",
            "SEMANTIC_SEARCH_IMAGE",
            "SEARCH_CONTACTS",
            "GET_NOTIFICATIONS",
            "NOTIFICATIONS",
            "HOTSPOT",
            "INSTANT_HOTSPOT",
            "DEVICE_STATUS",
            "RINGER_MODE",
            "CONTINUITY",
            "MESSAGING",
            "PHOTOS",
            "MIRROR",
            "PHONE_APPS",
            "CONTACTS",
            "CALL_LOGS",
            "FILES",
            "RECENT_APPS",
            "CAMERA_STREAM",
            "WALLPAPER"
    ));

    void install(ClassLoader loader, HookBridge.Installer installer) {
        hookBaseCapabilityGeneric(installer);
        hookFeatureCapabilityProvider(installer);
        hookCompatibility(loader, installer);
        hookNotificationGate(installer);
        hookHotspotSupported(installer);
        if (SPOOF_BRAND_TO_HONOR) {
            hookBrandSpoof(loader, installer);
        } else {
            ModuleLog.i(TAG, "Brand spoof disabled (SPOOF_BRAND_TO_HONOR=false)");
        }
        ModuleLog.i(TAG, "FeatureUnlockHooks install complete");
    }

    /** Prefer generic true for all BaseCapabilityProvider feature checks. */
    private void hookBaseCapabilityGeneric(HookBridge.Installer installer) {
        installer.hook(BASE_CAPABILITY, "isFeatureCapableOnCrossDevice",
                alwaysTrue("isFeatureCapableOnCrossDevice"),
                String.class, boolean.class);
        installer.hook(BASE_CAPABILITY, "isFeatureCapable",
                alwaysTrue("isFeatureCapable"),
                String.class, boolean.class);
        installer.hook(BASE_CAPABILITY, "isFeatureEnabledOnCrossDevice",
                alwaysTrue("isFeatureEnabledOnCrossDevice(String)"),
                String.class);
        installer.hook(BASE_CAPABILITY, "isFeatureEnabledOnCrossDevice",
                alwaysTrue("isFeatureEnabledOnCrossDevice()"));
    }

    private void hookFeatureCapabilityProvider(HookBridge.Installer installer) {
        installer.hook(FEATURE_CAPABILITY, "isFeatureCapableFromLocal",
                knownFeatureTrueHook("isFeatureCapableFromLocal"),
                String.class, String.class);
        installer.hook(FEATURE_CAPABILITY, "isFeatureCapableFromServer",
                knownFeatureTrueHook("isFeatureCapableFromServer"),
                String.class, String.class);
        installer.hook(FEATURE_CAPABILITY, "isFeatureEnabled",
                knownFeatureTrueHook("isFeatureEnabled"),
                String.class, String.class);
        installer.hook(FEATURE_CAPABILITY, "isFeatureEnabled",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) throws Throwable {
                        List<Object> args = chain.getArgs();
                        String name = args != null && args.size() == 1
                                ? String.valueOf(args.get(0)) : null;
                        if (isKnownFeature(name)) {
                            ModuleLog.d(TAG, "FeatureCapabilityProvider.isFeatureEnabled("
                                    + name + ") → true");
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    }
                }, String.class);
    }

    private void hookCompatibility(ClassLoader loader, HookBridge.Installer installer) {
        Class<?> iLogger;
        try {
            iLogger = Class.forName("com.microsoft.appmanager.telemetry.ILogger", false, loader);
        } catch (Throwable t) {
            ModuleLog.w(TAG, "ILogger not found; skip Compatibility hooks", t);
            return;
        }

        HookBridge.Hooker compatHook = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) throws Throwable {
                List<Object> args = chain.getArgs();
                String feature = args != null && args.size() > 1
                        ? String.valueOf(args.get(1)) : null;
                if (feature != null && EXTGENERIC_FEATURES.contains(feature)) {
                    ModuleLog.d(TAG, "Compatibility.isFeatureSupported(" + feature + ") → true");
                    return Boolean.TRUE;
                }
                return chain.proceed();
            }
        };

        installer.hook(COMPATIBILITY, "isFeatureSupported",
                compatHook, Context.class, String.class, iLogger);

        // isFeatureSupportedSafe(Context, String, boolean, boolean, ILogger)
        installer.hook(COMPATIBILITY, "isFeatureSupportedSafe",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) throws Throwable {
                        List<Object> args = chain.getArgs();
                        String feature = args != null && args.size() > 1
                                ? String.valueOf(args.get(1)) : null;
                        if (feature != null && EXTGENERIC_FEATURES.contains(feature)) {
                            ModuleLog.d(TAG, "Compatibility.isFeatureSupportedSafe("
                                    + feature + ") → true");
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    }
                }, Context.class, String.class, boolean.class, boolean.class, iLogger);

        installer.hook(COMPATIBILITY, "isFeatureSupportedSafe",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) throws Throwable {
                        List<Object> args = chain.getArgs();
                        String feature = args != null && args.size() > 1
                                ? String.valueOf(args.get(1)) : null;
                        if (feature != null && EXTGENERIC_FEATURES.contains(feature)) {
                            ModuleLog.d(TAG, "Compatibility.isFeatureSupportedSafe3("
                                    + feature + ") → true");
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    }
                }, Context.class, String.class, boolean.class, iLogger);
    }

    private void hookNotificationGate(HookBridge.Installer installer) {
        installer.hook(NOTIF_GATE, "isGetNotificationsOnCrossDeviceExpOn",
                new HookBridge.Hooker() {
                    @Override
                    public Object invoke(XposedInterface.Chain chain) {
                        ModuleLog.d(TAG, "isGetNotificationsOnCrossDeviceExpOn → true");
                        return Boolean.TRUE;
                    }
                });
    }

    /**
     * Advertise hotspot support so Phone Link UI / negotiation can show the tile.
     * Does not implement InstantHotspot OEM — start will still fail without KSU/OEM.
     */
    private void hookHotspotSupported(HookBridge.Installer installer) {
        HookBridge.Hooker trueHotspot = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) {
                ModuleLog.d(TAG, chain.getThisObject().getClass().getSimpleName()
                        + ".isHotspotSupported → true (advertise only)");
                return Boolean.TRUE;
            }
        };
        installer.hook(BASE_HOTSPOT, "isHotspotSupported", trueHotspot);
        installer.hook(EXT_HOTSPOT, "isHotspotSupported", trueHotspot);
        installer.hook(EXTGENERIC_HOTSPOT, "isHotspotSupported", trueHotspot);
    }

    private void hookBrandSpoof(ClassLoader loader, HookBridge.Installer installer) {
        // RISK: may bind Honor ExtGeneric / partner modules that expect OEM CP.
        try {
            Class<?> brandClass = Class.forName(DEVICE_BRAND, false, loader);
            Object honor = null;
            for (Object c : brandClass.getEnumConstants()) {
                if ("HONOR".equals(String.valueOf(c))) {
                    honor = c;
                    break;
                }
            }
            if (honor == null) {
                ModuleLog.w(TAG, "DeviceBrand.HONOR not found");
                return;
            }
            final Object honorBrand = honor;
            installer.hook(DEVICE_INFO, "getBrand",
                    new HookBridge.Hooker() {
                        @Override
                        public Object invoke(XposedInterface.Chain chain) {
                            ModuleLog.d(TAG, "DeviceInfoProvider.getBrand → HONOR (spoof)");
                            return honorBrand;
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.w(TAG, "Brand spoof hook failed", t);
        }
    }

    private static HookBridge.Hooker knownFeatureTrueHook(final String label) {
        return new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) throws Throwable {
                List<Object> args = chain.getArgs();
                String featureName = null;
                if (args != null && args.size() >= 2) {
                    featureName = String.valueOf(args.get(args.size() - 1));
                }
                if (isKnownFeature(featureName)) {
                    ModuleLog.d(TAG, "FeatureCapabilityProvider." + label + "("
                            + featureName + ") → true");
                    return Boolean.TRUE;
                }
                // Also force unknown ALL_CAPS-looking keys (future features)
                if (featureName != null && featureName.equals(featureName.toUpperCase())
                        && featureName.length() >= 3) {
                    ModuleLog.d(TAG, "FeatureCapabilityProvider." + label + "("
                            + featureName + ") → true (ALL_CAPS)");
                    return Boolean.TRUE;
                }
                return chain.proceed();
            }
        };
    }

    static boolean isKnownFeature(String name) {
        return name != null && KNOWN_FEATURE_KEYS.contains(name);
    }

    private static HookBridge.Hooker alwaysTrue(final String label) {
        return new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain chain) {
                ModuleLog.d(TAG, "BaseCapabilityProvider." + label + " → true (generic)");
                return Boolean.TRUE;
            }
        };
    }
}
