package com.qishimeng.game.role

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SharedPreferencesRoleStorage(context: Context) : RoleStorage {
    private val preferences = context.getSharedPreferences("game_roles", Context.MODE_PRIVATE)
    private val type = object : TypeToken<List<GameRole>>() {}.type

    override fun load(): List<GameRole> = runCatching {
        Gson().fromJson<List<GameRole>>(preferences.getString("roles", "[]"), type).orEmpty()
    }.getOrDefault(emptyList())

    override fun save(roles: List<GameRole>) {
        preferences.edit().putString("roles", Gson().toJson(roles)).apply()
    }
}
