package com.qishimeng.game.role

data class GameRole(
    val gameId: Int,
    val roleId: String,
    val roleName: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val account: String? = null,
    val roleLevel: Int? = null,
    val rolePower: Int? = null
)

interface RoleStorage {
    fun load(): List<GameRole>
    fun save(roles: List<GameRole>)
}

class GameRoleStore(private val storage: RoleStorage) {
    fun save(params: Map<String, Any?>): Boolean {
        val gameId = params.intOrNull("gameId") ?: return false
        val roleId = (params["roleId"] as? String)?.takeIf(String::isNotBlank) ?: return false
        return runCatching {
            val roles = storage.load().filterNot { it.gameId == gameId }.toMutableList()
            roles.add(GameRole(
                gameId = gameId,
                roleId = roleId,
                roleName = params.stringOrNull("roleName"),
                serverId = params.stringOrNull("serverId"),
                serverName = params.stringOrNull("serverName"),
                account = params.stringOrNull("account"),
                roleLevel = params.intOrNull("roleLevel"),
                rolePower = params.intOrNull("rolePower")
            ))
            storage.save(roles)
            true
        }.getOrDefault(false)
    }

    fun get(gameId: Int): GameRole? = runCatching {
        storage.load().lastOrNull { it.gameId == gameId }
    }.getOrNull()

    fun clear() {
        storage.save(emptyList())
    }

    private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.intOrNull(key: String): Int? {
        val value = this[key] as? Number ?: return null
        val integer = value.toLong()
        if (integer !in Int.MIN_VALUE..Int.MAX_VALUE || value.toDouble() != integer.toDouble()) return null
        return integer.toInt()
    }
}
