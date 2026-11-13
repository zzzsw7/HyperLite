package com.hyperlite;

/**
 * 功能二：状态栏电池。
 *
 * <p>由 {@code com.hyperlite.battery} 下的实现提供：
 * <ul>
 *   <li>{@code BatteryFrameHooks}：无数字实心电池框（接管 MiuiBatteryMeterIconView 的绘制）</li>
 *   <li>{@code BatteryIconSizeHooks}：电池图标尺寸（对子 View 做中心缩放，不动布局）</li>
 *   <li>{@code BatteryPercentHooks}：控制中心电量内嵌</li>
 * </ul>
 */
final class BatteryFeature {

    private BatteryFeature() {
    }

    static void install(ClassLoader loader) {
        com.hyperlite.battery.BatteryFrameHooks.install(loader);
        com.hyperlite.battery.BatteryIconSizeHooks.install(loader);
        com.hyperlite.battery.BatteryPercentHooks.install(loader);
    }
}
