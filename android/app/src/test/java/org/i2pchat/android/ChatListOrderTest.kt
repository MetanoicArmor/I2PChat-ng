package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListOrderTest {
    @Test
    fun shouldBumpUnread_whenSamePeerButConversationNotVisible() {
        assertTrue(
            shouldBumpUnread(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = false,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun shouldBumpUnread_whenSamePeerBackgroundedWhileViewing() {
        assertTrue(
            shouldBumpUnread(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = true,
                appInForeground = false,
            ),
        )
    }

    @Test
    fun shouldBumpUnread_whenDifferentPeer() {
        assertTrue(
            shouldBumpUnread(
                conversationId = "peer-b",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = true,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun shouldNotBumpUnread_whenForegroundConversation() {
        assertFalse(
            shouldBumpUnread(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = true,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun shouldNotify_whenBackgroundedEvenIfConversationVisible() {
        assertTrue(
            shouldNotifyMessage(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = true,
                appInForeground = false,
                notificationsMuted = false,
            ),
        )
    }

    @Test
    fun shouldNotNotify_whenMuted() {
        assertFalse(
            shouldNotifyMessage(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-b",
                selectedIsGroup = false,
                conversationVisible = false,
                appInForeground = false,
                notificationsMuted = true,
            ),
        )
    }

    @Test
    fun shouldNotNotify_whenViewingForegroundChat() {
        assertFalse(
            shouldNotifyMessage(
                conversationId = "peer-a",
                isGroup = false,
                selectedId = "peer-a",
                selectedIsGroup = false,
                conversationVisible = true,
                appInForeground = true,
                notificationsMuted = false,
            ),
        )
    }

    @Test
    fun sortConversations_pinnedThenMru() {
        val rows = listOf(
            ContactUi(addr = "u1", lastActivityTs = "2026-01-03T00:00:00Z"),
            ContactUi(addr = "p2", pinned = true, pinOrder = 1, lastActivityTs = "2026-01-01T00:00:00Z"),
            ContactUi(addr = "u0", lastActivityTs = "2026-01-04T00:00:00Z"),
            ContactUi(addr = "p1", pinned = true, pinOrder = 0, lastActivityTs = "2026-01-09T00:00:00Z"),
            ContactUi(addr = "uBlank", lastActivityTs = ""),
        )
        assertEquals(
            listOf("p1", "p2", "u0", "u1", "uBlank"),
            sortConversations(rows).map { it.addr },
        )
    }
}
