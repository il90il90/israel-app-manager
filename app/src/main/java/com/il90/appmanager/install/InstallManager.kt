package com.il90.appmanager.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.il90.appmanager.data.CatalogApp
import com.il90.appmanager.data.SettingsStore
import com.il90.appmanager.data.Variant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface TaskState {
    data object Queued : TaskState
    data class Downloading(val bytes: Long, val total: Long) : TaskState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : TaskState
    data object Installing : TaskState
    /** Android is showing (or waiting to show) its own confirmation. */
    data object AwaitingUser : TaskState
    data class Failed(val reason: FailReason) : TaskState

    val isActive: Boolean get() = this !is Failed
    /** Still needs this process to stay alive to make progress on its own. */
    val isWorking: Boolean get() = this is Queued || this is Downloading || this is Verifying || this is Installing
}

enum class FailReason { NETWORK, STORAGE, CORRUPT, CONFLICT, INCOMPATIBLE, BLOCKED, INVALID, DOWNGRADE, GENERIC }

data class Task(val appId: String, val name: String, val variant: Variant, val state: TaskState)

sealed interface InstallEvent {
    data class Installed(val appId: String, val name: String) : InstallEvent
}

data class ConfirmRequest(val sessionId: Int, val appName: String, val intent: Intent)

private class TaskFailure(val reason: FailReason) : Exception(reason.name)
private class UserAborted : Exception("aborted by user")

private class Session(val appId: String, val appName: String, val result: CompletableDeferred<Outcome>)

private sealed interface Outcome {
    data object Success : Outcome
    data object Aborted : Outcome
    data class Failure(val reason: FailReason) : Outcome
}

/** Downloads, verifies and installs APKs, one state per catalog app. */
class InstallManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val notifier: Notifier,
) {
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()

    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    /** A system confirmation the activity should put on screen. */
    val confirm: StateFlow<ConfirmRequest?> = _confirm.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val downloads = Semaphore(2)
    private val installs = Mutex()
    private val dir: File get() = File(context.cacheDir, "apks").apply { mkdirs() }

    fun enqueue(app: CatalogApp, variant: Variant) {
        val running = _tasks.value[app.id]
        if (running != null && running.state.isActive) return
        _tasks.update { it + (app.id to Task(app.id, app.name, variant, TaskState.Queued)) }
        DownloadService.start(context)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(app, variant) }
        jobs[app.id] = job
        job.start()
    }

    /** Stops a running task, or clears a failed one. */
    fun cancel(appId: String) {
        jobs.remove(appId)?.cancel()
        _tasks.update { it - appId }
    }

    fun confirmShown(request: ConfirmRequest) {
        _confirm.compareAndSet(request, null)
        notifier.cancelConfirm(request.sessionId)
    }

    private suspend fun run(app: CatalogApp, variant: Variant) {
        val me = currentCoroutineContext().job
        // A cancelled task may still be unwinding while the user starts the same app again:
        // only the job that currently owns the app may touch its state.
        fun owns() = me.isActive && jobs[app.id] === me
        fun set(state: TaskState) {
            if (owns()) _tasks.update { it + (app.id to Task(app.id, app.name, variant, state)) }
        }
        fun clear() {
            if (owns()) _tasks.update { it - app.id }
        }
        val file = File(dir, "${app.id}-${variant.versionCode}-${variant.sha256.take(8).ifEmpty { "x" }}.apk")
        try {
            downloads.withPermit {
                download(variant, file) { done, total -> set(TaskState.Downloading(done, total)) }
            }
            set(TaskState.Verifying)
            verify(file, variant)
            installs.withLock {
                set(TaskState.Installing)
                when (val outcome = install(app, file, variant)) {
                    Outcome.Success -> Unit
                    Outcome.Aborted -> throw UserAborted()
                    is Outcome.Failure -> throw TaskFailure(outcome.reason)
                }
            }
            if (settings.current().deleteAfterInstall) file.delete()
            clear()
            _events.tryEmit(InstallEvent.Installed(app.id, app.name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: UserAborted) {
            clear()
        } catch (e: TaskFailure) {
            if (e.reason == FailReason.CORRUPT || e.reason == FailReason.INVALID) file.delete()
            set(TaskState.Failed(e.reason))
        } catch (e: IOException) {
            val full = e.message?.contains("ENOSPC") == true || freeSpace() < 8L * 1024 * 1024
            set(TaskState.Failed(if (full) FailReason.STORAGE else FailReason.NETWORK))
        } catch (_: SecurityException) {
            set(TaskState.Failed(FailReason.BLOCKED))
        } catch (_: Exception) {
            set(TaskState.Failed(FailReason.GENERIC))
        } finally {
            jobs.remove(app.id, me)
        }
    }

    private suspend fun download(variant: Variant, target: File, onProgress: (Long, Long) -> Unit) {
        if (target.exists() && (variant.size <= 0 || target.length() == variant.size)) return
        target.delete()
        val part = File(target.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        if (variant.size > 0 && existing >= variant.size) {
            part.delete()
            existing = 0
        }
        // The installer makes its own copy, so the download needs about twice its size.
        if (variant.size > 0 && freeSpace() < (variant.size - existing) + variant.size + SPARE_BYTES) {
            throw TaskFailure(FailReason.STORAGE)
        }
        val request = Request.Builder().url(variant.url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = http.newCall(request)
        // A blocked socket read does not notice coroutine cancellation; closing the call does.
        val canceller = scope.launch(currentCoroutineContext().job + Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 416) part.delete()
                    throw IOException("HTTP ${response.code}")
                }
                val resumed = response.code == 206
                if (!resumed) existing = 0
                val body = response.body ?: throw IOException("empty body")
                val total = if (variant.size > 0) variant.size else body.contentLength().let { if (it > 0) it + existing else 0 }
                var done = existing
                var lastTick = 0L
                onProgress(done, total)
                FileOutputStream(part, resumed).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.nanoTime()
                        if (now - lastTick > 90_000_000L) {
                            lastTick = now
                            onProgress(done, total)
                        }
                    }
                    out.fd.sync()
                }
                onProgress(done, total)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // cancelled, not a network failure
            throw e
        } finally {
            canceller.cancel()
        }
        if (variant.size > 0 && part.length() != variant.size) {
            part.delete()
            throw TaskFailure(FailReason.CORRUPT)
        }
        if (!part.renameTo(target)) throw IOException("cannot move download into place")
    }

    private fun verify(file: File, variant: Variant) {
        if (variant.sha256.isNotBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(variant.sha256, ignoreCase = true)) throw TaskFailure(FailReason.CORRUPT)
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw TaskFailure(FailReason.INVALID)
        if (archive.packageName != variant.packageName) throw TaskFailure(FailReason.INVALID)
    }

    private suspend fun install(app: CatalogApp, file: File, variant: Variant): Outcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(variant.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Lets Android update apps this manager installed (and the manager itself) without a prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val sessionId = installer.createSession(params)
        val result = CompletableDeferred<Outcome>()
        sessions[sessionId] = Session(app.id, app.name, result)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION)
                    .setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
            return withTimeout(CONFIRM_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            runCatching { installer.abandonSession(sessionId) }
            return Outcome.Aborted
        } catch (e: CancellationException) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } finally {
            sessions.remove(sessionId)
            _confirm.update { if (it?.sessionId == sessionId) null else it }
            notifier.cancelConfirm(sessionId)
        }
    }

    /** Called by [InstallResultReceiver] for every status Android reports about a session. */
    fun onInstallResult(sessionId: Int, status: Int, message: String?, confirmIntent: Intent?) {
        val session = sessions[sessionId] ?: run {
            // A session from an earlier process: nobody is waiting for it any more.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            return
        }
        val appName = session.appName
        val result = session.result
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmIntent == null) {
                    result.complete(Outcome.Failure(FailReason.GENERIC))
                    return
                }
                _tasks.update { all ->
                    val task = all[session.appId]
                    if (task != null && task.state is TaskState.Installing) all + (session.appId to task.copy(state = TaskState.AwaitingUser)) else all
                }
                val request = ConfirmRequest(sessionId, appName, confirmIntent)
                _confirm.value = request
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (!foreground) notifier.postConfirm(sessionId, appName)
            }
            PackageInstaller.STATUS_SUCCESS -> result.complete(Outcome.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> result.complete(Outcome.Aborted)
            else -> {
                val text = message.orEmpty().uppercase()
                val reason = when {
                    "DOWNGRADE" in text -> FailReason.DOWNGRADE
                    "UPDATE_INCOMPATIBLE" in text || "SIGNATURES DO NOT MATCH" in text || "INCONSISTENT_CERTIFICATES" in text -> FailReason.CONFLICT
                    status == PackageInstaller.STATUS_FAILURE_CONFLICT -> FailReason.CONFLICT
                    status == PackageInstaller.STATUS_FAILURE_BLOCKED -> FailReason.BLOCKED
                    status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> FailReason.INCOMPATIBLE
                    status == PackageInstaller.STATUS_FAILURE_INVALID -> FailReason.INVALID
                    status == PackageInstaller.STATUS_FAILURE_STORAGE -> FailReason.STORAGE
                    else -> FailReason.GENERIC
                }
                result.complete(Outcome.Failure(reason))
            }
        }
    }

    /** Space Android can give this app, counting caches it may clear to make room. */
    private fun freeSpace(): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(dir))
    } catch (_: Exception) {
        dir.usableSpace
    }

    private fun sharedDir(): File = File(context.cacheDir, "shared").apply {
        mkdirs()
        // Files stay for a while: the app they were sent to may still be reading them.
        val old = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
    }

    /**
     * Fetches (or reuses) and checks the APK of [variant], and returns a copy under its real
     * file name that can be handed to another app. Null when it could not be fetched.
     */
    suspend fun prepareForSharing(app: CatalogApp, variant: Variant, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val name = variant.fileName.ifBlank { "${app.id}-${variant.versionName}.apk" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(sharedDir(), name)
            try {
                val cached = File(dir, "${app.id}-${variant.versionCode}-${variant.sha256.take(8).ifEmpty { "x" }}.apk")
                if (cached.exists() && variant.size > 0 && cached.length() == variant.size) {
                    cached.copyTo(out, overwrite = true)
                } else {
                    // Downloaded to its own file, so an install of the same app is never disturbed.
                    downloads.withPermit {
                        download(variant, out) { done, total -> if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
                verify(out, variant)
                out.setLastModified(System.currentTimeMillis())
                out
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                out.delete()
                null
            }
        }

    /** A copy of this app's own installed APK, to send to someone. */
    suspend fun ownApk(fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            File(context.applicationInfo.sourceDir).copyTo(File(sharedDir(), fileName), overwrite = true)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        (dir.listFiles()?.sumOf { it.length() } ?: 0L) + (File(context.cacheDir, "shared").listFiles()?.sumOf { it.length() } ?: 0L)
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        val busy = _tasks.value.values.any { it.state.isWorking }
        if (!busy) dir.listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "shared").listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val SPARE_BYTES = 24L * 1024 * 1024
        private const val CONFIRM_TIMEOUT_MS = 10L * 60 * 1000

        fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
