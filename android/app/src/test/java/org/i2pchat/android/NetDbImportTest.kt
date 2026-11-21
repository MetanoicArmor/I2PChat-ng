package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetDbImportTest {
    private fun ri(publishedMs: Long): ByteArray {
        val keys = ByteArray(256 + 128)
        val cert = byteArrayOf(0, 0, 0)
        val date = ByteArray(8)
        var v = publishedMs
        for (i in 7 downTo 0) {
            date[i] = (v and 0xff).toByte()
            v = v ushr 8
        }
        return keys + cert + date + ByteArray(16)
    }

    private fun zipOf(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun publishedDateRoundTrip() {
        val now = System.currentTimeMillis()
        assertEquals(now, NetDbImport.routerInfoPublishedMs(ri(now)))
        assertEquals(null, NetDbImport.routerInfoPublishedMs(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun acceptsMajorityFresh() {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val bytes = zipOf(
            mapOf(
                "netDb/routerInfo-a.dat" to ri(now - hour),
                "netDb/routerInfo-b.dat" to ri(now - 2 * hour),
                "readme.txt" to "hello".toByteArray(),
            ),
        )
        val stats = NetDbImport.validate(bytes, now)
        assertTrue(stats.accepted)
        assertEquals(3, stats.totalFiles)
        assertEquals(2, stats.freshRouterInfoFiles)
    }

    @Test
    fun rejectsStaleMajority() {
        val now = System.currentTimeMillis()
        val stale = now - (NetDbImport.MAX_AGE_HOURS + 1L) * 3_600_000L
        val bytes = zipOf(
            mapOf(
                "a.dat" to ri(stale),
                "b.dat" to ri(stale),
                "c.dat" to ri(now),
            ),
        )
        val stats = NetDbImport.inspect(bytes, now)
        assertEquals(1, stats.freshRouterInfoFiles)
        assertFalse(stats.accepted)
        try {
            NetDbImport.validate(bytes, now)
            fail("expected ImportException")
        } catch (e: NetDbImport.ImportException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun reseedIssueOffersImport() {
        val issue = RouterIssue(RouterIssueCode.RESEED_FAILED)
        assertTrue(issue.offersImportNetDb)
        assertFalse(issue.offersRestart)
    }
}
