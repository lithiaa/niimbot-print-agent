package com.niimbot.printagent.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntegrationConfigStoreTest {
    private lateinit var context: Context
    private lateinit var store: IntegrationConfigStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = IntegrationConfigStore(context)
        store.clearSession()
    }

    @Test
    fun `store and retrieve identity with toko environment and permissions`() = runBlocking {
        val identity = PosIdentity(
            username = "operator",
            role = "staff",
            environment = PosEnvironment(8, "Toko Satu", "active"),
            permissions = listOf("barang.read", "stok.write")
        )
        val token = "test-token-123"
        store.setAuthenticatedSession(token, identity)

        assertEquals(token, store.getAccessToken())
        val retrieved = store.getIdentity()
        assertEquals(identity.username, retrieved?.username)
        assertEquals(identity.role, retrieved?.role)
        assertEquals(identity.environment, retrieved?.environment)
        assertEquals(identity.permissions, retrieved?.permissions)
    }

    @Test
    fun `legacy identity without toko still works`() = runBlocking {
        val identity = PosIdentity(username = "operator", role = "admin")
        val token = "legacy-token"
        store.setAuthenticatedSession(token, identity)

        val retrieved = store.getIdentity()
        assertEquals(identity.username, retrieved?.username)
        assertEquals(identity.role, retrieved?.role)
        assertNull(retrieved?.environment)
        assertEquals(emptyList<String>(), retrieved?.permissions)
    }

    @Test
    fun `clear session removes all fields`() = runBlocking {
        val identity = PosIdentity(
            username = "operator",
            role = "staff",
            environment = PosEnvironment(8, "Toko Satu", "active"),
            permissions = listOf("barang.read")
        )
        store.setAuthenticatedSession("token", identity)
        assertTrue(store.hasAccessToken())

        store.clearSession()
        assertNull(store.getAccessToken())
        assertNull(store.getIdentity())
    }

    @Test
    fun `suspended toko status preserved`() = runBlocking {
        val identity = PosIdentity(
            username = "operator",
            role = "staff",
            environment = PosEnvironment(8, "Toko Satu", "suspended"),
            permissions = listOf("barang.read")
        )
        store.setAuthenticatedSession("token", identity)

        val retrieved = store.getIdentity()
        assertEquals("suspended", retrieved?.environment?.status)
    }
}