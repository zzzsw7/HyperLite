package com.hyperlite.battery;

import android.graphics.Color;

/**
 * 实心电池框的纯计算策略：进度遮罩位置、图标形态下标、颜色混合与亮度。
 *
 * <p>对应上游 StatusBarBatteryFramePolicy，以及上游直接调用的两个 AndroidX ColorUtils 方法。
 * 这里不依赖 AndroidX：颜色按 ARGB 四通道线性插值，亮度用 sRGB 相对亮度公式。
 */
final class BatteryFramePolicy {

    /** 自绘内容相对 28x20 原生坐标系再收一点，避免贴边。 */
    static final float BODY_WIDTH_SCALE = 0.98f;
    static final float BODY_HEIGHT_SCALE = 0.94f;

    private BatteryFramePolicy() {
    }

    /**
     * 电量遮罩的水平位移。
     *
     * <p>body 最左侧 2.6、最右侧 25.594；遮罩为 2.6+shift ~ 25.594+shift。
     * level=100 时 shift=0，整块可见；level=0 时 shift=-46，整块移出左侧（start 方向相反）。
     */
    static float maskShift(int level, boolean start) {
        int bounded = Math.max(0, Math.min(100, level));
        float shift = -23f * (1f - bounded / 100f);
        if (bounded == 0) shift *= 2f;
        return start ? shift : -shift;
    }

    /** 0=普通，1=充电，2=快充，再 +3 表示性能模式，共 6 个预生成 Path。 */
    static int shapeIndex(boolean charging, boolean quick, boolean performance) {
        return (quick ? 2 : charging ? 1 : 0) + (performance ? 3 : 0);
    }

    /** 等价于 AndroidX ColorUtils.blendARGB（逐通道线性插值，含 alpha）。 */
    static int blendARGB(int color1, int color2, float ratio) {
        if (ratio <= 0f) return color1;
        if (ratio >= 1f) return color2;
        float inverse = 1f - ratio;
        int alpha = (int) (Color.alpha(color1) * inverse + Color.alpha(color2) * ratio);
        int red = (int) (Color.red(color1) * inverse + Color.red(color2) * ratio);
        int green = (int) (Color.green(color1) * inverse + Color.green(color2) * ratio);
        int blue = (int) (Color.blue(color1) * inverse + Color.blue(color2) * ratio);
        return Color.argb(alpha, red, green, blue);
    }

    /** 等价于 AndroidX ColorUtils.calculateLuminance：sRGB 相对亮度。 */
    static double calculateLuminance(int color) {
        double red = linearize(Color.red(color) / 255.0);
        double green = linearize(Color.green(color) / 255.0);
        double blue = linearize(Color.blue(color) / 255.0);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double linearize(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
