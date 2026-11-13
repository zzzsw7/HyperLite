package com.hyperlite;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 极简设置界面：只用 framework 控件，不依赖 AndroidX / Compose。 */
public final class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, dp(8), pad, dp(48));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部固定栏：左标题、右上角「重启系统界面」
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int headerPad = dp(20);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(headerPad, dp(14), headerPad, dp(4));

        TextView title = new TextView(this);
        title.setText("HyperLite");
        title.setTextSize(24f);
        title.setTextColor(Color.BLACK);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Button restart = new android.widget.Button(this);
        restart.setText("重启系统界面");
        restart.setAllCaps(false);
        restart.setOnClickListener(v -> restartSystemUi());
        header.addView(restart);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // targetSdk 35 起窗口默认 edge-to-edge，不处理 insets 的话标题和右上角按钮会钻到状态栏底下。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        setContentView(root);

        buildUi();
        // 重装/清数据后把隐藏状态同步回来，避免“开关是开的但图标又出现了”。
        applyLauncherIcon(prefs.getBoolean(Config.KEY_HIDE_LAUNCHER_ICON, false));
    }

    /** 右上角按钮：拿到 root 后直接重启 SystemUI（几秒自动恢复，不用重启手机）。 */
    private void restartSystemUi() {
        android.widget.Toast.makeText(this, "正在重启系统界面…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                boolean root = isRootAvailable();
                android.util.Log.i("HyperLite", "restart-ui: root=" + root);
                if (!root) {
                    message = "没有 root 权限，无法自动重启";
                } else {
                    // killall 不行就退回 pidof + kill
                    int code = exec("killall com.android.systemui");
                    if (code != 0) code = exec("kill -9 $(pidof com.android.systemui)");
                    android.util.Log.i("HyperLite", "restart-ui: kill exit=" + code);
                    message = code == 0 ? "已重启系统界面" : "重启命令失败（exit=" + code + "）";
                }
            } catch (Throwable error) {
                android.util.Log.w("HyperLite", "restart-ui failed", error);
                message = "重启失败：" + error.getMessage();
            }
            final String text = message;
            runOnUiThread(() -> android.widget.Toast.makeText(
                    SettingsActivity.this, text, android.widget.Toast.LENGTH_SHORT).show());
        }, "HyperLite-restart-ui").start();
    }

    /** 允许 30 秒：第一次调用会弹 KernelSU 的授权框，用户可能要点一下。 */
    private boolean isRootAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return readAll(process).contains("uid=0");
        } catch (Throwable error) {
            if (process != null) process.destroyForcibly();
            return false;
        }
    }

    private int exec(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    private String readAll(Process process) {
        StringBuilder builder = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            for (String line; (line = reader.readLine()) != null; ) {
                builder.append(line).append('\n');
            }
        } catch (Throwable ignored) {
            // 读不到输出就按“没有 root”处理。
        }
        return builder.toString();
    }

    private void buildUi() {
        addHint("从 HyperModifier 抽出的功能。改完设置即时生效；重装模块后可用右上角按钮重启系统界面。");

        addSection("一、系统栏");
        addSwitch("sysbar_transparent", "旧版应用状态栏 / 导航栏完全透明", true,
                "旧版 / 非全屏（非 edge-to-edge）应用原本只允许半透明系统栏，会在状态栏和导航栏后面盖一层遮罩。"
                        + "开启后这类窗口也允许完全透明。这个走 WindowManager 的 DisplayPolicy，需要「系统框架」作用域。");
        addSwitch(BarScrimKey.STATUS, "去掉状态栏那层半透明黑", true,
                "横屏全屏应用（看视频、玩游戏）时，屏幕上沿的状态栏后面会铺一层 40% 黑（#66000000）。"
                        + "开启后状态栏完全透明，直接透出应用画面；下拉通知中心、锁屏等原生不透明状态不受影响。");
        addSwitch(BarScrimKey.NAV, "去掉导航栏 / 小白条那层半透明黑", true,
                "横屏时屏幕下沿的手势条区域同样会铺一层 40% 黑。开启后这一条也完全透明，只保留手势条本身。"
                        + "两个开关互不影响，可以只去掉其中一条。");

        addSection("二、状态栏电池");
        addSwitch("battery_solid_frame", "无数字电池实心框", true,
                "用模块自绘的实心电量框替换系统电池图标（与 HyperOS 原生几何一致），不套主题资源。");
        addSwitch("battery_percent_inside", "控制中心电池显示百分比", false,
                "展开控制中心时，在电池图标内部叠加当前电量数字。");
        addSlider("battery_icon_scale_pct", "电池图标尺寸", 50, 300, 100, "%",
                "100% = 系统原生，最大 300%（3 倍）。只缩放图标本身，状态栏布局和点击区域不变；放得很大时会和相邻图标挨得很近。");

        addSection("三、HyperMusicCover 扩展");
        addSwitch("hmc_enabled", "启用 HyperMusicCover 协同", true,
                "仅当已安装 HyperMusicCover 且它已在锁屏生效时起作用：接管时钟高度、AOD 帧与通知下沉。未安装时本项无副作用。");
        addSwitch("hmc_notification_sink", "锁屏通知下沉", true,
                "播放中把锁屏通知整体下移，给封面/时钟让位。");
        addSwitch("hmc_aod_media", "AOD 媒体卡片协同", true,
                "AOD 期间让出时钟高度与通知下沉，交给 HyperMusicCover 自己摆卡片，避免两边打架。");
        addSlider("hmc_clock_height", "锁屏时钟高度", 0, 160, 0, "px",
                "0 = 跟随 HyperMusicCover 自己的设置。");

        addSection("使用说明");
        addHint("1. 在 LSPosed 里启用 HyperLite，作用域勾选：系统界面（com.android.systemui）；"
                + "要用「旧版应用系统栏完全透明」时再勾上系统框架（android）。\n"
                + "2. 改完设置即时生效；重装模块后点右上角按钮重启系统界面即可（需要 root）。");

        addSection("桌面图标");
        addHideIconSwitch();

        addSection("许可证与归属");
        addHint("HyperLite 是 AritxOnly/HyperModifier 的衍生裁剪版，与上游一致以 GPL-3.0 发布，保留原作者署名。\n"
                + "HyperMusicCover 协同部分只通过反射调用 zyl6932/HyperMusicCover（AGPL-3.0）的运行时接口，未复制其代码。\n"
                + "版本：" + versionName());
        addLink("GPL-3.0 全文  https://www.gnu.org/licenses/gpl-3.0.txt\n"
                + "上游项目  https://github.com/AritxOnly/HyperModifier\n"
                + "HyperMusicCover  https://github.com/zyl6932/HyperMusicCover");
    }

    /** 隐藏 / 恢复桌面图标：只切换 Launcher 这个 activity-alias，设置页本体始终可用。 */
    private void addHideIconSwitch() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        Switch toggle = new Switch(this);
        toggle.setText("隐藏桌面图标");
        toggle.setTextSize(16f);
        toggle.setChecked(prefs.getBoolean(Config.KEY_HIDE_LAUNCHER_ICON, false));
        toggle.setOnCheckedChangeListener((buttonView, checked) -> {
            prefs.edit().putBoolean(Config.KEY_HIDE_LAUNCHER_ICON, checked).apply();
            applyLauncherIcon(checked);
            if (checked) {
                android.widget.Toast.makeText(this,
                        "桌面图标已隐藏。恢复：拨号盘输入 *#*#7648#*#*",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            notifyChanged();
        });
        row.addView(toggle);

        TextView desc = new TextView(this);
        desc.setText("开启后桌面和抽屉里都不再显示 HyperLite 图标，模块在 LSPosed 里照旧可见、保持启用。\n"
                + "恢复图标：拨号盘输入 *#*#7648#*#*（图标会立刻回来），或 adb 执行 "
                + "am start -n com.hyperlite/.SettingsActivity 后把开关关掉。");
        desc.setTextSize(12f);
        desc.setTextColor(0xFF777777);
        desc.setPadding(dp(4), 0, 0, 0);
        row.addView(desc);
        content.addView(row);
    }

    /** 桌面图标是一个 activity-alias（com.hyperlite.Launcher），禁用它就是“隐藏”。 */
    private void applyLauncherIcon(boolean hidden) {
        try {
            android.content.ComponentName alias =
                    new android.content.ComponentName(this, getPackageName() + ".Launcher");
            getPackageManager().setComponentEnabledSetting(alias,
                    hidden ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Throwable error) {
            android.util.Log.w("HyperLite", "toggle launcher icon failed", error);
            android.widget.Toast.makeText(this, "切换桌面图标失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 版本号直接从 PackageManager 取，避免和 build.gradle 里的值写两处。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private void addSection(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setTextColor(0xFF1565C0);
        view.setPadding(0, dp(24), 0, dp(4));
        content.addView(view);
    }

    private void addHint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(0xFF666666);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(4), 0, dp(8));
        content.addView(view);
    }

    private void addLink(String text) {
        TextView view = new TextView(this);
        view.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(0xFF1565C0);
        view.setPadding(0, dp(4), 0, dp(8));
        content.addView(view);
    }

    private void addSwitch(String key, String title, boolean fallback, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16f);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
            notifyChanged();
        });
        row.addView(toggle);

        if (description != null) {
            TextView desc = new TextView(this);
            desc.setText(description);
            desc.setTextSize(12f);
            desc.setTextColor(0xFF777777);
            desc.setPadding(dp(4), 0, 0, 0);
            row.addView(desc);
        }
        content.addView(row);
    }

    private void addSlider(String key, String title, int min, int max, int fallback, String unit,
                           String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        final TextView label = new TextView(this);
        label.setTextSize(16f);
        row.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        int value = prefs.getInt(key, fallback);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        label.setText(title + "：" + (value == 0 ? "默认" : value + " " + unit));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int current = progress + min;
                label.setText(title + "：" + (current == 0 ? "默认" : current + " " + unit));
                if (fromUser) {
                    prefs.edit().putInt(key, current).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyChanged();
            }
        });
        row.addView(bar);

        if (description != null) {
            TextView desc = new TextView(this);
            desc.setText(description);
            desc.setTextSize(12f);
            desc.setTextColor(0xFF777777);
            desc.setPadding(dp(4), 0, 0, 0);
            row.addView(desc);
        }
        content.addView(row);
    }

    private void notifyChanged() {
        try {
            getContentResolver().notifyChange(Config.URI, null);
        } catch (Throwable ignored) {
            // 观察者没注册成功时忽略，下次进程重启会重新读取。
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 系统栏遮罩的两个键，和 bars/BarScrimHooks 保持一致。 */
    private static final class BarScrimKey {
        static final String STATUS = "status_bar_scrim_off";
        static final String NAV = "nav_bar_scrim_off";
    }
}
