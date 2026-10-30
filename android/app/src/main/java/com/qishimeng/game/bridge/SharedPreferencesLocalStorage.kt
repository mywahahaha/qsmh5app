package com.qishimeng.game.bridge

import android.content.Context

class SharedPreferencesLocalStorage(context: Context) : LocalStoragePreferences {
    private val preferences = context.getSharedPreferences("h5_local_storage", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, "")

    override fun putAll(values: Map<String, String>): Boolean {
        if (values.isEmpty()) return true
        val editor = preferences.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        // 回调成功前完成持久化，避免紧接着重启时丢失已确认的数据。
        return editor.commit()
    }
}
