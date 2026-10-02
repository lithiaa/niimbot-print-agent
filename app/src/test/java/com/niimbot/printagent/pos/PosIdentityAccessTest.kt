package com.niimbot.printagent.pos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosIdentityAccessTest {
    @Test
    fun `empty permissions retain legacy mutation capability`() {
        assertTrue(PosIdentityAccess.canMutate(emptyList(), "barang.write"))
    }

    @Test
    fun `empty permissions retain legacy mutation capability`() {
        assertTrue(PosIdentityAccess.canMutate(emptyList(), "barang.write"))
    }

    @Test
    fun `declared permission gates matching mutation`() {
        assertTrue(PosIdentityAccess.canMutate(listOf("barang.write"), "barang.write"))
        assertFalse(PosIdentityAccess.canMutate(listOf("barang.read"), "barang.write"))
    }

    @Test
    fun `stok write permission checked independently`() {
        assertTrue(PosIdentityAccess.canMutate(listOf("stok.write"), "stok.write"))
        assertFalse(PosIdentityAccess.canMutate(listOf("barang.write"), "stok.write"))
    }
}
