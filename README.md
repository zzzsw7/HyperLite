# HyperLite

从 [HyperModifier](https://github.com/AritxOnly/HyperModifier) 里抽出来的三个功能，独立成一个约 100 KB 的 LSPosed 小模块。

| 功能 | 作用域 | Hook 落点 |
| --- | --- | --- |
| 旧版应用系统栏完全透明 | 系统框架 `android` | `DisplayPolicy#isFullyTransparentAllowed` |
| 去掉状态栏半透明黑 | 系统界面 | `BarTransitions$BarBackgroundDrawable#draw` |
| 去掉导航栏半透明黑 | 系统界面 | 同上（按 `NavigationBarTransitions` 区分） |
| 状态栏电池改造 | 系统界面 | `MiuiBatteryMeterIconView` / `MiuiBatteryMeterView` |
| HyperMusicCover 扩展 | 系统界面 | 反射 `com.os4.musiccover.Main` + `Settings.CLOCK_HEIGHT` |

设置页右上角还有一个 **「重启系统界面」** 按钮：点一下用 root 执行 `killall com.android.systemui`，
几秒自动恢复，不用重启手机（第一次点需要在内核管理器里允许 root）。

设置页下方还有 **「隐藏桌面图标」** 开关：隐藏后桌面/抽屉里不再显示 HyperLite，模块在 LSPosed 里照旧可见、保持启用。
恢复方式：拨号盘输入 `*#*#7648#*#*`（图标立刻回来），或 `adb shell am start -n com.hyperlite/.SettingsActivity`
把开关关掉。实现上是切换 `com.hyperlite.Launcher` 这个 activity-alias，设置页本体始终可用。

模块本体只有一个设置页和一个**只读** ContentProvider。设置改动后 App 发 `notifyChange`，
被注入进程异步重读快照，**不需要重启手机**；快照读取不进 Hook 热路径。

## 各功能细节

### 一、系统栏（三个独立开关）

| 设置键 | 作用 |
| --- | --- |
| `sysbar_transparent` | 旧版 / 非全屏（非 edge-to-edge）应用的系统栏完全透明，走 WindowManager 的 `DisplayPolicy`，需要「系统框架」作用域 |
| `status_bar_scrim_off` | 只去掉状态栏那条半透明黑（`PhoneStatusBarTransitions`） |
| `nav_bar_scrim_off` | 只去掉导航栏 / 小白条那条半透明黑（`NavigationBarTransitions`） |

后两个针对同一层东西：HyperOS 的系统栏背景不是普通 View 背景，而是 `BarTransitions` 里的
`BarBackgroundDrawable` 画的，半透明态（`mMode == 1/2`）用它从主题读到的 `#66000000`
（资源名 `status_bar_background_semi_transparent`）铺一层 40% 黑。横屏看视频时屏幕上沿、下沿那两条灰带就是它。

实现：`BarTransitions` 构造时记下「这个 drawable 属于哪条栏」，绘制时按各自开关跳过「半透明态」那一帧；
完全不透明状态（下拉通知中心、锁屏）和纯透明状态保持原生。

### 二、状态栏电池

- **无数字实心电池框**：整体接管 `MiuiBatteryMeterIconView` 的 `onFinishInflate/onDraw/clearTint/onDarkChangeInternal/updateResources$10`，
  用与 HyperOS 一致的几何自绘实心框，颜色仍从原生字段取（充电/快充/低电/省电/性能模式各有配色），
  进度方向跟随 `battery_meter_progress_gravity_start`。
- **电池图标尺寸**：在 `MiuiBatteryMeterView#onLayout` 之后对电池子 View 做中心缩放，
  布局尺寸不变，不会挤动状态栏其它图标，也不改变点击区域。设置项是**相对原生的百分比**
  （50%–300%，100% = 原生），内部收口在 0.5x–3.0x。
- **控制中心百分比**：展开控制中心时在实心框内叠加电量数字（用 CLEAR 擦出镂空再上色），
  普通状态栏不显示。

### 三、HyperMusicCover 扩展

不重写 HMC，只在 HMC 已接管锁屏时与它协同：

- **时钟高度**：拦 HMC `Settings.CLOCK_HEIGHT` 数值的 `get()`，按设置值换算成 dp 返回。
  比逐帧改 `TimeView` 的字轴安全；HMC 自己的收缩动画、AOD 姿态仍然由它算。
  已知限制：如果用户在 HMC 里显式设过「时钟尺寸」（`CLOCK_SIZE`），尺寸优先，本项高度不生效
  （不伪装成 NaN，避免把用户设置擦掉）。
- **锁屏通知下沉**：播放中把锁屏通知整体下移，给封面让位。
- **AOD 协同**：只做状态让位（AOD 期间暂停高度覆盖与通知下沉），**不硬改 AOD 卡片帧**——
  HyperOS 4 的 AOD 卡片由 `com.miui.aod` 绘制，从 SystemUI 侧改帧只会和系统打架。
- **指纹区避让**：未移植（留了明确的 TODO 注释），不影响其它子功能。

未安装 HMC 时整个功能静默跳过：不装 Hook，最多一条日志。

## 目录

```
app/src/main/java/com/hyperlite/
├── HyperLiteEntry.java          # 唯一入口，按进程 + 包名分发
├── Config.java                  # 被注入进程侧设置快照 + 变更通知
├── PrefsProvider.java           # 只读设置通道（Bundle）
├── HookUtil.java                # deoptimize 等旧 API 兼容工具
├── SettingsActivity.java        # 设置界面（纯 framework 控件，右上角带重启按钮）
├── SystemBarTransparency.java   # 旧版应用系统栏完全透明（系统框架）
├── bars/BarScrimHooks.java      # 状态栏 / 导航栏半透明遮罩（两个开关）
├── BatteryFeature.java          # 功能二入口
├── battery/                     # 功能二实现（实心框/尺寸/百分比）
└── HyperMusicCoverFeature.java  # 功能三入口
    hmc/                         # 功能三实现（反射适配/时钟/通知/AOD）
```

## 安装

1. 安装 APK（`app-release.apk` 已用 debug 签名，可直接装）。
2. LSPosed → 模块 → 启用 **HyperLite**。
3. 作用域勾选：**系统界面**（`com.android.systemui`）；要用「旧版应用系统栏完全透明」再勾 **系统框架**（`android`）。
4. 重装或改动后，点设置页右上角「重启系统界面」即可；系统框架那部分需要重启手机才生效。

只想要某个功能时把对应开关关掉即可：关闭后 Hook 都走原生逻辑，不残留副作用。

## 构建（本机离线可复现）

```powershell
$env:JAVA_HOME        = 'D:\phonetest\jdk21\jbrsdk_jcef-21.0.10-windows-x64-b1163.105'
$env:GRADLE_USER_HOME = 'D:\deepseek-workspace\.gradle-home'
$env:ANDROID_HOME     = 'D:\phonetest\sdk'
cd D:\deepseek-workspace\HyperLite
& 'D:\phonetest\gradle-9.4.1\bin\gradle.bat' --offline --no-daemon --no-watch-fs assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。
注意：Gradle 的 JVM 在受限文件沙箱下会把工程目录误判为不可写，构建需要完整文件权限。
Release 包默认复用 debug 签名，正式发布请自己配 keystore（见 `app/build.gradle.kts`）。

## 已知限制

- Hook 点基于 HyperOS 4（`BarTransitions$BarBackgroundDrawable`、`MiuiBatteryMeterIconView`、
  HMC `com.os4.musiccover.Main`）。系统或 HMC 大版本升级后签名可能变化：反射失败时该子功能静默跳过，不影响开机。
- 三个功能全部只在 SystemUI 进程生效，不再需要系统框架作用域。
- 未做机型/版本白名单，HyperOS 3 及更早版本未验证。

## 许可证与归属

本项目是 [AritxOnly/HyperModifier](https://github.com/AritxOnly/HyperModifier)（GPL-3.0）的衍生裁剪版，
同样以 **GPL-3.0** 发布，保留原作者署名。HMC 协同部分只通过反射调用
[zyl6932/HyperMusicCover](https://github.com/zyl6932/HyperMusicCover)（AGPL-3.0）的运行时接口，未复制其代码。

发布前请把 GPL-3.0 完整条文（<https://www.gnu.org/licenses/gpl-3.0.txt>）存为 `LICENSE`，当前 `LICENSE`
是版权声明与许可指引。
