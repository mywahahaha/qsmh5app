package com.qishimeng.game.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRoleStoreTest {
    @Test
    fun replacesAllFieldsAndPreviousRoleForSameGameWhilePreservingOtherGames() {
        val storage = InMemoryRoleStorage()
        val store = GameRoleStore(storage)
        assertTrue(store.save(mapOf("gameId" to 266, "roleId" to "r1", "roleName" to "warrior", "roleLevel" to 10)))
        assertTrue(store.save(mapOf("gameId" to 267, "roleId" to "other", "roleName" to "mage")))
        assertTrue(store.save(mapOf("gameId" to 266, "roleId" to "r2", "serverId" to "s1", "roleLevel" to "bad", "unexpected" to true)))

        assertEquals(listOf(GameRole(267, "other", roleName = "mage"), GameRole(266, "r2", serverId = "s1")), storage.load())
    }

    @Test
    fun savingExistingGameRemovesAllLegacyRolesOfThatGame() {
        val storage = InMemoryRoleStorage()
        storage.save(listOf(GameRole(266, "old1"), GameRole(266, "old2"), GameRole(267, "other")))
        val store = GameRoleStore(storage)
        assertTrue(store.save(mapOf("gameId" to 266, "roleId" to "new")))
        assertEquals(listOf(GameRole(267, "other"), GameRole(266, "new")), storage.load())
    }

    @Test
    fun savingSameRoleDoesNotRetainOmittedOrInvalidFields() {
        val storage = InMemoryRoleStorage()
        val store = GameRoleStore(storage)
        assertTrue(store.save(mapOf("gameId" to 266, "roleId" to "r1", "roleName" to "old", "roleLevel" to 10)))
        assertTrue(store.save(mapOf("gameId" to 266, "roleId" to "r1", "roleName" to null, "roleLevel" to "bad")))
        assertEquals(listOf(GameRole(266, "r1")), storage.load())
    }

    @Test
    fun missingIndexAndWrongIndexTypesAreRejected() {
        val store = GameRoleStore(InMemoryRoleStorage())
        assertFalse(store.save(mapOf("gameId" to 266)))
        assertFalse(store.save(mapOf("gameId" to "266", "roleId" to "r1")))
        assertEquals(null, store.get(266))
    }
}
