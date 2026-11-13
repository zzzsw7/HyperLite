package com.hyperlite;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/**
 * 隐藏桌面图标后的恢复入口：拨号盘输入 {@code *#*#7648#*#*}。
 *
 * <p>为什么不是“打开设置页”：Android 16 会拦掉广播接收器里的后台启动 Activity，
 * 所以这里改成把图标恢复出来（纯组件状态操作，不受限制），用户再点图标即可。
 * 如果系统允许，也会顺手把设置页拉起来。
 */
public final class SecretCodeReceiver extends BroadcastReceiver {

    /** 暗码：*#*#7648#*#* */
    public static final String CODE = "7648";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Uri data = intent == null ? null : intent.getData();
            if (data == null || !CODE.equals(data.getHost())) return;

            context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Config.KEY_HIDE_LAUNCHER_ICON, false)
                    .apply();
            ComponentName alias = new ComponentName(context, context.getPackageName() + ".Launcher");
            context.getPackageManager().setComponentEnabledSetting(alias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Toast.makeText(context, "HyperLite 桌面图标已恢复", Toast.LENGTH_LONG).show();

            Intent open = new Intent(Intent.ACTION_MAIN);
            open.setComponent(new ComponentName(context, SettingsActivity.class));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(open);
        } catch (Throwable ignored) {
            // 恢复图标失败也不抛给系统；用户还可以用 adb 或 LSPosed 的入口。
        }
    }
}
