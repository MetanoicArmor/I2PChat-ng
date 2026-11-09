package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedFilesTest {
    private val id = "seal_" + "ab".repeat(16)

    @Test
    fun historyLineKeepsTheDisplayNameApartFromTheId() {
        val parsed = parseSealedFileHistory("[file] $id quarterly report.pdf")
        assertEquals(id, parsed?.id)
        assertEquals("quarterly report.pdf", parsed?.displayName)
    }

    @Test
    fun aRealPathIsNotASealedFile() {
        assertNull(parseSealedFileHistory("[file] /tmp/$id report.pdf"))
        assertNull(parseSealedFileHistory("[file] $id"))
        assertNull(parseSealedFileHistory("[image] $id photo.png"))
    }

    @Test
    fun storeIsKeyedByPeerAndId() {
        val store = SealedFileStore()
        store.put("alice", id, "notes.txt", "/cache/$id.bin")
        assertEquals("notes.txt", store.get("alice", id)?.displayName)
        assertEquals("/cache/$id.bin", store.get("alice", id)?.path)
        assertTrue(store.get("bob", id) == null)
        store.removePeer("alice")
        assertTrue(store.get("alice", id) == null)
    }
}
