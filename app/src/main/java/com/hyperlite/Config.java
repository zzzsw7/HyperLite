package com.hyperlite;

import android.app.AndroidAppHelper;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 被注入进程侧的设置快照。
 *
 * <p>模块 App 的 SharedPreferences 通过 {@link PrefsProvider} 以 Bundle 暴露；本类只缓存一份快照，
 * 设置变化时 App 会 notifyChange，被注入进程收到后异步重读并回调监听器。
 * 所有 get* 方法都不会做 IPC，可以安全地放在热路径的 Hook 里。
 */
public final class Config {

    public static final String TAG = "HyperLite";
    public static final String AUTHORITY = "com.hyperlite.settings";
    public static final String PREFS = "hyperlite";
    /** 桌面图标是否隐藏（由 activity-alias 的启用状态实现）。 */
    public static final String KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static final AtomicInteger RETRIES = new AtomicInteger();
    private static final int MAX_RETRIES = 60;
    /** 观察者注册失败时的兜底轮询间隔。 */
    private static final long WATCHDOG_MS = 20_000L;

    private static volatile Bundle cache = Bundle.EMPTY;
    private static volatile Context context;
    private static volatile boolean observed;
    private static Handler mainHandler;

    private Config() {
    }

    /** 惰性创建主线程 Handler：开机早期静态初始化时 Looper 不一定就绪。 */
    private static synchronized Handler mainHandler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    /** 初值：必须在被注入进程的主线程调用一次（内部会用主线程 Looper 注册观察者）。 */
    public static void initialize() {
        refreshAsync();
        ensureObserver();
    }

    public static void addOnChange(Runnable listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    /**
     * SystemUI 里 {@code onPackageLoaded} 早于 Application 创建，此时拿不到 Context，
     * 设置快照会一直为空（所有开关退回默认值）。所以在 Application#onCreate 之后再初始化一次。
     */
    public static void initializeAfterApplication(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", loader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            context = null;
                            initialize();
                        }
                    });
        } catch (Throwable error) {
            Log.w(TAG, "application init hook failed", error);
        }
    }

    public static boolean getBoolean(String key, boolean fallback) {
        try {
            return cache.getBoolean(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static int getInt(String key, int fallback) {
        try {
            return cache.getInt(key, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static String getString(String key, String fallback) {
        try {
            String value = cache.getString(key);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void refreshAsync() {
        if (!FETCHING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            boolean ok = false;
            try {
                ok = refresh();
            } finally {
                FETCHING.set(false);
            }
            if (!ok) scheduleRetry();
        }, "HyperLite-config");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 开机阶段（用户还没解锁、App 还没启动过）provider 拿不到，配置会一直是空快照，
     * 所有开关退回默认值。所以失败后退避重试，直到读到一次成功为止。
     */
    private static void scheduleRetry() {
        int attempt = RETRIES.getAndIncrement();
        if (attempt >= MAX_RETRIES) return;
        long delay = Math.min(8000L, 800L + attempt * 400L);
        try {
            mainHandler().postDelayed(Config::retryTick, delay);
        } catch (Throwable ignored) {
            // 没有主线程 Looper 的进程直接放弃重试。
        }
    }

    /** 重试时顺便补一次观察者注册：开机早期 ContentService 还是 null，注册会失败。 */
    private static void retryTick() {
        ensureObserver();
        refreshAsync();
    }

    /**
     * 观察者没注册上（system_server 早期很常见）时，设置改了收不到通知，
     * 用低频轮询兜底；一旦观察者注册成功就停掉。
     */
    private static void scheduleWatchdog() {
        if (observed) return;
        try {
            mainHandler().postDelayed(() -> {
                if (observed) return;
                ensureObserver();
                refreshAsync();
                scheduleWatchdog();
            }, WATCHDOG_MS);
        } catch (Throwable ignored) {
            // 没有主线程 Looper 就放弃兜底。
        }
    }

    private static boolean refresh() {
        Context ctx = context();
        if (ctx == null) return false;
        try {
            Bundle result = ctx.getContentResolver().call(URI, "get", null, null);
            if (result == null) return false;
            cache = result;
            RETRIES.set(0);
            dispatch();
            scheduleWatchdog();
            return true;
        } catch (Throwable error) {
            // 开机阶段必然失败几次，只有第一次带堆栈，避免刷屏。
            if (RETRIES.get() == 0) {
                Log.w(TAG, "read module settings failed", error);
            } else {
                Log.w(TAG, "read module settings still failing: " + error);
            }
            return false;
        }
    }

    private static void dispatch() {
        if (LISTENERS.isEmpty()) return;
        Handler handler = mainHandler();
        for (Runnable listener : LISTENERS) {
            handler.post(() -> {
                try {
                    listener.run();
                } catch (Throwable error) {
                    Log.w(TAG, "settings listener failed", error);
                }
            });
        }
    }

    private static void ensureObserver() {
        if (observed) return;
        Context ctx = context();
        if (ctx == null) return;
        try {
            ContentResolver resolver = ctx.getContentResolver();
            resolver.registerContentObserver(URI, true, new ContentObserver(mainHandler()) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshAsync();
                }
            });
            observed = true;
        } catch (Throwable error) {
            Log.w(TAG, "register settings observer failed", error);
        }
    }

    /** 被注入进程里拿一个可用 Context（SystemUI 用 Application，system_server 用 system context）。 */
    public static Context context() {
        Context cached = context;
        if (cached != null) return cached;
        Context found = null;
        try {
            android.app.Application application = AndroidAppHelper.currentApplication();
            if (application != null) found = application;
        } catch (Throwable ignored) {
            // 兼容层在部分进程不可用，继续走 system context。
        }
        if (found == null) {
            // 只有 system_server（包名 android）才有 system context；在 SystemUI 里用它会导致
            // ContentResolver 报 "calling package android does not match caller's uid"。
            String packageName = null;
            try {
                packageName = AndroidAppHelper.currentPackageName();
            } catch (Throwable ignored) {
                // 取不到包名就不走 system context。
            }
            if ("android".equals(packageName)) {
                try {
                    Class<?> activityThread = Class.forName("android.app.ActivityThread");
                    Object thread = XposedHelpers.callStaticMethod(activityThread, "currentActivityThread");
                    if (thread == null) thread = XposedHelpers.callStaticMethod(activityThread, "systemMain");
                    Object ctx = XposedHelpers.callMethod(thread, "getSystemContext");
                    if (ctx instanceof Context) found = (Context) ctx;
                } catch (Throwable ignored) {
                    // 保留 null，调用方自行降级。
                }
            }
        }
        if (found != null) context = found;
        return found;
    }
}
