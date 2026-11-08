package org.i2pchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import org.i2pchat.android.ChatBridge
import org.i2pchat.android.ChatUiState
import org.i2pchat.android.ContactUi
import org.i2pchat.android.MainBottomBar
import org.i2pchat.android.R
import org.i2pchat.android.goTab
import org.i2pchat.android.peerLinkLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var startSheet by remember { mutableStateOf(false) }
    var newPeer by remember { mutableStateOf(false) }
    var pasteInvite by remember { mutableStateOf(false) }
    var peerAddr by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    val filtered = state.conversations.filter {
        query.isBlank() || it.title.contains(query, true) || it.addr.contains(query, true)
    }
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
    )
    if (newPeer) {
        AlertDialog(
            onDismissRequest = { newPeer = false },
            title = { Text(stringResource(R.string.open_chat_title)) },
            text = {
                OutlinedTextField(
                    value = peerAddr,
                    onValueChange = { peerAddr = it },
                    label = { Text(stringResource(R.string.i2p_address)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val addr = peerAddr.trim()
                    newPeer = false
                    if (addr.isNotBlank()) {
                        bridge.selectConversation(addr, false)
                        nav.navigate("conversation")
                    }
                }) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = {
                TextButton(onClick = { newPeer = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (pasteInvite) {
        AlertDialog(
            onDismissRequest = { pasteInvite = false },
            title = { Text(stringResource(R.string.paste_invite)) },
            text = {
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    label = { Text(stringResource(R.string.join_token)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val token = invite.trim()
                    pasteInvite = false
                    if (token.isNotBlank()) bridge.joinGroup(token)
                }) { Text(stringResource(R.string.join)) }
            },
            dismissButton = {
                TextButton(onClick = { pasteInvite = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (startSheet) {
        ModalBottomSheet(onDismissRequest = { startSheet = false }) {
            SheetAction(stringResource(R.string.menu_new_chat)) {
                startSheet = false
                newPeer = true
            }
            SheetAction(stringResource(R.string.menu_new_group)) {
                startSheet = false
                nav.navigate("group")
            }
            SheetAction(stringResource(R.string.paste_invite)) {
                startSheet = false
                pasteInvite = true
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = barColors,
                navigationIcon = {
                    PeerAvatar(
                        seed = state.localAddr.ifBlank { state.profile },
                        label = state.profile.ifBlank { stringResource(R.string.app_name) },
                        size = 36.dp,
                        onClick = { nav.goTab("settings") },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
                title = { Text(stringResource(R.string.tab_chats), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                    }
                    IconButton(onClick = { startSheet = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_new_chat))
                    }
                },
            )
        },
        bottomBar = { MainBottomBar(nav, "chats") },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.tunnelsReady) {
                Text(
                    stringResource(R.string.network_not_ready),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { nav.goTab("status") }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search)) },
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.addr }) { item ->
                    ChatRow(item, state, onClick = {
                        bridge.selectConversation(item.addr, item.isGroup)
                        nav.navigate("conversation")
                    })
                }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ChatRow(
    item: ContactUi,
    state: ChatUiState,
    onClick: () -> Unit,
) {
    val title = if (item.isGroup && item.displayName.isBlank()) {
        stringResource(R.string.group_default_title)
    } else {
        item.title
    }
    val preview = if (item.isGroup) {
        item.lastPreview.ifBlank { stringResource(R.string.group_members, item.members.size) }
    } else {
        item.lastPreview.ifBlank { peerLinkLabel(state, item) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(seed = item.addr, label = title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.lastActivityTs.isNotBlank()) {
                    Text(
                        shortClock(item.lastActivityTs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.unread > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadBadge(item.unread)
        }
    }
}
