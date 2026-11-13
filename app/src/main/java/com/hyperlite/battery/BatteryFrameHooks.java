// 移植自 AritxOnly/HyperModifier（GPL-3.0）的 StatusBarBatteryFrameHooks。
package com.hyperlite.battery;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hyperlite.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 状态栏“无数字电池实心框”。
 *
 * <p>Hook 点（全部在 com.android.systemui.statusbar.views.MiuiBatteryMeterIconView 上）：
 * <ul>
 *   <li>{@code onFinishInflate()}：开启时跳过原生初始化，避免加载主题 drawable；同时登记 View。</li>
 *   <li>{@code onDraw(Canvas)}：开启时完全接管绘制，用打包几何画 body/进度/闪电/性能标记/内嵌数字。</li>
 *   <li>{@code clearTint()}、{@code onDarkChangeInternal()}、{@code updateResources$10()}：
 *       开启时跳过原生 tint/资源刷新，避免把系统图标又画回来；后两者顺便刷新本框缓存的状态。</li>
 *   <li>{@code onAttachedToWindow()} / {@code onDetachedFromWindow()}：维护弱引用表，附着后立即取状态。</li>
 * </ul>
 *
 * <p>所有颜色和状态都从原生字段读取，保留 HyperOS 的充电/快充/低电/省电/性能配色和进度方向。
 * 旧 Xposed API 没有 libxposed 的 chain，所以用 beforeHookedMethod 改结果或在 afterHookedMethod 做事。
 */
public final class BatteryFrameHooks {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW = SYSTEM_UI + ".statusbar.views.MiuiBatteryMeterIconView";
    private static final String DARK_ICON_DISPATCHER = SYSTEM_UI + ".plugins.DarkIconDispatcher";

    private static final String[] FIELD_NAMES = {
            "mLevel", "mCharging", "mQuickCharging", "mPerformanceMode",
            "mPowerSave", "mLow", "mUseTint", "mTintAreas", "mTintColor", "mDarkIntensity",
            "mLightColor", "mDarkColor", "mMiuiOptimizationEnabled"
    };

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean();
    private static final AtomicBoolean PERCENT_REFRESH_PENDING = new AtomicBoolean();
    /** 只在异常路径打一次日志，避免每帧刷屏。 */
    private static final AtomicBoolean BLANK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean LAYER_LOGGED = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 主线程独占；value 为 null 表示“已登记但还没建好 Frame”。 */
    private static final WeakHashMap<View, Frame> FRAMES = new WeakHashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    private static Method getTintMethod;
    private static Method isInAreasMethod;
    private static Method updateResourcesMethod;
    private static boolean lastEnabled;

    private static final Runnable CONFIG_LISTENER = BatteryFrameHooks::onConfigChanged;

    private BatteryFrameHooks() {
    }

    /** 由入口在 SystemUI 进程调用；安装失败只记录，绝不外抛。 */
    public static void install(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> iconView = Class.forName(ICON_VIEW, false, loader);
            if (!resolveFields(iconView)) {
                Log.w(Config.TAG, "MiuiBatteryMeterIconView 字段不完整，跳过实心电池框");
                return;
            }

            Class<?> dispatcher = Class.forName(DARK_ICON_DISPATCHER, false, loader);
            getTintMethod = staticMethod(dispatcher, "getTint", Collection.class, View.class, int.class);
            isInAreasMethod = staticMethod(dispatcher, "isInAreas", Collection.class, View.class);
            if (getTintMethod == null || isInAreasMethod == null) {
                // tint 取不到就无法得到状态栏当前图标色，硬画可能变成黑块，因此整块跳过。
                Log.w(Config.TAG, "DarkIconDispatcher tint 方法缺失，跳过实心电池框");
                return;
            }

            Method inflate = declaredMethod(iconView, "onFinishInflate");
            Method draw = declaredMethod(iconView, "onDraw", Canvas.class);
            Method update = declaredMethod(iconView, "onDarkChangeInternal");
            // 上游实际方法名带 $10 后缀；它是补充刷新点，缺了也能工作。
            Method refreshResources = declaredMethod(iconView, "updateResources$10");
            if (inflate == null || draw == null || update == null) {
                Log.w(Config.TAG, "MiuiBatteryMeterIconView 绘制方法不完整，跳过实心电池框");
                return;
            }
            updateResourcesMethod = refreshResources;

            // 先安装 onDraw：它失败时不能留下“onFinishInflate 已跳过、却没人绘制”的半成品。
            if (!hookMethod(draw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!enabled()) return;
                        View view = (View) param.thisObject;
                        Frame frame = FRAMES.get(view);
                        if (frame == null && view.isAttachedToWindow()) {
                            // onAttachedToWindow 可能比第一帧晚，这里补建一次，避免首帧空白。
                            update(view);
                            frame = FRAMES.get(view);
                        }
                        if (frame == null) {
                            if (BLANK_LOGGED.compareAndSet(false, true)) {
                                Log.w(Config.TAG, "实心电池框还没有可用状态，本帧跳过绘制");
                            }
                            param.setResult(null);
                            return;
                        }
                        // 原生 CLEAR 混合依赖硬件层；层类型被改时宁可跳过，也不要让 CLEAR 擦到别的 View。
                        if (view.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                            if (LAYER_LOGGED.compareAndSet(false, true)) {
                                Log.w(Config.TAG, "电池图标不是硬件层，跳过实心框绘制（layerType="
                                        + view.getLayerType() + "）");
                            }
                            param.setResult(null);
                            return;
                        }
                        frame.draw((Canvas) param.args[0], view.getWidth(), view.getHeight());
                        param.setResult(null);
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "onDraw hook failed", error);
                    }
                }
            })) {
                Log.w(Config.TAG, "onDraw hook 安装失败，跳过实心电池框");
                return;
            }

            hookMethod(inflate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View view = (View) param.thisObject;
                        if (!FRAMES.containsKey(view)) FRAMES.put(view, null);
                        lastEnabled = enabled();
                        if (lastEnabled) param.setResult(null);
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "onFinishInflate hook failed", error);
                    }
                }
            });

            Method clearTint = declaredMethod(iconView, "clearTint");
            if (clearTint != null) {
                hookMethod(clearTint, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (enabled()) param.setResult(null);
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "clearTint hook failed", error);
                        }
                    }
                });
            }

            hookMethod(update, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!enabled()) return;
                        update((View) param.thisObject);
                        param.setResult(null);
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "onDarkChangeInternal hook failed", error);
                    }
                }
            });

            if (refreshResources != null) {
                hookMethod(refreshResources, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!enabled()) return;
                            update((View) param.thisObject);
                            param.setResult(null);
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "updateResources hook failed", error);
                        }
                    }
                });
            }

            Method attach = declaredMethod(iconView, "onAttachedToWindow");
            if (attach != null) {
                hookMethod(attach, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View view = (View) param.thisObject;
                            lastEnabled = enabled();
                            if (!FRAMES.containsKey(view)) FRAMES.put(view, null);
                            if (lastEnabled) update(view);
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "onAttachedToWindow hook failed", error);
                        }
                    }
                });
            }

            Method detach = declaredMethod(iconView, "onDetachedFromWindow");
            if (detach != null) {
                hookMethod(detach, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            FRAMES.remove(param.thisObject);
                        } catch (Throwable error) {
                            Log.w(Config.TAG, "onDetachedFromWindow hook failed", error);
                        }
                    }
                });
            }

            Config.addOnChange(CONFIG_LISTENER);
            Log.i(Config.TAG, "实心电池框 hook 已安装");
        } catch (Throwable error) {
            Log.w(Config.TAG, "安装实心电池框 hook 失败", error);
        }
    }

    private static boolean enabled() {
        try {
            return BatterySettings.solidFrame();
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean resolveFields(Class<?> type) {
        for (String name : FIELD_NAMES) {
            Field field = findField(type, name);
            if (field == null) {
                Log.w(Config.TAG, "MiuiBatteryMeterIconView 缺少字段 " + name);
                return false;
            }
            FIELDS.put(name, field);
        }
        return true;
    }

    /** 字段允许来自父类；和 hook 方法不同，字段查找不会造成全局副作用。 */
    private static Field findField(Class<?> type, String name) {
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
        return null;
    }

    /** 只取类自己声明的方法；绝不能回退到 View 基类，否则会变成全局 View hook。 */
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

    private static Method staticMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            if (!Modifier.isStatic(method.getModifiers())) {
                Log.w(Config.TAG, "DarkIconDispatcher#" + name + " 不是静态方法，跳过");
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            Log.w(Config.TAG, "DarkIconDispatcher#" + name + " 不存在", error);
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

    private static boolean flag(View view, String name) {
        Field field = FIELDS.get(name);
        if (field == null) return false;
        try {
            return field.getBoolean(view);
        } catch (Throwable error) {
            return false;
        }
    }

    private static int number(View view, String name, int fallback) {
        Field field = FIELDS.get(name);
        if (field == null) return fallback;
        try {
            return field.getInt(view);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static float decimal(View view, String name, float fallback) {
        Field field = FIELDS.get(name);
        if (field == null) return fallback;
        try {
            return field.getFloat(view);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static Collection<?> collection(View view, String name) {
        Field field = FIELDS.get(name);
        if (field == null) return null;
        try {
            Object value = field.get(view);
            return value instanceof Collection ? (Collection<?>) value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static void update(View view) {
        if (!view.isAttachedToWindow()) return;
        Frame frame = FRAMES.get(view);
        try {
            if (frame == null) {
                frame = new Frame(view);
                FRAMES.put(view, frame);
            }
            boolean charging = flag(view, "mCharging");
            boolean quick = flag(view, "mQuickCharging");
            boolean performance = flag(view, "mPerformanceMode");
            boolean tint = flag(view, "mUseTint");
            Collection<?> areas = collection(view, "mTintAreas");
            float darkIntensity = decimal(view, "mDarkIntensity", 0f);

            boolean inDarkArea = false;
            if (areas != null) {
                try {
                    Object value = isInAreasMethod.invoke(null, areas, view);
                    inDarkArea = value instanceof Boolean && (Boolean) value;
                } catch (Throwable error) {
                    Log.w(Config.TAG, "DarkIconDispatcher.isInAreas 调用失败", error);
                }
            }

            int base = Frame.color(view, inDarkArea && darkIntensity > 0f
                    ? frame.darkColorId : frame.lightColorId, Color.WHITE);
            int normal = base;
            if (tint) {
                if (areas != null) {
                    try {
                        Object value = getTintMethod.invoke(null, areas, view, number(view, "mTintColor", 0));
                        if (value instanceof Integer) base = (Integer) value;
                    } catch (Throwable error) {
                        Log.w(Config.TAG, "DarkIconDispatcher.getTint 调用失败", error);
                    }
                }
                normal = number(view, "mLightColor", normal);
                if (inDarkArea) {
                    normal = BatteryFramePolicy.blendARGB(
                            normal, number(view, "mDarkColor", normal), darkIntensity);
                }
            }

            int fill = normal;
            if (!tint || flag(view, "mMiuiOptimizationEnabled")) {
                if (quick || charging) fill = Frame.color(view, frame.chargingColorId, normal);
                else if (flag(view, "mPowerSave")) fill = Frame.color(view, frame.powerSaveColorId, normal);
                else if (performance) fill = Frame.color(view, frame.performanceColorId, normal);
                else if (flag(view, "mLow")) fill = Frame.color(view, frame.lowColorId, normal);
            }

            frame.configure(number(view, "mLevel", 0), base, fill, charging, quick, performance,
                    frame.progressStartsAtStart(view),
                    BatteryPercentHooks.shouldShowPercentInside());
            view.invalidate();
        } catch (Throwable error) {
            FRAMES.put(view, null);
            Log.w(Config.TAG, "刷新实心电池框失败", error);
        }
    }

    private static void restoreSystemResources(View view) {
        if (updateResourcesMethod == null) return;
        try {
            updateResourcesMethod.invoke(view);
        } catch (Throwable error) {
            Log.w(Config.TAG, "恢复原生电池资源失败", error);
        }
    }

    private static void onConfigChanged() {
        refresh();
    }

    /**
     * 设置变化后统一刷新。开关从关到开时重建 Frame；从开到关时调用原生
     * updateResources$10 恢复系统 drawable，再让下一帧走原生 onDraw。
     */
    private static void refresh() {
        if (!REFRESH_PENDING.compareAndSet(false, true)) return;
        MAIN.post(() -> {
            REFRESH_PENDING.set(false);
            try {
                boolean active = enabled();
                if (active == lastEnabled) return;
                lastEnabled = active;
                for (View view : new ArrayList<>(FRAMES.keySet())) {
                    if (view == null) continue;
                    if (active) {
                        update(view);
                    } else {
                        restoreSystemResources(view);
                        FRAMES.put(view, null);
                        view.invalidate();
                    }
                }
            } catch (Throwable error) {
                Log.w(Config.TAG, "刷新实心电池框设置失败", error);
            }
        });
    }

    /**
     * 控制中心展开/收起时，只重新配置已存在的图标，让内嵌数字出现或消失。
     * 与 {@link BatteryPercentHooks} 同在主线程调用，避免循环初始化。
     */
    static void refreshControlCenterPercent() {
        if (!enabled()) return;
        if (!PERCENT_REFRESH_PENDING.compareAndSet(false, true)) return;
        Runnable task = () -> {
            PERCENT_REFRESH_PENDING.set(false);
            try {
                for (View view : new ArrayList<>(FRAMES.keySet())) {
                    if (view != null) update(view);
                }
            } catch (Throwable error) {
                Log.w(Config.TAG, "刷新内嵌百分比失败", error);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else MAIN.post(task);
    }

    /** 每个图标一份的绘制缓存；不持有 View/Context，避免 WeakHashMap 的 value 反向强引用 key。 */
    private static final class Frame {
        final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint markClear = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint percentClear = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint percentTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path mark;
        final Path mask = new Path();
        final Path filled = new Path();
        final Path empty = new Path();
        final int lightColorId;
        final int darkColorId;
        final int chargingColorId;
        final int lowColorId;
        final int powerSaveColorId;
        final int performanceColorId;
        final int gravityStartId;
        int lastLevel = -1;
        boolean lastStart;
        boolean percentInside;
        String levelText = "";
        final float percentBaseline;

        Frame(View view) {
            // 与 MiuiHollowBatteryMeterIconView 一样的透明擦除：先用 CLEAR 连描边一起擦掉，
            // 再用低透明度对比色只补回字形内部。
            markClear.setBlendMode(BlendMode.CLEAR);
            markClear.setStyle(Paint.Style.FILL_AND_STROKE);
            markClear.setStrokeWidth(2f);
            markClear.setStrokeJoin(Paint.Join.ROUND);
            markClear.setStrokeCap(Paint.Cap.ROUND);
            percentClear.setBlendMode(BlendMode.CLEAR);
            percentClear.setTextAlign(Paint.Align.CENTER);
            percentClear.setTextSize(8.6f);
            percentClear.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            percentTint.setTextAlign(Paint.Align.CENTER);
            percentTint.setTextSize(8.6f);
            percentTint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            Paint.FontMetrics metrics = percentClear.getFontMetrics();
            percentBaseline = -(metrics.ascent + metrics.descent) / 2f;

            lightColorId = id(view, "light_mode_icon_color_single_tone", "color");
            darkColorId = id(view, "dark_mode_icon_color_single_tone", "color");
            chargingColorId = id(view, "status_bar_battery_charging", "color");
            lowColorId = id(view, "status_bar_battery_low", "color");
            powerSaveColorId = id(view, "status_bar_battery_power_save", "color");
            performanceColorId = id(view, "status_bar_battery_performance", "color");
            gravityStartId = id(view, "battery_meter_progress_gravity_start", "bool");
        }

        private static int id(View view, String name, String type) {
            try {
                return view.getResources().getIdentifier(name, type, SYSTEM_UI);
            } catch (Throwable error) {
                return 0;
            }
        }

        static int color(View view, int id, int fallback) {
            if (id == 0) return fallback;
            try {
                return view.getResources().getColor(id, view.getContext().getTheme());
            } catch (Throwable error) {
                return fallback;
            }
        }

        boolean progressStartsAtStart(View view) {
            if (gravityStartId == 0) return false;
            try {
                return view.getResources().getBoolean(gravityStartId);
            } catch (Throwable error) {
                return false;
            }
        }

        void configure(int level, int base, int fill, boolean charging, boolean quick,
                       boolean performance, boolean start, boolean percentInside) {
            background.setColor(base);
            background.setAlpha(Math.round(Color.alpha(base) * 0.4f));
            foreground.setColor(fill);
            markFill.setColor(base);

            int contrast = BatteryFramePolicy.calculateLuminance(base) > 0.5
                    ? Color.BLACK : Color.WHITE;
            percentTint.setColor(Color.argb(26,
                    Color.red(contrast), Color.green(contrast), Color.blue(contrast)));

            this.percentInside = percentInside;
            if (level != lastLevel) levelText = String.valueOf(level);
            mark = BatteryFrameGeometry.mark(charging, quick, performance);
            if (level == lastLevel && start == lastStart) return;

            Path body = BatteryFrameGeometry.body();
            float shift = BatteryFramePolicy.maskShift(level, start);
            mask.rewind();
            mask.addRoundRect(2.6f + shift, 3.88f, 25.594f + shift, 16.12f,
                    1f, 1f, Path.Direction.CW);
            if (!filled.op(body, mask, Path.Op.INTERSECT)
                    || !empty.op(body, mask, Path.Op.DIFFERENCE)) {
                throw new IllegalStateException("battery level path");
            }
            lastLevel = level;
            lastStart = start;
        }

        void draw(Canvas canvas, int width, int height) {
            if (width <= 0 || height <= 0) return;
            int checkpoint = canvas.save();
            try {
                canvas.scale(width / 28f, height / 20f);
                // 围绕中心再收一点，保持状态栏布局间距不变。
                canvas.scale(BatteryFramePolicy.BODY_WIDTH_SCALE,
                        BatteryFramePolicy.BODY_HEIGHT_SCALE, 14f, 10f);
                canvas.drawPath(empty, background);
                canvas.drawPath(filled, foreground);
                if (mark != null) {
                    canvas.drawPath(mark, markClear);
                    canvas.drawPath(mark, markFill);
                }
                if (percentInside && !levelText.isEmpty()) {
                    float baseline = 10f + percentBaseline;
                    canvas.drawText(levelText, 14f, baseline, percentClear);
                    canvas.drawText(levelText, 14f, baseline, percentTint);
                }
            } finally {
                canvas.restoreToCount(checkpoint);
            }
        }
    }
}
