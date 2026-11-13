package com.hyperlite.battery;

/**
 * 电池图标尺寸的纯策略。
 *
 * <p>设置项给的是「相对原生的百分比」：100 = 原生大小，50 = 一半，300 = 三倍。
 * 直接按比例缩放图标子 View，不再依赖图标宽度做 px 换算，所以换机型、换分辨率、
 * 换状态栏样式（电池内显数字/外显）都不会跑偏。
 *
 * <p>范围 0.5x ~ 3.0x：下限避免缩成看不见，上限避免把相邻的信号、时间挤出状态栏；
 * 超过 3x 之后即使不裁切也会和邻居重叠，收益很低。
 */
final class BatteryIconSizePolicy {

    static final float MIN_SCALE = 0.5f;
    static final float MAX_SCALE = 3.0f;
    static final float DEFAULT_SCALE = 1.0f;

    private BatteryIconSizePolicy() {
    }

    /**
     * 计算缩放倍数。
     *
     * @param percent 设置里的百分比，<=0 或 100 表示保持原生
     * @return 1.0 表示不需要缩放
     */
    static float resolveScale(int percent) {
        if (percent <= 0) return DEFAULT_SCALE;
        return normalize(percent / 100f);
    }

    static float normalize(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return DEFAULT_SCALE;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    static float scaledValue(float original, float scale) {
        return original * normalize(scale);
    }
}
