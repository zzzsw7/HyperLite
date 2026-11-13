package com.hyperlite.hmc;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * AOD 媒体卡片帧协同。
 *
 * <p>HMC 的封面会跨过熄屏一直活到 AOD：AOD 里那张紧凑媒体卡片是 HMC 自己画的，
 * 位置/尺寸由它自己的卡片层决定（{@code Main.liveMediaTop()} / {@code Main.coverCardInAod()}
 * 就是它对外报的「卡片现在在哪」）。扩展如果这时候还在改时钟高度、下沉通知，
 * 就会把 HMC 摆好的 AOD 卡片帧挤歪，所以这里做的是「让位」而不是「接管」：
 * <ul>
 *   <li>跟踪 Doze/AOD 状态（{@code StatusBarStateControllerImpl.setIsDozing} 和
 *       {@code KeyguardClockContainer.doAnimationToAod}）；</li>
 *   <li>{@code hmc_aod_media} 打开时，AOD 期间暂停时钟高度覆盖与通知下沉，
 *       让 HMC 的卡片帧保持它自己算出来的姿态。</li>
 * </ul>
 *
 * <p>TODO 未移植：直接把 OEM/AOD 媒体卡片容器按 HMC 的 {@code liveMediaTop()} 平移。
 * 上游这部分和它自己的 120dp AOD 卡片布局、glass 材质写在一起（见移植说明里放弃的部分），
 * 而 HyperOS 4 的 AOD 卡片由 com.miui.aod 进程绘制，从 SystemUI 侧硬改帧只会打架，
 * 所以这里只保留状态协同，不做帧写回。
 */
final class HmcAodFrame {

    private HmcAodFrame() {
    }

    static void install(HmcAdapter adapter, ClassLoader systemLoader) {
        hookDoze(systemLoader, "com.android.systemui.statusbar.StatusBarStateControllerImpl", "setIsDozing");
        hookDoze(systemLoader, "com.android.keyguard.clock.KeyguardClockContainer", "doAnimationToAod");
    }

    private static void hookDoze(ClassLoader loader, String ownerName, String methodName) {
        try {
            Class<?> owner = HmcSymbols.load(ownerName, loader);
            if (owner == null) return;
            Method target = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!methodName.equals(method.getName()) || method.getParameterCount() != 1) continue;
                if (method.getParameterTypes()[0] != boolean.class) continue;
                target = method;
                break;
            }
            if (target == null) return;
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0) return;
                        HmcState.setDozing(Boolean.TRUE.equals(param.args[0]));
                    } catch (Throwable error) {
                        HmcLog.warnOnce("aod-dozing", "同步 AOD 状态失败", error);
                    }
                }
            });
            HmcSymbols.deoptimize(target);
        } catch (Throwable error) {
            HmcLog.warnOnce("aod-install-" + methodName, "Hook AOD 状态失败: " + methodName, error);
        }
    }
}
