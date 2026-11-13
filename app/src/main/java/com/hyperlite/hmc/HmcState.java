package com.hyperlite.hmc;

import com.hyperlite.Config;

/**
 * 扩展的运行状态与四个设置键的缓存。
 *
 * <p>所有读取都走 {@link Config}（被注入进程里的设置快照），不碰 SharedPreferences。
 * 这里只缓存基本类型，Hook 热路径上不会做 IPC。
 *
 * <p>设置键：
 * <ul>
 *   <li>hmc_enabled：总开关，关闭时所有 Hook 原样放行；</li>
 *   <li>hmc_clock_height：时钟高度（px），0 = 不覆盖，跟随 HMC 自己的设置；</li>
 *   <li>hmc_notification_sink：锁屏通知下沉，把通知让位给封面；</li>
 *   <li>hmc_aod_media：AOD 里让位给 HMC 的媒体卡片帧，暂停高度覆盖与通知下沉。</li>
 * </ul>
 */
final class HmcState {

    static final String KEY_ENABLED = "hmc_enabled";
    static final String KEY_CLOCK_HEIGHT = "hmc_clock_height";
    static final String KEY_NOTIFICATION_SINK = "hmc_notification_sink";
    static final String KEY_AOD_MEDIA = "hmc_aod_media";

    /** 时钟高度允许的范围，超出直接夹住，避免把 HMC 的换算推成负数或天文数字。 */
    static final int MIN_CLOCK_HEIGHT = 0;
    static final int MAX_CLOCK_HEIGHT = 160;

    private static volatile HmcAdapter adapter;

    private static volatile boolean enabled = true;
    private static volatile boolean sinkEnabled = true;
    private static volatile boolean aodMedia = true;
    private static volatile int clockHeightPx;
    /** 是否处于 AOD/Doze。由 HmcAodFrame 的 Hook 维护，读不到时按 false 处理。 */
    private static volatile boolean dozing;

    private HmcState() {
    }

    static void reload() {
        enabled = Config.getBoolean(KEY_ENABLED, true);
        int height = Config.getInt(KEY_CLOCK_HEIGHT, 0);
        clockHeightPx = Math.max(MIN_CLOCK_HEIGHT, Math.min(MAX_CLOCK_HEIGHT, height));
        sinkEnabled = Config.getBoolean(KEY_NOTIFICATION_SINK, true);
        aodMedia = Config.getBoolean(KEY_AOD_MEDIA, true);
    }

    static void attach(HmcAdapter value) {
        adapter = value;
        reload();
    }

    static HmcAdapter adapter() {
        return adapter;
    }

    static boolean bound() {
        return adapter != null;
    }

    static boolean enabled() {
        return enabled;
    }

    static int clockHeightPx() {
        return clockHeightPx;
    }

    /** 总开关打开、HMC 已绑定、设置了非 0 的高度，并且 HMC 正握着时钟时才覆盖。 */
    static boolean clockHeightOn() {
        return enabled && clockHeightPx > MIN_CLOCK_HEIGHT && !aodYield() && posing();
    }

    /**
     * HMC 是否正握着自己的时钟（封面态，含退场动画，直到 onClockReleased）。
     *
     * <p>高度覆盖只在这个窗口里生效：HMC 只在握钟时读 CLOCK_HEIGHT 摆姿态，
     * 平时（HMC 的设置页、它自己保存状态时）用真实值，避免把扩展的高度写进 HMC 的存档。
     */
    static boolean posing() {
        HmcAdapter current = adapter;
        if (current == null) return false;
        if (current.clockHeld != null) return current.bool(current.clockHeld);
        return coverActive();
    }

    /** AOD 里让位给 HMC 自己的卡片帧：暂停扩展对时钟和通知的改写。 */
    static boolean aodYield() {
        return aodMedia && dozing;
    }

    static void setDozing(boolean value) {
        dozing = value;
    }

    static boolean dozing() {
        return dozing;
    }

    /**
     * HMC 的封面是否正在锁屏上生效。
     *
     * <p>只用「读字段」级别的入口（screenOnCached/coverModeOn/clockHeld），
     * 刻意不调 keyguardLocked() 之类会走 binder 的方法——这段代码在每帧绘制路径上。
     */
    static boolean coverActive() {
        HmcAdapter current = adapter;
        if (current == null || !enabled || !current.hasSceneState()) return false;
        try {
            if (!current.screenOnNow()) return false;
            if (current.coverModeOn != null && !current.bool(current.coverModeOn)) return false;
            if (current.clockHeld != null && !current.bool(current.clockHeld)) return false;
            return true;
        } catch (Throwable error) {
            HmcLog.warnOnce("cover-state", "读取 HMC 封面状态失败", error);
            return false;
        }
    }

    /** 通知下沉：只在封面生效、且没有在 AOD 里让位时改写 HMC 的避让输入。 */
    static boolean sinkOn() {
        return enabled && sinkEnabled && !aodYield() && coverActive();
    }

    /** 设置变化后的统一入口（Config 会在主线程回调）。 */
    static void refresh() {
        reload();
        HmcAdapter current = adapter;
        if (current == null) return;
        try {
            // 关掉总开关时也要走一遍：让 HMC 用回自己的值重算一次，而不是停在扩展最后一次的高度上。
            current.invalidateClock();
            current.requestNotificationSinkRefresh();
        } catch (Throwable error) {
            HmcLog.warnOnce("state-refresh", "刷新 HMC 扩展状态失败", error);
        }
    }

    /** 封面进入/退出时请求 HMC 重新评估通知下沉（不强求立即重发，见移植说明）。 */
    static void onCoverChanged() {
        HmcAdapter current = adapter;
        if (current == null) return;
        current.requestNotificationSinkRefresh();
    }
}
