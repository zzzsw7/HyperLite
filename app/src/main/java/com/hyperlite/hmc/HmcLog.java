package com.hyperlite.hmc;

import android.util.Log;

import com.hyperlite.Config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志闸门。
 *
 * <p>每个子功能的失败只记一条：反射失败是常态（HMC 版本、机型、ROM 都在变），
 * 反复打印会刷爆 logcat，也会拖慢 SystemUI。
 */
final class HmcLog {

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private HmcLog() {
    }

    static void info(String message) {
        try {
            Log.i(Config.TAG, message);
        } catch (Throwable ignored) {
            // 日志本身绝不影响业务。
        }
    }

    static void warnOnce(String key, String message, Throwable error) {
        try {
            if (!WARNED.add(key)) return;
            Log.w(Config.TAG, message, error);
        } catch (Throwable ignored) {
            // 同上。
        }
    }
}
