package com.hyperlite;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * 只读设置通道：把模块 App 的 SharedPreferences 整体打包成 Bundle 交给被注入进程。
 * 不接收写入，不暴露任何设备数据。
 */
public final class PrefsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        Bundle out = new Bundle();
        Map<String, ?> all = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;
            if (value instanceof Boolean) out.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) out.putInt(key, (Integer) value);
            else if (value instanceof Long) out.putLong(key, (Long) value);
            else if (value instanceof Float) out.putFloat(key, (Float) value);
            else if (value instanceof String) out.putString(key, (String) value);
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
