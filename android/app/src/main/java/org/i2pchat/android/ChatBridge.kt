package org.i2pchat.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import org.i2pchat.android.BuildConfig

data class ContactUi(
    val addr: String,
    val displayName: String = "",
    val note: String = "",
    val lastPreview: String = "",
    val lastActivityTs: String = "",
    /** Per-peer auto-delete age in days; 0 = follow the global history retention. */
    val historyRetentionDays: Int = 0,
    val live: Boolean = false,
    val offlineReady: Boolean = false,
    val unread: Int = 0,
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val epoch: Long = 0,
    /** Pinned chats stay above MRU; [pinOrder] is only meaningful when pinned. */
    val pinned: Boolean = false,
    val pinOrder: Int = 0,
    /** When true, skip system notifications for this conversation. */
    val notificationsMuted: Boolean = false,
) {
    val title: String
        get() = displayName.ifBlank { if (isGroup) "Group" else addr.take(18) }
}

/** Sort: pinned (by pinOrder) first, then unpinned by lastActivityTs descending. */
fun sortConversations(items: List<ContactUi>): List<ContactUi> =
    items.sortedWith(
        compareBy<ContactUi> { if (it.pinned) 0 else 1 }
            .thenBy { if (it.pinned) it.pinOrder else 0 }
            .thenByDescending { it.lastActivityTs },
    )

/**
 * True when the user is looking at this conversation right now (app in
 * foreground and ConversationScreen visible for the same peer/group).
 */
fun isViewingConversation(
    conversationId: String,
    isGroup: Boolean,
    selectedId: String,
    selectedIsGroup: Boolean,
    conversationVisible: Boolean,
    appInForeground: Boolean,
): Boolean =
    appInForeground &&
        conversationVisible &&
        conversationId == selectedId &&
        isGroup == selectedIsGroup

/** True when an incoming message should bump the Chats unread badge. */
fun shouldBumpUnread(
    conversationId: String,
    isGroup: Boolean,
    selectedId: String,
    selectedIsGroup: Boolean,
    conversationVisible: Boolean,
    appInForeground: Boolean,
): Boolean = !isViewingConversation(
    conversationId,
    isGroup,
    selectedId,
    selectedIsGroup,
    conversationVisible,
    appInForeground,
)

/** True when a system notification should be posted for an incoming message. */
fun shouldNotifyMessage(
    conversationId: String,
    isGroup: Boolean,
    selectedId: String,
    selectedIsGroup: Boolean,
    conversationVisible: Boolean,
    appInForeground: Boolean,
    notificationsMuted: Boolean,
): Boolean =
    !notificationsMuted &&
        shouldBumpUnread(
            conversationId,
            isGroup,
            selectedId,
            selectedIsGroup,
            conversationVisible,
            appInForeground,
        )

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
    /**
     * Key into [MemoryImages] when the picture was received without being
     * written to storage ("Do not keep files"); `text` is `[image] <name>`.
     */
    val memoryImage: String = "",
    /**
     * Id of a sealed download still held for this chat session. Empty once the
     * session key is gone: the history line keeps the display name only.
     */
    val sealedFile: String = "",
) {
    /** Unified attachment view-model for bubble rendering. */
    val attachment: ChatAttachment?
        get() = buildChatAttachment(
            text = text,
            imagePath = imagePath,
            filePath = filePath,
            memoryImage = memoryImage,
            sealedFile = sealedFile,
        )

    val fileCaption: String
        get() = attachment?.displayName.orEmpty().ifBlank {
            when {
                imagePath.isNotBlank() -> File(imagePath).name
                filePath.isNotBlank() -> File(filePath).name
                parseSealedFileHistory(text) != null -> parseSealedFileHistory(text)!!.displayName
                text.startsWith("[file] ") -> File(text.removePrefix("[file] ").trim()).name
                text.startsWith("[image] ") -> File(text.removePrefix("[image] ").trim()).name
                else -> ""
            }
        }
}

private fun resolveMediaPath(
    text: String,
    prefix: String,
    appRoot: File,
    subdirs: List<String>,
    extraDirs: List<File>,
): String {
    if (!text.startsWith(prefix)) return ""
    val raw = text.removePrefix(prefix).trim()
    if (raw.isBlank()) return ""
    val direct = File(raw)
    val profilesDir = File(appRoot, "profiles").canonicalFile
    // A peer can type "[image] /some/path" as plain text; only a path inside a
    // directory the app itself writes media to counts as media. `extraDirs`
    // are the directories in use when they are not the per-profile defaults:
    // a folder from Settings, or the cache of "Do not keep files".
    if (direct.isAbsolute) {
        val real = runCatching { direct.canonicalFile }.getOrNull() ?: return ""
        if (!real.isFile) return ""
        val allowed = extraDirs.any { dir ->
            runCatching { dir.canonicalFile }.getOrNull() == real.parentFile
        } || profilesDir.listFiles()?.any { profile ->
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
fun resolveChatImagePath(text: String, appRoot: File, extraDirs: List<File> = emptyList()): String =
    resolveMediaPath(text, "[image] ", appRoot, listOf("images", "data/images"), extraDirs)

/** Resolve `[file] path` under downloads. */
fun resolveChatFilePath(text: String, appRoot: File, extraDirs: List<File> = emptyList()): String =
    resolveMediaPath(
        text,
        "[file] ",
        appRoot,
        listOf("downloads", "data/downloads", "files"),
        extraDirs,
    )

/** A name from another app reduced to one plain file name; never a path. */
internal fun safeLocalFileName(raw: String?): String {
    val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.replace(Regex("[^A-Za-z0-9._ ()\\-\\p{L}\\p{N}]"), "_").trim('.', ' ')
    if (cleaned.isBlank() || cleaned.length > 200) {
        val ext = base.substringAfterLast('.', "").take(10).filter { it.isLetterOrDigit() }
        return "file-${System.currentTimeMillis()}" + if (ext.isNotEmpty()) ".$ext" else ""
    }
    return cleaned
}

/** `name` inside `dir`, or `name (N).ext` when that file already exists. */
internal fun uniqueFileIn(dir: File, name: String): File {
    val first = File(dir, name)
    if (!first.exists()) return first
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    for (n in 1..9999) {
        val candidate = File(dir, "$stem ($n)$ext")
        if (!candidate.exists()) return candidate
    }
    return File(dir, "$stem (${System.nanoTime()})$ext")
}


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

/** Live file/image transfer progress for the conversation UI. */
data class TransferProgressUi(
    val peer: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val incoming: Boolean,
    val image: Boolean = false,
    /** active | completed | failed */
    val outcome: String = "active",
) {
    val key: String
        get() = "$peer|${if (incoming) "in" else "out"}|$name"

    val percent: Int
        get() = when {
            outcome == "completed" -> 100
            size <= 0L -> 0
            else -> ((transferred * 100L) / size).toInt().coerceIn(0, 100)
        }
}

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
    /** Quit in progress. Keeps the profile screen from mounting and starting the router again. */
    val quitting: Boolean = false,
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
    /**
     * True only while [ConversationScreen] is on screen. Leaving the chat keeps
     * [selectedId] but clears this so unread badges keep working.
     */
    val conversationVisible: Boolean = false,
    /** Process-level foreground via [ProcessLifecycleOwner]; false when the app is backgrounded. */
    val appInForeground: Boolean = true,
    /** Changes when a notification should open the current chat. Zero means no request. */
    val openChatNonce: Long = 0,
    val compose: String = "",
    /** Read-only reply target shown above the composer (not part of [compose]). */
    val pendingReply: PendingReply? = null,
    val drafts: Map<String, String> = emptyMap(),
    val tofu: TofuPrompt? = null,
    val fileOffer: FileOfferPrompt? = null,
    /** Active / recently finished file transfers keyed for UI updates. */
    val transfers: List<TransferProgressUi> = emptyList(),
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
    /** Bumped when an avatar preview file changes so Compose reloads it. */
    val avatarEpoch: Long = 0,
    /** Cold-start auto-resume of the last unlocked profile is in flight. */
    val resuming: Boolean = false,
) {
    val conversations: List<ContactUi>
        get() = sortConversations(groups + contacts)

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
    /** Peers with a dial already in flight, so opening the chat twice does not start two handshakes. */
    private val dialingPeers = mutableSetOf<String>()
    /** Peers the user hung up on; ignore late auto-reconnect "connecting" events. */
    private val hungUpPeers = mutableSetOf<String>()

    /** Where the running engine writes pictures and files; null until it has started. */
    @Volatile private var activeImagesDir: File? = null
    @Volatile private var activeFilesDir: File? = null

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            _state.update { state ->
                val clearId = state.selectedId
                val clearUnread = state.conversationVisible && clearId.isNotBlank()
                state.copy(
                    appInForeground = true,
                    contacts = if (clearUnread) {
                        state.contacts.map { c ->
                            if (c.addr == clearId && !state.selectedIsGroup) c.copy(unread = 0) else c
                        }
                    } else {
                        state.contacts
                    },
                    groups = if (clearUnread) {
                        state.groups.map { c ->
                            if (c.addr == clearId && state.selectedIsGroup) c.copy(unread = 0) else c
                        }
                    } else {
                        state.groups
                    },
                )
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            _state.update { it.copy(appInForeground = false) }
        }
    }

    init {
        // A crash or a kill in the middle of a send or a backup leaves its
        // staged copy behind; nothing is in flight when the app starts.
        runCatching { stagingDir().deleteRecursively() }
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        }
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
                viewModelScope.launch(Dispatchers.IO) { rebindOrResumeSession() }
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

    private fun notificationsMutedFor(id: String, group: Boolean): Boolean {
        val rows = if (group) _state.value.groups else _state.value.contacts
        return rows.firstOrNull { it.addr == id }?.notificationsMuted == true
    }

    private fun shouldNotifyIncoming(id: String, group: Boolean): Boolean {
        val state = _state.value
        return shouldNotifyMessage(
            conversationId = id,
            isGroup = group,
            selectedId = state.selectedId,
            selectedIsGroup = state.selectedIsGroup,
            conversationVisible = state.conversationVisible,
            appInForeground = state.appInForeground,
            notificationsMuted = notificationsMutedFor(id, group),
        )
    }

    private fun shouldBumpIncoming(id: String, group: Boolean): Boolean {
        val state = _state.value
        return shouldBumpUnread(
            conversationId = id,
            isGroup = group,
            selectedId = state.selectedId,
            selectedIsGroup = state.selectedIsGroup,
            conversationVisible = state.conversationVisible,
            appInForeground = state.appInForeground,
        )
    }

    private fun isViewingIncoming(id: String, group: Boolean): Boolean {
        val state = _state.value
        return isViewingConversation(
            conversationId = id,
            isGroup = group,
            selectedId = state.selectedId,
            selectedIsGroup = state.selectedIsGroup,
            conversationVisible = state.conversationVisible,
            appInForeground = state.appInForeground,
        )
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
        val session = SessionPrefs.load(appRoot)
        _state.update {
            it.copy(
                profiles = list,
                profileAddresses = addresses,
                lockedProfiles = locked,
                profile = it.profile.ifBlank { session.lastProfile },
            )
        }
    }

    /**
     * After a process kill the engine is gone; after an Activity-only destroy
     * the engine may still be up under [ChatForegroundService]. Re-attach or
     * auto-start the last unlocked profile marked for resume.
     */
    private fun rebindOrResumeSession() {
        if (_state.value.running || _state.value.starting || _state.value.quitting) return
        if (rebindRunningEngine()) return
        val session = SessionPrefs.load(appRoot)
        if (!session.resume || session.lastProfile.isBlank()) return
        loadProfiles()
        if (session.lastProfile in _state.value.lockedProfiles) {
            // Password-protected: leave the chooser, but keep the name selected.
            _state.update { it.copy(profile = session.lastProfile, resuming = false) }
            return
        }
        _state.update { it.copy(resuming = true, profile = session.lastProfile) }
        start(session.lastProfile)
    }

    private fun rebindRunningEngine(): Boolean {
        val snap = runCatching { JSONObject(NativeEngine.nativeSnapshot()) }.getOrNull() ?: return false
        if (!snap.optBoolean("running", false)) return false
        val session = SessionPrefs.load(appRoot)
        val profile = _state.value.profile.ifBlank { session.lastProfile }
        if (profile.isBlank()) return false
        ChatForegroundService.start(getApplication(), profile)
        SessionPrefs(lastProfile = profile, resume = true).save(appRoot)
        val drafts = runCatching {
            val obj = JSONObject(NativeEngine.nativeComposeDrafts(false, "{}"))
            buildMap {
                obj.keys().forEach { key ->
                    val text = obj.optString(key).trim()
                    if (key.isNotBlank() && text.isNotEmpty()) put(key, text)
                }
            }
        }.getOrDefault(emptyMap())
        val addr = snap.optString("localAddr").ifBlank { snap.optString("local_addr") }
        onMain {
            _state.update {
                it.copy(
                    starting = false,
                    resuming = false,
                    running = true,
                    profile = profile,
                    localAddr = addr,
                    drafts = drafts,
                    status = str(R.string.status_warming),
                    error = "",
                    quitting = false,
                )
            }
        }
        refreshSnapshot()
        startPolling()
        startTunnelWatch(RouterPrefs.load(appRoot))
        return true
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

    /** Sync notification + battery-exemption status into [UiPrefs] for Settings. */
    fun refreshBackgroundAccess() {
        val allowed = BackgroundAccess.status(getApplication()).allowed
        val current = _state.value.uiPrefs
        if (current.backgroundAllowed == allowed) return
        val prefs = current.copy(backgroundAllowed = allowed)
        prefs.save(appRoot)
        _state.update { it.copy(uiPrefs = prefs) }
    }

    fun markBackgroundPromptSeen() {
        val current = _state.value.uiPrefs
        val allowed = BackgroundAccess.status(getApplication()).allowed
        val prefs = current.copy(backgroundPromptSeen = true, backgroundAllowed = allowed)
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

    /** Stop i2pd, import a validated netDb ZIP, then restart the bundled router. */
    fun importNetDbZip(uri: Uri, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch(Dispatchers.IO) {
            val router = RouterPrefs.load(appRoot)
            if (!router.usingBundled) {
                onMain { onDone(false, str(R.string.import_netdb_bundled_only)) }
                return@launch
            }
            val bytes = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                onMain { onDone(false, str(R.string.import_netdb_read_failed)) }
                return@launch
            }
            val result = runCatching {
                // Validate before touching the running router.
                NetDbImport.validate(bytes)
                runCatching { NativeEngine.nativeStop() }
                I2pdForegroundService.stop(getApplication())
                I2pdForegroundService.waitUntilStopped(getApplication(), 20_000)
                val stats = NetDbImport.extractToNetDb(bytes, NetDbImport.netDbDir(appRoot))
                I2pdForegroundService.restartBundled(getApplication(), appRoot)
                pollJob?.cancel()
                tunnelWatchJob?.cancel()
                stats
            }
            onMain {
                result.fold(
                    onSuccess = { stats ->
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
                        onDone(
                            true,
                            str(
                                R.string.import_netdb_ok,
                                stats.freshRouterInfoFiles,
                                stats.totalFiles,
                            ),
                        )
                    },
                    onFailure = { err ->
                        onDone(
                            false,
                            str(
                                R.string.import_netdb_failed,
                                err.message?.ifBlank { err.javaClass.simpleName }.orEmpty(),
                            ),
                        )
                    },
                )
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

    /**
     * Media directories in use besides the per-profile defaults: the one the
     * running engine was started with and the folder saved in Settings.
     */
    private fun mediaRoots(image: Boolean): List<File> {
        val ui = _state.value.uiPrefs
        val saved = (if (image) ui.imagesDir else ui.filesDir).trim()
        return listOfNotNull(
            if (image) activeImagesDir else activeFilesDir,
            saved.takeIf { it.isNotEmpty() }?.let(::File),
        ).distinct()
    }

    fun start(profile: String, profilePassword: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (_state.value.running || _state.value.starting) {
                pollJob?.cancel()
                tunnelWatchJob?.cancel()
                runCatching { NativeEngine.nativeStop() }
                _state.update {
                    it.copy(
                        running = false,
                        starting = false,
                        localAddr = "",
                        contacts = emptyList(),
                        groups = emptyList(),
                        messages = emptyList(),
                        notices = emptyList(),
                        verboseLog = emptyList(),
                        peerStates = emptyMap(),
                        selectedId = "",
                    )
                }
            }
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
            appendNotice(
                "app.version",
                NoticeUi.LEVEL_USER,
                text = "I2PChat ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            _state.update { it.copy(samReady = true, routerIssue = null) }
            clearIncomingCache()
            MemoryImages.clear()
            SealedFiles.clear()
            val (filesDir, imagesDir) = mediaDirs(ui)
            // Empty means the engine default under the profile directory.
            val profileDir = File(appRoot, "profiles/$profile")
            activeFilesDir = if (filesDir.isBlank()) File(profileDir, "downloads") else File(filesDir)
            activeImagesDir = if (imagesDir.isBlank()) File(profileDir, "images") else File(imagesDir)
            sessionPassword = profilePassword
            val result = runCatching {
                nativeStartSession(profile, profilePassword, router, ui, filesDir, imagesDir)
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
                    obj.keys().forEach { key ->
                        val text = obj.optString(key).trim()
                        if (key.isNotBlank() && text.isNotEmpty()) put(key, text)
                    }
                }
            }.getOrDefault(emptyMap())
            val addr = result.optString("localAddr")
            SessionPrefs(lastProfile = profile, resume = true).save(appRoot)
            ChatForegroundService.start(getApplication(), profile)
            refreshSnapshot()
            _state.update {
                it.copy(
                    starting = false,
                    resuming = false,
                    running = true,
                    localAddr = addr,
                    drafts = drafts,
                    status = str(R.string.status_warming),
                    error = "",
                    quitting = false,
                )
            }
            startPolling()
            startTunnelWatch(router)
        }
    }

    private var tunnelWatchJob: Job? = null

    /**
     * Profile password of the running session, kept in memory only so the
     * engine can be started again when an external router comes back.
     */
    private var sessionPassword = ""

    private fun nativeStartSession(
        profile: String,
        profilePassword: String,
        router: RouterPrefs,
        ui: UiPrefs,
        filesDir: String,
        imagesDir: String,
    ): JSONObject = JSONObject(
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
            // Received pictures then never reach the cache
            // directory, which is on flash storage like any other.
            ui.ephemeralMedia,
        ),
    )

    /**
     * While tunnels are not ready, keep looking for a router problem the user
     * can fix. With an external router the watch goes on after tunnels are up:
     * when its SAM bridge dies the problem is shown, and once it answers again
     * the engine session is rebuilt without restarting the app.
     */
    private fun startTunnelWatch(router: RouterPrefs) {
        tunnelWatchJob?.cancel()
        tunnelWatchJob = viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var samLost = false
            var misses = 0
            while (isActive && _state.value.running) {
                delay(5_000)
                if (!router.usingBundled) {
                    val up = samProbe(router.samHost, router.samPort)
                    misses = if (up) 0 else misses + 1
                    // One failed probe can be a busy router; two in a row is an outage.
                    if (!up && misses < 2) continue
                    if (!up) {
                        if (!samLost) {
                            samLost = true
                            onMain {
                                _state.update {
                                    it.copy(
                                        samReady = false,
                                        status = str(R.string.status_failed),
                                        routerIssue = RouterIssue(
                                            RouterIssueCode.SYSTEM_SAM_UNREACHABLE,
                                            "${router.samHost}:${router.samPort}",
                                        ),
                                    )
                                }
                            }
                        }
                        continue
                    }
                    if (samLost) {
                        // Router answered but the session did not come up: try again next tick.
                        if (!rebuildExternalSession(router)) continue
                        samLost = false
                    }
                }
                if (_state.value.tunnelsReady) {
                    onMain { _state.update { it.copy(routerIssue = null) } }
                    if (router.usingBundled) return@launch
                    continue
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

    /**
     * The external router's SAM bridge is back after an outage. Stops the engine
     * and starts it again on the same profile so tunnels and the SAM session are
     * rebuilt; the open chat, drafts and contacts stay on screen. Returns false
     * when the engine did not come back, so the caller can try again.
     */
    private suspend fun rebuildExternalSession(router: RouterPrefs): Boolean {
        val profile = _state.value.profile
        if (profile.isBlank()) return false
        pollJob?.cancel()
        runCatching { NativeEngine.nativeStop() }
        // nativeStop destroys the session key that opens sealed downloads.
        SealedFiles.clear()
        onMain {
            _state.update {
                it.copy(
                    transport = "starting",
                    status = str(R.string.status_starting),
                    peerStates = emptyMap(),
                )
            }
        }
        // Quit while the engine was stopping: do not bring it back.
        if (!_state.value.running || _state.value.quitting) return true
        val ui = UiPrefs.load(appRoot)
        val (filesDir, imagesDir) = mediaDirs(ui)
        val result = runCatching {
            nativeStartSession(profile, sessionPassword, router, ui, filesDir, imagesDir)
        }.getOrNull()
        if (result == null || !result.optBoolean("ok", false)) {
            onMain {
                _state.update {
                    it.copy(
                        samReady = false,
                        status = str(R.string.status_failed),
                        routerIssue = RouterIssue(
                            RouterIssueCode.SYSTEM_SAM_UNREACHABLE,
                            "${router.samHost}:${router.samPort}",
                        ),
                    )
                }
            }
            return false
        }
        refreshSnapshot()
        onMain {
            _state.update {
                it.copy(
                    samReady = true,
                    routerIssue = null,
                    localAddr = result.optString("localAddr", it.localAddr),
                    status = str(R.string.status_warming),
                    error = "",
                )
            }
        }
        startPolling()
        loadHistory()
        return true
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

    /** Direct-chat drafts only. Group compose stays on disk. */
    fun directDrafts(): List<Pair<String, String>> {
        persistDraft(_state.value.selectedId, _state.value.compose)
        val groups = _state.value.groups.map { it.addr }.toSet()
        return _state.value.drafts
            .mapNotNull { (id, text) ->
                val body = text.trim()
                if (id.isBlank() || body.isEmpty()) return@mapNotNull null
                if (id in groups || id.startsWith("group:")) return@mapNotNull null
                id to body
            }
    }

    /** True only when a quit prompt should offer BlindBox flush. */
    fun hasUnsentDirectDrafts(): Boolean = directDrafts().isNotEmpty()

    fun quit(flushOffline: Boolean, onClosed: () -> Unit = {}) {
        stop(if (flushOffline) directDrafts() else emptyList(), onClosed)
    }

    fun stop(flushOffline: List<Pair<String, String>> = emptyList(), onClosed: () -> Unit = {}) {
        _state.update { it.copy(quitting = true) }
        pollJob?.cancel()
        tunnelWatchJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val sent = mutableSetOf<String>()
            for ((id, text) in flushOffline) {
                val raw = runCatching { NativeEngine.nativeSendOfflineText(id, text) }.getOrDefault("")
                    .trim()
                // A queued send returns a JSON list of message ids. An error object
                // can also contain digits, so only an array counts.
                if (raw.startsWith("[") && raw.any { it.isDigit() }) sent += id
            }
            if (sent.isNotEmpty()) {
                val drafts = _state.value.drafts.filterKeys { it !in sent }
                val json = JSONObject()
                drafts.forEach { (key, value) -> json.put(key, value) }
                runCatching { NativeEngine.nativeComposeDrafts(true, json.toString()) }
                onMain {
                    _state.update { state ->
                        state.copy(
                            drafts = drafts,
                            compose = if (state.selectedId in sent) "" else state.compose,
                        )
                    }
                }
            }
            runCatching { NativeEngine.nativeStop() }
            sessionPassword = ""
            MemoryImages.clear()
            SealedFiles.clear()
            clearIncomingCache()
            val last = _state.value.profile
            SessionPrefs(lastProfile = last, resume = false).save(appRoot)
            ChatForegroundService.stop(getApplication())
            I2pdForegroundService.stop(getApplication())
            onMain {
                // Close first. Flipping running=false rebuilds NavHost onto the
                // profile screen, and that screen starts the router again.
                onClosed()
                _state.update {
                    it.copy(
                        quitting = true,
                        running = false,
                        starting = false,
                        resuming = false,
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
    }

    fun setConversationVisible(visible: Boolean) {
        _state.update { state ->
            val clearId = state.selectedId
            if (!visible) {
                return@update state.copy(conversationVisible = false)
            }
            state.copy(
                conversationVisible = true,
                contacts = state.contacts.map { c ->
                    if (c.addr == clearId) c.copy(unread = 0) else c
                },
                groups = state.groups.map { c ->
                    if (c.addr == clearId) c.copy(unread = 0) else c
                },
            )
        }
    }

    fun selectConversation(id: String, group: Boolean) {
        persistDraft(_state.value.selectedId, _state.value.compose)
        val draft = _state.value.drafts[id].orEmpty()
        val alreadySaved = group || id.isBlank() || _state.value.contacts.any { it.addr == id }
        _state.update {
            val contacts = if (alreadySaved) {
                it.contacts
            } else {
                listOf(
                    ContactUi(addr = id, lastActivityTs = java.time.Instant.now().toString()),
                ) + it.contacts
            }
            it.copy(
                selectedId = id,
                selectedIsGroup = group,
                conversationVisible = true,
                compose = draft,
                pendingReply = null,
                contacts = contacts.map { c -> if (c.addr == id) c.copy(unread = 0) else c },
                groups = it.groups.map { c -> if (c.addr == id) c.copy(unread = 0) else c },
            )
        }
        // A brand-new direct chat dials immediately. Reopening a saved chat does not.
        val dial = !alreadySaved && _state.value.peerLink(id) != "secure"
        if (dial) setPeerState(id, "connecting")
        if (!group && id.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val canonical = runCatching { NativeEngine.nativeRememberPeer(id) }.getOrDefault("")
                if (canonical.isBlank() && !alreadySaved) {
                    onMain {
                        _state.update { state ->
                            state.copy(contacts = state.contacts.filterNot { it.addr == id })
                        }
                        if (dial) setPeerState(id, "failed")
                    }
                } else if (canonical.isNotBlank() && canonical != id) {
                    onMain {
                        _state.update { state ->
                            if (state.selectedId != id) {
                                state
                            } else {
                                val drafts = state.drafts.toMutableMap()
                                drafts.remove(id)?.let { text -> drafts[canonical] = text }
                                val link = state.peerStates[id]
                                state.copy(
                                    selectedId = canonical,
                                    drafts = drafts,
                                    contacts = state.contacts.map { contact ->
                                        if (contact.addr == id) contact.copy(addr = canonical) else contact
                                    },
                                    peerStates = if (link == null) {
                                        state.peerStates
                                    } else {
                                        state.peerStates + (canonical to link)
                                    },
                                )
                            }
                        }
                    }
                }
                refreshSnapshot()
                if (dial && canonical.isNotBlank()) dialPeer(canonical)
            }
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

    fun setPendingReply(reply: PendingReply?) {
        _state.update { it.copy(pendingReply = reply) }
    }

    fun clearPendingReply() {
        _state.update { it.copy(pendingReply = null) }
    }

    fun send() {
        val id = _state.value.selectedId
        val answer = _state.value.compose.trim()
        val pending = _state.value.pendingReply
        val text = if (pending != null) {
            formatReplyQuote(pending.author, pending.summary) + answer
        } else {
            answer
        }
        if (id.isBlank() || text.isBlank()) return
        _state.update { it.copy(compose = "", pendingReply = null) }
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

    /** Same dial `/connect` starts on desktop: the badge leaves offline before the first message. */
    private suspend fun dialPeer(addr: String) {
        if (!synchronized(dialingPeers) { dialingPeers.add(addr) }) return
        synchronized(hungUpPeers) { hungUpPeers.remove(addr) }
        try {
            if (peerLiveInSnapshot(addr)) {
                onMain { setPeerState(addr, "secure") }
                return
            }
            onMain { setPeerState(addr, "connecting") }
            val ok = runCatching { NativeEngine.nativeConnectPeer(addr) }.getOrDefault(false)
            if (!ok) {
                onMain {
                    setPeerState(addr, "failed")
                    appendNotice("peer.connect_failed", NoticeUi.LEVEL_USER, addr)
                }
            } else {
                onMain { setPeerState(addr, "secure") }
            }
            refreshSnapshot()
        } finally {
            synchronized(dialingPeers) { dialingPeers.remove(addr) }
        }
    }

    fun connectSelected() {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup) return
        synchronized(hungUpPeers) { hungUpPeers.remove(id) }
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
        // Reflect hang-up immediately; native also cancels auto-reconnect dials.
        synchronized(hungUpPeers) { hungUpPeers.add(id) }
        setPeerState(id, "disconnected")
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeDisconnectPeer(id)
            refreshSnapshot()
        }
    }

    /** Re-read BlindBox/replica fields for Status without waiting for a peer event. */
    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    fun pollOffline() {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativePollBlindbox()
            refreshSnapshot()
            loadHistory()
        }
    }

    fun sendFile(uri: Uri, image: Boolean) {
        sendFiles(listOf(uri), image)
    }

    /** Queue several files sequentially on the live peer (Kennedy g2387). */
    fun sendFiles(uris: List<Uri>, image: Boolean) {
        val id = _state.value.selectedId
        if (id.isBlank() || _state.value.selectedIsGroup || uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val copied = stageOutgoing(uri, image) ?: continue
                try {
                    // The engine streams every chunk before returning; file_send_inflight queues peers.
                    NativeEngine.nativeSendFile(id, copied.absolutePath, image)
                } finally {
                    if (image) {
                        if (!historyShowsImage(id, copied)) copied.delete()
                    } else {
                        copied.delete()
                        copied.parentFile?.delete()
                    }
                }
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

    fun setPeerRetentionDays(addr: String, days: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeSetPeerRetentionDays(addr, days.coerceIn(0, 3650))
            refreshSnapshot()
        }
    }

    /** Pin or unpin a chat; new pins go to the end of the pinned block. */
    fun setConversationPinned(id: String, group: Boolean, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val order = if (pinned) {
                (_state.value.conversations.filter { it.pinned }.maxOfOrNull { it.pinOrder } ?: -1) + 1
            } else {
                0
            }
            NativeEngine.nativeSetConversationPin(id, group, pinned, order)
            refreshSnapshot()
        }
    }

    fun setConversationNotificationsMuted(id: String, group: Boolean, muted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeSetConversationNotificationsMuted(id, group, muted)
            refreshSnapshot()
        }
    }

    /** Move a pinned chat up (-1) or down (+1) among pinned rows; renumbers pinOrder. */
    fun movePinnedConversation(id: String, delta: Int) {
        if (delta == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val pinned = _state.value.conversations.filter { it.pinned }.toMutableList()
            val idx = pinned.indexOfFirst { it.addr == id }
            if (idx < 0) return@launch
            val target = (idx + delta).coerceIn(0, pinned.lastIndex)
            if (target == idx) return@launch
            val item = pinned.removeAt(idx)
            pinned.add(target, item)
            pinned.forEachIndexed { order, row ->
                NativeEngine.nativeSetConversationPin(row.addr, row.isGroup, true, order)
            }
            refreshSnapshot()
        }
    }

    fun removePeer(addr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NativeEngine.nativeRemovePeer(addr)
            MemoryImages.removePeer(addr)
            SealedFiles.removePeer(addr)
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
            MemoryImages.removePeer(addr)
            SealedFiles.removePeer(addr)
            loadHistory()
        }
    }

    /** Deletes one direct-chat message from the list, the in-memory history and the history file. */
    fun deleteMessage(msg: MessageUi) {
        val peer = _state.value.selectedId
        if (peer.isBlank() || _state.value.selectedIsGroup) return
        val deletedText = msg.text
        val attachmentName = msg.attachment?.displayName?.ifBlank { null }
            ?: msg.fileCaption.ifBlank { null }
        _state.update { s ->
            val remaining = s.messages.filterNot { it === msg }.map { row ->
                val scrubbed = scrubReplyTextIfMatches(row.text, deletedText, attachmentName)
                if (scrubbed != null) row.copy(text = scrubbed) else row
            }
            s.copy(messages = remaining)
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Native delete also scrubs quoting replies in the persisted history.
            NativeEngine.nativeDeleteMessage(peer, msg.messageId, msg.kind, msg.ts, msg.text)
            loadHistory()
            refreshSnapshot()
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

    fun saveReplicas(endpoints: List<String>, auth: Map<String, String>, done: ((Boolean) -> Unit)? = null) {
        if (!_state.value.running && !_state.value.starting) {
            onMain {
                Toast.makeText(getApplication(), str(R.string.blindbox_need_session), Toast.LENGTH_LONG).show()
                done?.invoke(false)
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val json = JSONObject().put("endpoints", JSONArray(endpoints))
            val authJson = JSONObject()
            auth.forEach { (k, v) -> authJson.put(k, v) }
            json.put("auth", authJson)
            val ok = runCatching {
                NativeEngine.nativeSaveReplicas(json.toString())
                true
            }.getOrDefault(false)
            refreshSnapshot()
            onMain {
                Toast.makeText(
                    getApplication(),
                    if (ok) str(R.string.replicas_saved) else str(R.string.blindbox_need_session),
                    Toast.LENGTH_SHORT,
                ).show()
                done?.invoke(ok)
            }
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
            if (result.optBoolean("ok") && op == "importProfile") {
                val peers = _state.value.contacts.map { it.addr }
                AvatarStore.markNeedRequestAll(appRoot, _state.value.profile, peers)
                onMain { bumpAvatarEpoch() }
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
            val imageDirs = mediaRoots(image = true)
            val fileDirs = mediaRoots(image = false)
            val messages = buildList {
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    val text = o.optString("text")
                    val kind = o.optString("kind")
                    add(
                        MessageUi(
                            kind = kind,
                            text = text,
                            ts = o.optString("ts").ifBlank { o.optString("createdAt") },
                            sender = o.optString("senderId"),
                            delivery = o.optString("deliveryState"),
                            deliveryHint = o.optString("deliveryHint"),
                            deliveryReason = o.optString("deliveryReason"),
                            retryable = o.optBoolean("retryable"),
                            messageId = o.optString("messageId").ifBlank { o.optString("msgId") },
                            imagePath = resolveChatImagePath(text, appRoot, imageDirs),
                            filePath = resolveChatFilePath(text, appRoot, fileDirs),
                            memoryImage = if (!group && kind == "in") resolveMemoryImage(text, id) else "",
                            sealedFile = if (!group && kind == "in") {
                                parseSealedFileHistory(text)?.id?.takeIf { SealedFiles.contains(id, it) }.orEmpty()
                            } else {
                                ""
                            },
                        ),
                    )
                }
            }
            _state.update { it.copy(messages = messages) }
        }
    }

    private fun refreshSnapshot() {
        val snap = runCatching { JSONObject(NativeEngine.nativeSnapshot()) }.getOrNull() ?: return
        // A failed/empty native reply must not wipe BlindBox readiness while the
        // session is still up (optBoolean defaults missing keys to false).
        if (snap.has("error") && !snap.has("blindboxReady") && !snap.has("replicas")) return
        val contacts = jsonContacts(snap.optJSONArray("contacts") ?: JSONArray())
        val groups = jsonGroups(snap.optJSONArray("groups") ?: JSONArray())
        val unreadContacts = mergeUnread(contacts, _state.value.contacts)
        val unreadGroups = mergeUnread(groups, _state.value.groups)
        val hasReplicaList = snap.has("replicas")
        val replicas = if (hasReplicaList) {
            buildList {
                val arr = snap.optJSONArray("replicas") ?: JSONArray()
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        } else {
            _state.value.replicas
        }
        // Native ready = enabled + endpoints. Do not invent true from an
        // empty/partial snapshot; Status still waits for tunnelsReady.
        val bbReady = when {
            snap.has("blindboxReady") -> snap.optBoolean("blindboxReady")
            replicas.isNotEmpty() -> true
            else -> _state.value.blindboxReady
        }
        val replicaSource = when {
            snap.has("replicaSource") -> snap.optString("replicaSource")
            replicas.isNotEmpty() && _state.value.replicaSource.isBlank() -> "profile-file"
            else -> _state.value.replicaSource
        }
        _state.update {
            it.copy(
                localAddr = snap.optString("localAddr", it.localAddr),
                running = snap.optBoolean("running", it.running),
                blindboxReady = bbReady,
                replicaSource = replicaSource,
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
                    historyRetentionDays = o.optInt("historyRetentionDays", 0),
                    live = o.optBoolean("live"),
                    offlineReady = o.optBoolean("offlineReady"),
                    pinned = o.optBoolean("pinned"),
                    pinOrder = o.optInt("pinOrder", 0).coerceAtLeast(0),
                    notificationsMuted = o.optBoolean("notificationsMuted"),
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
                    lastActivityTs = o.optString("lastActivityTs"),
                    isGroup = true,
                    members = members,
                    epoch = o.optLong("epoch"),
                    pinned = o.optBoolean("pinned"),
                    pinOrder = o.optInt("pinOrder", 0).coerceAtLeast(0),
                    notificationsMuted = o.optBoolean("notificationsMuted"),
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

    private fun stagingDir(): File = File(getApplication<Application>().cacheDir, "staging")

    private fun copyUri(uri: Uri, outgoing: Boolean = false): File? {
        val resolver = getApplication<Application>().contentResolver
        val name = safeLocalFileName(uri.lastPathSegment)
        val stagingDir = stagingDir().apply { mkdirs() }
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

    /**
     * The file name the providing app reports for a picked document. The last
     * URI segment is only a fallback: for most pickers it is a document id
     * such as "397324", and that is what the peer would see as the file name.
     */
    private fun displayNameOf(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val reported = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        val name = safeLocalFileName(reported?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment)
        if (name.contains('.')) return name
        val ext = runCatching {
            resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        }.getOrNull()
        return if (ext.isNullOrBlank()) name else "$name.$ext"
    }

    /**
     * Copy a picked document to where the engine can read it. A picture goes
     * into the images directory and stays there, because the chat shows it
     * from that file. Any other file is staged in the cache for the send only.
     */
    private fun stageOutgoing(uri: Uri, image: Boolean): File? {
        val name = displayNameOf(uri)
        // Pictures must land in a media directory the chat can resolve; without
        // that directory there is nothing useful to stage.
        val keepDir = if (image) activeImagesDir ?: return null else null
        val staging = if (keepDir == null) File(stagingDir(), System.nanoTime().toString()) else null
        val dir = keepDir ?: staging!!
        return try {
            dir.mkdirs()
            val dest = if (keepDir != null) uniqueFileIn(dir, name) else File(dir, name)
            // The name came from another app's content provider; it must stay a
            // plain file directly inside the target directory.
            if (dest.canonicalFile.parentFile != dir.canonicalFile) return null
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return null
            try {
                input.use { src -> dest.outputStream().use { src.copyTo(it) } }
            } catch (error: Exception) {
                dest.delete()
                throw error
            }
            dest
        } catch (_: Exception) {
            // A failed staging attempt leaves an empty per-send directory behind.
            staging?.deleteRecursively()
            null
        }
    }

    /** True when the engine recorded this picture in the chat, so its file must stay. */
    private fun historyShowsImage(peer: String, file: File): Boolean = runCatching {
        val rows = JSONArray(NativeEngine.nativeHistory(peer))
        val wanted = "[image] ${file.absolutePath}"
        (0 until rows.length()).any { rows.getJSONObject(it).optString("text") == wanted }
    }.getOrDefault(true)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun failStart(message: String, issue: RouterIssue?) {
        sessionPassword = ""
        val last = _state.value.profile
        // Keep the last profile name, but do not loop auto-resume on a hard fail.
        SessionPrefs(lastProfile = last, resume = false).save(appRoot)
        ChatForegroundService.stop(getApplication())
        onMain {
            _state.update {
                it.copy(
                    starting = false,
                    resuming = false,
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
        val hungUp = synchronized(hungUpPeers) { addr in hungUpPeers }
        if (hungUp && (state == "connecting" || state == "handshaking")) {
            // Auto-reconnect notices that raced past Disconnect.
            return
        }
        if (state == "secure" || state == "disconnected" || state == "failed") {
            if (state == "secure") {
                synchronized(hungUpPeers) { hungUpPeers.remove(addr) }
            }
        }
        _state.update { it.copy(peerStates = it.peerStates + (addr to state)) }
        if (state == "secure") {
            viewModelScope.launch(Dispatchers.IO) { syncAvatarsAfterSecure(addr) }
        }
    }

    fun ownAvatarPath(): String? {
        val profile = _state.value.profile
        if (profile.isBlank()) return null
        val file = AvatarStore.ownPreview(appRoot, profile)
        return file.takeIf { it.isFile }?.absolutePath
    }

    fun peerAvatarPath(peer: String): String? {
        val profile = _state.value.profile
        if (profile.isBlank() || peer.isBlank()) return null
        val file = AvatarStore.peerPreview(appRoot, profile, peer) ?: return null
        return file.takeIf { it.isFile }?.absolutePath
    }

    fun setOwnAvatarFromUri(uri: Uri, onDone: (Boolean) -> Unit = {}) {
        val profile = _state.value.profile
        if (profile.isBlank()) {
            onDone(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching false
                AvatarStore.setOwnAvatar(appRoot, profile, bytes)
                true
            }.getOrDefault(false)
            onMain {
                if (ok) {
                    _state.update { it.copy(avatarEpoch = it.avatarEpoch + 1) }
                    val peer = _state.value.selectedId
                    if (peer.isNotBlank() && !_state.value.selectedIsGroup) {
                        viewModelScope.launch(Dispatchers.IO) { pushOwnAvatarIfNeeded(peer, force = true) }
                    }
                }
                onDone(ok)
            }
        }
    }

    private fun bumpAvatarEpoch() {
        _state.update { it.copy(avatarEpoch = it.avatarEpoch + 1) }
    }

    private fun syncAvatarsAfterSecure(peer: String) {
        val profile = _state.value.profile
        if (profile.isBlank() || peer.isBlank()) return
        pushOwnAvatarIfNeeded(peer)
        if (AvatarStore.needsRequest(appRoot, profile, peer)) {
            runCatching {
                NativeEngine.nativeSendSignal(peer, "AVATAR_REQUEST")
            }
        }
    }

    private fun pushOwnAvatarIfNeeded(peer: String, force: Boolean = false) {
        val profile = _state.value.profile
        if (profile.isBlank() || peer.isBlank()) return
        if (!force && !AvatarStore.needsOwnPush(appRoot, profile, peer)) return
        val own = AvatarStore.ownPreview(appRoot, profile)
        if (!own.isFile) return
        val wire = File(stagingDir().apply { mkdirs() }, AvatarStore.WIRE_FILENAME)
        try {
            own.copyTo(wire, overwrite = true)
            val ok = runCatching {
                NativeEngine.nativeSendFile(peer, wire.absolutePath, false)
            }.getOrDefault(false)
            if (ok) {
                AvatarStore.markDelivered(appRoot, profile, peer)
            }
        } finally {
            wire.delete()
        }
    }

    private fun ingestAvatarFile(peer: String, path: String): Boolean {
        val profile = _state.value.profile
        if (profile.isBlank() || !AvatarStore.isWireFilename(path)) return false
        val src = File(path)
        if (!src.isFile) return false
        return try {
            val bytes = src.readBytes()
            AvatarStore.setPeerAvatar(appRoot, profile, peer, bytes)
            src.delete()
            onMain { bumpAvatarEpoch() }
            true
        } catch (_: Exception) {
            false
        }
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
        val viewingThisChat = isViewingIncoming(peer, false)
        if ((kind == "in" || kind == "peer") && shouldBumpIncoming(peer, false)) {
            val preview = entry.optString("text")
            val ts = entry.optString("ts").ifBlank { java.time.Instant.now().toString() }
            _state.update {
                it.copy(
                    contacts = it.contacts.map { c ->
                        if (c.addr == peer) {
                            c.copy(
                                unread = c.unread + 1,
                                lastPreview = preview,
                                lastActivityTs = ts.ifBlank { c.lastActivityTs },
                            )
                        } else {
                            c
                        }
                    },
                )
            }
            if (shouldNotifyIncoming(peer, false)) {
                NotificationHelper.notifyMessage(getApplication(), peer, preview)
            }
        }
        if (viewingThisChat) {
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
        val router = _state.value.router
        val lost = state == "failed"
        val issue = when {
            state == "ready" -> null
            !lost -> _state.value.routerIssue
            router.usingBundled -> RouterIssue(RouterIssueCode.ROUTER_CRASHED, reason)
            else -> RouterDiagnostics.diagnoseSystemRouter(router.samHost, router.samPort, false)
        }
        _state.update {
            it.copy(
                transport = state,
                samReady = if (lost) false else it.samReady,
                status = when (state) {
                    "ready" -> str(R.string.status_online)
                    "failed" -> str(R.string.status_failed)
                    else -> str(R.string.status_warming)
                },
                routerIssue = issue,
            )
        }
        // Replica list is known early in start(); refresh so Status does not
        // keep a stale blindboxReady=false from the first empty paint.
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onTransfer(peer: String, json: String) = onMain {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return@onMain
        val update = TransferProgressUi(
            peer = peer,
            name = o.optString("name").ifBlank { str(R.string.transfer_unnamed) },
            size = o.optLong("size"),
            transferred = o.optLong("transferred"),
            incoming = o.optBoolean("incoming"),
            image = o.optBoolean("image"),
            outcome = o.optString("outcome", "active").ifBlank { "active" },
        )
        // Start-of-receive notice; durable chat row comes from C++ history (system + final [file]).
        val incomingStart =
            update.incoming &&
                update.outcome == "active" &&
                update.transferred == 0L &&
                !update.image &&
                !AvatarStore.isWireFilename(update.name)
        if (incomingStart) {
            appendNotice(
                "transfer.incoming_start",
                NoticeUi.LEVEL_USER,
                peer,
                text = str(R.string.transfer_peer_started_file, update.name, update.size),
            )
            if (isViewingIncoming(peer, false)) {
                loadHistory()
            }
        }
        _state.update { state ->
            val without = state.transfers.filterNot { it.key == update.key }
            val next = when (update.outcome) {
                "completed", "failed" -> without + update
                else -> without + update
            }
            state.copy(transfers = next)
        }
        if (update.outcome == "completed" || update.outcome == "failed") {
            viewModelScope.launch {
                delay(2_500)
                _state.update { state ->
                    state.copy(transfers = state.transfers.filterNot {
                        it.key == update.key && it.outcome == update.outcome
                    })
                }
            }
        }
    }

    override fun onFileReceived(peer: String, path: String) {
        if (AvatarStore.isWireFilename(path)) {
            viewModelScope.launch(Dispatchers.IO) { ingestAvatarFile(peer, path) }
            return
        }
        onMain {
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
            if (shouldNotifyIncoming(peer, false)) {
                NotificationHelper.notifyMessage(
                    getApplication(),
                    peer,
                    str(R.string.notify_file, File(path).name),
                )
            }
            if (shouldBumpIncoming(peer, false)) {
                val now = java.time.Instant.now().toString()
                _state.update {
                    it.copy(
                        contacts = it.contacts.map { c ->
                            if (c.addr == peer) {
                                c.copy(
                                    unread = c.unread + 1,
                                    lastPreview = str(R.string.notify_file, File(path).name),
                                    lastActivityTs = now,
                                )
                            } else {
                                c
                            }
                        },
                    )
                }
            }
            if (isViewingIncoming(peer, false)) loadHistory()
        }
    }

    override fun onAvatarRequest(peer: String) {
        viewModelScope.launch(Dispatchers.IO) { pushOwnAvatarIfNeeded(peer, force = true) }
    }

    override fun onSealedFile(peer: String, id: String, displayName: String, path: String) {
        SealedFiles.put(peer, id, displayName, path)
        val dest = saveAsUri
        saveAsUri = null
        if (dest != null) {
            viewModelScope.launch(Dispatchers.IO) {
                exportSealed(peer, id, dest)
            }
        }
        onMain {
            if (shouldNotifyIncoming(peer, false)) {
                NotificationHelper.notifyMessage(
                    getApplication(),
                    peer,
                    str(R.string.notify_file, displayName),
                )
            }
            if (shouldBumpIncoming(peer, false)) {
                val now = java.time.Instant.now().toString()
                _state.update {
                    it.copy(
                        contacts = it.contacts.map { c ->
                            if (c.addr == peer) {
                                c.copy(
                                    unread = c.unread + 1,
                                    lastPreview = str(R.string.notify_file, displayName),
                                    lastActivityTs = now,
                                )
                            } else {
                                c
                            }
                        },
                    )
                }
            }
            if (isViewingIncoming(peer, false)) loadHistory()
        }
    }

    /** Decrypts a sealed download into a short-lived cache file and opens it. */
    fun openSealedFile(
        peer: String,
        id: String,
        onReady: (String) -> Unit = { openReceivedFile(getApplication(), it) },
        onFailed: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val plain = decryptSealedToView(peer, id)
            onMain {
                if (plain == null) {
                    Toast.makeText(getApplication(), str(R.string.file_gone), Toast.LENGTH_SHORT).show()
                    onFailed()
                } else {
                    onReady(plain.absolutePath)
                }
            }
        }
    }

    /** Copies a received file the core wrote into a document the user picked. */
    fun saveLocalFile(path: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val src = File(path)
            val saved = runCatching {
                val allowed = isReceivedMediaFile(getApplication(), src) ||
                    isEphemeralViewFile(getApplication(), src)
                if (!allowed || !src.isFile) return@runCatching false
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: return@runCatching false
                out.use { dest -> src.inputStream().use { it.copyTo(dest) } }
                true
            }.getOrDefault(false)
            onMain {
                Toast.makeText(
                    getApplication(),
                    str(if (saved) R.string.file_saved else R.string.file_gone),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Decrypts a sealed download into a document the user picked. */
    fun saveSealedFile(peer: String, id: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = exportSealed(peer, id, uri)
            onMain {
                Toast.makeText(
                    getApplication(),
                    str(if (saved) R.string.file_saved else R.string.file_gone),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun decryptSealedToView(peer: String, id: String): File? {
        val entry = SealedFiles.get(peer, id) ?: return null
        val viewDir = File(incomingCache(), "view")
        viewDir.mkdirs()
        viewDir.listFiles()?.forEach { it.delete() }
        val dest = File(viewDir, safeLocalFileName(entry.displayName))
        val ok = runCatching { NativeEngine.nativeDecryptSealed(entry.path, dest.absolutePath) }
            .getOrDefault(false)
        if (!ok) {
            dest.delete()
            return null
        }
        return dest
    }

    private fun exportSealed(peer: String, id: String, uri: Uri): Boolean {
        val plain = decryptSealedToView(peer, id) ?: return false
        return try {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: return false
            out.use { dest -> plain.inputStream().use { it.copyTo(dest) } }
            true
        } catch (_: Exception) {
            false
        } finally {
            plain.delete()
        }
    }

    override fun onImageData(peer: String, name: String, data: ByteArray) {
        // Stored on the engine thread, before anything is posted: the history
        // entry that follows is resolved against the store.
        MemoryImages.put(peer, name, data)
        onImageReceived(peer, name)
    }

    /** Writes an in-memory picture to a document the user picked. */
    fun saveMemoryImage(key: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val data = MemoryImages.get(key) ?: return@runCatching false
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: return@runCatching false
                out.use { it.write(data) }
                true
            }.getOrDefault(false)
            onMain {
                Toast.makeText(
                    getApplication(),
                    str(if (saved) R.string.image_saved else R.string.image_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onImageReceived(peer: String, path: String) = onMain {
        if (shouldNotifyIncoming(peer, false)) {
            NotificationHelper.notifyMessage(getApplication(), peer, str(R.string.notify_image))
        }
        if (isViewingIncoming(peer, false)) {
            loadHistory()
        } else if (shouldBumpIncoming(peer, false)) {
            val now = java.time.Instant.now().toString()
            _state.update {
                it.copy(
                    contacts = it.contacts.map { c ->
                        if (c.addr == peer) {
                            c.copy(
                                unread = c.unread + 1,
                                lastPreview = str(R.string.image_preview),
                                lastActivityTs = now,
                            )
                        } else {
                            c
                        }
                    },
                )
            }
        }
    }

    override fun onImageText(peer: String, text: String) = onMain {
        if (isViewingIncoming(peer, false)) {
            loadHistory()
        }
    }

    override fun onContactsChanged() = onMain {
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshot() }
    }

    override fun onGroupMessage(groupId: String) = onMain {
        if (isViewingIncoming(groupId, true)) {
            loadHistory()
        } else if (shouldBumpIncoming(groupId, true)) {
            val now = java.time.Instant.now().toString()
            _state.update {
                it.copy(
                    groups = it.groups.map { g ->
                        if (g.addr == groupId) {
                            g.copy(unread = g.unread + 1, lastActivityTs = now)
                        } else {
                            g
                        }
                    },
                )
            }
            if (shouldNotifyIncoming(groupId, true)) {
                val title = _state.value.groups.firstOrNull { it.addr == groupId }?.title
                    ?: groupId.take(18)
                NotificationHelper.notifyMessage(
                    getApplication(),
                    groupId,
                    str(R.string.notify_group_message, title),
                )
            }
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
        // Avatar pushes are auto-accepted in native; this is a fallback if they defer.
        if (AvatarStore.isWireFilename(name)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { NativeEngine.nativeFileRespond(peer, true) }
            }
            return@onMain
        }
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
        val peer = intent?.getStringExtra("openPeer")?.trim().orEmpty()
        if (peer.isEmpty() || !_state.value.running) return
        selectConversation(peer, false)
        _state.update { it.copy(openChatNonce = System.nanoTime()) }
    }

    override fun onCleared() {
        main.post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        }
        clearIncomingCache()
        MemoryImages.clear()
        SealedFiles.clear()
        // Application-scoped ViewModel: cleared on process death or explicit
        // store clear. If the chat FGS still holds us, leave the engine up so
        // a later Activity can rebind; otherwise tear down.
        if (ChatForegroundService.isHeld()) {
            NativeEngine.nativeSetListener(null)
        } else {
            NativeEngine.nativeSetListener(null)
            runCatching { NativeEngine.nativeStop() }
        }
        super.onCleared()
    }
}
