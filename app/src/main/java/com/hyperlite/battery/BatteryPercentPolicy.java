package com.hyperlite.battery;

/**
 * 控制中心电池百分比的纯策略。
 *
 * <p>本模块只保留“展开控制中心时把数字画进实心框”这一种形态：
 * 上游的 outside placement、临时 mBatteryStyle=3、外部百分比容器居中/显隐等分支都裁剪掉了，
 * 因此这里只保留内嵌判定和控制中心展开状态机。
 */
final class BatteryPercentPolicy {

    /** 与上游 ControlCenterBatteryPanelState 一致：EXPANDING 既可能是打开也可能是关闭。 */
    static final int COLLAPSED = 0;
    static final int EXPANDING = 1;
    static final int EXPANDED = 2;

    private BatteryPercentPolicy() {
    }

    /** 只有实心框开启且用户开启了内嵌百分比，才有“框内数字”这回事。 */
    static boolean drawsInside(boolean solidFrame, boolean percentInside) {
        return solidFrame && percentInside;
    }

    /**
     * 控制中心面板状态机。
     *
     * <p>raw EXPANDING 无法区分方向，所以保留上一次稳定状态：从 COLLAPSED 进入 EXPANDING 视为正在展开，
     * 从 EXPANDED 进入 EXPANDING 视为正在收起。
     */
    static final class PanelState {
        private int phase = COLLAPSED;
        private volatile boolean showing;

        /**
         * @return showing 是否发生变化，调用方据此决定要不要刷新电池图标
         */
        synchronized boolean update(int next) {
            boolean previous = showing;
            if (next == EXPANDED) {
                showing = true;
            } else if (next == COLLAPSED) {
                showing = false;
            } else if (next == EXPANDING && phase != EXPANDING) {
                showing = phase != EXPANDED;
            }
            phase = next;
            return previous != showing;
        }

        boolean showing() {
            return showing;
        }
    }
}
