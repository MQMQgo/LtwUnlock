package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;
public final class XposedBridge {
    public static class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
    public static Set<Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return new HashSet<>(); }
    public static Set<Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) { return new HashSet<>(); }
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable { return null; }
}
