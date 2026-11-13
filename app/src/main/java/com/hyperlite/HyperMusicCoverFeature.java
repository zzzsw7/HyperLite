package com.hyperlite;

/**
 * 功能三：HyperMusicCover 扩展。
 *
 * <p>HyperMusicCover（包名 com.github.zyl6932.HyperMusicCover，运行时类 com.os4.musiccover.*）
 * 自己会 hook 锁屏媒体卡片。这里不重写它，只在它已经接管锁屏时做协同扩展：
 * 时钟高度/字体高度、AOD 帧、通知下沉、指纹区避让。未安装 HMC 时全部静默跳过。
 */
final class HyperMusicCoverFeature {

    static final String KEY_ENABLED = "hmc_enabled";

    private HyperMusicCoverFeature() {
    }

    static void install(ClassLoader loader) {
        com.hyperlite.hmc.HyperMusicCoverExtension.install(loader);
    }
}
