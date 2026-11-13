// 移植自 AritxOnly/HyperModifier（GPL-3.0）的 StatusBarBatteryIconSizeHooks。
package com.hyperlite.battery;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hyperlite.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 状态栏电池图标尺寸。
 *
 * <p>Hook 点是 MiuiBatteryMeterView（不是布局父容器）：
 * <ul>
 *   <li>{@code onLayout(boolean,int,int,int,int)}：原生布局完成后，对 mBatteryDigitalView /
 *       mBatteryChargingView 两个子 View 做以中心为轴心的 scaleX/scaleY。布局尺寸完全不动，
 *       所以状态栏其它元素不会被挤开，也不会改变点击区域。</li>
 *   <li>{@code onAttachedToWindow()}：登记并尽量早应用一次；此时宽度可能还是 0，等 onLayout 再补。</li>
 *   <li>{@code onDetachedFromWindow()}：恢复原始 scale 和 clip 设置，避免复用 View 时残留。</li>
 * </ul>
 *
 * <p>上游用“倍数”设置；本模块的设置键是「相对原生的百分比」（50~300，100 = 原生），
 * 直接换算成倍数后在 0.5x~3.0x 之间收口。0 或 100 表示保持原生。
 */
public final class BatteryIconSizeHooks {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String METER_VIEW = SYSTEM_UI + ".statusbar.views.MiuiBatteryMeterView";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Binding> VIEWS = new WeakHashMap<>();
    private static final Runnable CONFIG_LISTENER = BatteryIconSizeHooks::refresh;

    private static Field digitalViewField;
    private static Field chargingViewField;

    private BatteryIconSizeHooks() {
    }

    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> meterView = Class.forName(METER_VIEW, false, loader);
            digitalViewField = declaredField(meterView, "mBatteryDigitalView");
            chargingViewField = declaredField(meterView, "mBatteryChargingView");
            if (digitalViewField == null && chargingViewField == null) {
                Log.w(Config.TAG, "MiuiBatteryMeterView 没有电池子 View 字段，跳过图标尺寸");
                return;
            }

            Method layout = declaredMethod(meterView, "onLayout",
                    boolean.class, int.class, int.class, int.class, int.class);
            if (layout == null) {
                Log.w(Config.TAG, "MiuiBatteryMeterView#onLayout 不存在，跳过图标尺寸");
                return;
            }

            if (!hookMethod(layout, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        Binding binding = VIEWS.get(view);
                        if (binding == null) binding = remember(view);
                        apply(view, binding);
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "onLayout 调整电池尺寸失败", error);
                    }
                }
            })) {
                Log.w(Config.TAG, "onLayout hook 安装失败，跳过图标尺寸");
                return;
            }
            Method attach = declaredMethod(meterView, "onAttachedToWindow");
            if (attach != null) {
                hookMethod(attach, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View view = (View) param.thisObject;
                            apply(view, remember(view));
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "onAttachedToWindow 调整电池尺寸失败", error);
                        }
                    }
                });
            }

            Method detach = declaredMethod(meterView, "onDetachedFromWindow");
            if (detach != null) {
                hookMethod(detach, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            View view = (View) param.thisObject;
                            Binding binding = VIEWS.remove(view);
                            if (binding != null) restore(view, binding);
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "onDetachedFromWindow 恢复电池尺寸失败", error);
                        }
                    }
                });
            }

            Config.addOnChange(CONFIG_LISTENER);
            Log.i(Config.TAG, "电池图标尺寸 hook 已安装");
        } catch (Throwable error) {
            Log.w(Config.TAG, "安装电池图标尺寸 hook 失败", error);
        }
    }

    private static Field declaredField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                // 继续往父类找。
            }
        }
        Log.w(Config.TAG, "MiuiBatteryMeterView 缺少字段 " + name);
        return null;
    }

    /** 只取类自己声明的方法，避免回退到 View/ViewGroup 造成全局 hook。 */
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

    private static boolean hookMethod(Method method, XC_MethodHook callback) {
        try {
            XposedBridge.hookMethod(method, callback);
            return true;
        } catch (Throwable error) {
            Log.w(Config.TAG, "hook " + method + " 失败", error);
            return false;
        }
    }

    private static Binding remember(View view) {
        Binding binding = VIEWS.get(view);
        if (binding == null) {
            binding = new Binding(view);
            VIEWS.put(view, binding);
        }
        return binding;
    }

    private static void apply(View view, Binding binding) {
        if (binding.failed) return;
        try {
            int percent = BatterySettings.iconScalePercent();
            if (percent <= 0) {
                restore(view, binding);
                return;
            }
            if (!(view instanceof ViewGroup)) return;

            View digital = child(digitalViewField, view);
            View charging = child(chargingViewField, view);
            if (digital == null && charging == null) return;

            float scale = BatteryIconSizePolicy.resolveScale(percent);
            if (Math.abs(scale - BatteryIconSizePolicy.DEFAULT_SCALE) < 0.0001f) {
                restore(view, binding);
                return;
            }

            ViewGroup group = (ViewGroup) view;
            binding.applied = true;
            group.setClipChildren(false);
            group.setClipToPadding(false);

            scaleChild(digital, binding.originalDigitalScaleX, binding.originalDigitalScaleY, scale);
            scaleChild(charging, binding.originalChargingScaleX, binding.originalChargingScaleY, scale);
        } catch (Throwable error) {
            binding.failed = true;
            try {
                restore(view, binding);
            } catch (Throwable ignored) {
                // 恢复失败也不能外抛。
            }
            Log.w(Config.TAG, "应用电池图标尺寸失败", error);
        }
    }

    private static void scaleChild(View child, float originalScaleX, float originalScaleY, float scale) {
        if (child == null) return;
        child.setPivotX(child.getWidth() / 2f);
        child.setPivotY(child.getHeight() / 2f);
        child.setScaleX(BatteryIconSizePolicy.scaledValue(originalScaleX, scale));
        child.setScaleY(BatteryIconSizePolicy.scaledValue(originalScaleY, scale));
        child.invalidate();
    }

    private static void restore(View view, Binding binding) {
        if (!binding.applied) return;
        try {
            restoreChild(child(digitalViewField, view),
                    binding.originalDigitalScaleX, binding.originalDigitalScaleY);
            restoreChild(child(chargingViewField, view),
                    binding.originalChargingScaleX, binding.originalChargingScaleY);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                group.setClipChildren(binding.originalClipChildren);
                group.setClipToPadding(binding.originalClipToPadding);
            }
            binding.applied = false;
        } catch (Throwable error) {
            Log.w(Config.TAG, "恢复电池图标尺寸失败", error);
        }
    }

    private static void restoreChild(View child, float scaleX, float scaleY) {
        if (child == null) return;
        child.setScaleX(scaleX);
        child.setScaleY(scaleY);
        child.invalidate();
    }

    private static View child(Field field, Object target) {
        if (field == null) return null;
        try {
            Object value = field.get(target);
            return value instanceof View ? (View) value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 设置变化后按新百分比重新缩放；改成 0 或 100 时恢复原生。 */
    private static void refresh() {
        if (!REFRESH_PENDING.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            REFRESH_PENDING.set(false);
            try {
                for (Map.Entry<View, Binding> entry : new ArrayList<>(VIEWS.entrySet())) {
                    View view = entry.getKey();
                    if (view != null) apply(view, entry.getValue());
                }
            } catch (Throwable error) {
                Log.w(Config.TAG, "刷新电池图标尺寸设置失败", error);
            }
        });
    }

    /** 记住原生 scale 和 clip 设置，关闭功能时精确还原。 */
    private static final class Binding {
        final float originalDigitalScaleX;
        final float originalDigitalScaleY;
        final float originalChargingScaleX;
        final float originalChargingScaleY;
        final boolean originalClipChildren;
        final boolean originalClipToPadding;
        boolean applied;
        boolean failed;

        Binding(View view) {
            View digital = child(digitalViewField, view);
            View charging = child(chargingViewField, view);
            originalDigitalScaleX = digital == null ? 1f : digital.getScaleX();
            originalDigitalScaleY = digital == null ? 1f : digital.getScaleY();
            originalChargingScaleX = charging == null ? 1f : charging.getScaleX();
            originalChargingScaleY = charging == null ? 1f : charging.getScaleY();

            ViewGroup group = view instanceof ViewGroup ? (ViewGroup) view : null;
            originalClipChildren = group == null || group.getClipChildren();
            originalClipToPadding = group == null || group.getClipToPadding();
        }
    }
}
