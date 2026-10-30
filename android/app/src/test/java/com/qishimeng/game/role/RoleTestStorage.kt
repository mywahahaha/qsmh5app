package com.qishimeng.game.role

internal class InMemoryRoleStorage : RoleStorage {
    private var roles = emptyList<GameRole>()
    override fun load(): List<GameRole> = roles.toList()
    override fun save(roles: List<GameRole>) { this.roles = roles.toList() }
}
