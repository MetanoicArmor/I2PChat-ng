package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentTest {
    @Test
    fun plainTextHasNoAttachment() {
        assertNull(buildChatAttachment("hello"))
    }

    @Test
    fun imageLineIsInlinePreview() {
        val att = buildChatAttachment(
            text = "[image] /profiles/a/images/photo.png",
            imagePath = "/profiles/a/images/photo.png",
        )
        assertNotNull(att)
        assertTrue(att!!.inlinePreview)
        assertEquals(MediaKind.Image, att.kind)
        assertEquals("photo.png", att.displayName)
        assertEquals("/profiles/a/images/photo.png", att.path)
    }

    @Test
    fun filePngIsDocumentNotInline() {
        val att = buildChatAttachment(
            text = "[file] /profiles/a/downloads/photo.png",
            filePath = "/profiles/a/downloads/photo.png",
        )
        assertNotNull(att)
        assertFalse(att!!.inlinePreview)
        assertEquals(MediaKind.Image, att.kind)
        assertEquals("photo.png", att.displayName)
    }

    @Test
    fun fileAudioKind() {
        val att = buildChatAttachment(
            text = "[file] /x/downloads/clip.mp3",
            filePath = "/x/downloads/clip.mp3",
        )
        assertNotNull(att)
        assertEquals(MediaKind.Audio, att!!.kind)
        assertFalse(att.inlinePreview)
    }

    @Test
    fun fileVideoKind() {
        val att = buildChatAttachment(
            text = "[file] clip.mp4",
        )
        assertNotNull(att)
        assertEquals(MediaKind.Video, att!!.kind)
        assertEquals("clip.mp4", att.displayName)
        assertFalse(att.inlinePreview)
    }

    @Test
    fun memoryImageIsInline() {
        val att = buildChatAttachment(
            text = "[image] ephemeral.jpg",
            memoryImage = "peer|ephemeral.jpg",
        )
        assertNotNull(att)
        assertTrue(att!!.inlinePreview)
        assertTrue(att.hasMemory)
        assertEquals("ephemeral.jpg", att.displayName)
    }

    @Test
    fun messageUiExposesAttachment() {
        val msg = MessageUi(
            kind = "in",
            text = "[file] /tmp/downloads/a.pdf",
            ts = "12:00",
            filePath = "/tmp/downloads/a.pdf",
        )
        assertNotNull(msg.attachment)
        assertEquals(MediaKind.Other, msg.attachment!!.kind)
        assertEquals("a.pdf", msg.fileCaption)
    }
}
