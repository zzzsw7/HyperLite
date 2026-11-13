package com.hyperlite.hmc;

/**
 * FOD / 指纹区避让。
 *
 * <p>上游做法（HyperModifier）：在 {@code com.miui.keyguard.biometrics.fod.MiuiGxzwIconView} /
 * {@code MiuiGxzwAnimationView} 的 onDraw/draw/dispatchDraw 里，当扩展展开时返回 null 跳过绘制；
 * 对光环动画 {@code MiuiGxzwFrameAnimation.draw(int)} 把资源换成透明色；退出时再把最后一帧
 * 重新画一遍。它依赖两个上游状态：「扩展展开（expanded）」和「HMC 是否认为该在锁屏上避让」
 * （{@code Main.fodForKeyguard(Object)}）。
 *
 * <p>本移植没有对应的设置键（只有总开关、时钟高度、通知下沉、AOD 卡片四项），也没有「展开」
 * 这个概念，硬做只能变成「无条件隐藏指纹图标」——那是会把用户锁在锁屏外面的行为，收益为负。
 * 因此这个子功能按 TODO 留空：不装任何 Hook，绝不影响系统指纹动画。
 *
 * <p>TODO 若要补回：需要新增一个 {@code hmc_fod_avoid} 开关，并把「封面已展开」映射到
 * {@code HmcState.coverActive()}；然后用 {@code XposedBridge.hookAllMethods} 挂
 * MiuiGxzwIconView / MiuiGxzwAnimationView 的绘制方法，在 Hook 体内检查 HMC 的
 * {@code fodForKeyguard(Object)} 返回 true 后 {@code param.setResult(null)}。
 */
final class HmcFodAvoid {

    private HmcFodAvoid() {
    }

    static void install(HmcAdapter adapter, ClassLoader systemLoader) {
        // 有意留空：见类注释。HMC 自己的指纹隐藏（HMC 设置里的 fingerprint 开关）仍然照常工作。
    }
}
