// 移植自 AritxOnly/HyperModifier（GPL-3.0）的 ControlCenterBatteryPercentHooks。
package com.hyperlite.battery;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hyperlite.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 控制中心电池百分比：展开控制中心时，把电量数字画进实心电池框。
 *
 * <p>本移植只保留“图标内嵌”形态。数字本身由 {@link BatteryFrameHooks} 的 Frame 绘制，
 * 这里只负责判断控制中心面板是否处于展开状态，并对外提供
 * {@link #shouldShowPercentInside()}。
 *
 * <p>面板状态来源（按上游契约）：
 * <ul>
 *   <li>{@code ControlCenterContainerController$onExpandChangeListener$1#onExpandStateChanged(ExpandState)}：
 *       最完整的插件状态，能区分 EXPANDING 的方向。</li>
 *   <li>{@code ControlCenterContainerController#onContentAttached/onContentDetached(ControlCenterContent)}：
 *       内容挂载时补一次状态，卸载时视为收起。</li>
 *   <li>{@code ControlCenterContent#getExpandState()}：作为版本差异时的兜底，每次取状态后同步面板状态。</li>
 * </ul>
 */
public final class BatteryPercentHooks {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CONTENT =
            SYSTEM_UI + ".plugins.miui.controlcenter.ControlCenterContent";
    private static final String CONTAINER =
            "com.miui.systemui.controlcenter.container.ControlCenterContainerController";
    /** 上游依赖的匿名监听器类名；HyperOS 改内部类编号时会找不到，此时退到 getExpandState 兜底。 */
    private static final String EXPAND_LISTENER = CONTAINER + "$onExpandChangeListener$1";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final BatteryPercentPolicy.PanelState PANEL = new BatteryPercentPolicy.PanelState();
    private static final Runnable CONFIG_LISTENER = BatteryPercentHooks::refresh;

    private static volatile Object expandingState;
    private static volatile Object expandedState;
    private static Method getExpandStateMethod;

    private BatteryPercentHooks() {
    }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> phase = Class.forName(CONTENT + "$ExpandState", false, loader);
            expandingState = staticField(phase, "EXPANDING");
            expandedState = staticField(phase, "EXPANDED");
            if (expandingState == null || expandedState == null) {
                Log.w(Config.TAG, "ControlCenterContent$ExpandState 常量缺失，跳过控制中心电量");
                return;
            }

            boolean hooked = false;
            boolean stateSource = false;

            try {
                Class<?> listener = Class.forName(EXPAND_LISTENER, false, loader);
                if (!hasMethodNamed(listener, "onExpandStateChanged")) {
                    throw new NoSuchMethodException(EXPAND_LISTENER + "#onExpandStateChanged");
                }
                XposedBridge.hookAllMethods(listener, "onExpandStateChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object state = param.args.length > 0 ? param.args[0] : null;
                            if (PANEL.update(phaseOf(state))) requestFrameRefresh();
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "onExpandStateChanged hook failed", error);
                        }
                    }
                });
                hooked = true;
                stateSource = true;
            } catch (Throwable error) {
                Log.w(Config.TAG, "找不到控制中心展开监听器，改用 getExpandState 兜底", error);
            }

            Class<?> content = Class.forName(CONTENT, false, loader);
            getExpandStateMethod = declaredMethod(content, "getExpandState");
            if (getExpandStateMethod != null && hookMethod(getExpandStateMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (PANEL.update(phaseOf(param.getResult()))) requestFrameRefresh();
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "getExpandState hook failed", error);
                    }
                }
            })) {
                hooked = true;
                stateSource = true;
            }

            try {
                Class<?> container = Class.forName(CONTAINER, false, loader);
                Method attached = declaredMethod(container, "onContentAttached", content);
                if (attached != null
                        && hookMethod(attached, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    int phase = contentPhase(param.args.length > 0 ? param.args[0] : null);
                                    if (PANEL.update(phase)) requestFrameRefresh();
                                } catch (Throwable error) {
                                    Log.w(Config.TAG, "onContentAttached hook failed", error);
                                }
                            }
                        })) {
                    hooked = true;
                }
                Method detached = declaredMethod(container, "onContentDetached", content);
                if (detached != null
                        && hookMethod(detached, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (PANEL.update(BatteryPercentPolicy.COLLAPSED)) requestFrameRefresh();
                                } catch (Throwable error) {
                                    Log.w(Config.TAG, "onContentDetached hook failed", error);
                                }
                            }
                        })) {
                    hooked = true;
                }
            } catch (Throwable error) {
                Log.w(Config.TAG, "控制中心容器类不可用，展开状态只能靠监听器", error);
            }

            if (!hooked) {
                Log.w(Config.TAG, "控制中心电量没有可用的状态 hook，保持关闭");
                return;
            }
            if (!stateSource) {
                Log.w(Config.TAG, "控制中心电量没有展开状态来源，保持关闭");
                return;
            }

            Config.addOnChange(CONFIG_LISTENER);
            Log.i(Config.TAG, "控制中心电池百分比 hook 已安装");
        } catch (Throwable error) {
            Log.w(Config.TAG, "安装控制中心电量 hook 失败", error);
        }
    }

    /**
     * 供 BatteryFrameHooks 在绘制时判断是否画内嵌数字。
     *
     * <p>同包直接静态调用；本方法不引用 BatteryFrameHooks，避免两个类的静态初始化互相等待。
     */
    public static boolean shouldShowPercentInside() {
        try {
            return BatteryPercentPolicy.drawsInside(
                    BatterySettings.solidFrame(), BatterySettings.percentInside())
                    && PANEL.showing();
        } catch (Throwable error) {
            Log.w(Config.TAG, "读取控制中心电量状态失败", error);
            return false;
        }
    }

    private static int phaseOf(Object state) {
        if (state == null) return BatteryPercentPolicy.COLLAPSED;
        if (Objects.equals(state, expandedState)) return BatteryPercentPolicy.EXPANDED;
        if (Objects.equals(state, expandingState)) return BatteryPercentPolicy.EXPANDING;
        return BatteryPercentPolicy.COLLAPSED;
    }

    private static int contentPhase(Object content) {
        if (content == null || getExpandStateMethod == null) return BatteryPercentPolicy.COLLAPSED;
        try {
            return phaseOf(getExpandStateMethod.invoke(content));
        } catch (Throwable error) {
            Log.w(Config.TAG, "读取 ControlCenterContent 展开状态失败", error);
            return BatteryPercentPolicy.COLLAPSED;
        }
    }

    private static Field staticField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable error) {
            Log.w(Config.TAG, "静态字段不存在: " + type.getSimpleName() + "#" + name, error);
            return null;
        }
    }

    /** 只取类自己声明的方法；不要回退到父类，避免 hook 到全局方法。 */
    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            Log.w(Config.TAG, "方法不存在: " + type.getSimpleName() + "#" + name, error);
            return null;
        }
    }

    private static boolean hasMethodNamed(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) return true;
        }
        return false;
    }

    private static boolean hookMethod(Method method, XC_MethodHook callback) {
        if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
            // 接口/抽象方法无法 hook（ControlCenterContent#getExpandState 就是接口方法），
            // 直接跳过，交给其它实现点兜底。
            return false;
        }
        try {
            XposedBridge.hookMethod(method, callback);
            return true;
        } catch (Throwable error) {
            Log.w(Config.TAG, "hook " + method + " 失败", error);
            return false;
        }
    }

    private static void requestFrameRefresh() {
        try {
            BatteryFrameHooks.refreshControlCenterPercent();
        } catch (Throwable error) {
            Log.w(Config.TAG, "请求刷新内嵌电量失败", error);
        }
    }

    private static void refresh() {
        if (!REFRESH_PENDING.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            REFRESH_PENDING.set(false);
            requestFrameRefresh();
        });
    }
}
