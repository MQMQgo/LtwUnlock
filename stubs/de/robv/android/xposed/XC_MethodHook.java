package de.robv.android.xposed;
import java.lang.reflect.Member;
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Member method;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.throwable = null; this.returnEarly = true; }
        public void setThrowable(Throwable t) { this.throwable = t; this.returnEarly = true; }
        public boolean hasThrowable() { return throwable != null; }
        public Throwable getThrowable() { return throwable; }
        public Object getResultOrThrowable() throws Throwable { if (throwable != null) throw throwable; return result; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
