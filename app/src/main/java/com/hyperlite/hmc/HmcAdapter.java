package com.hyperlite.hmc;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HMC（com.os4.musiccover.Main）的反射句柄表。
 *
 * <p>这里只保留本移植真正用到的入口，并按 HMC 的版本差异做多候选查找：
 * 旧版这些静态成员全在 Main 上，新版把字形测量拆到了 ClockViews、把房间高度拆到了 ClockRoom。
 * 任何一项拿不到都只是对应子功能降级，不影响其它项。
 *
 * <p>HMC 的类由它自己的 LSPosed ClassLoader 加载，所以这里的 Class.forName 一律用
 * {@code main.getClassLoader()}，不要用 SystemUI 的 loader。
 */
final class HmcAdapter {

    /** HMC 的入口类，拿它主要为了拿 HMC 的 ClassLoader 和少量静态入口。 */
    final Class<?> main;
    /** HMC 自己的 ClassLoader；HMC 的其它类都要用它加载。 */
    final ClassLoader loader;
    /** SystemUI 的 ClassLoader；OEM 类（TimeView、KeyguardClockContainer）用它加载。 */
    final ClassLoader systemLoader;

    // ---- 时钟 / 场景状态（都是无参静态方法） ----
    final Method clockRoots;        // View[] clockRoots()
    final Method clockTarget;       // View clockTarget(View root)
    final Method clockHeld;         // boolean clockHeld()
    final Method screenOn;          // boolean screenOnCached() / screenOn()
    /** screenOn 是不是无 binder 的缓存版；旧版只有 screenOn()，需要我们自己节流。 */
    final boolean screenOnCached;
    final Method keyguardLocked;    // boolean keyguardLocked()
    final Method onKeyguardNow;     // boolean onKeyguardNow()
    final Method controlCenter;     // boolean controlCenterShown()
    final Method coverModeOn;       // boolean coverModeOn()

    private volatile long screenOnAt;
    private volatile boolean screenOnValue = true;

    // ---- 字形测量（只用于诊断与高度换算） ----
    final Method glyphUnit;         // float glyphUnit(RectF box)

    // ---- 媒体卡片（AOD 协同诊断） ----
    final Method liveMediaTop;      // float liveMediaTop()
    final Method coverCardInAod;    // boolean coverCardInAod()

    // ---- 协同触发 ----
    final Method refreshNotificationSink; // void refreshNotificationSink()
    final Method clockRefresh;            // void ClockCollapse.refresh()

    /** HMC 自己进入/退出封面模式的方法，用来在状态切换时请求重新评估。 */
    final List<Method> coverTransitions;

    HmcAdapter(Class<?> main, ClassLoader systemLoader) {
        this.main = main;
        this.loader = main == null ? null : main.getClassLoader();
        this.systemLoader = systemLoader;
        ClassLoader hmc = loader;

        clockRoots = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN, HmcSymbols.CLOCK_VIEWS),
                "clockRoots", View[].class);
        clockTarget = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN, HmcSymbols.CLOCK_VIEWS),
                "clockTarget", View.class, View.class);
        clockHeld = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN, HmcSymbols.CLOCK_ROOM),
                "clockHeld", boolean.class);
        screenOnCached = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "screenOnCached", boolean.class) != null;
        screenOn = first(HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "screenOnCached", boolean.class),
                HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "screenOn", boolean.class));
        keyguardLocked = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "keyguardLocked", boolean.class);
        onKeyguardNow = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "onKeyguardNow", boolean.class);
        controlCenter = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "controlCenterShown", boolean.class);
        coverModeOn = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "coverModeOn", boolean.class);

        glyphUnit = HmcSymbols.method(hmc, owners(HmcSymbols.CLOCK_VIEWS, HmcSymbols.MAIN),
                "glyphUnit", float.class, android.graphics.RectF.class);

        liveMediaTop = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "liveMediaTop", float.class);
        coverCardInAod = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN), "coverCardInAod", boolean.class);

        refreshNotificationSink = HmcSymbols.method(hmc, owners(HmcSymbols.MAIN),
                "refreshNotificationSink", void.class);
        clockRefresh = HmcSymbols.method(hmc, owners(HmcSymbols.CLOCK_COLLAPSE, HmcSymbols.MAIN),
                "refresh", void.class);

        coverTransitions = resolveCoverTransitions(main);
    }

    /** 至少要能拿到时钟树或场景状态之一，否则这个版本不值得继续绑定。 */
    boolean usable() {
        return main != null
                && (clockRoots != null || coverModeOn != null || clockHeld != null || screenOn != null);
    }

    /** 是否至少有一项「封面是否在锁屏上」的判据；一项都没有时不能贸然认为封面生效。 */
    boolean hasSceneState() {
        return coverModeOn != null || clockHeld != null || screenOn != null;
    }

    /** 调一个「无参、返回 boolean」的 HMC 静态入口；失败一律当 false。 */
    boolean bool(Method method) {
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (Throwable error) {
            HmcLog.warnOnce("adapter-bool-" + method.getName(), "调用 HMC " + method.getName() + " 失败", error);
            return false;
        }
    }

    /** 调一个「无参、返回 float」的 HMC 静态入口；失败返回 NaN。 */
    float number(Method method) {
        if (method == null) return Float.NaN;
        try {
            Object value = method.invoke(null);
            return value instanceof Number ? ((Number) value).floatValue() : Float.NaN;
        } catch (Throwable error) {
            HmcLog.warnOnce("adapter-number-" + method.getName(), "调用 HMC " + method.getName() + " 失败", error);
            return Float.NaN;
        }
    }

    /** 屏幕上是否亮着；拿不到就按 true（宁可多协同一次，也不要漏掉封面）。 */
    boolean screenOnNow() {
        if (screenOn == null) return true;
        if (screenOnCached) return bool(screenOn);
        long now = SystemClock.uptimeMillis();
        long at = screenOnAt;
        if (at != 0L && now - at < 250L) return screenOnValue;
        boolean value = bool(screenOn);
        screenOnValue = value;
        screenOnAt = now;
        return value;
    }

    View[] roots() {
        if (clockRoots == null) return new View[0];
        try {
            Object value = clockRoots.invoke(null);
            return value instanceof View[] ? (View[]) value : new View[0];
        } catch (Throwable error) {
            HmcLog.warnOnce("adapter-roots", "读取 HMC 时钟树失败", error);
            return new View[0];
        }
    }

    View target(View root) {
        if (clockTarget == null || root == null) return null;
        try {
            Object value = clockTarget.invoke(null, root);
            return value instanceof View ? (View) value : null;
        } catch (Throwable error) {
            HmcLog.warnOnce("adapter-target", "读取 HMC 时钟目标失败", error);
            return null;
        }
    }

    /** 配置变化后让时钟重画：先让 HMC 自己刷新，再兜底把 TimeView 打脏。 */
    void invalidateClock() {
        requestClockRefresh();
        for (View root : roots()) {
            View target = root == null ? null : target(root);
            invalidateTree(target == null ? root : target, 0);
        }
    }

    void requestClockRefresh() {
        if (clockRefresh == null) return;
        try {
            clockRefresh.invoke(null);
        } catch (Throwable ignored) {
            // HMC 不在封面态时 refresh() 自己会早退，这里失败也无所谓。
        }
    }

    void requestNotificationSinkRefresh() {
        if (refreshNotificationSink == null) return;
        try {
            refreshNotificationSink.invoke(null);
        } catch (Throwable ignored) {
            // 同上：只是请求，不保证有视图在。
        }
    }

    private static void invalidateTree(View view, int depth) {
        if (view == null || depth > 12) return;
        try {
            view.invalidate();
        } catch (Throwable ignored) {
            // 单个视图失效不影响其它。
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            invalidateTree(group.getChildAt(i), depth + 1);
        }
    }

    private static List<Method> resolveCoverTransitions(Class<?> main) {
        if (main == null) return Collections.emptyList();
        List<Method> found = new ArrayList<>();
        for (Method method : main.getDeclaredMethods()) {
            String name = method.getName();
            if (!"enterCoverMode".equals(name) && !"exitCoverMode".equals(name)) continue;
            if (method.getParameterCount() == 0) continue;
            if (method.getParameterTypes()[0] != boolean.class) continue;
            try {
                method.setAccessible(true);
                found.add(method);
            } catch (Throwable ignored) {
                // 单个方法不可访问就跳过。
            }
        }
        return found;
    }

    private static String[] owners(String... names) {
        return names;
    }

    private static Method first(Method preferred, Method fallback) {
        return preferred != null ? preferred : fallback;
    }
}
