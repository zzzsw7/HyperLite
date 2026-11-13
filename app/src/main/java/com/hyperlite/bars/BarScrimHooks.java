package com.hyperlite.bars;

import android.graphics.Canvas;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.hyperlite.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 状态栏 / 导航栏「半透明遮罩」分开控制。
 *
 * <p>HyperOS 的系统栏背景不是普通 View 背景，而是 {@code BarTransitions} 里的
 * {@code BarBackgroundDrawable} 画的：半透明态（{@code mMode == 1/2}）用它从主题读到的
 * {@code #66000000}（资源名 {@code status_bar_background_semi_transparent}）铺一层 40% 黑。
 * 横屏看视频时，屏幕上沿（状态栏）和下沿（手势条）那两条灰带就是它。
 *
 * <p>状态栏用 {@code PhoneStatusBarTransitions}，导航栏用 {@code NavigationBarTransitions}，
 * 二者共用同一个 drawable 类，所以这里在 {@code BarTransitions} 构造时记下「这个 drawable 属于谁」，
 * 绘制时再按各自的开关决定要不要跳过这一帧。
 */
public final class BarScrimHooks {

    /** 去掉状态栏那条半透明黑。 */
    public static final String KEY_STATUS = "status_bar_scrim_off";
    /** 去掉导航栏 / 手势条那条半透明黑。 */
    public static final String KEY_NAV = "nav_bar_scrim_off";

    /** BarTransitions 的半透明态：1 = TRANSLUCENT，2 = SEMI_TRANSPARENT。 */
    private static final int MODE_TRANSLUCENT = 1;
    private static final int MODE_SEMI_TRANSPARENT = 2;

    private static final String BAR_TRANSITIONS =
            "com.android.systemui.shared.statusbar.phone.BarTransitions";
    private static final String BAR_BACKGROUND_DRAWABLE = BAR_TRANSITIONS + "$BarBackgroundDrawable";
    private static final String NAV_TRANSITIONS_NAME = "NavigationBarTransitions";

    /** drawable -> 是否属于导航栏。主线程写、绘制线程读，用同步包装的 WeakHashMap。 */
    private static final Map<Object, Boolean> OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BarScrimHooks() {
    }

    static boolean statusEnabled() {
        return Config.getBoolean(KEY_STATUS, true);
    }

    static boolean navEnabled() {
        return Config.getBoolean(KEY_NAV, true);
    }

    /** 由入口在 SystemUI 进程调用；安装失败只记录，不影响系统栏本身。 */
    public static void install(ClassLoader loader) {
        try {
            Class<?> transitions = Class.forName(BAR_TRANSITIONS, false, loader);
            Class<?> drawable = Class.forName(BAR_BACKGROUND_DRAWABLE, false, loader);

            Field barBackground = transitions.getDeclaredField("mBarBackground");
            barBackground.setAccessible(true);
            Field mode = drawable.getDeclaredField("mMode");
            mode.setAccessible(true);
            Method draw = drawable.getDeclaredMethod("draw", Canvas.class);

            // BarTransitions 是抽象类，子类构造时一定会先走它的构造器，因此这里能拿到每个实例。
            XposedBridge.hookAllConstructors(transitions, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Object background = barBackground.get(self);
                        if (background == null) return;
                        boolean nav = self.getClass().getName().contains(NAV_TRANSITIONS_NAME);
                        OWNERS.put(background, nav);
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "bar scrim owner tracking failed", error);
                    }
                }
            });

            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Boolean nav = OWNERS.get(self);
                        if (nav == null) return; // 不认识的 BarTransitions：不动
                        if (!(nav ? navEnabled() : statusEnabled())) return;
                        int current = mode.getInt(self);
                        if (current == MODE_TRANSLUCENT || current == MODE_SEMI_TRANSPARENT) {
                            // 半透明态整层不画：背景完全透出应用内容。
                            param.setResult(null);
                            debugLog(nav, current, true);
                        } else {
                            debugLog(nav, current, false);
                        }
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "bar scrim draw hook failed", error);
                    }
                }
            });
            Log.i(Config.TAG, "系统栏遮罩 hook 已安装（状态栏/导航栏独立）");
        } catch (Throwable error) {
            Log.w(Config.TAG, "系统栏遮罩 hook 安装失败", error);
        }
    }

    /** 每种 (nav, mode, skip) 组合只记一次，方便现场确认到底是哪个模式在画。 */
    private static final java.util.Set<String> LOGGED =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void debugLog(boolean nav, int mode, boolean skipped) {
        String key = nav + "/" + mode + "/" + skipped;
        if (!LOGGED.add(key)) return;
        Log.i(Config.TAG, "bar scrim: navBar=" + nav + " mode=" + mode + " skipped=" + skipped);
    }
}
