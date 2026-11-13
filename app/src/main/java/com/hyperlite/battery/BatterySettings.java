package com.hyperlite.battery;

import com.hyperlite.Config;

/**
 * 电池三件套的设置读取集中入口。
 *
 * <p>所有键名都固定为模块设置页里的契约；这里只读 {@link Config} 的进程内快照，
 * 不做 IPC，也不直接碰 SharedPreferences。
 */
final class BatterySettings {

    /** 无数字电池实心框。 */
    static final String KEY_SOLID_FRAME = "battery_solid_frame";
    /** 电池图标尺寸：相对原生的百分比，100 = 原生，范围 50~300。 */
    static final String KEY_ICON_SCALE_PCT = "battery_icon_scale_pct";
    /** 展开控制中心时在实心框内嵌电量数字。 */
    static final String KEY_PERCENT_INSIDE = "battery_percent_inside";

    static final boolean DEFAULT_SOLID_FRAME = true;
    static final int DEFAULT_ICON_SCALE_PCT = 100;
    static final boolean DEFAULT_PERCENT_INSIDE = false;

    private BatterySettings() {
    }

    static boolean solidFrame() {
        return Config.getBoolean(KEY_SOLID_FRAME, DEFAULT_SOLID_FRAME);
    }

    static int iconScalePercent() {
        return Config.getInt(KEY_ICON_SCALE_PCT, DEFAULT_ICON_SCALE_PCT);
    }

    static boolean percentInside() {
        return Config.getBoolean(KEY_PERCENT_INSIDE, DEFAULT_PERCENT_INSIDE);
    }
}
