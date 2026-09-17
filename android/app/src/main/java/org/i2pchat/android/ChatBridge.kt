package org.i2pchat.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class ContactUi(
    val addr: String,
    val displayName: String = "",
    val note: String = "",
    val lastPreview: String = "",
    val lastActivityTs: String = "",
    val live: Boolean = false,
    val offlineReady: Boolean = false,
    val unread: Int = 0,
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val epoch: Long = 0,
) {
    val title: String
        get() = displayName.ifBlank { if (isGroup) "Group" else addr.take(18) }
}

data class MessageUi(
    val kind: String,
    val text: String,
    val ts: String,
    val sender: String = "",
    val delivery: String = "",
    val messageId: String = "",
    /** Absolute local path when `text` is `[image] …`; empty for plain messages. */
    val imagePath: String = "",
    /** Absolute local path when `text` is `[file] …`. */
    val filePath: String = "",
) {
    val fileCaption: String
        get() = when {
            imagePath.isNotBlank() -> File(imagePath).name
            filePath.isNotBlank() -> File(filePath).name
            text.startsWith("[file] ") -> File(text.removePrefix("[file] ").trim()).name
            else -> ""
        }
}

private fun resolveMediaPath(text: String, prefix: String, appRoot: File, subdirs: List<String>): String {
    if (!text.startsWith(prefix)) return ""
    val raw = text.removePrefix(prefix).trim()
    if (raw.isBlank()) return ""
    val direct = File(raw)
    if (direct.isFile) return direct.absolutePath
    val name = direct.name
    if (name.isBlank()) return ""
    File(appRoot, "profiles").listFiles()?.forEach { profile ->
        subdirs.forEach { sub ->
            val candidate = File(profile, "$sub/$name")
            if (candidate.isFile) return candidate.absolutePath
        }
    }
    return ""
}

/** Resolve `[image] path` to an existing local file when possible. */
fun resolveChatImagePath(text: String, appRoot: File): String =
    resolveMediaPath(text, "[image] ", appRoot, listOf("images", "data/images"))

/** Resolve `[file] path` under downloads. */
fun resolveChatFilePath(text: String, appRoot: File): String =
    resolveMediaPath(text, "[file] ", appRoot, listOf("downloads", "data/downloads", "files"))


data class TofuPrompt(
    val kind: String,
    val peer: String,
    val newKey: String,
    val oldKey: String,
)

data class ChatUiState(
    val profiles: List<String> = emptyList(),
    val profile: String = "",
    val running: Boolean = false,
    val starting: Boolean = false,
    val status: String = "Stopped",
    val localAddr: String = "",
    val transport: String = "Stopped",
    val contacts: List<ContactUi> = emptyList(),
    val groups: List<ContactUi> = emptyList(),
    val messages: List<MessageUi> = emptyList(),
    val sessionNotices: List<MessageUi> = emptyList(),
    val selectedId: String = "",
    val selectedIsGroup: Boolean = false,
    val compose: String = "",
    val drafts: Map<String, String> = emptyMap(),
    val tofu: TofuPrompt? = null,
    val error: String = "",
    val theme: String = "system",
    val router: RouterPrefs = RouterPrefs(),
    val uiPrefs: UiPrefs = UiPrefs(),
    val blindboxReady: Boolean = false,
    val replicaSource: String = "",
    val replicas: List<String> = emptyList(),
    val topologyJson: String = "",
    val inviteToken: String = "",
    val nativeLoaded: Boolean = true,
) {
    val conversations: List<ContactUi>
        get() = (groups + contacts).sortedByDescending { it.lastActivityTs }
}

class ChatBridge(application: Application) : AndroidViewModel(application), NativeEngine.Listener {
    val appRoot: File = File(application.filesDir, "i2pchat").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(
        ChatUiState(
            router = RouterPrefs.load(appRoot),
            uiPrefs = UiPrefs.load(appRoot),
            theme = UiPrefs.load(appRoot).theme,
        ),
    )
    val state: StateFlow<ChatUiState> = _state
    private var pollJob: Job? = null
    private var draftJob: Job? = null

    init {
        if (!NativeEngine.available) {
            _state.update {
                it.copy(
                    nativeLoaded = false,
                    error = "libi2pchat_jni.so is missing for this ABI. Rebuild after Gradle sync (x86_64 emulator or arm64 phone).",
                )
            }
        } else {
            try {
                NativeEngine.nativeSetListener(this)
                refreshProfiles()
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        nativeLoaded = false,
                        error = error.message ?: "Failed to load native library",
                    )
                }
            }
        }
    }

    fun refreshProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val names = runCatching {
                JSONArray(NativeEngine.nativeListProfiles(appRoot.absolutePath))
            }.getOrNull() ?: JSONArray()
            val list = buildList {
                add("random_address")
                for (i in 0 until names.length()) add(names.getString(i))
            }.distinct()
            _state.update { it.copy(profiles = list) }
        }
    }

    fun setTheme(theme: String) {
        val prefs = _state.value.uiPrefs.copy(theme = theme)
        prefs.save(appRoot)
        _state.update { it.copy(theme = theme, uiPrefs = prefs) }
    }

    fun saveRouter(prefs: RouterPrefs) {
        val normalized = prefs.normalized()
        normalized.save(appRoot)
        _state.update { it.copy(router = normalized) }
    }

    fun saveRetention(maxMessages: Int, days: Int) {
        val prefs = _state.value.uiPrefs.copy(
            historyMaxMessages = maxMessages.coerceAtLeast(0),
            historyRetentionDays = days.coerceAtLeast(0),
        )
        prefs.save(appRoot)
        _state.update { it.copy(uiPrefs = prefs) }
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeSetRetention(prefs.historyMaxMessages, prefs.historyRetentionDays)
        }
    }

    fun warmRouter() {
        val router = RouterPrefs.load(appRoot)
        if (!router.usingBundled) return
        monitorRouterWarmup()
        if (samProbe(router.samHost, router.samPort)) {
            I2pdForegroundService.start(
                getApplication(),
                appRoot,
                true,
                "SAM ready on ${router.samPort}",
            )
            return
        }
        if (I2pdForegroundService.isAlive(appRoot)) {
            I2pdForegroundService.start(
                getApplication(),
                appRoot,
                true,
                I2pdForegroundService.readStatus(appRoot),
            )
        } else {
            I2pdForegroundService.start(getApplication(), appRoot, true, "Starting bundled i2pd…")
        }
    }

    private var routerMonitorJob: Job? = null

    private fun monitorRouterWarmup() {
        routerMonitorJob?.cancel()
        routerMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            val router = RouterPrefs.load(appRoot)
            if (!router.usingBundled) return@launch
            val t0 = System.currentTimeMillis()
            while (isActive && !_state.value.running && !_state.value.starting) {
                if (samProbe(router.samHost, router.samPort)) {
                    onMain {
                        _state.update {
                            it.copy(status = "SAM ready — tap Open (first run can take 1–2 min)")
                        }
                    }
                    return@launch
                }
                val sec = (System.currentTimeMillis() - t0) / 1000
                val msg = I2pdForegroundService.readStatus(appRoot)
                onMain {
                    _state.update {
                        it.copy(
                            status = if (msg.isNotBlank()) {
                                "Waiting for SAM ${router.samPort}… ${sec}s — $msg"
                            } else {
                                "Starting bundled i2pd… ${sec}s"
                            },
                        )
                    }
                }
                delay(400)
            }
        }
    }

    fun restartBundledRouter() {
        viewModelScope.launch(Dispatchers.IO) {
            val router = RouterPrefs.load(appRoot)
            if (!router.usingBundled) {
                onMain { _state.update { it.copy(error = "Bundled router is disabled in settings") } }
                return@launch
            }
            runCatching { NativeEngine.nativeStop() }
            I2pdForegroundService.restartBundled(getApplication(), appRoot)
            onMain {
                _state.update {
                    it.copy(
                        running = false,
                        starting = false,
                        status = "Restarting bundled i2pd…",
                        error = "",
                    )
                }
            }
        }
    }

    fun start(profile: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val router = RouterPrefs.load(appRoot)
            val ui = UiPrefs.load(appRoot)
            _state.update {
                it.copy(
                    starting = true,
                    profile = profile,
                    error = "",
                    status = "Starting I2P…",
                    sessionNotices = listOf(
                        MessageUi(
                            "system",
                            if (router.usingBundled) {
                                "Starting bundled i2pd…"
                            } else {
                                "Starting I2P session, please wait…"
                            },
                            "",
                        ),
                    ),
                )
            }
            routerMonitorJob?.cancel()
            var ready = samProbe(router.samHost, router.samPort)
            if (!ready) {
                if (router.usingBundled) {
                    if (!I2pdForegroundService.isAlive(appRoot)) {
                        I2pdForegroundService.start(
                            getApplication(),
                            appRoot,
                            true,
                            "Starting bundled i2pd…",
                        )
                    }
                } else {
                    I2pdForegroundService.start(
                        getApplication(),
                        appRoot,
                        false,
                        "Using external SAM ${router.samHost}:${router.samPort}",
                    )
                }
                appendNotice("system", "Waiting for SAM ${router.samHost}:${router.samPort}…")
                val timeout = if (router.usingBundled) 90_000 else 8_000
                ready = waitForSam(router.samHost, router.samPort, timeout) { elapsedSec ->
                    val msg = I2pdForegroundService.readStatus(appRoot)
                    _state.update {
                        it.copy(
                            status = buildString {
                                append("Waiting for SAM ${router.samHost}:${router.samPort}… ${elapsedSec}s")
                                if (msg.isNotBlank()) append(" — ").append(msg)
                            },
                        )
                    }
                }
                if (!ready && router.usingBundled) {
                    appendNotice("system", "SAM not ready — restarting bundled i2pd…")
                    I2pdForegroundService.restartBundled(getApplication(), appRoot)
                    ready = waitForSam(router.samHost, router.samPort, 75_000) { elapsedSec ->
                        val msg = I2pdForegroundService.readStatus(appRoot)
                        _state.update {
                            it.copy(
                                status = buildString {
                                    append("Restarting i2pd, waiting for SAM… ${elapsedSec}s")
                                    if (msg.isNotBlank()) append(" — ").append(msg)
                                },
                            )
                        }
                    }
                }
            } else {
                appendNotice("system", "SAM already ready on ${router.samHost}:${router.samPort}")
            }
            if (!ready) {
                val routerStatus = I2pdForegroundService.readStatus(appRoot)
                failStart(
                    "I2P SAM ${router.samHost}:${router.samPort} is not ready. $routerStatus " +
                        "Try Router settings → Restart bundled i2pd, or wait a minute and Open again.",
                )
                return@launch
            }
            val result = runCatching {
                JSONObject(
                    NativeEngine.nativeStart(
                        appRoot.absolutePath,
                        profile,
                        router.samHost,
                        router.samPort,
                        ui.historyMaxMessages,
                        ui.historyRetentionDays,
                    ),
                )
            }.getOrElse { ex ->
                failStart("Cannot start I2P session: ${ex.message}")
                return@launch
            }
            if (!result.optBoolean("ok", false)) {
                failStart(result.optString("error", "Failed to start"))
                return@launch
            }
            val drafts = runCatching {
                val obj = JSONObject(NativeEngine.nativeComposeDrafts(false, "{}"))
                buildMap {
                    obj.keys().forEach { key -> put(key, obj.getString(key)) }
                }
            }.getOrDefault(emptyMap())
            val addr = result.optString("localAddr")
            refreshSnapshot()
            _state.update {
                it.copy(
                    starting = false,
                    running = true,
                    localAddr = addr,
                    drafts = drafts,
                    status = if (addr.isBlank()) "I2P is warming tunnels…" else "Online",
                    error = "",
                )
            }
            startPolling()
        }
    }

    fun stop() {
        pollJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { NativeEngine.nativeStop() }
            I2pdForegroundService.stop(getApplication())
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    status = "Stopped",
                    localAddr = "",
                    contacts = emptyList(),
                    groups = emptyList(),
                    messages = emptyList(),
                    sessionNotices = emptyList(),
                )
            }
        }
    }

    fun selectConversation(id: String, group: Boolean) {
        persistDraft(_state.value.selectedId, _state.value.compose)
        val draft = _state.value.drafts[id].orEmpty()
        _state.update {
            it.copy(
                selectedId = id,
                selectedIsGroup = group,
                compose = draft,
                contacts = it.contacts.map { c -> if (c.addr == id) c.copy(unread = 0) else c },
                groups = it.groups.map { c -> if (c.addr == id) c.copy(unread = 0) else c },
            )
        }
        loadHistory()
    }

    fun setCompose(text: String) {
        _state.update { it.copy(compose = text) }
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(1500)
            persistDraft(_state.value.selectedId, text)
        }
    }

    fun send() {
        val id = _state.value.selectedId
        val text = _state.value.compose.trim()
        if (id.isBlank() || text.isBlank()) return
        _state.update { it.copy(compose = "") }
        persistDraft(id, "")
        viewModelScope.launch(Dispatchers.IO) {
            if (!_state.value.selectedIsGroup) {
                ensureLiveForSend(id)
            }
            if (_state.value.selectedIsGroup) {
                NativeEngine.nativeSendGroupText(id, text)
            } else {
                NativeEngine.nativeSendText(id, text)
            }
            loadHistory()
            refreshSnapshot()
        }
    }

    private fun peerLiveInSnapshot(addr: String): Boolean {
        val snap = runCatching { JSONObject(NativeEngine.nativeSnapshot()) }.getOrNull() ?: return false
        val arr = snap.optJSONArray("contacts") ?: return false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("addr") == addr) return o.optBoolean("live")
        }
        return false
    }

    /** Dial only when sending and not live; native side waits for inbound if tie-break says so. */
    private suspend fun ensureLiveForSend(addr: String) {
        if (peerLiveInSnapshot(addr)) return
        onMain { appendNotice("system", "Подключаемся для отправки…") }
        val ok = runCatching { NativeEngine.nativeConnectPeer(addr) }.getOrDefault(false)
        if (!ok) return
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            refreshSnapshot()
            if (peerLiveInSnapshot(addr)) {
                onMain { appendNotice("system", "Канал готов, отправляем сообщение.") }
                return
            }
        }
    }

    fun connectSelected() {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { NativeEngine.nativeConnectPeer(id) }
                .getOrElse { ex ->
                    onMain { _state.update { it.copy(error = "Connect failed: ${ex.message}") } }
                    false
                }
            if (!ok) {
                onMain { appendNotice("error", "Could not connect to $id") }
            }
            refreshSnapshot()
        }
    }

    fun disconnectSelected() {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup) return
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeDisconnectPeer(id)
            refreshSnapshot()
        }
    }

    fun pollOffline() {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativePollBlindbox()
            refreshSnapshot()
            loadHistory()
        }
    }

    fun sendFile(uri: Uri, image: Boolean) {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup) return
        viewModelScope.launch(Dispatchers.IO) {
            val copied = copyUri(uri) ?: return@launch
            NativeEngine.nativeSendFile(id, copied.absolutePath, image)
            loadHistory()
            refreshSnapshot()
        }
    }

    fun answerTofu(accept: Boolean) {
        _state.update { it.copy(tofu = null) }
        viewModelScope.launch(Dispatchers.IO) { NativeEngine.nativeTrustRespond(accept) }
    }

    fun setPeerProfile(addr: String, name: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeSetPeerProfile(addr, name, note)
            refreshSnapshot()
        }
    }

    fun removePeer(addr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeRemovePeer(addr)
            if (_state.value.selectedId == addr) {
                _state.update { it.copy(selectedId = "", messages = emptyList()) }
            }
            refreshSnapshot()
        }
    }

    fun forgetPin(addr: String) {
        viewModelScope.launch(Dispatchers.IO) { NativeEngine.nativeForgetPin(addr) }
    }

    fun clearHistory(addr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeClearHistory(addr)
            loadHistory()
        }
    }

    fun createGroup(title: String, members: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeCreateGroup(title, JSONArray(members).toString())
            refreshSnapshot()
        }
    }

    fun updateGroup(id: String, title: String, members: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeUpdateGroup(id, title, JSONArray(members).toString())
            refreshSnapshot()
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeDeleteGroup(id)
            refreshSnapshot()
        }
    }

    fun joinGroup(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = JSONObject(NativeEngine.nativeJoinGroup(token))
            if (result.has("error")) {
                _state.update { it.copy(error = result.getString("error")) }
            }
            refreshSnapshot()
        }
    }

    fun encodeInvite(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val token = NativeEngine.nativeEncodeInvite(id)
            _state.update { it.copy(inviteToken = token) }
        }
    }

    fun loadTopology(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(topologyJson = NativeEngine.nativeGroupTopology(id)) }
        }
    }

    fun saveReplicas(endpoints: List<String>, auth: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = JSONObject().put("endpoints", JSONArray(endpoints))
            val authJson = JSONObject()
            auth.forEach { (k, v) -> authJson.put(k, v) }
            json.put("auth", authJson)
            NativeEngine.nativeSaveReplicas(json.toString())
            refreshSnapshot()
        }
    }

    fun backup(op: String, uri: Uri, passphrase: String, includeHistory: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val dest = copyUri(uri, outgoing = op.startsWith("export")) ?: return@launch
            val result = JSONObject(
                NativeEngine.nativeBackup(
                    op,
                    appRoot.absolutePath,
                    _state.value.profile,
                    dest.absolutePath,
                    passphrase,
                    includeHistory,
                ),
            )
            if (op.startsWith("export") && dest.exists()) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    dest.inputStream().use { it.copyTo(out) }
                }
            }
            _state.update {
                it.copy(
                    error = if (result.optBoolean("ok")) "" else result.optString("error"),
                    status = if (result.optBoolean("ok")) "Backup ok" else it.status,
                )
            }
        }
    }

    fun copyLocalAddr(): String = _state.value.localAddr

    private fun persistDraft(id: String, text: String) {
        if (id.isBlank()) return
        val drafts = _state.value.drafts.toMutableMap()
        if (text.isBlank()) drafts.remove(id) else drafts[id] = text
        _state.update { it.copy(drafts = drafts) }
        viewModelScope.launch(Dispatchers.IO) {
            val json = JSONObject()
            drafts.forEach { (k, v) -> json.put(k, v) }
            NativeEngine.nativeComposeDrafts(true, json.toString())
        }
    }

    private fun loadHistory() {
        val id = _state.value.selectedId
        val group = _state.value.selectedIsGroup
        if (id.isBlank()) {
            _state.update { it.copy(messages = emptyList()) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val raw = if (group) NativeEngine.nativeGroupHistory(id) else NativeEngine.nativeHistory(id)
            val rows = JSONArray(raw)
            val messages = buildList {
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    val text = o.optString("text")
                    add(
                        MessageUi(
                            kind = o.optString("kind"),
                            text = text,
                            ts = o.optString("ts").ifBlank { o.optString("createdAt") },
                            sender = o.optString("senderId"),
                            delivery = o.optString("deliveryState"),
                            messageId = o.optString("messageId").ifBlank { o.optString("msgId") },
                            imagePath = resolveChatImagePath(text, appRoot),
                            filePath = resolveChatFilePath(text, appRoot),
                        ),
                    )
                }
            }
            _state.update { it.copy(messages = messages) }
        }
    }

    private fun refreshSnapshot() {
        val snap = runCatching { JSONObject(NativeEngine.nativeSnapshot()) }.getOrNull() ?: return
        val contacts = jsonContacts(snap.optJSONArray("contacts") ?: JSONArray())
        val groups = jsonGroups(snap.optJSONArray("groups") ?: JSONArray())
        val unreadContacts = mergeUnread(contacts, _state.value.contacts)
        val unreadGroups = mergeUnread(groups, _state.value.groups)
        val replicas = buildList {
            val arr = snap.optJSONArray("replicas") ?: JSONArray()
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
        _state.update {
            it.copy(
                localAddr = snap.optString("localAddr", it.localAddr),
                running = snap.optBoolean("running", it.running),
                blindboxReady = snap.optBoolean("blindboxReady"),
                replicaSource = snap.optString("replicaSource"),
                replicas = replicas,
                contacts = unreadContacts,
                groups = unreadGroups,
            )
        }
    }

    private fun mergeUnread(fresh: List<ContactUi>, old: List<ContactUi>): List<ContactUi> {
        val map = old.associateBy { it.addr }
        return fresh.map { it.copy(unread = map[it.addr]?.unread ?: it.unread) }
    }

    private fun jsonContacts(arr: JSONArray): List<ContactUi> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(
                ContactUi(
                    addr = o.getString("addr"),
                    displayName = o.optString("displayName"),
                    note = o.optString("note"),
                    lastPreview = o.optString("lastPreview"),
                    lastActivityTs = o.optString("lastActivityTs"),
                    live = o.optBoolean("live"),
                    offlineReady = o.optBoolean("offlineReady"),
                ),
            )
        }
    }

    private fun jsonGroups(arr: JSONArray): List<ContactUi> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val members = buildList {
                val m = o.optJSONArray("members") ?: JSONArray()
                for (j in 0 until m.length()) add(m.getString(j))
            }
            add(
                ContactUi(
                    addr = o.getString("groupId"),
                    displayName = o.optString("title"),
                    lastPreview = "${members.size} members",
                    isGroup = true,
                    members = members,
                    epoch = o.optLong("epoch"),
                ),
            )
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _state.value.running) {
                delay(25_000)
                runCatching { NativeEngine.nativePollBlindbox() }
            }
        }
    }

    private fun samProbe(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1_000)
                socket.soTimeout = 2_000
                socket.tcpNoDelay = true
                val out = socket.getOutputStream()
                out.write("HELLO VERSION MIN=3.1 MAX=3.3\n".toByteArray())
                out.flush()
                val buf = ByteArray(512)
                val n = socket.getInputStream().read(buf)
                n > 0 && String(buf, 0, n).contains("HELLO REPLY")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForSam(
        host: String,
        port: Int,
        timeoutMs: Int,
        onTick: ((elapsedSec: Int) -> Unit)? = null,
    ): Boolean {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (samProbe(host, port)) {
                return true
            }
            val elapsedSec = ((System.currentTimeMillis() - t0) / 1000).toInt()
            onTick?.invoke(elapsedSec)
            val sleepMs = if (elapsedSec < 45) 100L else 250L
            Thread.sleep(sleepMs)
        }
        return false
    }

    private fun copyUri(uri: Uri, outgoing: Boolean = false): File? {
        val resolver = getApplication<Application>().contentResolver
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file-${System.currentTimeMillis()}"
        val dest = File(getApplication<Application>().cacheDir, name)
        return try {
            if (outgoing) {
                dest
            } else {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun failStart(message: String) {
        onMain {
            _state.update {
                it.copy(
                    starting = false,
                    running = false,
                    transport = "Failed",
                    status = "Failed",
                    error = message,
                )
            }
            appendNotice("error", message)
        }
    }

    private fun appendNotice(kind: String, text: String) {
        val noticeKind = if (text.startsWith("Online! My Address:")) "success" else kind
        _state.update {
            val notices = if (it.sessionNotices.lastOrNull()?.text == text) {
                it.sessionNotices
            } else {
                it.sessionNotices + MessageUi(noticeKind, text, "")
            }
            it.copy(sessionNotices = notices, status = text)
        }
    }

    override fun onSystem(message: String) = onMain {
        appendNotice("system", message)
        if (message.startsWith("Disconnected from")) {
            viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
        }
    }

    override fun onError(message: String) = onMain {
        appendNotice("error", message)
        _state.update { it.copy(error = message) }
    }

    override fun onHistory(peer: String, json: String) = onMain {
        val entry = runCatching { JSONObject(json) }.getOrNull() ?: return@onMain
        val kind = entry.optString("kind")
        if ((kind == "in" || kind == "peer") && peer != _state.value.selectedId) {
            _state.update {
                it.copy(
                    contacts = it.contacts.map { c ->
                        if (c.addr == peer) c.copy(unread = c.unread + 1, lastPreview = entry.optString("text")) else c
                    },
                )
            }
            NotificationHelper.notifyMessage(
                getApplication(),
                peer,
                entry.optString("text"),
            )
        }
        if (peer == _state.value.selectedId && !_state.value.selectedIsGroup) {
            loadHistory()
        }
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onDelivery(json: String) = onMain { loadHistory() }

    override fun onPeerState(peer: String, state: String, reason: String) = onMain {
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onTransportState(state: String, reason: String) = onMain {
        _state.update { it.copy(transport = state, status = reason.ifBlank { state }) }
    }

    override fun onTransfer(peer: String, json: String) = onMain { }

    override fun onFileReceived(peer: String, path: String) = onMain {
        NotificationHelper.notifyMessage(getApplication(), peer, "File received: ${File(path).name}")
        if (peer == _state.value.selectedId) loadHistory()
    }

    override fun onImageReceived(peer: String, path: String) = onMain {
        NotificationHelper.notifyMessage(getApplication(), peer, "Image received")
        if (peer == _state.value.selectedId) loadHistory()
        else {
            _state.update {
                it.copy(
                    contacts = it.contacts.map { c ->
                        if (c.addr == peer) c.copy(unread = c.unread + 1, lastPreview = "📷 Image") else c
                    },
                )
            }
        }
    }

    override fun onImageText(peer: String, text: String) = onMain {
        if (peer == _state.value.selectedId) loadHistory()
    }

    override fun onContactsChanged() = onMain {
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onGroupMessage(groupId: String) = onMain {
        if (groupId != _state.value.selectedId) {
            _state.update {
                it.copy(
                    groups = it.groups.map { g ->
                        if (g.addr == groupId) g.copy(unread = g.unread + 1) else g
                    },
                )
            }
        } else {
            loadHistory()
        }
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onLocalAddress(addr: String) = onMain {
        _state.update { it.copy(localAddr = addr) }
    }

    override fun onTrustPrompt(kind: String, peer: String, newKey: String, oldKey: String) = onMain {
        _state.update { it.copy(tofu = TofuPrompt(kind, peer, newKey, oldKey)) }
        NotificationHelper.notifyTrustPrompt(getApplication(), peer)
        appendNotice("system", "Нужно подтвердить ключ $peer для handshake")
    }

    override fun onStarted() = onMain {
        _state.update {
            it.copy(
                running = true,
                starting = false,
                status = if (it.localAddr.isBlank()) "I2P is warming tunnels…" else "Online",
            )
        }
    }

    override fun onStartFailed(message: String) = onMain {
        _state.update { it.copy(running = false, starting = false, error = message, status = "Failed") }
    }

    fun consumeOpenIntent(intent: Intent?) {
        val peer = intent?.getStringExtra("openPeer") ?: return
        selectConversation(peer, false)
    }

    override fun onCleared() {
        NativeEngine.nativeSetListener(null)
        runCatching { NativeEngine.nativeStop() }
        super.onCleared()
    }
}
