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
    val deliveryHint: String = "",
    val deliveryReason: String = "",
    val retryable: Boolean = false,
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
    val profilesDir = File(appRoot, "profiles").canonicalFile
    // A peer can type "[image] /some/path" as plain text; only a path the core
    // itself wrote under a profile's media directory counts as media.
    if (direct.isAbsolute) {
        val real = runCatching { direct.canonicalFile }.getOrNull() ?: return ""
        if (!real.isFile) return ""
        val allowed = profilesDir.listFiles()?.any { profile ->
            subdirs.any { sub -> File(profile, sub).canonicalFile == real.parentFile }
        } ?: false
        return if (allowed) real.absolutePath else ""
    }
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


/**
 * A service notice. `level` is "user" (shown on the Status screen),
 * "verbose" (only with the Verbose log setting) or "peer_state" (drives the
 * contact badge). `text` is the English fallback for unknown codes.
 */
data class NoticeUi(
    val code: String,
    val level: String,
    val arg: String = "",
    val text: String = "",
    val ts: String = "",
) {
    val isUser: Boolean get() = level == LEVEL_USER

    companion object {
        const val LEVEL_USER = "user"
        const val LEVEL_VERBOSE = "verbose"
        const val LEVEL_PEER_STATE = "peer_state"
    }
}

data class TofuPrompt(
    val kind: String,
    val peer: String,
    val newKey: String,
    val oldKey: String,
)

data class FileOfferPrompt(
    val peer: String,
    val name: String,
    val size: Long,
)

data class ProfileOp(
    val ok: Boolean,
    val code: String = "",
    val address: String = "",
    val error: String = "",
)

private fun profileOp(json: String): ProfileOp {
    val obj = runCatching { JSONObject(json) }.getOrNull()
        ?: return ProfileOp(ok = false, code = "failed")
    return ProfileOp(
        ok = obj.optBoolean("ok"),
        code = obj.optString("code"),
        address = obj.optString("address"),
        error = obj.optString("error"),
    )
}

data class ChatUiState(
    val profiles: List<String> = emptyList(),
    /** Base32 address for a saved profile that can be opened without a password. */
    val profileAddresses: Map<String, String> = emptyMap(),
    /** Saved profiles whose identity is sealed with a password. */
    val lockedProfiles: Set<String> = emptySet(),
    val profile: String = "",
    val running: Boolean = false,
    val starting: Boolean = false,
    val status: String = "",
    val localAddr: String = "",
    val transport: String = "stopped",
    val contacts: List<ContactUi> = emptyList(),
    val groups: List<ContactUi> = emptyList(),
    val messages: List<MessageUi> = emptyList(),
    val notices: List<NoticeUi> = emptyList(),
    val verboseLog: List<NoticeUi> = emptyList(),
    /** Raw peer link state from the engine: connecting / handshaking / secure / disconnected / failed / stale. */
    val peerStates: Map<String, String> = emptyMap(),
    val samReady: Boolean = false,
    val routerIssue: RouterIssue? = null,
    val selectedId: String = "",
    val selectedIsGroup: Boolean = false,
    val compose: String = "",
    val drafts: Map<String, String> = emptyMap(),
    val tofu: TofuPrompt? = null,
    val fileOffer: FileOfferPrompt? = null,
    val error: String = "",
    val theme: String = "dark",
    val router: RouterPrefs = RouterPrefs().normalized(),
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

    val tunnelsReady: Boolean
        get() = running && transport == "ready"

    /** secure / connecting / offline for the contact badge. */
    fun peerLink(addr: String): String {
        if (contacts.firstOrNull { it.addr == addr }?.live == true) return "secure"
        return when (peerStates[addr]) {
            "secure" -> "secure"
            "connecting", "handshaking" -> "connecting"
            else -> "offline"
        }
    }
}

private const val MAX_NOTICES = 200
private const val MAX_VERBOSE = 500

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
                    error = str(R.string.native_missing_abi),
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
                        error = error.message ?: str(R.string.native_load_failed),
                    )
                }
            }
        }
    }

    fun refreshProfiles() {
        viewModelScope.launch(Dispatchers.IO) { loadProfiles() }
    }

    private fun loadProfiles() {
        val names = runCatching {
            JSONArray(NativeEngine.nativeListProfiles(appRoot.absolutePath))
        }.getOrNull() ?: JSONArray()
        val saved = buildList {
            for (i in 0 until names.length()) add(names.getString(i))
        }.distinct()
        val addresses = mutableMapOf<String, String>()
        val locked = mutableSetOf<String>()
        for (name in saved) {
            val op = profileOp(NativeEngine.nativeProfileAddress(appRoot.absolutePath, name, ""))
            when {
                op.ok && op.address.isNotBlank() -> addresses[name] = op.address
                op.code == "needs_password" -> locked += name
            }
        }
        val list = buildList {
            add("random_address")
            addAll(saved)
        }.distinct()
        _state.update {
            it.copy(profiles = list, profileAddresses = addresses, lockedProfiles = locked)
        }
    }

    fun profileAddress(name: String, password: String, done: (ProfileOp) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val op = runCatching {
                profileOp(NativeEngine.nativeProfileAddress(appRoot.absolutePath, name, password))
            }.getOrElse { ProfileOp(ok = false, code = "failed", error = it.message.orEmpty()) }
            if (op.ok && op.address.isNotBlank()) {
                _state.update {
                    it.copy(
                        profileAddresses = it.profileAddresses + (name to op.address),
                        lockedProfiles = it.lockedProfiles - name,
                    )
                }
            }
            onMain { done(op) }
        }
    }

    fun renameProfile(from: String, to: String, done: (ProfileOp) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val op = runCatching {
                profileOp(NativeEngine.nativeRenameProfile(appRoot.absolutePath, from, to.trim()))
            }.getOrElse { ProfileOp(ok = false, code = "failed", error = it.message.orEmpty()) }
            if (op.ok) loadProfiles()
            onMain { done(op) }
        }
    }

    fun deleteProfile(name: String, done: (ProfileOp) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val op = runCatching {
                profileOp(NativeEngine.nativeDeleteProfile(appRoot.absolutePath, name))
            }.getOrElse { ProfileOp(ok = false, code = "failed", error = it.message.orEmpty()) }
            if (op.ok) loadProfiles()
            onMain { done(op) }
        }
    }

    fun setTheme(theme: String) {
        val prefs = _state.value.uiPrefs.copy(theme = theme)
        prefs.save(appRoot)
        _state.update { it.copy(theme = theme, uiPrefs = prefs) }
    }

    fun setVerboseLog(enabled: Boolean) {
        val prefs = _state.value.uiPrefs.copy(verboseLog = enabled)
        prefs.save(appRoot)
        _state.update { it.copy(uiPrefs = prefs) }
    }

    /** Saves router settings. Returns why the SAM host was rejected, without changing anything. */
    fun saveRouter(prefs: RouterPrefs): SamHostProblem? {
        prefs.systemSamProblem()?.let { return it }
        val normalized = prefs.normalized()
        normalized.save(appRoot)
        _state.update { it.copy(router = normalized) }
        return null
    }

    fun setProfilePassword(password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = runCatching { NativeEngine.nativeSetProfilePassword(password) }.getOrElse {
                it.message.orEmpty()
            }
            onMain {
                if (error.isNullOrBlank()) {
                    _state.update { it.copy(error = "", status = str(R.string.profile_password_saved)) }
                } else {
                    _state.update { it.copy(error = error) }
                }
            }
        }
    }

    fun saveMedia(filesDir: String, imagesDir: String, ephemeral: Boolean) {
        val prefs = _state.value.uiPrefs.copy(
            filesDir = filesDir.trim(),
            imagesDir = imagesDir.trim(),
            ephemeralMedia = ephemeral,
        )
        prefs.save(appRoot)
        _state.update { it.copy(uiPrefs = prefs) }
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
            I2pdForegroundService.start(getApplication(), appRoot, true, str(R.string.svc_running))
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
            I2pdForegroundService.start(getApplication(), appRoot, true, str(R.string.svc_starting))
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
                            it.copy(
                                status = str(R.string.status_router_ready_tap_open),
                                samReady = true,
                                routerIssue = null,
                            )
                        }
                    }
                    return@launch
                }
                val sec = (System.currentTimeMillis() - t0) / 1000
                val issue = bundledIssue(sec)
                onMain {
                    _state.update {
                        it.copy(
                            status = str(R.string.status_waiting_router, sec.toInt()),
                            routerIssue = issue,
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
                return@launch
            }
            runCatching { NativeEngine.nativeStop() }
            I2pdForegroundService.restartBundled(getApplication(), appRoot)
            pollJob?.cancel()
            tunnelWatchJob?.cancel()
            onMain {
                _state.update {
                    it.copy(
                        running = false,
                        starting = false,
                        samReady = false,
                        routerIssue = null,
                        status = str(R.string.svc_restarting),
                        error = "",
                    )
                }
                monitorRouterWarmup()
            }
        }
    }

    private var saveAsUri: Uri? = null

    private fun incomingCache(): File = File(getApplication<Application>().cacheDir, "incoming")

    private fun clearIncomingCache() {
        runCatching { incomingCache().deleteRecursively() }
    }

    /** Empty strings keep the core defaults. Ephemeral mode uses a cache deleted on exit. */
    private fun mediaDirs(ui: UiPrefs): Pair<String, String> {
        if (!ui.ephemeralMedia) {
            return ui.filesDir.trim() to ui.imagesDir.trim()
        }
        val files = File(incomingCache(), "files")
        val images = File(incomingCache(), "images")
        files.mkdirs()
        images.mkdirs()
        return files.absolutePath to images.absolutePath
    }

    fun start(profile: String, profilePassword: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val router = RouterPrefs.load(appRoot)
            val ui = UiPrefs.load(appRoot)
            val sam = "${router.samHost}:${router.samPort}"
            _state.update {
                it.copy(
                    starting = true,
                    profile = profile,
                    error = "",
                    routerIssue = null,
                    status = str(R.string.status_starting),
                    notices = emptyList(),
                    verboseLog = emptyList(),
                    peerStates = emptyMap(),
                )
            }
            routerMonitorJob?.cancel()
            var ready = samProbe(router.samHost, router.samPort)
            if (!ready) {
                if (router.usingBundled) {
                    appendNotice("router.starting", NoticeUi.LEVEL_USER)
                    if (!I2pdForegroundService.isAlive(appRoot)) {
                        I2pdForegroundService.start(getApplication(), appRoot, true, str(R.string.svc_starting))
                    }
                } else {
                    I2pdForegroundService.start(getApplication(), appRoot, false, str(R.string.svc_external))
                }
                appendNotice("router.waiting", NoticeUi.LEVEL_VERBOSE, sam)
                val timeout = if (router.usingBundled) 90_000 else 8_000
                ready = waitForSam(router.samHost, router.samPort, timeout) { elapsedSec ->
                    val issue = if (router.usingBundled) bundledIssue(elapsedSec.toLong()) else null
                    _state.update {
                        it.copy(status = str(R.string.status_waiting_router, elapsedSec), routerIssue = issue)
                    }
                }
                val known = if (router.usingBundled) bundledIssue(0) else null
                if (!ready && router.usingBundled && (known == null || known.offersRestart)) {
                    appendNotice("router.restarting", NoticeUi.LEVEL_USER)
                    I2pdForegroundService.restartBundled(getApplication(), appRoot)
                    ready = waitForSam(router.samHost, router.samPort, 75_000) { elapsedSec ->
                        _state.update {
                            it.copy(
                                status = str(R.string.status_restarting_router, elapsedSec),
                                routerIssue = bundledIssue(elapsedSec.toLong()),
                            )
                        }
                    }
                }
            }
            if (!ready) {
                val issue = if (router.usingBundled) {
                    bundledIssue(Long.MAX_VALUE) ?: RouterIssue(RouterIssueCode.ROUTER_CRASHED)
                } else {
                    RouterDiagnostics.diagnoseSystemRouter(router.samHost, router.samPort, false)
                }
                failStart(issue?.let { issueText(it) } ?: str(R.string.status_failed), issue)
                return@launch
            }
            appendNotice("router.sam_ready", NoticeUi.LEVEL_VERBOSE, sam)
            _state.update { it.copy(samReady = true, routerIssue = null) }
            clearIncomingCache()
            val (filesDir, imagesDir) = mediaDirs(ui)
            val result = runCatching {
                JSONObject(
                    NativeEngine.nativeStart(
                        appRoot.absolutePath,
                        profile,
                        router.samHost,
                        router.samPort,
                        ui.historyMaxMessages,
                        ui.historyRetentionDays,
                        router.tunnelLength,
                        filesDir,
                        imagesDir,
                        profilePassword,
                    ),
                )
            }.getOrElse { ex ->
                failStart(str(R.string.notice_start_failed, ex.message.orEmpty()), null)
                return@launch
            }
            if (!result.optBoolean("ok", false)) {
                failStart(str(R.string.notice_start_failed, result.optString("error")), null)
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
                    status = str(R.string.status_warming),
                    error = "",
                )
            }
            startPolling()
            startTunnelWatch(router)
        }
    }

    private var tunnelWatchJob: Job? = null

    /** While tunnels are not ready, keep looking for a router problem the user can fix. */
    private fun startTunnelWatch(router: RouterPrefs) {
        tunnelWatchJob?.cancel()
        tunnelWatchJob = viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            while (isActive && _state.value.running) {
                delay(5_000)
                if (_state.value.tunnelsReady) {
                    onMain { _state.update { it.copy(routerIssue = null) } }
                    return@launch
                }
                val sec = (System.currentTimeMillis() - t0) / 1000
                val issue = if (router.usingBundled) {
                    bundledIssue(sec)
                } else if (!samProbe(router.samHost, router.samPort)) {
                    RouterDiagnostics.diagnoseSystemRouter(router.samHost, router.samPort, false)
                } else {
                    null
                }
                onMain { _state.update { it.copy(routerIssue = issue) } }
            }
        }
    }

    private fun bundledIssue(warmingSeconds: Long): RouterIssue? {
        if (I2pdForegroundService.hasFailed(appRoot)) {
            val detail = I2pdForegroundService.readStatus(appRoot).trim()
            if (detail.isNotEmpty()) {
                return RouterIssue(RouterIssueCode.ROUTER_CRASHED, detail)
            }
        }
        val log = RouterDiagnostics.readLogTail(I2pdForegroundService.logFile(appRoot))
        return RouterDiagnostics.diagnoseBundledRouter(
            log,
            processAlive = !I2pdForegroundService.hasFailed(appRoot),
            networkReady = false,
            warmingSeconds = warmingSeconds,
        )
    }

    fun issueText(issue: RouterIssue): String = when (issue.code) {
        RouterIssueCode.SYSTEM_SAM_UNREACHABLE -> str(R.string.diag_system_sam_unreachable, issue.arg)
        RouterIssueCode.RESEED_FAILED -> str(R.string.diag_reseed_failed)
        RouterIssueCode.CLOCK_SKEW -> str(R.string.diag_clock_skew)
        RouterIssueCode.PORT_IN_USE -> str(R.string.diag_port_in_use)
        RouterIssueCode.NO_NETWORK -> str(R.string.diag_no_network)
        RouterIssueCode.TUNNELS_SLOW -> str(R.string.diag_tunnels_slow)
        RouterIssueCode.ROUTER_CRASHED ->
            issue.arg.ifBlank { str(R.string.diag_router_crashed) }
    }

    fun stop() {
        pollJob?.cancel()
        tunnelWatchJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { NativeEngine.nativeStop() }
            I2pdForegroundService.stop(getApplication())
            _state.update {
                it.copy(
                    running = false,
                    starting = false,
                    samReady = false,
                    routerIssue = null,
                    transport = "stopped",
                    status = str(R.string.status_stopped),
                    localAddr = "",
                    contacts = emptyList(),
                    groups = emptyList(),
                    messages = emptyList(),
                    notices = emptyList(),
                    verboseLog = emptyList(),
                    peerStates = emptyMap(),
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

    /** Send again without touching the draft. Desktop retry does the same for text. */
    fun resend(text: String) {
        val id = _state.value.selectedId
        val body = text.trim()
        if (id.isBlank() || body.isBlank()) return
        val group = _state.value.selectedIsGroup
        viewModelScope.launch(Dispatchers.IO) {
            if (!group) ensureLiveForSend(id)
            if (group) NativeEngine.nativeSendGroupText(id, body)
            else NativeEngine.nativeSendText(id, body)
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
        onMain { setPeerState(addr, "connecting") }
        val ok = runCatching { NativeEngine.nativeConnectPeer(addr) }.getOrDefault(false)
        if (!ok) {
            onMain { setPeerState(addr, "failed") }
            return
        }
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            delay(250)
            refreshSnapshot()
            if (peerLiveInSnapshot(addr)) {
                onMain { setPeerState(addr, "secure") }
                return
            }
        }
        onMain { if (_state.value.peerStates[addr] == "connecting") setPeerState(addr, "failed") }
    }

    fun connectSelected() {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup) return
        viewModelScope.launch(Dispatchers.IO) {
            onMain { setPeerState(id, "connecting") }
            val ok = runCatching { NativeEngine.nativeConnectPeer(id) }.getOrDefault(false)
            if (!ok) {
                onMain {
                    setPeerState(id, "failed")
                    appendNotice("peer.connect_failed", NoticeUi.LEVEL_USER, id)
                }
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
            try {
                // The engine streams every chunk before returning, so the
                // staged copy is no longer needed afterwards.
                NativeEngine.nativeSendFile(id, copied.absolutePath, image)
            } finally {
                copied.delete()
            }
            loadHistory()
            refreshSnapshot()
        }
    }

    fun answerTofu(accept: Boolean) {
        val prompt = _state.value.tofu ?: return
        _state.update { it.copy(tofu = null) }
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeTrustRespond(prompt.peer, prompt.newKey, accept)
            if (accept) {
                refreshSnapshot()
            }
        }
    }

    /** Accept and, once the file is complete, copy it into the document the user created. */
    fun answerFileOfferSavingTo(uri: Uri) {
        saveAsUri = uri
        answerFileOffer(true)
    }

    fun answerFileOffer(accept: Boolean) {
        val offer = _state.value.fileOffer ?: return
        _state.update { it.copy(fileOffer = null) }
        viewModelScope.launch(Dispatchers.IO) {
            if (!NativeEngine.nativeFileRespond(offer.peer, accept)) {
                onMain { appendNotice("file.offer_stale", NoticeUi.LEVEL_USER, offer.name) }
            }
        }
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
            val result = try {
                val json = JSONObject(
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
                json
            } finally {
                // Bundles hold identity keys (encrypted) and plaintext history;
                // the staged copy in cacheDir must not outlive the operation.
                dest.delete()
            }
            _state.update {
                it.copy(
                    error = if (result.optBoolean("ok")) "" else result.optString("error"),
                    status = if (result.optBoolean("ok")) str(R.string.status_backup_ok) else it.status,
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
                            deliveryHint = o.optString("deliveryHint"),
                            deliveryReason = o.optString("deliveryReason"),
                            retryable = o.optBoolean("retryable"),
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
                    lastPreview = str(R.string.group_members, members.size),
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
        val name = safeLocalFileName(uri.lastPathSegment)
        val stagingDir = File(getApplication<Application>().cacheDir, "staging").apply { mkdirs() }
        val dest = File(stagingDir, name)
        // The name came from another app's content provider; it must stay a
        // plain file directly inside the staging directory.
        if (dest.canonicalFile.parentFile != stagingDir.canonicalFile) return null
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

    private fun safeLocalFileName(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]"), "_").trim('.', ' ')
        if (cleaned.isBlank() || cleaned.length > 200) {
            val ext = base.substringAfterLast('.', "").take(10).filter { it.isLetterOrDigit() }
            return "file-${System.currentTimeMillis()}" + if (ext.isNotEmpty()) ".$ext" else ""
        }
        return cleaned
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun failStart(message: String, issue: RouterIssue?) {
        onMain {
            _state.update {
                it.copy(
                    starting = false,
                    running = false,
                    transport = "failed",
                    status = str(R.string.status_failed),
                    error = message,
                    routerIssue = issue,
                )
            }
            if (issue == null) appendNotice("error.other", NoticeUi.LEVEL_USER, text = message)
        }
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun setPeerState(addr: String, state: String) {
        if (addr.isBlank()) return
        _state.update { it.copy(peerStates = it.peerStates + (addr to state)) }
    }

    private fun appendNotice(code: String, level: String, arg: String = "", text: String = "") {
        val notice = NoticeUi(code, level, arg, text, noticeTimestamp())
        _state.update {
            val notices = if (notice.isUser && it.notices.lastOrNull()?.let { last ->
                    last.code == code && last.arg == arg && last.text == text
                } != true
            ) {
                (it.notices + notice).takeLast(MAX_NOTICES)
            } else {
                it.notices
            }
            it.copy(notices = notices, verboseLog = (it.verboseLog + notice).takeLast(MAX_VERBOSE))
        }
    }

    private fun noticeTimestamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())

    override fun onNotice(json: String) = onMain {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@onMain
        val code = o.optString("code")
        val level = o.optString("level", NoticeUi.LEVEL_VERBOSE)
        val arg = o.optString("arg")
        appendNotice(code, level, arg, o.optString("text"))
        when (code) {
            "peer.connecting" -> setPeerState(arg, "connecting")
            "peer.secure" -> setPeerState(arg, "secure")
            "peer.disconnected" -> {
                setPeerState(arg, "disconnected")
                viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
            }
        }
    }

    override fun onSystem(message: String) = onMain {
        appendNotice("system.other", NoticeUi.LEVEL_VERBOSE, text = message)
    }

    override fun onError(message: String) = onMain {
        appendNotice("error.other", NoticeUi.LEVEL_USER, text = message)
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
        setPeerState(peer, state)
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onTransportState(state: String, reason: String) = onMain {
        _state.update {
            it.copy(
                transport = state,
                status = when (state) {
                    "ready" -> str(R.string.status_online)
                    "failed" -> str(R.string.status_failed)
                    else -> str(R.string.status_warming)
                },
                routerIssue = if (state == "ready") null else it.routerIssue,
            )
        }
    }

    override fun onTransfer(peer: String, json: String) = onMain { }

    override fun onFileReceived(peer: String, path: String) = onMain {
        val dest = saveAsUri
        saveAsUri = null
        if (dest != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(dest)?.use { out ->
                        File(path).inputStream().use { input -> input.copyTo(out) }
                    }
                }
            }
        }
        NotificationHelper.notifyMessage(getApplication(), peer, str(R.string.notify_file, File(path).name))
        if (peer == _state.value.selectedId) loadHistory()
    }

    override fun onImageReceived(peer: String, path: String) = onMain {
        NotificationHelper.notifyMessage(getApplication(), peer, str(R.string.notify_image))
        if (peer == _state.value.selectedId) loadHistory()
        else {
            _state.update {
                it.copy(
                    contacts = it.contacts.map { c ->
                        if (c.addr == peer) c.copy(unread = c.unread + 1, lastPreview = str(R.string.image_preview)) else c
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
        // The handshake was refused without blocking the engine; answering
        // pins the key and reconnects.
        _state.update { it.copy(tofu = TofuPrompt(kind, peer, newKey, oldKey)) }
        NotificationHelper.notifyTrustPrompt(getApplication(), peer)
        appendNotice("trust.prompt", NoticeUi.LEVEL_USER, peer)
    }

    override fun onFileOffer(peer: String, name: String, size: Long) = onMain {
        // Bytes spool to a .pending file meanwhile; the dialog is the only way to say yes.
        _state.update { it.copy(fileOffer = FileOfferPrompt(peer, name, size)) }
        appendNotice("file.offer", NoticeUi.LEVEL_VERBOSE, peer, "$name (${formatSize(size)})")
    }

    fun formatSize(size: Long): String = when {
        size >= 1L shl 30 -> "%.1f GiB".format(size / (1L shl 30).toDouble())
        size >= 1L shl 20 -> "%.1f MiB".format(size / (1L shl 20).toDouble())
        size >= 1L shl 10 -> "%.1f KiB".format(size / (1L shl 10).toDouble())
        else -> "$size B"
    }

    override fun onStarted() = onMain {
        _state.update {
            it.copy(
                running = true,
                starting = false,
                status = str(R.string.status_warming),
            )
        }
    }

    override fun onStartFailed(message: String) = onMain {
        _state.update {
            it.copy(running = false, starting = false, error = message, status = str(R.string.status_failed))
        }
        appendNotice("error.other", NoticeUi.LEVEL_USER, text = message)
    }

    fun consumeOpenIntent(intent: Intent?) {
        val peer = intent?.getStringExtra("openPeer") ?: return
        selectConversation(peer, false)
    }

    override fun onCleared() {
        clearIncomingCache()
        NativeEngine.nativeSetListener(null)
        runCatching { NativeEngine.nativeStop() }
        super.onCleared()
    }
}
