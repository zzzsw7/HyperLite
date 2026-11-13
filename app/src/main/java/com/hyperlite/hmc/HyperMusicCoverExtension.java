package com.hyperlite.hmc;

import android.os.Build;
import android.os.SystemClock;

import com.hyperlite.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * HyperMusicCover 扩展：在 SystemUI 进程里与已生效的 HMC 协同。
 *
 * <p>这里不重写 HMC，也不接管锁屏布局；只通过反射调用
 * {@code com.os4.musiccover.Main} 的静态入口，做四件协同的事：
 * 时钟高度、锁屏通知下沉、AOD 媒体卡片帧让位、指纹区避让（后者留 TODO）。
 *
 * <p>未安装 HMC 时整体放弃：不 Hook、不刷日志。HMC 由它自己的 LSPosed ClassLoader 加载，
 * 可能在我们的回调之后才可用，所以直接反射失败时会在 SystemUI 的时钟回调里用栈帧补一次发现
 * （需要 API 34+ 的 StackWalker；更低版本直接放弃），仍然找不到就保持静默。
 */
public final class HyperMusicCoverExtension {

    /** HMC 的运行时入口类名。 */
    private static final String HMC_MAIN = HmcSymbols.MAIN;
    /** 发现阶段最多尝试多少次（每次至少间隔 1 秒），避免 Hook 永久留在 SystemUI 里空转。 */
    private static final int MAX_DISCOVER_ATTEMPTS = 60;

    private static final AtomicBoolean BOUND = new AtomicBoolean();
    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final AtomicBoolean RETRY_REGISTERED = new AtomicBoolean();
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();
    /** 上一次发现尝试的时间：栈回溯不便宜，节流到每秒最多一次。 */
    private static volatile long lastAttemptAt = Long.MIN_VALUE;

    private HyperMusicCoverExtension() {
    }

    /** 入口：由 com.hyperlite.HyperMusicCoverFeature 在 SystemUI 进程调用。 */
    public static void install(ClassLoader loader) {
        if (RETRY_REGISTERED.compareAndSet(false, true)) {
            // 设置快照在 Application 创建后才就绪，HMC 也可能比我们晚加载：
            // 每次设置变化（或用户重新开关一次）都重试一次对接，避免一次失败就永久放弃。
            Config.addOnChange(() -> {
                if (!BOUND.get()) install(loader);
            });
        }
        try {
            HmcState.reload();
            if (!HmcState.enabled()) return;
            Class<?> main = HmcSymbols.load(HMC_MAIN, loader);
            if (main != null) {
                bind(loader, main);
                return;
            }
            watchForLateLoad(loader);
        } catch (Throwable error) {
            HmcLog.warnOnce("extension-install", "HyperMusicCover 扩展安装失败", error);
        }
    }

    /**
     * HMC 的类可能由另一个 ClassLoader 持有：在 HMC 自身也参与的 SystemUI 回调里补一次发现。
     * 只在 API 34+ 做（需要 StackWalker 拿到栈上的 Class 对象）。
     */
    private static void watchForLateLoad(ClassLoader loader) {
        try {
            if (Build.VERSION.SDK_INT < 34) {
                HmcLog.info("未检测到 HyperMusicCover，扩展未启用");
                return;
            }
            if (!WATCHING.compareAndSet(false, true)) return;
            Class<?> container = HmcSymbols.load(
                    "com.android.keyguard.clock.KeyguardClockContainer", loader);
            if (container == null) {
                WATCHING.set(false);
                return;
            }
            // 用最低优先级：HMC 的 Hook 优先级更高，链上它会先执行，
            // 等它 call proceed 进到我们这里时，栈上就留着 com.os4.musiccover.* 的帧。
            XC_MethodHook hook = new XC_MethodHook(-10000) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    tryBind(loader);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    tryBind(loader);
                }
            };
            XposedBridge.hookAllMethods(container, "notifStateChange", hook);
        } catch (Throwable error) {
            HmcLog.warnOnce("extension-watch", "等待 HyperMusicCover 失败", error);
        }
    }

    private static void tryBind(ClassLoader loader) {
        if (BOUND.get()) return;
        long now = SystemClock.uptimeMillis();
        long last = lastAttemptAt;
        if (last != Long.MIN_VALUE && now - last < 1000L) return;
        lastAttemptAt = now;
        int attempt = ATTEMPTS.incrementAndGet();
        if (attempt > MAX_DISCOVER_ATTEMPTS) {
            if (attempt == MAX_DISCOVER_ATTEMPTS + 1) {
                HmcLog.info("未检测到 HyperMusicCover，扩展未启用");
            }
            return;
        }
        try {
            Class<?> main = HmcSymbols.load(HMC_MAIN, loader);
            if (main == null) {
                // LSPosed 在调用某个模块的回调时会把该模块的 loader 设为线程上下文 loader；
                // 如果此刻外层是 HMC 的回调，这里拿到的就是 HMC 的 loader。
                ClassLoader context = currentContextLoader();
                if (context != null && context != loader) main = HmcSymbols.load(HMC_MAIN, context);
            }
            if (main == null && Build.VERSION.SDK_INT >= 34) {
                Class<?> owner = StackFinder.find();
                if (owner != null) main = HmcSymbols.load(HMC_MAIN, owner.getClassLoader());
            }
            if (main != null) {
                bind(loader, main);
                return;
            }
        } catch (Throwable error) {
            HmcLog.warnOnce("extension-discover", "查找 HyperMusicCover 失败", error);
        }
    }

    private static ClassLoader currentContextLoader() {
        try {
            return Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void bind(ClassLoader systemLoader, Class<?> main) {
        // 先占位：绑定要么成功一次，要么就放弃，避免重复装 Hook。
        if (!BOUND.compareAndSet(false, true)) return;
        try {
            HmcAdapter adapter = new HmcAdapter(main, systemLoader);
            if (!adapter.usable()) {
                HmcLog.info("HyperMusicCover 接口不可用，扩展未启用");
                return;
            }
            HmcState.attach(adapter);

            HmcClockHooks.install(adapter, systemLoader);
            HmcNotificationSink.install(adapter, systemLoader);
            HmcNotificationSink.watchCoverTransitions(adapter);
            HmcAodFrame.install(adapter, systemLoader);
            HmcFodAvoid.install(adapter, systemLoader);

            Config.addOnChange(HmcState::refresh);
            HmcLog.info("HyperMusicCover 扩展已挂载");
        } catch (Throwable error) {
            HmcLog.warnOnce("extension-bind", "HyperMusicCover 扩展对接失败", error);
        }
    }

    /**
     * 从当前调用栈上找 HMC 的类。
     *
     * <p>HMC 用自己的 ClassLoader，Class.forName 在 SystemUI 的 loader 上可能看不到它；但 HMC
     * 的 Hook 回调会在栈上留下 {@code com.os4.musiccover.*} 的帧（它 call proceed 后进入我们的
     * 回调），拿到那个 Class 就能顺着它的 ClassLoader 反射到 Main。
     */
    private static final class StackFinder {
        private StackFinder() {
        }

        static Class<?> find() {
            try {
                return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames ->
                        frames.map(StackWalker.StackFrame::getDeclaringClass)
                                .filter(type -> type.getName().startsWith("com.os4.musiccover."))
                                .findFirst().orElse(null));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 保留给可能的外部诊断：扩展是否已完成对接。 */
    static boolean bound() {
        return BOUND.get();
    }
}
