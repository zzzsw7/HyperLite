package com.hyperlite.hmc;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 时钟高度协同。
 *
 * <p>HMC 的「时钟高度」最终由 {@code Settings.CLOCK_HEIGHT}（dp）换算：封面时钟的缩放比例 =
 * 高度 * density / 完整字形高度，而 {@code Settings.CLOCK_SIZE} 一旦被设成数字就优先于高度。
 * 所以这里不碰 HMC 的视图树，只在它「握着时钟摆姿态」的窗口里拦它读取高度的地方，
 * 返回扩展配置的 px 换算出的 dp。
 * 这比逐帧改写 TimeView 的字体轴安全得多：HMC 自己的排版、收缩动画、AOD 姿态全部不用重算，
 * 也不会出现字形溢出视图被裁掉的问题。
 *
 * <p>两个已知限制：
 * <ul>
 *   <li>HMC 的「尺寸」滑块（CLOCK_SIZE）优先于高度。若用户在 HMC 里显式设过尺寸，本扩展的高度
 *       不会生效。这里不把 CLOCK_SIZE 伪装成 NaN——那会让 HMC 在下一次保存状态时把用户的尺寸
 *       擦掉，代价比「高度不生效」大。</li>
 *   <li>上游的「原生字体高度」是另一套东西：它直接改 TimeView 的 HGHT/WDTH 变量字轴并按
 *       {@code HyperMusicCoverFontShapePolicy} 解比例。那需要逐帧写 OEM 视图字段并重算分段钟表的
 *       x/advance，本移植没有把握，见下面的 TODO。</li>
 * </ul>
 */
final class HmcClockHooks {

    /** 换算成 dp 后的安全范围：太小的值会让 HMC 的缩放变负，太大则直接糊出屏幕。 */
    private static final float MIN_DP = 8f;
    private static final float MAX_DP = 200f;

    private static volatile float density = 3f;
    private static volatile boolean unitReported;

    private HmcClockHooks() {
    }

    static void install(HmcAdapter adapter, ClassLoader systemLoader) {
        hookClockHeight(adapter);
        hookGlyphUnitReport(adapter);
        // TODO 字体高度（HMC 的 HGHT/WDTH 变量字轴整形）未移植：
        //  上游 HyperMusicCoverFontHeight 会在 TimeView.onDraw 前写入 height/width/weight/textSizePx，
        //  再用 HyperMusicCoverClockLayout 重排 all_in_one 的分段钟表。本移植只有一个 px 高度键，
        //  走上面 CLOCK_HEIGHT 的路线即可达成「改高度」，而字轴整形的收益（字重/字宽比例）
        //  不足以承担逐帧改 OEM 私有字段的风险。
    }

    private static void hookClockHeight(HmcAdapter adapter) {
        try {
            Class<?> settings = HmcSymbols.load(HmcSymbols.SETTINGS, adapter.loader);
            if (settings == null) return;
            Object heightSetting = HmcSymbols.staticValue(HmcSymbols.fieldIn(settings, "CLOCK_HEIGHT"));
            if (heightSetting == null) return;
            Class<?> real = heightSetting.getClass();
            Method getter = HmcSymbols.numericGetter(real, "get");
            if (getter == null || getter.getParameterCount() != 0) return;
            density = density();

            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject != heightSetting) return;
                        if (!HmcState.clockHeightOn()) return;
                        float dp = clamp(HmcState.clockHeightPx() / density);
                        param.setResult(number(dp, getter.getReturnType()));
                    } catch (Throwable error) {
                        HmcLog.warnOnce("clock-height", "覆盖 HMC 时钟高度失败", error);
                    }
                }
            });
            HmcSymbols.deoptimize(getter);
        } catch (Throwable error) {
            HmcLog.warnOnce("clock-height-install", "安装 HMC 时钟高度 Hook 失败", error);
        }
    }

    /**
     * 只做一次诊断：把 HMC 实测的字形行高和扩展目标一起打出来，方便判断设置值是否合理。
     * 这里不修改返回值——保留 HMC 自己的测量结果，排版才不会歪。
     */
    private static void hookGlyphUnitReport(HmcAdapter adapter) {
        Method glyphUnit = adapter.glyphUnit;
        if (glyphUnit == null) return;
        try {
            XposedBridge.hookMethod(glyphUnit, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (unitReported || !HmcState.clockHeightOn()) return;
                        Object value = param.getResult();
                        if (!(value instanceof Number)) return;
                        unitReported = true;
                        HmcLog.info("HMC 时钟高度目标 " + HmcState.clockHeightPx()
                                + "px，当前字形行高 " + Math.round(((Number) value).floatValue()) + "px");
                    } catch (Throwable error) {
                        HmcLog.warnOnce("clock-unit", "读取 HMC 字形高度失败", error);
                    }
                }
            });
            HmcSymbols.deoptimize(glyphUnit);
        } catch (Throwable error) {
            HmcLog.warnOnce("clock-unit-install", "安装 HMC 字形高度诊断失败", error);
        }
    }

    private static float clamp(float dp) {
        if (!Float.isFinite(dp)) return MIN_DP;
        return Math.max(MIN_DP, Math.min(MAX_DP, dp));
    }

    /** 按 HMC getter 的真实返回类型装箱，避免 IllegalArgumentException。 */
    private static Object number(float value, Class<?> type) {
        if (type == double.class || type == Double.class) return Double.valueOf(value);
        if (type == int.class || type == Integer.class) return Integer.valueOf(Math.round(value));
        if (type == long.class || type == Long.class) return Long.valueOf(Math.round(value));
        return Float.valueOf(value);
    }

    private static float density() {
        try {
            float value = android.content.res.Resources.getSystem().getDisplayMetrics().density;
            return value > 0.5f ? value : 3f;
        } catch (Throwable ignored) {
            return 3f;
        }
    }
}
