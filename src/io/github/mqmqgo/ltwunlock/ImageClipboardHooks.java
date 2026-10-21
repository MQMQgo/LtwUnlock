package io.github.mqmqgo.ltwunlock;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.SystemClock;

import java.io.InputStream;
import java.lang.reflect.Constructor;

import io.github.libxposed.api.XposedInterface;

/**
 * Image clipboard sync on Meizu + diagnostics (LTW process).
 * <ul>
 *   <li>Force image/* mime support.</li>
 *   <li>Dedupe onPrimaryClipChanged (~2s same fingerprint) — dual broker / Flyme double-set.</li>
 *   <li>getImageInfo: if provider lacks _size, measure via openInputStream (screenshots).</li>
 *   <li>SecurityException retry on image byte reads (SS URI grant race).</li>
 * </ul>
 */
final class ImageClipboardHooks {
    private static final String TAG = ModuleLog.TAG_IMG;
    private static final String UTIL = "com.microsoft.mmx.agents.clipboard.ClipboardUtility";
    private static final String UTIL_COMPANION = UTIL + "$Companion";
    private static final String CTCM =
            "com.microsoft.mmx.agents.clipboard.transport.ContentTransferClipboardMessage";
    private static final String BASE_BROKER =
            "com.microsoft.deviceExperiences.clipboard.BaseClipboardManagerBroker";
    private static final String EXTGENERIC_BROKER =
            "com.microsoft.appmanager.extgeneric.clipboard.ExtGenericClipboardManagerBroker";
    private static final String SUBSCRIBER =
            "com.microsoft.mmx.agents.clipboard.ClipboardEventSubscriber";

    private static final long DEDUPE_MS = 2_000L;
    private static final Object DEDUPE_LOCK = new Object();
    private static String sLastFp;
    private static long sLastFpAt;

    private static final Object STACK_RL = new Object();
    private static long sStackWinStart;
    private static int sStackWinCount;

    private ClassLoader loader;

    void install(ClassLoader loader, HookBridge.Installer in) {
        this.loader = loader;
        ModuleLog.i(ModuleLog.TAG, "ImageClipboardHooks install begin");
        try {
            installInner(in);
            ModuleLog.i(ModuleLog.TAG, "ImageClipboardHooks install complete");
        } catch (Throwable t) {
            // Log without relying on 4-arg xp.log
            ModuleLog.e(ModuleLog.TAG, "ImageClipboardHooks install THREW: " + t);
            ModuleLog.e(ModuleLog.TAG, "ImageClipboardHooks install failed", t);
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private void installInner(HookBridge.Installer in) {
        HookBridge.Hooker mimeStr = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object r = c.proceed();
                try {
                    Object a = c.getArg(0);
                    if (!Boolean.TRUE.equals(r) && a instanceof String && isImage((String) a)) {
                        ModuleLog.i(TAG, "isMimeTypeSupported(String " + a + ") " + r + " -> true");
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "mimeStr hook err", t);
                }
                return r;
            }
        };
        in.hook(UTIL_COMPANION, "isMimeTypeSupported", mimeStr, String.class);
        in.hook(UTIL, "isMimeTypeSupported", mimeStr, String.class);
        in.hook(CTCM, "isMimeTypeSupported", mimeStr, String.class);
        in.hook(UTIL_COMPANION, "isMimeTypeSupported", new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object r = c.proceed();
                try {
                    Object a = c.getArg(0);
                    if (a instanceof ClipDescription) {
                        ClipDescription d = (ClipDescription) a;
                        boolean img = false;
                        for (int i = 0; i < d.getMimeTypeCount(); i++) {
                            if (isImage(d.getMimeType(i))) img = true;
                        }
                        ModuleLog.i(TAG, "isMimeTypeSupported(ClipDescription " + mimes(d) + ") orig=" + r
                                + (img && !Boolean.TRUE.equals(r) ? " -> true" : ""));
                        if (img) return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "mimeDesc hook err", t);
                }
                return r;
            }
        }, ClipDescription.class);

        HookBridge.Hooker getClip = new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object r;
                try {
                    r = c.proceed();
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "getPrimaryClip threw " + t, t);
                    throw t;
                }
                try {
                    ModuleLog.i(TAG, "getPrimaryClip(" + c.getThisObject().getClass().getSimpleName()
                            + ") -> " + describe(r));
                } catch (Throwable ignored) {
                }
                return r;
            }
        };
        in.hook(BASE_BROKER, "getPrimaryClip", getClip);
        in.hook(EXTGENERIC_BROKER, "getPrimaryClip", getClip);

        in.hook(SUBSCRIBER, "onPrimaryClipChanged", new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                String fp = null;
                try {
                    fp = fingerprintFromSubscriber(c.getThisObject());
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "fingerprint err: " + t);
                }
                if (fp != null && isDuplicate(fp)) {
                    ModuleLog.i(TAG, "onPrimaryClipChanged DEDUPE skip fp=" + fp);
                    return null;
                }
                ModuleLog.i(TAG, "ClipboardEventSubscriber.onPrimaryClipChanged enter fp=" + fp);
                ClipboardRequestDedupeHooks.onNewClip();
                try {
                    Object r = c.proceed();
                    ModuleLog.i(TAG, "ClipboardEventSubscriber.onPrimaryClipChanged exit");
                    return r;
                } catch (Throwable t) {
                    ModuleLog.w(TAG, "onPrimaryClipChanged threw " + t, t);
                    throw t;
                }
            }
        });

        // Dual-send root cause: onPrimaryClipChanged$1 runs BOTH
        // processClipDataInternalLegacy (ContentTransfer / "Sending to PL") AND
        // ClipboardPublisher.publishClipboardChange ("Sending to Cross Device").
        // Each calls getImageData→getImageBytes. Skip legacy for image clips only.
        in.hookByName(SUBSCRIBER, "processClipDataInternalLegacy", 4, new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object clip = null;
                try { clip = c.getArg(2); } catch (Throwable ignored) {}
                if (clipHasImage(clip)) {
                    ModuleLog.i(TAG, "SKIP legacy ContentTransfer for image clip"
                            + " (cross-device ClipboardPublisher sends once); "
                            + describe(clip));
                    return null;
                }
                ModuleLog.i(TAG, "legacy ContentTransfer proceed (non-image)");
                return c.proceed();
            }
        });

        in.hookByName(UTIL, "getImageInfo", 2, imageInfoHook());
        in.hookByName(UTIL, "getImageBytes", 2, imageBytesHook("getImageBytes", 1));
        in.hookByName(UTIL, "getScaledImageBytes", 3, imageBytesHook("getScaledImageBytes", 2));
        in.hookByName(UTIL, "getRealUri", 2, new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object r = c.proceed();
                try {
                    ModuleLog.i(TAG, "getRealUri(" + uriInfo(c.getArg(1)) + ") -> " + uriInfo(r));
                } catch (Throwable ignored) {
                }
                return r;
            }
        });
    }

    /** Skip if same clip fingerprint seen within DEDUPE_MS. */
    private static boolean isDuplicate(String fp) {
        long now = SystemClock.elapsedRealtime();
        synchronized (DEDUPE_LOCK) {
            if (fp.equals(sLastFp) && now - sLastFpAt < DEDUPE_MS) {
                return true;
            }
            sLastFp = fp;
            sLastFpAt = now;
            return false;
        }
    }

    /**
     * Fingerprint: timestamp|mimes|firstUri|sensitive. Pulled from broker on the subscriber
     * when possible; falls back to "ts0".
     */
    private static String fingerprintFromSubscriber(Object subscriber) {
        try {
            Object broker = null;
            for (Class<?> k = subscriber.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField("clipboardManagerBroker");
                    f.setAccessible(true);
                    broker = f.get(subscriber);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (broker == null) return "no-broker";
            ClipDescription desc = null;
            ClipData clip = null;
            try {
                java.lang.reflect.Method m = broker.getClass().getMethod("getPrimaryClipDescription");
                Object d = m.invoke(broker);
                if (d instanceof ClipDescription) desc = (ClipDescription) d;
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Method m = broker.getClass().getMethod("getPrimaryClip");
                Object c = m.invoke(broker);
                if (c instanceof ClipData) clip = (ClipData) c;
            } catch (Throwable ignored) {
            }
            return fingerprint(desc, clip);
        } catch (Throwable t) {
            return "err";
        }
    }

    static String fingerprint(ClipDescription desc, ClipData clip) {
        StringBuilder sb = new StringBuilder();
        long ts = 0;
        if (desc != null) {
            try { ts = desc.getTimestamp(); } catch (Throwable ignored) {}
            sb.append("ts=").append(ts);
            sb.append("|mimes=").append(mimes(desc));
            try {
                PersistableBundle extras = desc.getExtras();
                if (extras != null) {
                    // Android 13+ sensitive clip
                    boolean sens = false;
                    try {
                        sens = extras.getBoolean("android.content.extra.IS_SENSITIVE", false);
                    } catch (Throwable ignored) {
                    }
                    sb.append("|sens=").append(sens);
                }
            } catch (Throwable ignored) {
            }
        } else {
            sb.append("ts=0|mimes=[]");
        }
        if (clip != null && clip.getItemCount() > 0) {
            try {
                Uri u = clip.getItemAt(0).getUri();
                sb.append("|uri=").append(u == null ? "null" : (u.getAuthority() + u.getPath()));
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    /** If _size query fails / returns 0 (common for screenshot providers), measure stream. */
    private HookBridge.Hooker imageInfoHook() {
        final ClassLoader cl = this.loader;
        return new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                Object ctx = c.getArg(0);
                Object uriObj = c.getArg(1);
                String u = uriInfo(uriObj);
                Object r;
                try {
                    r = c.proceed();
                } catch (SecurityException se) {
                    ModuleLog.w(TAG, "getImageInfo(" + u + ") SecurityException — retry 300ms", se);
                    try {
                        Thread.sleep(300);
                        r = c.proceed();
                    } catch (Throwable t2) {
                        ModuleLog.w(TAG, "getImageInfo retry failed: " + t2, t2);
                        throw se;
                    }
                }
                String rs = String.valueOf(r);
                ModuleLog.i(TAG, "getImageInfo(" + u + ") -> " + rs);
                if (ctx instanceof Context && uriObj instanceof Uri && needsSizeFallback(r)) {
                    long measured = measureSize((Context) ctx, (Uri) uriObj);
                    ModuleLog.i(TAG, "getImageInfo size-fallback authority="
                            + ((Uri) uriObj).getAuthority() + " measured=" + measured
                            + " (orig=" + rs + ")");
                    if (measured > 0) {
                        Object success = buildSuccess(cl, measured);
                        if (success != null) {
                            ModuleLog.i(TAG, "getImageInfo -> Success(size=" + measured
                                    + ") via stream measure");
                            return success;
                        }
                    }
                }
                return r;
            }
        };
    }

    private static boolean needsSizeFallback(Object r) {
        if (r == null) return true;
        String s = r.getClass().getName();
        // Fail or Reject from Kotlin sealed result
        if (s.endsWith("$Fail") || s.endsWith("$Reject")) return true;
        if (s.endsWith("$Success")) {
            try {
                Object info = r.getClass().getMethod("getImageInfo").invoke(r);
                if (info != null) {
                    Object sz = info.getClass().getMethod("getSize").invoke(info);
                    if (sz instanceof Long && (Long) sz <= 0L) return true;
                    // field access fallback
                    try {
                        java.lang.reflect.Field f = info.getClass().getDeclaredField("size");
                        f.setAccessible(true);
                        Object v = f.get(info);
                        if (v instanceof Long && (Long) v <= 0L) return true;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static long measureSize(Context ctx, Uri uri) {
        // Try query _size first with broader projection
        try {
            android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{"_size", "mime_type", "_display_name"}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex("_size");
                        if (idx >= 0) {
                            long v = c.getLong(idx);
                            if (v > 0) return v;
                        }
                        ModuleLog.i(TAG, "size-query cols=" + java.util.Arrays.toString(c.getColumnNames())
                                + " authority=" + uri.getAuthority());
                    } else {
                        ModuleLog.i(TAG, "size-query empty cursor authority=" + uri.getAuthority());
                    }
                } finally {
                    c.close();
                }
            } else {
                ModuleLog.i(TAG, "size-query null cursor authority=" + uri.getAuthority());
            }
        } catch (SecurityException se) {
            ModuleLog.w(TAG, "size-query SecurityException authority=" + uri.getAuthority()
                    + ": " + se.getMessage());
        } catch (Throwable t) {
            ModuleLog.w(TAG, "size-query err authority=" + uri.getAuthority() + ": " + t);
        }
        // Stream measure (works for many screenshot / MediaStore URIs without _size)
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                ModuleLog.w(TAG, "openInputStream null authority=" + uri.getAuthority());
                return -1;
            }
            long n = 0;
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                n += r;
                if (n > 40L * 1024 * 1024) break; // cap
            }
            return n;
        } catch (SecurityException se) {
            ModuleLog.w(TAG, "openInputStream SecurityException authority=" + uri.getAuthority()
                    + ": " + se.getMessage());
            return -2;
        } catch (Throwable t) {
            ModuleLog.w(TAG, "openInputStream err authority=" + uri.getAuthority() + ": " + t);
            return -3;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static Object buildSuccess(ClassLoader cl, long size) {
        try {
            Class<?> infoCls = Class.forName(UTIL + "$ClipboardImageInfo", false, cl);
            Constructor<?> infoCtor = infoCls.getDeclaredConstructor(long.class);
            infoCtor.setAccessible(true);
            Object info = infoCtor.newInstance(size);
            Class<?> successCls = Class.forName(UTIL + "$ImageInfoResult$Success", false, cl);
            Constructor<?> sCtor = successCls.getDeclaredConstructor(infoCls);
            sCtor.setAccessible(true);
            return sCtor.newInstance(info);
        } catch (Throwable t) {
            ModuleLog.w(TAG, "buildSuccess failed: " + t, t);
            return null;
        }
    }

    private static HookBridge.Hooker diag(final String name, final int uriIdx, final boolean bytes) {
        return new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                String u = "?";
                try {
                    u = uriInfo(c.getArg(uriIdx));
                } catch (Throwable ignored) {
                }
                try {
                    Object r = c.proceed();
                    logResult(name, u, r, bytes);
                    return r;
                } catch (SecurityException se) {
                    ModuleLog.w(TAG, name + "(" + u + ") SecurityException: " + se.getMessage()
                            + " — retrying once after 300ms (SS grant)", se);
                    try {
                        Thread.sleep(300);
                        Object r = c.proceed();
                        ModuleLog.i(TAG, name + " retry OK");
                        logResult(name, u, r, bytes);
                        return r;
                    } catch (Throwable t2) {
                        ModuleLog.w(TAG, name + " retry failed: " + t2, t2);
                    }
                    throw se;
                } catch (Throwable t) {
                    ModuleLog.w(TAG, name + "(" + u + ") threw " + t, t);
                    throw t;
                }
            }
        };
    }

    private static void logResult(String name, String u, Object r, boolean bytes) {
        try {
            String rs = bytes ? (r instanceof byte[] ? ((byte[]) r).length + " bytes" : String.valueOf(r))
                    : String.valueOf(r);
            ModuleLog.i(TAG, name + "(" + u + ") -> " + rs);
        } catch (Throwable ignored) {
        }
    }


    private static boolean clipHasImage(Object clip) {
        if (!(clip instanceof ClipData)) return false;
        ClipData cd = (ClipData) clip;
        try {
            ClipDescription d = cd.getDescription();
            if (d != null) {
                for (int i = 0; i < d.getMimeTypeCount(); i++) {
                    if (isImage(d.getMimeType(i))) return true;
                }
            }
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) {
                    String s = String.valueOf(u);
                    if (s.contains("image") || s.contains("screenshot") || s.contains("Screenshot"))
                        return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Log caller stacks (rate-limited) so we can confirm ContentTransfer vs ClipboardMessageBuilder. */
    private static HookBridge.Hooker imageBytesHook(final String name, final int uriIdx) {
        return new HookBridge.Hooker() {
            @Override
            public Object invoke(XposedInterface.Chain c) throws Throwable {
                String u = "?";
                try { u = uriInfo(c.getArg(uriIdx)); } catch (Throwable ignored) {}
                logCallerStack(name, u);
                try {
                    Object r = c.proceed();
                    logResult(name, u, r, true);
                    return r;
                } catch (SecurityException se) {
                    ModuleLog.w(TAG, name + "(" + u + ") SecurityException — retry 300ms", se);
                    try {
                        Thread.sleep(300);
                        Object r = c.proceed();
                        ModuleLog.i(TAG, name + " retry OK");
                        logResult(name, u, r, true);
                        return r;
                    } catch (Throwable t2) {
                        ModuleLog.w(TAG, name + " retry failed: " + t2, t2);
                    }
                    throw se;
                } catch (Throwable t) {
                    ModuleLog.w(TAG, name + "(" + u + ") threw " + t, t);
                    throw t;
                }
            }
        };
    }

    private static void logCallerStack(String name, String uriInfo) {
        synchronized (STACK_RL) {
            long now = SystemClock.elapsedRealtime();
            if (now - sStackWinStart > 10_000L) {
                sStackWinStart = now;
                sStackWinCount = 0;
            }
            if (sStackWinCount >= 8) return;
            sStackWinCount++;
        }
        try {
            StackTraceElement[] st = new Throwable("getImageBytes-caller").getStackTrace();
            StringBuilder sb = new StringBuilder();
            String pathHint = "?";
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.contains("ContentTransferClipboardMessage")) pathHint = "legacy-ContentTransfer";
                else if (cn.contains("ClipboardMessageBuilder")) pathHint = "crossDevice-MessageBuilder";
                else if (cn.contains("ClipboardResourceRequestHandler")) pathHint = "resource-request";
            }
            sb.append(name).append(" path=").append(pathHint)
                    .append(" uri=").append(uriInfo).append('\n');
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.startsWith("io.github.mqmqgo.ltwunlock")) continue;
                if (cn.startsWith("io.github.libxposed")) continue;
                if (cn.startsWith("LSPHooker")) continue;
                if (cn.startsWith("de.robv.android.xposed")) continue;
                sb.append("  at ").append(e.toString()).append('\n');
                if (++n >= 15) break;
            }
            ModuleLog.i(TAG, sb.toString().trim());
        } catch (Throwable ignored) {
        }
    }

    static boolean isImage(String m) {
        return m != null && m.toLowerCase(java.util.Locale.US).startsWith("image/");
    }

    private static String mimes(ClipDescription d) {
        StringBuilder sb = new StringBuilder("[");
        try {
            for (int i = 0; i < d.getMimeTypeCount(); i++) {
                if (i > 0) sb.append(',');
                sb.append(d.getMimeType(i));
            }
        } catch (Throwable ignored) {
        }
        return sb.append(']').toString();
    }

    private static String uriInfo(Object o) {
        if (!(o instanceof Uri)) return String.valueOf(o == null ? "null" : o.getClass().getSimpleName());
        Uri u = (Uri) o;
        return "uri scheme=" + u.getScheme() + " authority=" + u.getAuthority()
                + " path=" + u.getPath();
    }

    static String describe(Object o) {
        if (o == null) return "null";
        if (!(o instanceof ClipData)) return o.getClass().getName();
        ClipData cd = (ClipData) o;
        StringBuilder sb = new StringBuilder();
        try {
            ClipDescription d = cd.getDescription();
            sb.append("ClipData mimes=").append(d == null ? "null" : mimes(d));
            if (d != null) {
                try { sb.append(" ts=").append(d.getTimestamp()); } catch (Throwable ignored) {}
                try {
                    PersistableBundle ex = d.getExtras();
                    if (ex != null) {
                        sb.append(" sens=").append(
                                ex.getBoolean("android.content.extra.IS_SENSITIVE", false));
                    }
                } catch (Throwable ignored) {
                }
            }
            sb.append(" items=").append(cd.getItemCount());
            for (int i = 0; i < cd.getItemCount() && i < 5; i++) {
                ClipData.Item it = cd.getItemAt(i);
                sb.append(" #").append(i).append('{');
                if (it.getUri() != null) sb.append(uriInfo(it.getUri()));
                else sb.append("uri=null");
                if (it.getText() != null) sb.append(" textLen=").append(it.getText().length());
                if (it.getHtmlText() != null) sb.append(" html");
                if (it.getIntent() != null) sb.append(" intent");
                sb.append('}');
            }
        } catch (Throwable t) {
            sb.append(" describeErr=").append(t);
        }
        return sb.toString();
    }
}
