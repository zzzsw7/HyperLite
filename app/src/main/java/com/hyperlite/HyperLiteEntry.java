package com.hyperlite;

import android.util.Log;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 唯一入口。
 *
 * <p>两条路：
 * <ul>
 *   <li>system_server（LSPosed 里叫「系统框架」）：旧版应用系统栏完全透明</li>
 *   <li>SystemUI：系统栏遮罩、电池、HyperMusicCover 扩展</li>
 * </ul>
 *
 * <p>只看包名不够：SystemUI 里也会为系统资源创建 {@code android}/{@code system} 的 LoadedApk，
 * LSPosed 会照样回调一次，所以先确认自己在哪个进程。
 */
public final class HyperLiteEntry implements IXposedHookLoadPackage {

    private static final String PROCESS_SYSTEM_SERVER = "system_server";
    private static final String PROCESS_SYSTEM_UI = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;
        String process = processName();
        try {
            if (PROCESS_SYSTEM_SERVER.equals(process)
                    && ("android".equals(pkg) || "system".equals(pkg))) {
                Config.initialize();
                SystemBarTransparency.install(lpparam.classLoader);
            } else if (PROCESS_SYSTEM_UI.equals(process) && "com.android.systemui".equals(pkg)) {
                Config.initialize();
                Config.initializeAfterApplication(lpparam.classLoader);
                com.hyperlite.bars.BarScrimHooks.install(lpparam.classLoader);
                BatteryFeature.install(lpparam.classLoader);
                HyperMusicCoverFeature.install(lpparam.classLoader);
            }
        } catch (Throwable error) {
            XposedBridge.log("[HyperLite] " + pkg + " failed: " + Log.getStackTraceString(error));
        }
    }

    /** /proc/self/cmdline 的第一段就是进程名，比猜 uid 可靠。 */
    private static String processName() {
        byte[] buffer = new byte[256];
        try (FileInputStream stream = new FileInputStream("/proc/self/cmdline")) {
            int read = stream.read(buffer);
            if (read <= 0) return "";
            int end = 0;
            while (end < read && buffer[end] != 0) end++;
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
