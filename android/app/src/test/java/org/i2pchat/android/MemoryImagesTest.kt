package org.i2pchat.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImagesTest {
    @Test
    fun putAndGetRoundTrip() {
        val store = MemoryImageStore()
        val key = store.put("peerA", "img_1_abcd.png", byteArrayOf(1, 2, 3))
        assertEquals(MemoryImageStore.keyOf("peerA", "img_1_abcd.png"), key)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.get(key))
        assertTrue(store.contains(key))
    }

    @Test
    fun resolveMemoryImageOnlyForBareNamesStillHeld() {
        val store = MemoryImageStore()
        store.put("peerA", "img_1_abcd.png", byteArrayOf(9))
        assertEquals(
            MemoryImageStore.keyOf("peerA", "img_1_abcd.png"),
            resolveMemoryImage("[image] img_1_abcd.png", "peerA", store),
        )
        assertEquals("", resolveMemoryImage("[image] /tmp/img_1_abcd.png", "peerA", store))
        assertEquals("", resolveMemoryImage("[image] img_1_abcd.png", "peerB", store))
        assertEquals("", resolveMemoryImage("[file] img_1_abcd.png", "peerA", store))
        assertEquals("", resolveMemoryImage("[image] missing.png", "peerA", store))
    }

    @Test
    fun peerCannotSeeAnotherPeersPictureName() {
        val store = MemoryImageStore()
        store.put("alice", "photo.png", byteArrayOf(1))
        assertEquals("", resolveMemoryImage("[image] photo.png", "bob", store))
        assertTrue(store.contains(MemoryImageStore.keyOf("alice", "photo.png")))
    }

    @Test
    fun removePeerDropsOnlyThatPeersPictures() {
        val store = MemoryImageStore()
        store.put("alice", "a.png", byteArrayOf(1))
        store.put("bob", "b.png", byteArrayOf(2))
        store.removePeer("alice")
        assertFalse(store.contains(MemoryImageStore.keyOf("alice", "a.png")))
        assertTrue(store.contains(MemoryImageStore.keyOf("bob", "b.png")))
    }

    @Test
    fun evictionKeepsNewestWithinBudget() {
        val store = MemoryImageStore(maxTotalBytes = 6)
        store.put("p", "old.bin", ByteArray(4) { 1 })
        store.put("p", "new.bin", ByteArray(4) { 2 })
        assertFalse(store.contains(MemoryImageStore.keyOf("p", "old.bin")))
        assertArrayEquals(ByteArray(4) { 2 }, store.get(MemoryImageStore.keyOf("p", "new.bin")))
        assertEquals(4L, store.totalBytes)
    }

    @Test
    fun pictureLargerThanBudgetIsNotKept() {
        val store = MemoryImageStore(maxTotalBytes = 4)
        val key = store.put("p", "huge.bin", ByteArray(8) { 3 })
        assertEquals(MemoryImageStore.keyOf("p", "huge.bin"), key)
        assertFalse(store.contains(key))
        assertEquals(0L, store.totalBytes)
    }
}
