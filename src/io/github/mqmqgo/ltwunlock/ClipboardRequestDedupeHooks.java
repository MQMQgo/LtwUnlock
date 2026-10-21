package io.github.mqmqgo.ltwunlock;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Diagnostics + dedupe for PC-initiated clipboard content pulls.
 * Phone publishes ONE metadata announcement (ClipboardPublisher); the PC then pulls content via
 * ClipboardResourceRequestHandler (CONTENT request -> handleContentRequest). Observed: two pulls
 * ~0.6-0.9s apart for one clip => image lands twice in PC clipboard. We serve the first successful
 * image pull per clip generation and answer later pulls (within window) with an invalid response.
 * Text clips are never deduped.
 */
final class ClipboardRequestDedupeHooks {
    private static final String TAG = "LtwUnlock-Req";
    private static final String P = "com.microsoft.mmx.agents.clipboard.platform.";
    private static final String HANDLER = P + "ClipboardResourceRequestHandler";
    private static final String PUBLISHER = P + "ClipboardPublisher";
    private static final String BUILDER = P + "ClipboardMessageBuilder";
    static final long WINDOW_MS = 8_000L;

    private static final Object LOCK = new Object();
    private static volatile long sClipGen;
    private static long sServedGen = -1;
    private static long sServedAt;
    private static String sServedClient;
    private static volatile String sLastClient = "?";
    private static int sReqSeq;

    private ClipboardRequestDedupeHooks() {}

    /** Called when LTW processes a new primary clip (one per real clipboard change). */
    static void onNewClip() {
        synchronized (LOCK) { sClipGen++; }
        ModuleLog.i(TAG, "clip generation -> " + sClipGen);
    }

    static void install(HookBridge.Installer in) {
        in.hookByName(HANDLER, "handleRequestInternal", 6, c -> {
            int seq;
            synchronized (LOCK) { seq = ++sReqSeq; }
            String client = String.valueOf(c.getArg(0));
            sLastClient = client;
            byte[] payload = c.getArg(3) instanceof byte[] ? (byte[]) c.getArg(3) : null;
            ModuleLog.i(TAG, "IN #" + seq + " dcgClient=" + client + " type=" + c.getArg(1)
                    + " path=" + c.getArg(2) + " payloadLen=" + (payload == null ? -1 : payload.length)
                    + " trace=" + traceOf(c.getArg(4)) + " clipGen=" + sClipGen);
            return c.proceed();
        });

        in.hookByName(HANDLER, "handleContentRequest", 1, c -> {
            String corr = String.valueOf(c.getArg(0));
            String client = sLastClient;
            long now = SystemClock.elapsedRealtime();
            long gen = sClipGen;
            synchronized (LOCK) {
                if (gen == sServedGen && now - sServedAt < WINDOW_MS) {
                    ModuleLog.i(TAG, "CONTENT DEDUPE corr=" + corr + " client=" + client
                            + " (image of clipGen=" + gen + " already served "
                            + (now - sServedAt) + "ms ago to client=" + sServedClient + ")");
                    Object b = field(c.getThisObject(), "messageBuilder");
                    Method m = b.getClass().getMethod("createInvalidRequestTypeResponseMessage", String.class);
                    return m.invoke(b, corr);
                }
            }
            ModuleLog.i(TAG, "CONTENT request corr=" + corr + " client=" + client + " clipGen=" + gen);
            Object r = c.proceed();
            boolean image = false, text = false;
            try {
                Object b = field(c.getThisObject(), "messageBuilder");
                image = field(b, "image") != null;
                text = field(b, "text") != null;
            } catch (Throwable ignored) {}
            ModuleLog.i(TAG, "CONTENT served corr=" + corr + " image=" + image + " text=" + text
                    + " resp=" + summarize(r));
            if (image) {
                synchronized (LOCK) {
                    sServedGen = gen;
                    sServedAt = now;
                    sServedClient = client;
                }
            }
            return r;
        });

        in.hookByName(PUBLISHER, "publishClipboardChange", 2, c -> {
            ModuleLog.i(TAG, "OUT publishClipboardChange trace=" + traceOf(c.getArg(0)) + " clipGen=" + sClipGen);
            return c.proceed();
        });
        in.hookByName(BUILDER, "createCopyPublishMetadataMessage", 1, c -> {
            Object r = c.proceed();
            ModuleLog.i(TAG, "OUT metadata traceId=" + c.getArg(0) + " msg=" + summarize(r));
            return r;
        });
    }

    private static String traceOf(Object tc) {
        if (tc == null) return "null";
        try {
            Object t = tc.getClass().getMethod("getTraceId").invoke(tc);
            Object co = null;
            try { co = tc.getClass().getMethod("getCorrelationId").invoke(tc); } catch (Throwable ignored) {}
            return t + "/" + co;
        } catch (Throwable e) {
            return tc.getClass().getSimpleName();
        }
    }

    private static String summarize(Object msg) {
        if (msg == null) return "null";
        try {
            Object n = msg.getClass().getMethod("getSerializedSize").invoke(msg);
            if (n instanceof Integer && (Integer) n > 2048) {
                return msg.getClass().getSimpleName() + "{bytes=" + n + "}";
            }
        } catch (Throwable ignored) {}
        String s = String.valueOf(msg).replace('\n', ' ');
        // protobuf toString may include bytes; keep short
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        return s;
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
