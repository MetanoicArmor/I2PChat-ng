package org.i2pchat.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaPathsTest {
    private lateinit var root: File
    private lateinit var appRoot: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("i2pchat-media").toFile()
        appRoot = File(root, "files/i2pchat").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(dir: File, name: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText("x") }
    }

    @Test
    fun pictureInTheProfileImagesDirIsShown() {
        val picture = write(File(appRoot, "profiles/default/images"), "img_1_abcd.jpg")
        assertEquals(
            picture.canonicalPath,
            resolveChatImagePath("[image] ${picture.absolutePath}", appRoot),
        )
    }

    @Test
    fun pictureInTheStagingCacheIsNotShown() {
        // What a sent picture used to point at: a staged copy outside every media directory.
        val staged = write(File(root, "cache/staging"), "397324")
        assertEquals("", resolveChatImagePath("[image] ${staged.absolutePath}", appRoot))
    }

    @Test
    fun pictureInTheActiveCacheOrCustomDirIsShownOnlyWhenThatDirIsAllowed() {
        val cacheImages = File(root, "cache/incoming/images")
        val custom = File(root, "sdcard/Pictures/chat")
        val cached = write(cacheImages, "photo.jpg")
        val saved = write(custom, "img_2_ef01.png")

        assertEquals("", resolveChatImagePath("[image] ${cached.absolutePath}", appRoot))
        assertEquals("", resolveChatImagePath("[image] ${saved.absolutePath}", appRoot))

        val dirs = listOf(cacheImages, custom)
        assertEquals(
            cached.canonicalPath,
            resolveChatImagePath("[image] ${cached.absolutePath}", appRoot, dirs),
        )
        assertEquals(
            saved.canonicalPath,
            resolveChatImagePath("[image] ${saved.absolutePath}", appRoot, dirs),
        )
    }

    @Test
    fun allowedDirDoesNotCoverItsSubdirsOrOtherPlaces() {
        val cacheImages = File(root, "cache/incoming/images")
        val nested = write(File(cacheImages, "deeper"), "photo.jpg")
        val elsewhere = write(File(root, "cache/other"), "photo.jpg")
        val escape = "[image] ${cacheImages.absolutePath}/../../other/photo.jpg"
        val dirs = listOf(cacheImages)

        assertEquals("", resolveChatImagePath("[image] ${nested.absolutePath}", appRoot, dirs))
        assertEquals("", resolveChatImagePath("[image] ${elsewhere.absolutePath}", appRoot, dirs))
        assertEquals("", resolveChatImagePath(escape, appRoot, dirs))
        // A file that was deleted (cache cleared) is not a picture any more.
        assertEquals(
            "",
            resolveChatImagePath("[image] ${File(cacheImages, "gone.jpg").absolutePath}", appRoot, dirs),
        )
    }

    @Test
    fun receivedFileInACustomDownloadsDirIsResolved() {
        val custom = File(root, "sdcard/Download/chat")
        val file = write(custom, "report.pdf")
        assertEquals("", resolveChatFilePath("[file] ${file.absolutePath}", appRoot))
        assertEquals(
            file.canonicalPath,
            resolveChatFilePath("[file] ${file.absolutePath}", appRoot, listOf(custom)),
        )
    }

    @Test
    fun uniqueFileNeverReusesAnExistingName() {
        val dir = File(root, "images").apply { mkdirs() }
        assertEquals(File(dir, "photo.jpg"), uniqueFileIn(dir, "photo.jpg"))
        write(dir, "photo.jpg")
        assertEquals(File(dir, "photo (1).jpg"), uniqueFileIn(dir, "photo.jpg"))
        write(dir, "photo (1).jpg")
        assertEquals(File(dir, "photo (2).jpg"), uniqueFileIn(dir, "photo.jpg"))
        write(dir, "397324")
        assertEquals(File(dir, "397324 (1)"), uniqueFileIn(dir, "397324"))
    }

    @Test
    fun namesFromOtherAppsStayPlainFileNames() {
        assertEquals("IMG_2024.jpg", safeLocalFileName("IMG_2024.jpg"))
        assertEquals("фото 1.png", safeLocalFileName("фото 1.png"))
        assertEquals("passwd", safeLocalFileName("../../etc/passwd"))
        assertEquals("evil.sh", safeLocalFileName("a\\b\\evil.sh"))
        val fallback = safeLocalFileName("..")
        assertTrue(fallback.startsWith("file-"))
        assertFalse(fallback.contains('/'))
    }

    @Test
    fun captionOfAPictureWhoseFileIsGoneIsItsName() {
        val gone = MessageUi(
            kind = "out",
            text = "[image] /data/user/10/org.i2pchat.android/cache/incoming/images/holiday.jpg",
            ts = "",
        )
        assertEquals("", gone.imagePath)
        assertEquals("holiday.jpg", gone.fileCaption)
    }
}
