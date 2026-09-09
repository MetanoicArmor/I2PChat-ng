package org.i2pchat.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.i2pchat.android.ui.EmojiChars
import org.i2pchat.android.ui.theme.I2PChatTheme

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        bridge.consumeOpenIntent(intent)
    }
}

@Composable
fun I2PChatRoot(bridge: ChatBridge) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    if (state.tofu != null) {
        TofuDialog(state.tofu!!, onAccept = { bridge.answerTofu(true) }, onReject = { bridge.answerTofu(false) })
    }
    if (!state.nativeLoaded) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Native library failed to load", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(state.error.ifBlank {
                "Rebuild the app after Gradle sync. The emulator needs the x86_64 native library; a phone needs arm64-v8a."
            })
        }
        return
    }
    NavHost(navController = nav, startDestination = if (state.running) "chats" else "profile") {
        composable("profile") { ProfileScreen(bridge, nav) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(state.profiles.firstOrNull().orEmpty()) }
    LaunchedEffect(Unit) {
        bridge.refreshProfiles()
        bridge.warmRouter()
    }
    LaunchedEffect(state.running, state.starting) {
        if (state.running && !state.starting) {
            nav.navigate("chats") { popUpTo("profile") { inclusive = true } }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("I2PChat") }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).fillMaxSize()) {
            Text("Choose profile", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use random_address for a one-time session. TOFU pins are not stored in that mode. " +
                    "I2P on a phone keeps a foreground service running and uses battery.",
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(state.profiles) { profile ->
                    Text(
                        profile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { name = profile }
                            .padding(12.dp),
                    )
                }
            }
            if (state.error.isNotBlank()) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { bridge.start(name.ifBlank { "random_address" }) },
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.starting) "Starting I2P…" else "Open") }
            TextButton(onClick = { nav.navigate("router") }) { Text("Router settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var newPeer by remember { mutableStateOf(false) }
    var peerAddr by remember { mutableStateOf("") }
    val filtered = state.conversations.filter {
        query.isBlank() || it.title.contains(query, true) || it.addr.contains(query, true)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.localAddr.isBlank()) "I2PChat" else "I2PChat @ ${state.profile}") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New chat") }, onClick = {
                            menu = false
                            newPeer = true
                        })
                        DropdownMenuItem(text = { Text("New group") }, onClick = {
                            menu = false
                            nav.navigate("group")
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false
                            nav.navigate("settings")
                        })
                        DropdownMenuItem(text = { Text("Switch profile") }, onClick = {
                            menu = false
                            bridge.stop()
                            nav.navigate("profile") { popUpTo("chats") { inclusive = true } }
                        })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, label = { Text("Chats") }, icon = { Text("💬") })
                NavigationBarItem(
                    selected = false,
                    onClick = { nav.navigate("settings") },
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, null) },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newPeer = true }) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        },
    ) { padding ->
        if (newPeer) {
            AlertDialog(
                onDismissRequest = { newPeer = false },
                title = { Text("Open chat") },
                text = {
                    OutlinedTextField(
                        value = peerAddr,
                        onValueChange = { peerAddr = it },
                        label = { Text("I2P address") },
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
                    }) { Text("Open") }
                },
                dismissButton = { TextButton(onClick = { newPeer = false }) { Text("Cancel") } },
            )
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Search") },
            )
            Text(
                "${state.status} · ${state.transport}" +
                    if (state.localAddr.isNotBlank()) " · ${state.localAddr.take(20)}…" else "",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.addr }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                bridge.selectConversation(item.addr, item.isGroup)
                                nav.navigate("conversation")
                            }
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (item.unread > 0) Text(item.unread.toString(), color = MaterialTheme.colorScheme.primary)
                            if (item.live) Text(" live", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            if (item.isGroup) "Group · ${item.lastPreview}" else item.lastPreview.ifBlank { item.addr },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
    var search by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bridge.sendFile(uri, false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) bridge.sendFile(uri, true)
    }
    LaunchedEffect(id, group) { bridge.selectConversation(id, group) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex)
    }
    val title = state.conversations.firstOrNull { it.addr == id }?.title ?: id.take(18)
    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Display name") },
            text = {
                OutlinedTextField(newName, { newName = it }, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    bridge.setPeerProfile(id, newName, state.contacts.firstOrNull { it.addr == id }?.note.orEmpty())
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!group) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Connect") }, onClick = { menu = false; bridge.connectSelected() })
                            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; bridge.disconnectSelected() })
                            DropdownMenuItem(text = { Text("Forget TOFU pin") }, onClick = { menu = false; bridge.forgetPin(id) })
                            DropdownMenuItem(text = { Text("Clear history") }, onClick = { menu = false; bridge.clearHistory(id) })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = {
                                menu = false
                                bridge.removePeer(id)
                                nav.popBackStack()
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Edit group") }, onClick = {
                                menu = false
                                nav.navigate("groupEdit")
                            })
                            DropdownMenuItem(text = { Text("Copy invite") }, onClick = {
                                menu = false
                                bridge.encodeInvite(id)
                            })
                            DropdownMenuItem(text = { Text("Topology") }, onClick = {
                                menu = false
                                nav.navigate("topology")
                            })
                            DropdownMenuItem(text = { Text("Delete group") }, onClick = {
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
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text("Find in chat") },
            )
            val shown = if (search.isBlank()) state.messages else state.messages.filter {
                it.text.contains(search, true) || it.sender.contains(search, true)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                items(shown) { msg ->
                    val mine = msg.kind == "out" || msg.kind == "me"
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    ) {
                        Card {
                            Column(Modifier.padding(10.dp)) {
                                if (group && msg.sender.isNotBlank()) {
                                    Text(msg.sender.take(16), style = MaterialTheme.typography.labelSmall)
                                }
                                Text(msg.text)
                                Text(
                                    listOf(msg.ts.take(19), msg.delivery).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            if (emoji) {
                LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.height(180.dp)) {
                    items(EmojiChars) { glyph ->
                        Text(
                            glyph,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clickable { bridge.setCompose(state.compose + glyph) }
                                .padding(8.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { emoji = !emoji }) { Icon(Icons.Default.EmojiEmotions, "Emoji") }
                if (!group) {
                    IconButton(onClick = { filePicker.launch("*/*") }) { Icon(Icons.Default.AttachFile, "File") }
                    IconButton(onClick = { imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, "Image") }
                }
                OutlinedTextField(
                    value = state.compose,
                    onValueChange = bridge::setCompose,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                IconButton(onClick = { bridge.send() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            LaunchedEffect(state.inviteToken) {
                if (state.inviteToken.isNotBlank()) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("invite", state.inviteToken))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var maxMessages by remember { mutableStateOf(state.uiPrefs.historyMaxMessages.toString()) }
    var days by remember { mutableStateOf(state.uiPrefs.historyRetentionDays.toString()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp)) {
            item {
                Text("Theme", fontWeight = FontWeight.SemiBold)
                Row {
                    listOf("system", "light", "dark").forEach { value ->
                        FilterChip(
                            selected = state.theme == value,
                            onClick = { bridge.setTheme(value) },
                            label = { Text(value) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Your address", fontWeight = FontWeight.SemiBold)
                Text(state.localAddr.ifBlank { "Waiting for SAM…" })
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("i2p", state.localAddr))
                }) { Text("Copy address") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { nav.navigate("router") }, modifier = Modifier.fillMaxWidth()) { Text("I2P router") }
                Button(onClick = { nav.navigate("blindbox") }, modifier = Modifier.fillMaxWidth()) { Text("BlindBox") }
                Button(onClick = { nav.navigate("backup") }, modifier = Modifier.fillMaxWidth()) { Text("Backup / restore") }
                Button(onClick = { bridge.pollOffline() }, modifier = Modifier.fillMaxWidth()) { Text("Check offline now") }
                Spacer(Modifier.height(16.dp))
                Text("History retention", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = maxMessages, onValueChange = { maxMessages = it }, label = { Text("Max messages") })
                OutlinedTextField(value = days, onValueChange = { days = it }, label = { Text("Max age (days, 0 = none)") })
                Button(onClick = {
                    bridge.saveRetention(maxMessages.toIntOrNull() ?: 1000, days.toIntOrNull() ?: 0)
                }) { Text("Save retention") }
                Spacer(Modifier.height(16.dp))
                Text("I2P stays in a foreground service on this phone. That is required to keep tunnels alive; it will use battery.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterScreen(bridge: ChatBridge, nav: NavHostController) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var prefs by remember { mutableStateOf(state.router) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("I2P router") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bundled i2pd", modifier = Modifier.weight(1f))
                Switch(
                    checked = prefs.usingBundled,
                    onCheckedChange = { on ->
                        prefs = prefs.copy(backend = if (on) "bundled" else "system")
                    },
                )
            }
            Text("External SAM is any I2P router on this phone (loopback only). Bundled needs libi2pd.so in jniLibs/arm64-v8a.")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(prefs.systemSamHost, { prefs = prefs.copy(systemSamHost = it) }, label = { Text("External host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefs.systemSamPort.toString(), { prefs = prefs.copy(systemSamPort = it.toIntOrNull() ?: prefs.systemSamPort) }, label = { Text("External SAM port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefs.bundledSamPort.toString(), { prefs = prefs.copy(bundledSamPort = it.toIntOrNull() ?: prefs.bundledSamPort) }, label = { Text("Bundled SAM port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefs.bundledHttpProxyPort.toString(), { prefs = prefs.copy(bundledHttpProxyPort = it.toIntOrNull() ?: prefs.bundledHttpProxyPort) }, label = { Text("HTTP proxy") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefs.bundledSocksProxyPort.toString(), { prefs = prefs.copy(bundledSocksProxyPort = it.toIntOrNull() ?: prefs.bundledSocksProxyPort) }, label = { Text("SOCKS proxy") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prefs.bundledControlHttpPort.toString(), { prefs = prefs.copy(bundledControlHttpPort = it.toIntOrNull() ?: prefs.bundledControlHttpPort) }, label = { Text("Control HTTP") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick = { bridge.saveRouter(prefs) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
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
                title = { Text("BlindBox") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Ready: ${state.blindboxReady} · source: ${state.replicaSource.ifBlank { "none" }}")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                label = { Text("Replica endpoints, one per line") },
            )
            Button(onClick = {
                bridge.saveReplicas(text.lines().map { it.trim() }.filter { it.isNotBlank() }, emptyMap())
            }) { Text("Save replicas") }
            Button(onClick = { bridge.pollOffline() }) { Text("Check now") }
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
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include history", modifier = Modifier.weight(1f))
                Switch(checked = includeHistory, onCheckedChange = { includeHistory = it })
            }
            Button(onClick = { exportProfile.launch("i2pchat-profile.i2pbk") }, modifier = Modifier.fillMaxWidth()) { Text("Export profile") }
            Button(onClick = { exportHistory.launch("i2pchat-history.i2pbk") }, modifier = Modifier.fillMaxWidth()) { Text("Export history") }
            Button(onClick = { importProfile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import profile") }
            Button(onClick = { importHistory.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import history") }
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
                title = { Text(if (existingId == null) "New group" else "Edit group") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                members,
                { members = it },
                label = { Text("Members, one address per line") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Button(onClick = {
                val list = members.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (existingId == null) bridge.createGroup(title, list) else bridge.updateGroup(existingId, title, list)
                nav.popBackStack()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(join, { join = it }, label = { Text("Join with invite token") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                bridge.joinGroup(join)
                nav.popBackStack()
            }, modifier = Modifier.fillMaxWidth()) { Text("Join") }
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
                title = { Text("Group map") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp)) {
            item { Text(state.topologyJson.ifBlank { "No topology yet" }) }
        }
    }
}

@Composable
fun TofuDialog(prompt: TofuPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(if (prompt.kind == "keyChanged") "Signing key changed" else "New peer key") },
        text = {
            Text(
                "Peer ${prompt.peer}\nNew: ${prompt.newKey}\nOld: ${prompt.oldKey.ifBlank { "(none)" }}\n\nAccept only if you expected this identity.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject") } },
    )
}
