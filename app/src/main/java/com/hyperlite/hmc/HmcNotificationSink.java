package com.hyperlite.hmc;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 锁屏通知下沉。
 *
 * <p>目标：封面在锁屏上展开时，让通知栈给封面/指纹区让位（下沉），封面退出后恢复。
 *
 * <p>实现路线（有意做减法）：
 * <ul>
 *   <li>HMC 自己已经有一整套通知下沉机制：它 hook 了 SystemUI 的 combine lambda，
 *       在每次发射前调用 {@code NotificationSink.withAvoidance(inputs, mode)} 改写
 *       第 6/7 个输入（两个「避让」布尔值），mode==2 时两个都为 true。</li>
 *   <li>上游 HyperModifier 为了把「扩展展开」这一状态也塞进 combine，额外注入了一条
 *       StateFlow 并 hook 了 kotlinx.coroutines 的 combineInternal 等内部类。本移植不碰
 *       coroutine 内部实现（类名随 R8 变化，且旧 Xposed API 没有 chain 嵌套的便利），
 *       直接在 {@code withAvoidance} 的返回值上把两个布尔改成 true。</li>
 *   <li>拿不到 withAvoidance 时降级为改写 HMC 的 {@code Settings.FP_AVOID} 读取结果（2 =
 *       总是避让），只改内存里的读取值，不改 HMC 的存档。</li>
 * </ul>
 *
 * <p>已知限制：改的是 combine 的输入而不是新增一条流，所以生效时机取决于下一次自然发射
 * （锁屏上通知/时钟状态变化很频繁，实际几乎立即）。上游用额外 StateFlow 解决的是「状态变化
 * 但没有任何输入变化时不重发」，这里用 HMC 的 refreshNotificationSink() 尽量催一次。
 */
final class HmcNotificationSink {

    /** 避让输入的固定下标：HMC 的 NotificationSink 用 5/6 表示两个避让布尔。 */
    private static final int AVOID_A = 5;
    private static final int AVOID_B = 6;
    /** HMC 的 FP_AVOID 约定：0 交给系统，1 从不避让，2 总是避让。 */
    private static final int FP_AVOID_ALWAYS = 2;

    private HmcNotificationSink() {
    }

    static void install(HmcAdapter adapter, ClassLoader systemLoader) {
        if (installWithAvoidance(adapter)) return;
        installSettingsFallback(adapter);
    }

    private static boolean installWithAvoidance(HmcAdapter adapter) {
        try {
            Class<?> sink = HmcSymbols.load(HmcSymbols.NOTIFICATION_SINK, adapter.loader);
            Method withAvoidance = HmcSymbols.methodIn(sink, "withAvoidance", Object[].class, int.class);
            if (withAvoidance == null) return false;
            XposedBridge.hookMethod(withAvoidance, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!HmcState.sinkOn()) return;
                        Object result = param.getResult();
                        if (!(result instanceof Object[])) return;
                        Object[] values = ((Object[]) result).clone();
                        if (values.length <= AVOID_B) return;
                        if (Boolean.TRUE.equals(values[AVOID_A]) && Boolean.TRUE.equals(values[AVOID_B])) {
                            return;
                        }
                        // 克隆后再改：combine 会跨发射复用原数组。
                        values[AVOID_A] = Boolean.TRUE;
                        values[AVOID_B] = Boolean.TRUE;
                        param.setResult(values);
                    } catch (Throwable error) {
                        HmcLog.warnOnce("sink-avoidance", "改写 HMC 通知避让输入失败", error);
                    }
                }
            });
            HmcSymbols.deoptimize(withAvoidance);
            return true;
        } catch (Throwable error) {
            HmcLog.warnOnce("sink-install", "安装 HMC 通知下沉 Hook 失败", error);
            return false;
        }
    }

    /** 兜底：直接改 HMC 读取 FP_AVOID 设置的结果，让它自己的整条下沉链路按「总是避让」走。 */
    private static void installSettingsFallback(HmcAdapter adapter) {
        try {
            Class<?> settings = HmcSymbols.load(HmcSymbols.SETTINGS, adapter.loader);
            Object fpAvoid = HmcSymbols.staticValue(HmcSymbols.fieldIn(settings, "FP_AVOID"));
            if (fpAvoid == null) return;
            Method getter = HmcSymbols.numericGetter(fpAvoid.getClass(), "get");
            if (getter == null || getter.getParameterCount() != 0) return;
            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!HmcState.sinkOn() || param.thisObject != fpAvoid) return;
                        Class<?> type = getter.getReturnType();
                        if (type == long.class || type == Long.class) {
                            param.setResult(Long.valueOf(FP_AVOID_ALWAYS));
                        } else {
                            param.setResult(Integer.valueOf(FP_AVOID_ALWAYS));
                        }
                    } catch (Throwable error) {
                        HmcLog.warnOnce("sink-fp", "改写 HMC 指纹避让模式失败", error);
                    }
                }
            });
            HmcSymbols.deoptimize(getter);
        } catch (Throwable error) {
            HmcLog.warnOnce("sink-fallback", "安装 HMC 通知下沉兜底失败", error);
        }
    }

    /** 封面进入/退出时请 HMC 重新评估下沉。 */
    static void watchCoverTransitions(HmcAdapter adapter) {
        for (Method method : adapter.coverTransitions) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            HmcState.onCoverChanged();
                        } catch (Throwable error) {
                            HmcLog.warnOnce("sink-cover", "封面状态切换后刷新通知失败", error);
                        }
                    }
                });
                HmcSymbols.deoptimize(method);
            } catch (Throwable error) {
                HmcLog.warnOnce("sink-cover-install", "Hook 封面状态切换失败", error);
            }
        }
    }
}
