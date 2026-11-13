package com.hyperlite;

import android.os.Handler;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 旧版（非 edge-to-edge）应用的状态栏 / 导航栏完全透明。
 *
 * <p>系统对「兼容模式 / letterbox」窗口默认只允许半透明系统栏，于是会在应用上面盖一层遮罩。
 * 这里改的是 WindowManager 侧的判定入口 {@code DisplayPolicy#isFullyTransparentAllowed}，
 * 让兼容窗口也能拿到全透明系统栏；不动任何资源与颜色，关掉开关即时恢复。
 *
 * <p>只跑在 system_server（LSPosed 里叫「系统框架」）进程，因此需要单独的作用域。
 */
final class SystemBarTransparency {

    static final String KEY = "sysbar_transparent";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final WeakHashMap<Object, Refresh> POLICIES = new WeakHashMap<>();

    private SystemBarTransparency() {
    }

    static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> policy = findClass("com.android.server.wm.DisplayPolicy", loader);
            Class<?> windowState = findClass("com.android.server.wm.WindowState", loader);
            if (policy == null || windowState == null) {
                Log.w(Config.TAG, "旧版系统栏透明：找不到 DisplayPolicy，跳过");
                return;
            }

            final Method update = policy.getDeclaredMethod("updateSystemBarAttributes");
            update.setAccessible(true);
            final Field lock = policy.getDeclaredField("mLock");
            lock.setAccessible(true);
            final Field handler = policy.getDeclaredField("mHandler");
            handler.setAccessible(true);

            XposedHelpers.findAndHookMethod(policy, "isFullyTransparentAllowed", windowState, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (Config.getBoolean(KEY, true)) param.setResult(Boolean.TRUE);
                            } catch (Throwable error) {
                                Log.w(Config.TAG, "isFullyTransparentAllowed hook failed", error);
                            }
                        }
                    });

            XposedBridge.hookAllConstructors(policy, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object target = param.thisObject;
                        Handler targetHandler = (Handler) handler.get(target);
                        synchronized (POLICIES) {
                            POLICIES.put(target, new Refresh(target, targetHandler, lock, update));
                        }
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "DisplayPolicy tracking failed", error);
                    }
                }
            });

            deoptimize(policy);
            Config.addOnChange(SystemBarTransparency::refresh);
            Log.i(Config.TAG, "旧版系统栏透明 hook 已安装");
        } catch (Throwable error) {
            INSTALLED.set(false);
            Log.w(Config.TAG, "旧版系统栏透明 hook 安装失败", error);
        }
    }

    /**
     * services.jar 在 system_server 的引导路径上，回调给的 classLoader 不一定包含它，
     * 所以再用模块自己的 loader 兜底。
     */
    private static Class<?> findClass(String name, ClassLoader loader) {
        if (loader != null) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type != null) return type;
        }
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 让 ART 放弃对这些小方法的内联，否则 Hook 会被绕过；旧 API 没有该能力时静默跳过。 */
    private static void deoptimize(Class<?> type) {
        try {
            Method deoptimize = null;
            for (Method method : XposedBridge.class.getMethods()) {
                if ("deoptimizeMethod".equals(method.getName())) {
                    deoptimize = method;
                    break;
                }
            }
            if (deoptimize == null) return;
            for (Method method : type.getDeclaredMethods()) {
                try {
                    deoptimize.invoke(null, method);
                } catch (Throwable ignored) {
                    // 单个方法失败不影响其它。
                }
            }
        } catch (Throwable error) {
            Log.w(Config.TAG, "deoptimize DisplayPolicy failed", error);
        }
    }

    /** 开关切换后让系统重算一次窗口属性，避免要等下一次窗口变化才生效。 */
    private static void refresh() {
        ArrayList<Refresh> targets;
        synchronized (POLICIES) {
            targets = new ArrayList<>(POLICIES.values());
        }
        for (Refresh refresh : targets) refresh.schedule();
    }

    private static final class Refresh implements Runnable {
        private final WeakReference<Object> policy;
        private final WeakReference<Handler> handler;
        private final Field lock;
        private final Method update;
        private final AtomicBoolean queued = new AtomicBoolean();

        Refresh(Object policy, Handler handler, Field lock, Method update) {
            this.policy = new WeakReference<>(policy);
            this.handler = new WeakReference<>(handler);
            this.lock = lock;
            this.update = update;
        }

        void schedule() {
            Handler target = handler.get();
            if (target != null && queued.compareAndSet(false, true) && !target.post(this)) queued.set(false);
        }

        @Override
        public void run() {
            queued.set(false);
            Object target = policy.get();
            if (target == null) return;
            try {
                synchronized (lock.get(target)) {
                    update.invoke(target);
                }
            } catch (Throwable error) {
                Log.w(Config.TAG, "system bar refresh failed", error);
            }
        }
    }
}
