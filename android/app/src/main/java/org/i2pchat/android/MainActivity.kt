package org.i2pchat.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import org.i2pchat.android.ui.ChatListScreen
import org.i2pchat.android.ui.EmojiChars
import org.i2pchat.android.ui.KeetComposer
import org.i2pchat.android.ui.KeetMessageBubble
import org.i2pchat.android.ui.PeerAvatar
import org.i2pchat.android.ui.theme.I2PChatTheme
import org.i2pchat.android.ui.theme.TerminalPalettes

class MainActivity : ComponentActivity() {
    private val bridge: ChatBridge by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        bridge.consumeOpenIntent(intent)
        setContent {
            val state by bridge.state.collectAsStateWithLifecycle()
            I2PChatTheme(theme = state.theme) {
                I2PChatRoot(bridge)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bridge.consumeOpenIntent(intent)
    }
}

@Composable
fun I2PChatRoot(bridge: ChatBridge) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    LaunchedEffect(state.openChatNonce) {
        if (state.openChatNonce == 0L || !state.running) return@LaunchedEffect
        nav.navigate("conversation") { launchSingleTop = true }
    }
    if (state.tofu != null) {
        TofuDialog(state.tofu!!, onAccept = { bridge.answerTofu(true) }, onReject = { bridge.answerTofu(false) })
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) bridge.answerFileOffer(false)
        else bridge.answerFileOfferSavingTo(uri)
    }
    if (state.fileOffer != null) {
        val offer = state.fileOffer!!
        FileOfferDialog(
            offer,
            bridge,
            onAccept = { bridge.answerFileOffer(true) },
            onReject = { bridge.answerFileOffer(false) },
            onSaveAs = { saveAsLauncher.launch(offer.name) },
        )
    }
    if (!state.nativeLoaded) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.native_failed_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(state.error.ifBlank { stringResource(R.string.native_failed_hint) })
        }
        return
    }
    NavHost(navController = nav, startDestination = if (state.running) "chats" else "profile") {
        composable("profile") { ProfileScreen(bridge, nav, switching = false) }
        composable("switchProfile") { ProfileScreen(bridge, nav, switching = true) }
        composable("status") { StatusScreen(bridge, nav) }
        composable("chats") { ChatListScreen(bridge, nav) }
        composable("conversation") { ConversationScreen(bridge, nav) }
        composable("settings") { SettingsScreen(bridge, nav) }
        composable("router") { RouterScreen(bridge, nav) }
        composable("blindbox") { BlindBoxScreen(bridge, nav) }
        composable("backup") { BackupScreen(bridge, nav) }
        composable("group") { GroupEditScreen(bridge, nav, null) }
        composable("groupEdit") {
            GroupEditScreen(bridge, nav, bridge.state.value.selectedId)
        }
        composable("topology") {
            TopologyScreen(bridge, nav, bridge.state.value.selectedId)
        }
    }
}

/** Status, Chats and Settings sit on top of the chat list in the back stack. */
fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo("chats") { inclusive = route == "chats" }
        launchSingleTop = true
    }
}

@Composable
fun MainBottomBar(nav: NavHostController, current: String) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = current == "status",
            onClick = { nav.goTab("status") },
            label = { Text(stringResource(R.string.tab_status)) },
            icon = { Icon(Icons.Default.Info, null) },
        )
        NavigationBarItem(
            selected = current == "chats",
            onClick = { nav.goTab("chats") },
            label = { Text(stringResource(R.string.tab_chats)) },
            icon = { Text("💬") },
        )
        NavigationBarItem(
            selected = current == "settings",
            onClick = { nav.goTab("settings") },
            label = { Text(stringResource(R.string.tab_settings)) },
            icon = { Icon(Icons.Default.Settings, null) },
        )
    }
}

@Composable
fun peerLinkLabel(state: ChatUiState, contact: ContactUi?): String {
    val addr = contact?.addr ?: return ""
    return when (state.peerLink(addr)) {
        "secure" -> stringResource(R.string.peer_online)
        "connecting" -> stringResource(R.string.peer_connecting)
        else -> if (contact.offlineReady) {
            stringResource(R.string.peer_offline_blindbox)
        } else {
            stringResource(R.string.peer_offline)
        }
    }
}

@Composable
fun PeerLinkDot(link: String) {
    val color = when (link) {
        "secure" -> Color(0xFF3CB86A)
        "connecting" -> Color(0xFFE2B340)
        else -> MaterialTheme.colorScheme.outline
    }
    Text("●", color = color, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
}

@Composable
fun ChatImageThumb(
    path: String = "",
    memoryKey: String = "",
    onOpenPath: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val caption = when {
        path.isNotBlank() -> File(path).name
        memoryKey.isNotBlank() -> memoryKey.substringAfter('\n', memoryKey)
        else -> ""
    }
    val bitmap = remember(path, memoryKey) {
        runCatching {
            decodeChatImageThumb(path, memoryKey)
        }.getOrNull()
    }
    val openPath: (() -> Unit)? = when {
        path.isNotBlank() -> ({
            if (onOpenPath != null) onOpenPath(path) else openReceivedFile(context, path)
        })
        else -> null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = caption,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(if (openPath != null) Modifier.clickable(onClick = openPath) else Modifier),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            "📷 ${caption.ifBlank { path.ifBlank { memoryKey } }}",
            modifier = if (openPath != null) Modifier.clickable(onClick = openPath) else Modifier,
        )
    }
}

/** Thumbnail decode shared by on-disk and in-memory received pictures. */
fun decodeChatImageThumb(path: String, memoryKey: String = ""): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when {
        path.isNotBlank() -> BitmapFactory.decodeFile(path, bounds)
        memoryKey.isNotBlank() -> {
            val bytes = MemoryImages.get(memoryKey) ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        }
        else -> return null
    }
    var sample = 1
    val maxSide = 1280
    while (
        (bounds.outWidth / sample) > maxSide ||
        (bounds.outHeight / sample) > maxSide
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = when {
        path.isNotBlank() -> BitmapFactory.decodeFile(path, opts)
        else -> {
            val bytes = MemoryImages.get(memoryKey) ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
    return bitmap?.asImageBitmap()
}

/** True when `file` sits in a received-media directory of some profile (downloads/ or images/). */
fun isReceivedMediaFile(context: android.content.Context, file: File): Boolean {
    val profiles = File(File(context.filesDir, "i2pchat"), "profiles").canonicalFile
    val real = runCatching { file.canonicalFile }.getOrNull() ?: return false
    val mediaDir = real.parentFile ?: return false
    if (mediaDir.name != "downloads" && mediaDir.name != "images") return false
    // <profile>/images or the older <profile>/data/images layout.
    var profileDir = mediaDir.parentFile ?: return false
    if (profileDir.name == "data") profileDir = profileDir.parentFile ?: return false
    return profileDir.parentFile == profiles
}

fun openReceivedFile(context: android.content.Context, path: String) {
    val file = File(path)
    if (!file.isFile) return
    // Only files the core wrote for us are shared out, and only through the
    // provider: a file:// URI would hand the viewer a path into our sandbox.
    if (!isReceivedMediaFile(context, file)) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file.canonicalFile)
    }.getOrNull() ?: return
    val ext = file.extension.lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when {
            ext in listOf("jpg", "jpeg", "png", "gif", "webp", "heic") -> "image/*"
            ext == "pdf" -> "application/pdf"
            else -> "*/*"
        }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, file.name))
    }
}

fun openReceivedFolder(context: android.content.Context, path: String) {
    val dir = File(path)
    if (!dir.isDirectory || (dir.name != "downloads" && dir.name != "images")) return
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", dir.canonicalFile)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, dir.name)) }
}

/** Same quote block the desktop client inserts into the compose field. */
fun formatReplyQuote(sender: String, text: String): String {
    val who = sender.trim().ifBlank { "message" }
    val quoted = text.trimEnd('\n').lines().joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
    return "@$who wrote:\n$quoted\n\n"
}

@Composable
fun OpenFileWarningDialog(
    path: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = File(path).name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_file_title)) },
        text = { Text(stringResource(R.string.open_file_body, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun MessageActionsDialog(
    msg: MessageUi,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onReply: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSaveMemoryImage: ((String) -> Unit)? = null,
) {
    val path = msg.imagePath.ifBlank { msg.filePath }
    val folder = path.takeIf { it.isNotBlank() }?.let { File(it).parentFile }?.takeIf {
        it.isDirectory && (it.name == "downloads" || it.name == "images")
    }
    val plain = msg.text.isNotBlank() &&
        msg.imagePath.isBlank() &&
        msg.memoryImage.isBlank() &&
        !msg.text.startsWith("[file] ")
    val detail = msg.deliveryHint.ifBlank { msg.deliveryReason }.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_message_actions)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (path.isNotBlank()) {
                    TextButton(onClick = { onOpen(path) }) { Text(stringResource(R.string.action_open)) }
                    TextButton(onClick = { onCopy(path) }) { Text(stringResource(R.string.menu_copy_path)) }
                }
                if (msg.memoryImage.isNotBlank() && onSaveMemoryImage != null) {
                    TextButton(onClick = { onSaveMemoryImage(msg.memoryImage) }) {
                        Text(stringResource(R.string.action_save_as))
                    }
                }
                if (folder != null) {
                    TextButton(onClick = { onOpenFolder(folder.absolutePath) }) { Text(stringResource(R.string.menu_open_folder)) }
                    TextButton(onClick = { onCopy(folder.absolutePath) }) { Text(stringResource(R.string.menu_copy_folder)) }
                }
                if (msg.fileCaption.isNotBlank() && path.isBlank()) {
                    TextButton(onClick = { onCopy(msg.fileCaption) }) { Text(stringResource(R.string.menu_copy_filename)) }
                }
                if (msg.text.isNotBlank()) {
                    TextButton(onClick = { onCopy(msg.text) }) { Text(stringResource(R.string.menu_copy_text)) }
                    if (msg.ts.isNotBlank()) {
                        TextButton(onClick = { onCopy("[${msg.ts}] ${msg.sender}: ${msg.text}") }) {
                            Text(stringResource(R.string.menu_copy_with_time))
                        }
                    }
                }
                if (plain) {
                    TextButton(onClick = onReply) { Text(stringResource(R.string.menu_reply)) }
                }
                if (msg.retryable && plain) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.menu_retry)) }
                }
                if (detail.isNotBlank()) {
                    TextButton(onClick = { onCopy(detail) }) { Text(stringResource(R.string.menu_copy_delivery)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A non-message row stored in history (e.g. group membership changes). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryNoticeRow(msg: MessageUi, onLongClick: () -> Unit = {}) {
    Text(
        msg.text,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 3.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (msg.kind == "error") {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
fun NoticeRow(notice: NoticeUi, technical: Boolean = false) {
    val context = LocalContext.current
    val color = when {
        noticeIsError(notice) -> MaterialTheme.colorScheme.error
        noticeIsSuccess(notice) && !technical -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val body = if (technical) {
        listOf(notice.code, notice.text.ifBlank { notice.arg }).filter { it.isNotBlank() }.joinToString(" · ")
    } else {
        renderNotice(context, notice)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            notice.ts,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            body,
            style = if (technical) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(bridge: ChatBridge, nav: NavHostController, switching: Boolean = false) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var name by remember {
        mutableStateOf(state.profile.ifBlank { "random_address" })
    }
    var opened by remember { mutableStateOf(!switching) }
    var sawStart by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFrom by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var addressTarget by remember { mutableStateOf<String?>(null) }
    var addressPassword by remember { mutableStateOf("") }
    var addressShown by remember { mutableStateOf("") }
    var askPassword by remember { mutableStateOf(false) }
    var opError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        bridge.refreshProfiles()
        bridge.warmRouter()
    }
    LaunchedEffect(state.starting) {
        if (state.starting) sawStart = true
    }
    LaunchedEffect(state.running, state.starting, opened, sawStart) {
        if (!opened) return@LaunchedEffect
        val ready = if (switching) sawStart && state.running && !state.starting else state.running || state.starting
        if (!ready) return@LaunchedEffect
        if (switching) {
            nav.navigate("chats") { popUpTo("chats") { inclusive = true } }
        } else {
            nav.navigate("chats") { popUpTo("profile") { inclusive = true } }
        }
    }
    fun showAddress(profile: String, password: String) {
        busy = true
        opError = ""
        bridge.profileAddress(profile, password) { op ->
            busy = false
            when {
                op.ok -> {
                    addressShown = op.address
                    askPassword = false
                    addressTarget = profile
                }
                op.code == "needs_password" -> {
                    askPassword = true
                    addressShown = ""
                    addressTarget = profile
                }
                else -> opError = op.code
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (switching) R.string.menu_switch_profile else R.string.app_name)) },
                navigationIcon = {
                    if (switching) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_cancel))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.profile_intro))
            Spacer(Modifier.height(16.dp))
            var profilePassword by remember { mutableStateOf("") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.profile_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = profilePassword,
                onValueChange = { profilePassword = it },
                label = { Text(stringResource(R.string.profile_password)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.profile_password_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth()) {
                for (profile in state.profiles) {
                    val saved = profile != "random_address"
                    val address = state.profileAddresses[profile].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { name = profile }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                profile,
                                fontWeight = if (name == profile) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (saved && address.isNotBlank()) {
                                Text(
                                    address,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (saved && profile in state.lockedProfiles) {
                                Text(
                                    stringResource(R.string.profile_address_locked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (saved) {
                            Box {
                                IconButton(onClick = { menuFor = profile }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.cd_profile_menu))
                                }
                                DropdownMenu(
                                    expanded = menuFor == profile,
                                    onDismissRequest = { menuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_menu_address)) },
                                        onClick = {
                                            menuFor = null
                                            addressPassword = ""
                                            addressShown = address
                                            if (address.isNotBlank()) {
                                                askPassword = false
                                                addressTarget = profile
                                            } else {
                                                showAddress(profile, "")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_rename)) },
                                        onClick = {
                                            menuFor = null
                                            renameFrom = profile
                                            renameText = profile
                                            opError = ""
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.profile_delete)) },
                                        onClick = {
                                            menuFor = null
                                            deleteTarget = profile
                                            opError = ""
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (opError.isNotBlank() && addressTarget == null && renameFrom == null && deleteTarget == null) {
                Text(profileOpMessage(opError), color = MaterialTheme.colorScheme.error)
            }
            state.routerIssue?.let { issue ->
                Text(bridge.issueText(issue), color = MaterialTheme.colorScheme.error)
            }
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    opened = true
                    bridge.start(name.ifBlank { "random_address" }, profilePassword)
                },
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.starting) {
                        stringResource(R.string.profile_starting)
                    } else {
                        stringResource(R.string.action_open)
                    },
                )
            }
            if (switching) {
                TextButton(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            TextButton(onClick = { nav.navigate("router") }) { Text(stringResource(R.string.router_settings)) }
        }
    }
    renameFrom?.let { from ->
        AlertDialog(
            onDismissRequest = { if (!busy) renameFrom = null },
            title = { Text(stringResource(R.string.profile_rename_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.profile_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (opError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(profileOpMessage(opError), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && renameText.isNotBlank(),
                    onClick = {
                        busy = true
                        opError = ""
                        bridge.renameProfile(from, renameText) { op ->
                            busy = false
                            if (op.ok) {
                                if (name == from) name = renameText.trim()
                                renameFrom = null
                            } else {
                                opError = op.code.ifBlank { "failed" }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { renameFrom = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.profile_delete_body, target))
                    if (opError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(profileOpMessage(opError), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        opError = ""
                        bridge.deleteProfile(target) { op ->
                            busy = false
                            if (op.ok) {
                                if (name == target) name = ""
                                deleteTarget = null
                            } else {
                                opError = op.code.ifBlank { "failed" }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (addressTarget != null && (addressShown.isNotBlank() || askPassword)) {
        val target = addressTarget!!
        AlertDialog(
            onDismissRequest = {
                addressTarget = null
                addressShown = ""
                askPassword = false
                opError = ""
            },
            title = { Text(stringResource(R.string.profile_address_title)) },
            text = {
                Column {
                    if (addressShown.isNotBlank()) {
                        SelectionContainer {
                            Text(addressShown, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        OutlinedTextField(
                            value = addressPassword,
                            onValueChange = { addressPassword = it },
                            label = { Text(stringResource(R.string.profile_password_for_address)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (opError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(profileOpMessage(opError), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                if (addressShown.isNotBlank()) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("i2p", addressShown))
                    }) { Text(stringResource(R.string.copy_address)) }
                } else {
                    TextButton(
                        enabled = !busy && addressPassword.isNotEmpty(),
                        onClick = { showAddress(target, addressPassword) },
                    ) { Text(stringResource(R.string.action_open)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    addressTarget = null
                    addressShown = ""
                    askPassword = false
                    opError = ""
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun profileOpMessage(code: String): String = when (code) {
    "invalid_name" -> stringResource(R.string.profile_name_invalid)
    "reserved" -> stringResource(R.string.profile_name_reserved)
    "exists" -> stringResource(R.string.profile_exists)
    "missing" -> stringResource(R.string.profile_missing)
    "busy" -> stringResource(R.string.profile_busy)
    "wrong_password" -> stringResource(R.string.profile_password_wrong)
    "needs_password" -> stringResource(R.string.profile_address_locked)
    else -> stringResource(R.string.profile_failed)
}

@Composable
private fun CheckRow(label: String, value: String, check: String, onClick: (() -> Unit)? = null) {
    val (mark, color) = when (check) {
        "ok" -> "✓" to Color(0xFF3CB86A)
        "failed" -> "✗" to MaterialTheme.colorScheme.error
        "pending" -> "…" to Color(0xFFE2B340)
        else -> "–" to MaterialTheme.colorScheme.outline
    }
    val stateText = when (check) {
        "ok" -> stringResource(R.string.state_ok)
        "failed" -> stringResource(R.string.state_failed)
        "pending" -> stringResource(R.string.state_pending)
        else -> stringResource(R.string.state_off)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall)
        }
        Text(stateText, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val router = state.router
    val active = state.running || state.starting
    val issue = state.routerIssue
    val routerDown = state.transport == "failed"
    val routerCheck = when {
        routerDown -> "failed"
        state.samReady -> "ok"
        issue != null -> "failed"
        state.starting -> "pending"
        else -> "off"
    }
    val tunnelsCheck = when {
        state.tunnelsReady -> "ok"
        routerDown -> "failed"
        state.running -> if (issue != null && issue.code != RouterIssueCode.TUNNELS_SLOW) "failed" else "pending"
        state.starting -> "pending"
        else -> "off"
    }
    val copyAddress = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("i2p", state.localAddr))
        Toast.makeText(context, context.getString(R.string.address_copied), Toast.LENGTH_SHORT).show()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.profile.isBlank()) {
                            stringResource(R.string.tab_status)
                        } else {
                            stringResource(R.string.title_with_profile, state.profile)
                        },
                    )
                },
            )
        },
        bottomBar = { MainBottomBar(nav, "status") },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    if (state.tunnelsReady) {
                        stringResource(R.string.status_ready)
                    } else {
                        stringResource(R.string.status_not_ready)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.tunnelsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (state.status.isNotBlank()) {
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }
            if (issue != null || (!active && state.error.isNotBlank())) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.status_problem), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(if (issue != null) bridge.issueText(issue) else state.error)
                            Spacer(Modifier.height(8.dp))
                            if (issue?.offersRestart == true && router.usingBundled) {
                                OutlinedButton(onClick = { bridge.restartBundledRouter() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.restart_bundled))
                                }
                            }
                            OutlinedButton(onClick = { nav.navigate("router") }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.router_settings))
                            }
                        }
                    }
                }
            }
            item {
                CheckRow(
                    stringResource(R.string.check_router),
                    if (router.usingBundled) {
                        stringResource(R.string.router_kind_bundled)
                    } else {
                        stringResource(R.string.router_kind_system, "${router.systemSamHost}:${router.systemSamPort}")
                    },
                    routerCheck,
                )
                CheckRow(
                    stringResource(R.string.check_sam),
                    "${router.samHost}:${router.samPort}",
                    if (routerDown) "failed" else if (state.samReady) "ok" else routerCheck,
                )
                CheckRow(stringResource(R.string.check_tunnels), "", tunnelsCheck)
                CheckRow(
                    stringResource(R.string.check_address),
                    if (state.localAddr.isBlank()) "" else state.localAddr.take(24) + "…",
                    when {
                        routerDown -> "failed"
                        state.localAddr.isNotBlank() -> "ok"
                        active -> "pending"
                        else -> "off"
                    },
                    onClick = if (state.localAddr.isNotBlank()) copyAddress else null,
                )
                CheckRow(
                    stringResource(R.string.check_blindbox),
                    "",
                    when {
                        state.blindboxReady -> "ok"
                        state.running && state.replicaSource != "none" -> "pending"
                        else -> "off"
                    },
                )
                if (!active) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { nav.navigate("profile") { popUpTo("chats") { inclusive = true } } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.status_choose_profile)) }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.status_events), fontWeight = FontWeight.SemiBold)
                if (state.notices.isEmpty()) {
                    Text(stringResource(R.string.status_no_events), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.notices.asReversed()) { notice -> NoticeRow(notice) }
            if (state.uiPrefs.verboseLog) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.verbose_log), fontWeight = FontWeight.SemiBold)
                }
                items(state.verboseLog.asReversed()) { notice -> NoticeRow(notice, technical = true) }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val id = state.selectedId
    val group = state.selectedIsGroup
    val context = LocalContext.current
    var emoji by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    var messageMenu by remember { mutableStateOf<MessageUi?>(null) }
    var pendingOpenPath by remember { mutableStateOf<String?>(null) }
    var pendingSaveMemoryKey by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bridge.sendFile(uri, false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bridge.sendFile(uri, true)
    }
    val saveMemoryImage = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        val key = pendingSaveMemoryKey
        pendingSaveMemoryKey = null
        if (uri != null && key != null) bridge.saveMemoryImage(key, uri)
    }
    LaunchedEffect(id, group) { bridge.selectConversation(id, group) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    val conversation = state.conversations.firstOrNull { it.addr == id }
    val title = conversation?.title ?: id.take(18)
    val contact = state.contacts.firstOrNull { it.addr == id } ?: ContactUi(addr = id).takeIf { !group }
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.display_name)) },
            text = {
                Column {
                    OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newNote, { newNote = it }, label = { Text(stringResource(R.string.peer_note)) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    bridge.setPeerProfile(id, newName, newNote)
                    renaming = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pendingOpenPath?.let { path ->
        OpenFileWarningDialog(
            path = path,
            onConfirm = {
                pendingOpenPath = null
                openReceivedFile(context, path)
            },
            onDismiss = { pendingOpenPath = null },
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(seed = id, label = title, size = 36.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(title, maxLines = 1)
                            if (!group) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PeerLinkDot(state.peerLink(id))
                                    Text(peerLinkLabel(state, contact), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.find_in_chat)) }, onClick = {
                            menu = false
                            finding = !finding
                        })
                        if (!group) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = {
                                menu = false
                                newName = contact?.displayName.orEmpty()
                                newNote = contact?.note.orEmpty()
                                renaming = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_copy_peer)) }, onClick = {
                                menu = false
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(ClipData.newPlainText("i2p", id))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_connect)) }, onClick = { menu = false; bridge.connectSelected() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_disconnect)) }, onClick = { menu = false; bridge.disconnectSelected() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_forget_pin)) }, onClick = { menu = false; bridge.forgetPin(id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_clear_history)) }, onClick = { menu = false; bridge.clearHistory(id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_remove)) }, onClick = {
                                menu = false
                                bridge.removePeer(id)
                                nav.popBackStack()
                            })
                        } else {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit_group)) }, onClick = {
                                menu = false
                                nav.navigate("groupEdit")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_copy_invite)) }, onClick = {
                                menu = false
                                bridge.encodeInvite(id)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_topology)) }, onClick = {
                                menu = false
                                nav.navigate("topology")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete_group)) }, onClick = {
                                menu = false
                                bridge.deleteGroup(id)
                                nav.popBackStack()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().fillMaxSize()) {
            if (finding) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    label = { Text(stringResource(R.string.find_in_chat)) },
                )
            }
            val shown = if (!finding || search.isBlank()) state.messages else state.messages.filter {
                it.text.contains(search, true) || it.sender.contains(search, true)
            }
            LaunchedEffect(shown.size) {
                if (shown.isEmpty() || search.isNotBlank()) return@LaunchedEffect
                val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val atBottom = lastVisible == null || lastVisible >= shown.lastIndex - 1
                if (atBottom) list.animateScrollToItem(shown.lastIndex)
            }
            messageMenu?.let { msg ->
                MessageActionsDialog(
                    msg = msg,
                    onDismiss = { messageMenu = null },
                    onCopy = { text ->
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("i2pchat", text))
                        messageMenu = null
                    },
                    onReply = {
                        val quote = formatReplyQuote(msg.sender, msg.text)
                        val draft = state.compose
                        bridge.setCompose(if (draft.isBlank()) quote else "$draft\n\n$quote")
                        messageMenu = null
                    },
                    onRetry = {
                        bridge.resend(msg.text)
                        messageMenu = null
                    },
                    onOpen = { path ->
                        pendingOpenPath = path
                        messageMenu = null
                    },
                    onOpenFolder = { path ->
                        openReceivedFolder(context, path)
                        messageMenu = null
                    },
                    onSaveMemoryImage = { key ->
                        pendingSaveMemoryKey = key
                        messageMenu = null
                        saveMemoryImage.launch(msg.fileCaption.ifBlank { "image.jpg" })
                    },
                )
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                items(shown) { msg ->
                    if (msg.kind == "system" || msg.kind == "info" || msg.kind == "success" || msg.kind == "error") {
                        HistoryNoticeRow(msg, onLongClick = { messageMenu = msg })
                    } else {
                        KeetMessageBubble(
                            msg = msg,
                            mine = msg.kind == "out" || msg.kind == "me",
                            showSender = group,
                            textStyle = emojiSafeTextStyle(),
                            onOpenFile = { pendingOpenPath = it },
                            onLongClick = { messageMenu = msg },
                        )
                    }
                }
            }
            if (emoji) {
                LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.height(180.dp)) {
                    items(EmojiChars) { glyph ->
                        Text(
                            glyph,
                            style = emojiSafeTextStyle().copy(fontSize = 22.sp),
                            modifier = Modifier
                                .clickable { bridge.setCompose(state.compose + glyph) }
                                .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            KeetComposer(
                text = state.compose,
                onTextChange = bridge::setCompose,
                onSend = { bridge.send() },
                onToggleEmoji = { emoji = !emoji },
                showAttach = !group,
                onPickFile = { filePicker.launch("*/*") },
                onPickImage = { imagePicker.launch("image/*") },
            )
            LaunchedEffect(state.inviteToken) {
                if (state.inviteToken.isNotBlank()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("invite", state.inviteToken))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var maxMessages by remember { mutableStateOf(state.uiPrefs.historyMaxMessages.toString()) }
    var days by remember { mutableStateOf(state.uiPrefs.historyRetentionDays.toString()) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.tab_settings)) })
        },
        bottomBar = { MainBottomBar(nav, "settings") },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.your_address), fontWeight = FontWeight.SemiBold)
                        Text(state.localAddr.ifBlank { stringResource(R.string.waiting_for_address) })
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("i2p", state.localAddr))
                        }) { Text(stringResource(R.string.copy_address)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { nav.navigate("switchProfile") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.menu_switch_profile)) }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.theme), fontWeight = FontWeight.SemiBold)
                FlowRow {
                    listOf(
                        "system" to R.string.theme_system,
                        "light" to R.string.theme_light,
                        "dark" to R.string.theme_dark,
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.theme == value,
                            onClick = { bridge.setTheme(value) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.theme_palette), fontWeight = FontWeight.SemiBold)
                FlowRow {
                    TerminalPalettes.forEach { palette ->
                        FilterChip(
                            selected = state.theme == palette.id,
                            onClick = { bridge.setTheme(palette.id) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(palette.primary),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(palette.label)
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.navigate("router") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.i2p_router))
                }
                Button(onClick = { nav.navigate("blindbox") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.blindbox))
                }
                Button(onClick = { nav.navigate("backup") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_restore))
                }
                Button(onClick = { bridge.pollOffline() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.check_offline_now))
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.verbose_log), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.verbose_log_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.uiPrefs.verboseLog, onCheckedChange = { bridge.setVerboseLog(it) })
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.incoming_folders), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.incoming_folders_hint), style = MaterialTheme.typography.bodySmall)
                var filesDir by remember { mutableStateOf(state.uiPrefs.filesDir) }
                var imagesDir by remember { mutableStateOf(state.uiPrefs.imagesDir) }
                var ephemeral by remember { mutableStateOf(state.uiPrefs.ephemeralMedia) }
                OutlinedTextField(
                    value = filesDir,
                    onValueChange = { filesDir = it },
                    label = { Text(stringResource(R.string.files_folder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imagesDir,
                    onValueChange = { imagesDir = it },
                    label = { Text(stringResource(R.string.images_folder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ephemeral_media), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ephemeral_media_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = ephemeral, onCheckedChange = { ephemeral = it })
                }
                Button(onClick = { bridge.saveMedia(filesDir, imagesDir, ephemeral) }) {
                    Text(stringResource(R.string.action_save))
                }
                Spacer(Modifier.height(16.dp))
                var profilePassword by remember { mutableStateOf("") }
                Text(stringResource(R.string.profile_password), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.profile_password_change_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = profilePassword,
                    onValueChange = { profilePassword = it },
                    label = { Text(stringResource(R.string.profile_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { bridge.setProfilePassword(profilePassword) }) {
                    Text(stringResource(R.string.profile_password_save))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.history_retention), fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = maxMessages,
                    onValueChange = { maxMessages = it },
                    label = { Text(stringResource(R.string.max_messages)) },
                )
                OutlinedTextField(
                    value = days,
                    onValueChange = { days = it },
                    label = { Text(stringResource(R.string.max_age_days)) },
                )
                Button(onClick = {
                    bridge.saveRetention(maxMessages.toIntOrNull() ?: 1000, days.toIntOrNull() ?: 0)
                }) { Text(stringResource(R.string.save_retention)) }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.foreground_note))
        }
    }
}

/**
 * Integer field that keeps a string draft so the user can clear digits and type
 * a replacement. Parsed ints are pushed up only when the draft is a valid number;
 * empty input leaves the last committed [value] unchanged until Save.
 */
@Composable
private fun IntDraftField(
    value: Int,
    onIntChange: (Int) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: @Composable (() -> Unit)? = null,
    maxDigits: Int = 5,
) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(maxDigits)
            text = filtered
            filtered.toIntOrNull()?.let(onIntChange)
        },
        label = label,
        supportingText = supportingText,
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var prefs by remember { mutableStateOf(state.router) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(saved) {
        if (saved) {
            delay(700)
            nav.popBackStack()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.i2p_router)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (BuildConfig.BUNDLED_I2PD) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bundled_i2pd), modifier = Modifier.weight(1f))
                    Switch(
                        checked = prefs.usingBundled,
                        onCheckedChange = { on ->
                            prefs = prefs.copy(backend = if (on) "bundled" else "system")
                        },
                    )
                }
                Text(stringResource(R.string.router_external_hint))
            } else {
                Text(stringResource(R.string.router_no_bundled))
            }
            Spacer(Modifier.height(12.dp))
            IntDraftField(
                value = prefs.tunnelLength,
                onIntChange = { prefs = prefs.copy(tunnelLength = it) },
                label = {
                    Text(stringResource(R.string.tunnel_length, RouterPrefs.MIN_TUNNEL_LENGTH, RouterPrefs.MAX_TUNNEL_LENGTH))
                },
                supportingText = { Text(stringResource(R.string.tunnel_length_hint, RouterPrefs.DEFAULT_TUNNEL_LENGTH)) },
                modifier = Modifier.fillMaxWidth(),
                maxDigits = 1,
            )
            OutlinedTextField(prefs.systemSamHost, { prefs = prefs.copy(systemSamHost = it); hostError = null }, label = { Text(stringResource(R.string.external_host)) }, modifier = Modifier.fillMaxWidth())
            IntDraftField(
                value = prefs.systemSamPort,
                onIntChange = { prefs = prefs.copy(systemSamPort = it) },
                label = { Text(stringResource(R.string.external_sam_port)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.router_allow_remote), modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs.allowRemoteSam,
                    onCheckedChange = { on ->
                        prefs = prefs.copy(allowRemoteSam = on)
                        hostError = null
                    },
                )
            }
            if (BuildConfig.BUNDLED_I2PD) {
                IntDraftField(
                    value = prefs.bundledSamPort,
                    onIntChange = { prefs = prefs.copy(bundledSamPort = it) },
                    label = { Text(stringResource(R.string.bundled_sam_port)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                IntDraftField(
                    value = prefs.bundledHttpProxyPort,
                    onIntChange = { prefs = prefs.copy(bundledHttpProxyPort = it) },
                    label = { Text(stringResource(R.string.http_proxy)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                IntDraftField(
                    value = prefs.bundledSocksProxyPort,
                    onIntChange = { prefs = prefs.copy(bundledSocksProxyPort = it) },
                    label = { Text(stringResource(R.string.socks_proxy)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                IntDraftField(
                    value = prefs.bundledControlHttpPort,
                    onIntChange = { prefs = prefs.copy(bundledControlHttpPort = it) },
                    label = { Text(stringResource(R.string.control_http)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            hostError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (saved) {
                Text(stringResource(R.string.router_saved))
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    val problem = bridge.saveRouter(prefs)
                    if (problem == null) {
                        hostError = null
                        saved = true
                    } else {
                        saved = false
                        hostError = when (problem) {
                            SamHostProblem.EMPTY -> context.getString(R.string.router_host_empty)
                            SamHostProblem.INVALID -> context.getString(R.string.router_host_invalid)
                            SamHostProblem.REMOTE_NOT_ALLOWED -> context.getString(R.string.router_host_remote)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
            if (BuildConfig.BUNDLED_I2PD && prefs.usingBundled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { bridge.restartBundledRouter() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.restart_bundled))
                }
                Text(
                    stringResource(R.string.restart_bundled_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlindBoxScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(state.replicas.joinToString("\n")) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blindbox)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text(
                stringResource(
                    R.string.blindbox_state,
                    stringResource(if (state.blindboxReady) R.string.yes_value else R.string.no_value),
                    state.replicaSource.ifBlank { stringResource(R.string.none_value) },
                ),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                label = { Text(stringResource(R.string.replicas_label)) },
            )
            Button(onClick = {
                bridge.saveReplicas(text.lines().map { it.trim() }.filter { it.isNotBlank() }, emptyMap())
            }) { Text(stringResource(R.string.save_replicas)) }
            Button(onClick = { bridge.pollOffline() }) { Text(stringResource(R.string.check_now)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(bridge: ChatBridge, nav: NavHostController) {
    var passphrase by remember { mutableStateOf("") }
    var includeHistory by remember { mutableStateOf(true) }
    val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) bridge.backup("exportProfile", uri, passphrase, includeHistory)
    }
    val exportHistory = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) bridge.backup("exportHistory", uri, passphrase, true)
    }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) bridge.backup("importProfile", uri, passphrase, includeHistory)
    }
    val importHistory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) bridge.backup("importHistory", uri, passphrase, true)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        val backupState by bridge.state.collectAsStateWithLifecycle()
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.backup_transfer_hint))
            Spacer(Modifier.height(8.dp))
            if (backupState.error.isNotBlank()) {
                Text(backupState.error, color = MaterialTheme.colorScheme.error)
            } else if (backupState.status.isNotBlank()) {
                Text(backupState.status)
            }
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text(stringResource(R.string.passphrase)) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.include_history), modifier = Modifier.weight(1f))
                Switch(checked = includeHistory, onCheckedChange = { includeHistory = it })
            }
            Button(onClick = { exportProfile.launch("i2pchat-profile.i2pbk") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_profile)) }
            Button(onClick = { exportHistory.launch("i2pchat-history.i2pbk") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_history)) }
            Button(onClick = { importProfile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_profile)) }
            Button(onClick = { importHistory.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_history)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditScreen(bridge: ChatBridge, nav: NavHostController, existingId: String?) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val existing = state.groups.firstOrNull { it.addr == existingId }
    var title by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var members by remember { mutableStateOf(existing?.members?.joinToString("\n").orEmpty()) }
    var join by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existingId == null) R.string.new_group else R.string.edit_group)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.group_title)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                members,
                { members = it },
                label = { Text(stringResource(R.string.group_members_hint)) },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Button(onClick = {
                val list = members.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (existingId == null) bridge.createGroup(title, list) else bridge.updateGroup(existingId, title, list)
                nav.popBackStack()
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_save)) }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(join, { join = it }, label = { Text(stringResource(R.string.join_token)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                bridge.joinGroup(join)
                nav.popBackStack()
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.join)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopologyScreen(bridge: ChatBridge, nav: NavHostController, id: String) {
    val state by bridge.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { bridge.loadTopology(id) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_map)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp)) {
            item { Text(state.topologyJson.ifBlank { stringResource(R.string.no_topology) }) }
        }
    }
}

@Composable
fun TofuDialog(prompt: TofuPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(stringResource(if (prompt.kind == "keyChanged") R.string.tofu_key_changed else R.string.tofu_new_key))
        },
        text = {
            Text(
                stringResource(
                    R.string.tofu_body,
                    prompt.peer,
                    prompt.newKey,
                    prompt.oldKey.ifBlank { stringResource(R.string.tofu_no_old_key) },
                ),
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.action_accept)) } },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.action_reject)) } },
    )
}

/** Extra line height so emoji glyphs are not clipped by the font metrics. */
internal fun emojiSafeTextStyle(): TextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = true),
    lineHeight = 24.sp,
)

@Composable
fun FileOfferDialog(
    prompt: FileOfferPrompt,
    bridge: ChatBridge,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onSaveAs: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.incoming_file)) },
        text = {
            Text(stringResource(R.string.file_offer_body, prompt.peer, prompt.name, bridge.formatSize(prompt.size)))
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSaveAs) { Text(stringResource(R.string.action_save_as)) }
                TextButton(onClick = onAccept) { Text(stringResource(R.string.action_accept)) }
            }
        },
        dismissButton = { TextButton(onClick = onReject) { Text(stringResource(R.string.action_reject)) } },
    )
}
