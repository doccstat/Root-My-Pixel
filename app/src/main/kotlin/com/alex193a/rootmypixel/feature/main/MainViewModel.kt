package com.alex193a.rootmypixel.feature.main

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex193a.rootmypixel.R
import com.alex193a.rootmypixel.core.Result
import com.alex193a.rootmypixel.domain.model.DeviceSnapshot
import com.alex193a.rootmypixel.domain.model.InstallPhase
import com.alex193a.rootmypixel.domain.model.InstallUiState
import com.alex193a.rootmypixel.domain.model.UnrootWarningUi
import com.alex193a.rootmypixel.domain.usecase.ResolveTargetUseCase
import com.alex193a.rootmypixel.feature.install.InstallActivity
import com.alex193a.rootmypixel.shizuku.ExploitService
import com.alex193a.rootmypixel.shizuku.IExploitService
import com.alex193a.rootmypixel.utils.AppBackupRunner
import com.alex193a.rootmypixel.utils.AppBackupStore
import com.alex193a.rootmypixel.utils.KernelSuPresence
import com.alex193a.rootmypixel.utils.NativeProbe
import com.alex193a.rootmypixel.utils.RootShell
import com.alex193a.rootmypixel.utils.RootShellProbe
import com.alex193a.rootmypixel.utils.TempRootCleanup
import com.alex193a.rootmypixel.utils.UnrootCommandOutcome
import com.alex193a.rootmypixel.utils.UnrootIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val resolveTargetUseCase: ResolveTargetUseCase by lazy {
        get(ResolveTargetUseCase::class.java)
    }

    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableShizukuAvailable = MutableStateFlow(false)
    private val mutableKernelSuInstalled = MutableStateFlow(false)
    private val mutableUptimeExceeded = MutableStateFlow(false)
    private val mutableBackupPlan = MutableStateFlow<List<String>>(emptyList())
    private val mutableArchivedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val mutableExtraPaths = MutableStateFlow<List<String>>(emptyList())
    private val mutableHasRootState = MutableStateFlow(false)
    private var refreshJob: Job? = null

    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val shizukuAvailable: StateFlow<Boolean> = mutableShizukuAvailable.asStateFlow()
    val kernelSuInstalled: StateFlow<Boolean> = mutableKernelSuInstalled.asStateFlow()
    val uptimeExceeded: StateFlow<Boolean> = mutableUptimeExceeded.asStateFlow()

    /** Packages selected in the app picker, backed up and removed before unroot. */
    val backupPlan: StateFlow<List<String>> = mutableBackupPlan.asStateFlow()

    /** Packages that currently have an archive on disk. */
    val archivedPackages: StateFlow<Set<String>> = mutableArchivedPackages.asStateFlow()

    /** Extra absolute directories archived and removed with the selected apps. */
    val extraPaths: StateFlow<List<String>> = mutableExtraPaths.asStateFlow()

    /** True when a KernelSU/Vector root-state archive is on disk. */
    val rootStateArchived: StateFlow<Boolean> = mutableHasRootState.asStateFlow()


    private val shizukuPermissionHandler = Handler(Looper.getMainLooper())
    private val shizukuListener = Shizuku.OnBinderReceivedListener {
        shizukuPermissionHandler.post { checkShizuku() }
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        shizukuPermissionHandler.post { mutableShizukuAvailable.value = false }
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_PERMISSION_CODE) {
            shizukuPermissionHandler.post { checkShizuku() }
        }
    }

    init {
        reloadBackupState()
        refresh()
    }

    fun initShizuku() {
        Shizuku.addBinderReceivedListener(shizukuListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Shizuku.pingBinder()) {
            checkShizuku()
        }
    }

    private fun checkShizuku() {
        val available = try {
            Shizuku.pingBinder() &&
            Shizuku.isPreV11().not() &&
            Shizuku.getUid() == 2000 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        if (!available && Shizuku.pingBinder() && Shizuku.isPreV11().not()) {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }
        }

        mutableShizukuAvailable.value = available
    }

    override fun onCleared() {
        super.onCleared()
        Shizuku.removeBinderReceivedListener(shizukuListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(phase = InstallPhase.Checking)
            mutableUptimeExceeded.value = SystemClock.elapsedRealtime() > UPTIME_THRESHOLD_MS
            reloadBackupState()

            try {
                val kernelSuStatus = NativeProbe.kernelSuStatus()
                val kernelSuActive = KernelSuPresence.isActive(kernelSuStatus)
                mutableKernelSuInstalled.value = kernelSuActive ||
                    app.packageManager.getLaunchIntentForPackage("me.weishu.kernelsu") != null
                val probe = NativeProbe.run()
                if (kernelSuActive) {
                    val rootTransport = findAvailableRootTransport()
                    mutableState.value = InstallUiState(
                        phase = InstallPhase.Installed,
                        message = app.getString(R.string.status_ksu_active),
                        probeOutput = probe,
                        log = buildString {
                            appendLine(probe)
                            appendLine(
                                "KernelSU UAPI root-profile grant for this app: " +
                                        kernelSuStatus.appRootGranted,
                            )
                            if (!kernelSuStatus.isActive) {
                                appendLine(
                                    "[i] KernelSU detected through its su grant; " +
                                            "the legacy UAPI probe did not answer",
                                )
                            }
                            append(
                                rootTransport?.let {
                                    "[+] Unroot root transport verified: ${it.label}"
                                } ?: "[!] No usable root transport for Unroot",
                            )
                        },
                        canUnrootCurrentSession = rootTransport != null,
                    )
                    return@launch
                }
                val deviceInfo = NativeProbe.readDeviceSnapshot()
                val snapshot = DeviceSnapshot(
                    kernelRelease = deviceInfo.kernelRelease,
                    kernelVersion = deviceInfo.kernelVersion,
                    buildDisplay = deviceInfo.buildDisplay,
                    sdkVersion = deviceInfo.sdkVersion,
                    abi = deviceInfo.abi,
                    pageSize = deviceInfo.pageSize,
                    model = deviceInfo.model,
                    device = deviceInfo.device,
                )

                when (val result = resolveTargetUseCase(snapshot)) {
                    is Result.Success -> {
                        val profile = result.data
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Ready,
                            message = app.getString(R.string.status_not_installed),
                            probeOutput = probe,
                            log = buildString {
                                appendLine(probe)
                                appendLine("Matched profile: ${profile.profileId}")
                                appendLine("Device: ${deviceInfo.model} (${deviceInfo.device})")
                                appendLine("Kernel: ${deviceInfo.kernelRelease}")
                                appendLine("Build: ${deviceInfo.buildDisplay}")
                                appendLine("SDK: ${deviceInfo.sdkVersion}  ABI: ${deviceInfo.abi}")
                            },
                        )
                    }
                    is Result.Error -> {
                        mutableState.value = InstallUiState(
                            phase = InstallPhase.Failed,
                            message = app.getString(R.string.status_support_failed),
                            probeOutput = probe,
                            log = "$probe\n[-] ${result.error.message}",
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Failed,
                    message = app.getString(R.string.status_support_failed),
                    log = "[-] ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun install() {
        val intent = Intent(app, InstallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    /**
     * Removes the temporary exploit files from /data/local/tmp. Needs the app's
     * KernelSU root grant; KernelSU's su_compat path is /system/bin/su.
     */
    fun cleanupTemporaryFiles() {
        if (mutableState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            appendUnrootLog("[*] Removing temporary exploit files...")
            // Works without root: the staged copies live in this app's own
            // private storage, so they must not depend on the su grant.
            val purged = TempRootCleanup.purgeAppArtifacts(app)
            if (purged.isNotEmpty()) {
                appendUnrootLog("[+] Removed app-private leftovers: ${purged.joinToString()}")
            }
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            val outcome = TempRootCleanup.run(
                includeTransport = true,
                helper = helper,
                timeoutSeconds = ROOT_PROBE_TIMEOUT_SECONDS,
            )
            if (outcome.success) {
                appendUnrootLog("[+] Removed the temporary exploit files and transport")
            } else {
                appendUnrootLog(
                    "[!] Remaining /data/local/tmp cleanup needs KernelSU root; " +
                        "grant this app root in the manager",
                )
            }
        }
    }

    /**
     * Emulated soft reboot (`ksud soft-reboot`), not a bare `killall
     * system_server`. The emulation runs the post-fs-data and service stages
     * itself, which is the only window where a Zygisk module's `service.sh`
     * starts while `system_server` is down - required for Vector's `vectord` to
     * claim the `serial` service.
     */
    fun softReboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            if (!helper.exists()) return@launch

            val result = runCatching {
                RootShell.run(
                    "/data/adb/ksud soft-reboot",
                    helper = helper,
                    timeoutSeconds = 30L,
                )
            }.getOrNull()
            android.util.Log.i("RootMyPixel", "[softReboot] ${result?.output ?: "unavailable"}")
        }
    }

    fun exportLog() {
        val logFile = File(app.filesDir, "exploit.log")
        if (!logFile.exists()) return

        val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", logFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Export exploit.log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(chooserIntent)
    }

    private data class ShizukuServiceHandle(
        val service: IExploitService,
        val conn: ServiceConnection,
    )

    private enum class RootTransport(val label: String) {
        AppSu("KernelSU app su"),
        AppCveHelper("current-install CVE helper"),
        ShizukuCveSu("current-install CVE su via Shizuku"),
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun bindExploitService(): ShizukuServiceHandle? {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)

        var service: IExploitService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IExploitService.Stub.asInterface(binder)
                synchronized(this) {
                    (this as Object).notifyAll()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        Shizuku.bindUserService(args, conn)

        synchronized(conn as Object) {
            if (service == null) {
                try {
                    (conn as Object).wait(5000)
                } catch (_: InterruptedException) {
                }
            }
        }

        val svc = service ?: run {
            Shizuku.unbindUserService(args, conn, true)
            return null
        }
        return ShizukuServiceHandle(svc, conn)
    }

    private fun unbindExploitService(handle: ShizukuServiceHandle) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, ExploitService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("exploit_service")
            .version(1)
        Shizuku.unbindUserService(args, handle.conn, true)
    }

    fun unrootAndReboot() {
        if (mutableState.value.busy ||
            mutableState.value.phase != InstallPhase.Installed ||
            !mutableState.value.canUnrootCurrentSession
        ) return

        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Checking,
                message = app.getString(R.string.status_unrooting),
                unrootWarning = null,
            )
            appendUnrootLog("[*] Starting verified unroot cleanup...")

            // Archive and remove the selected apps first: the clean state must
            // not keep any root/root-adjacent package installed.
            if (!backupAndRemoveSelectedApps()) {
                showUnrootWarning(listOf(UnrootIssue.Backup))
                return@launch
            }

            val script = runCatching {
                app.assets.open("unroot.sh").bufferedReader().use { it.readText() }
            }.getOrElse {
                showUnrootWarning(listOf(UnrootIssue.Unknown))
                return@launch
            }

            val outcome = executeUnrootScript(script)
            // The unroot shell runs in the KernelSU domain, which can be denied
            // unlink on this app's MLS-categorised data dir. Delete the staged
            // payloads/scripts as the app itself so they never outlive the
            // exploit regardless of which steps reported OK.
            val purged = TempRootCleanup.purgeAppArtifacts(app)
            if (purged.isNotEmpty()) {
                appendUnrootLog("[+] Removed app-private leftovers: ${purged.joinToString()}")
            }
            if (outcome.cleanupComplete && outcome.rebootRequested) {
                appendUnrootLog("[+] Cleanup complete; reboot requested")
                delay(3000.milliseconds)
                refresh()
            } else {
                val issues = outcome.issues.toMutableList()
                if (outcome.cleanupComplete && !outcome.rebootRequested) {
                    issues += UnrootIssue.Reboot
                }
                showUnrootWarning(issues)
            }
        }
    }

    fun continueUnrootReboot() {
        if (mutableState.value.unrootWarning == null) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Checking,
                message = app.getString(R.string.status_unrooting),
                unrootWarning = null,
            )
            appendUnrootLog("[*] User requested reboot despite incomplete cleanup")
            if (!requestReboot()) {
                showUnrootWarning(listOf(UnrootIssue.Reboot))
            }
        }
    }

    fun cancelUnrootReboot() {
        mutableState.value = mutableState.value.copy(
            phase = InstallPhase.Installed,
            message = app.getString(R.string.status_unroot_incomplete),
            unrootWarning = null,
        )
        appendUnrootLog("[*] Reboot cancelled by user")
    }

    private fun executeUnrootScript(script: String): UnrootCommandOutcome {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        fun parseAttempt(transport: String, output: String): UnrootCommandOutcome? {
            val outcome = UnrootCommandOutcome.parse(output)
            appendUnrootLog("[*] $transport output:\n${output.ifBlank { "no output" }}")
            return if (outcome.cleanupComplete ||
                (outcome.hasStructuredOutput && !outcome.transportUnavailable)
            ) outcome else null
        }

        for (su in SU_CANDIDATES) {
            runCatching { runCommand(listOf(su, "-c", script)).output }
                .getOrNull()
                ?.let { parseAttempt(su, it) }
                ?.let { return it }
        }

        if (helper.exists()) {
            runCatching { runCommand(listOf(helper.absolutePath, "-c", script)).output }
                .getOrNull()
                ?.let { parseAttempt("CVE helper", it) }
                ?.let { return it }
        }

        if (isShizukuShellActive()) {
            val handle = runCatching { bindExploitService() }.getOrNull()
            if (handle != null) {
                try {
                    val command = "$SHIZUKU_CVE_SU -c ${shellQuote(script)}"
                    parseAttempt("Shizuku CVE su", handle.service.exec(command))?.let { return it }
                } catch (error: Exception) {
                    appendUnrootLog("[-] Shizuku CVE unroot error: ${error.message}")
                } finally {
                    unbindExploitService(handle)
                }
            }
        }

        return UnrootCommandOutcome(
            cleanupComplete = false,
            rebootRequested = false,
            transportUnavailable = true,
            issues = UnrootIssue.affectedByMissingTransport,
            hasStructuredOutput = true,
        )
    }

    private fun requestReboot(): Boolean {
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        val commands = buildList {
            SU_CANDIDATES.forEach { add(listOf(it, "-c", REBOOT_COMMAND)) }
            if (helper.exists()) add(listOf(helper.absolutePath, "-c", REBOOT_COMMAND))
        }
        commands.forEach { command ->
            val output = runCatching { runCommand(command).output }.getOrDefault("")
            appendUnrootLog("[*] Reboot attempt: ${output.ifBlank { "no output" }}")
            if (output.contains("UNROOT_REBOOT_REQUESTED")) return true
        }

        if (isShizukuShellActive()) {
            val handle = runCatching { bindExploitService() }.getOrNull() ?: return false
            try {
                val output = handle.service.exec(
                    "$SHIZUKU_CVE_SU -c ${shellQuote(REBOOT_COMMAND)}",
                )
                appendUnrootLog("[*] Shizuku CVE reboot attempt: $output")
                return output.contains("UNROOT_REBOOT_REQUESTED")
            } catch (error: Exception) {
                appendUnrootLog("[-] Shizuku CVE reboot error: ${error.message}")
            } finally {
                unbindExploitService(handle)
            }
        }
        return false
    }

    private fun findAvailableRootTransport(): RootTransport? {
        for (su in SU_CANDIDATES) {
            val suResult = runCatching {
                runCommand(listOf(su, "-c", ROOT_ID_COMMAND), ROOT_PROBE_TIMEOUT_SECONDS)
            }.getOrNull()
            if (suResult != null && RootShellProbe.isRoot(suResult.code, suResult.output)) {
                return RootTransport.AppSu
            }
        }

        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        if (helper.exists()) {
            val helperResult = runCatching {
                runCommand(
                    listOf(helper.absolutePath, "-c", ROOT_ID_COMMAND),
                    ROOT_PROBE_TIMEOUT_SECONDS,
                )
            }.getOrNull()
            if (helperResult != null &&
                RootShellProbe.isRoot(helperResult.code, helperResult.output)
            ) {
                return RootTransport.AppCveHelper
            }
        }

        if (!File(SHIZUKU_CVE_SU).exists() ||
            !File(SHIZUKU_CVE_SOCKET).exists() ||
            !isShizukuShellActive()
        ) return null

        val handle = runCatching { bindExploitService() }.getOrNull() ?: return null
        return try {
            val output = handle.service.exec("$SHIZUKU_CVE_SU -c '$ROOT_ID_COMMAND'")
            if (RootShellProbe.isRoot(0, output)) RootTransport.ShizukuCveSu else null
        } catch (_: Exception) {
            null
        } finally {
            unbindExploitService(handle)
        }
    }

    private fun isShizukuShellActive(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.isPreV11().not() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                Shizuku.getUid() == 2000
    } catch (_: Exception) {
        false
    }

    private fun runCommand(
        command: List<String>,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
    ): CommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        return CommandResult(
            code = if (finished) process.exitValue() else COMMAND_TIMEOUT_CODE,
            output = process.inputStream.bufferedReader().use { it.readText() }.trim(),
        )
    }

    // --- Selected-app backup and restore ---

    fun reloadBackupState() {
        mutableBackupPlan.value = AppBackupStore.loadPlan(app)
        mutableArchivedPackages.value = AppBackupStore.archivedPackages(app)
        mutableExtraPaths.value = AppBackupStore.loadExtraPaths(app)
        mutableHasRootState.value = AppBackupStore.hasRootStateBackup(app)
    }

    /** Persists the picker selection. */
    fun setBackupPlan(packages: List<String>) {
        AppBackupStore.savePlan(app, packages)
        reloadBackupState()
    }

    /** Persists the extra-directory list entered in the picker. */
    fun setExtraPaths(paths: List<String>) {
        AppBackupStore.saveExtraPaths(app, paths)
        reloadBackupState()
    }

    /**
     * Archives every selected app that is installed and then uninstalls it, so
     * the clean state has no root-adjacent app left. Returns false — leaving the
     * device untouched — when an archive could not be produced.
     */
    private fun backupAndRemoveSelectedApps(): Boolean {
        reloadBackupState()
        val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
        val installed = mutableBackupPlan.value.filter(::isPackageInstalled)
        val extras = AppBackupStore.loadExtraPaths(app)
        if (installed.isEmpty() && extras.isEmpty()) {
            appendUnrootLog(
                "[i] No selected apps or directories; archiving the KernelSU/Vector " +
                    "root state only",
            )
        }

        mutableState.value = mutableState.value.copy(message = app.getString(R.string.status_backing_up))

        // Archive everything before removing anything, so a failed archive
        // leaves the device untouched.
        if (installed.isNotEmpty()) {
            appendUnrootLog("[*] Backing up ${installed.size} selected app(s)...")
            val outcome = AppBackupRunner.backup(app, installed, helper)
            appendUnrootLog(
                "[*] App backup ${outcome.summary}" +
                    if (outcome.raw.isBlank()) "" else "\n${outcome.raw.trim()}",
            )
            if (!outcome.isComplete) {
                appendUnrootLog("[!] Aborting unroot: app backup incomplete, nothing was removed")
                return false
            }
        }

        if (extras.isNotEmpty()) {
            appendUnrootLog("[*] Archiving and removing ${extras.size} extra director(ies)...")
            val outcome = AppBackupRunner.backupExtras(app, extras, helper)
            appendUnrootLog(
                "[*] Extra-path backup ${outcome.summary}" +
                    if (outcome.raw.isBlank()) "" else "\n${outcome.raw.trim()}",
            )
            if (!outcome.isComplete) {
                appendUnrootLog("[!] Aborting unroot: extra-path backup incomplete")
                return false
            }
        }

        // The KernelSU/Vector layer outside any app sandbox: superuser grants,
        // app profiles, module files and markers, Vector's module config and
        // the staged `.d` scripts. `unroot.sh` removes all of /data/adb, so
        // this has to be captured before the reboot.
        appendUnrootLog("[*] Archiving KernelSU/Vector root state...")
        val rootState = AppBackupRunner.backupRootState(app, helper)
        appendUnrootLog(
            "[*] Root-state backup ${rootState.summary}" +
                if (rootState.raw.isBlank()) "" else "\n${rootState.raw.trim()}",
        )
        if (!rootState.isComplete) {
            appendUnrootLog("[!] Aborting unroot: root-state backup incomplete")
            return false
        }

        var removedAll = true
        for (pkg in installed) {
            if (!AppBackupStore.hasBackup(app, pkg)) {
                appendUnrootLog("[!] $pkg has no archive on disk; keeping it installed")
                removedAll = false
                continue
            }
            val result = runCommand(listOf(KERNEL_SU_PATH, "-c", "pm uninstall $pkg"))
            appendUnrootLog(
                if (result.code == 0) "[+] Removed $pkg (archive kept)"
                else "[!] Could not remove $pkg: ${result.output.take(120)}",
            )
        }
        reloadBackupState()
        return removedAll
    }

    /** Reinstalls archived apps that are missing and restores their data. */
    fun restoreBackedUpApps() {
        if (mutableState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            reloadBackupState()
            val helper = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")
            val targets = AppBackupStore.restorable(app).filterNot(::isPackageInstalled)
            val extras = AppBackupStore.loadExtraPaths(app)
                .takeIf { AppBackupStore.hasExtraBackup(app) }
                .orEmpty()
            val rootState = AppBackupStore.hasRootStateBackup(app)
            if (targets.isEmpty() && extras.isEmpty() && !rootState) {
                appendUnrootLog(
                    "[i] Nothing to restore: archived apps are installed, no extra " +
                        "directory has an archive and there is no root-state archive",
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                message = app.getString(R.string.status_restoring),
            )
            if (targets.isNotEmpty()) {
                appendUnrootLog("[*] Restoring ${targets.size} app(s) from backup...")
                val outcome = AppBackupRunner.restore(app, targets, helper)
                appendUnrootLog(
                    "[*] App restore ${outcome.summary}" +
                        if (outcome.raw.isBlank()) "" else "\n${outcome.raw.trim()}",
                )
                outcome.failed.forEach { (pkg, reason) ->
                    appendUnrootLog("[!] Restore failed: $pkg ($reason)")
                }
            }
            if (extras.isNotEmpty()) {
                appendUnrootLog("[*] Restoring ${extras.size} extra director(ies) from backup...")
                val outcome = AppBackupRunner.restoreExtras(app, extras, helper)
                appendUnrootLog(
                    "[*] Extra-path restore ${outcome.summary}" +
                        if (outcome.raw.isBlank()) "" else "\n${outcome.raw.trim()}",
                )
            }
            if (rootState) {
                appendUnrootLog("[*] Restoring KernelSU/Vector root state...")
                val outcome = AppBackupRunner.restoreRootState(app, helper)
                appendUnrootLog(
                    "[*] Root-state restore ${outcome.summary}" +
                        if (outcome.raw.isBlank()) "" else "\n${outcome.raw.trim()}",
                )
                if (outcome.isComplete) {
                    // A soft restart reloads the modules, but KernelSU re-reads
                    // its in-memory allowlist only on a real boot.
                    mutableState.value = mutableState.value.copy(rebootAfterRestore = true)
                } else {
                    appendUnrootLog(
                        "[!] Root-state restore incomplete; a soft restart may be " +
                            "needed before modules and grants take effect",
                    )
                }
            }
            mutableState.value = mutableState.value.copy(
                phase = InstallPhase.Installed,
                message = app.getString(R.string.status_ksu_active),
            )
            reloadBackupState()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            app.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    /** Reboots so KernelSU re-reads the restored allowlist and module set. */
    fun rebootAfterRestore() {
        if (mutableState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            if (requestReboot()) {
                mutableState.value = mutableState.value.copy(rebootAfterRestore = false)
                appendUnrootLog("[+] Reboot requested to apply the restored root state")
            } else {
                appendUnrootLog("[!] Reboot request failed; reboot manually")
            }
        }
    }

    /** Keeps the restored state but suppresses the reboot prompt. */
    fun dismissRebootPrompt() {
        mutableState.value = mutableState.value.copy(rebootAfterRestore = false)
    }

    private fun showUnrootWarning(issues: List<UnrootIssue>) {
        val outcome = UnrootCommandOutcome(
            cleanupComplete = false,
            rebootRequested = false,
            transportUnavailable = UnrootIssue.RootTransport in issues,
            issues = issues.distinct(),
            hasStructuredOutput = true,
        )
        mutableState.value = mutableState.value.copy(
            phase = InstallPhase.Installed,
            message = app.getString(R.string.status_unroot_incomplete),
            canUnrootCurrentSession = findAvailableRootTransport() != null,
            unrootWarning = UnrootWarningUi(outcome.failedItemsText(app)),
        )
        appendUnrootLog("[!] Cleanup incomplete:\n${outcome.failedItemsText(app)}")
    }

    private fun appendUnrootLog(message: String) {
        android.util.Log.i("RootMyPixel", "[unroot] $message")
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + message).trim(),
        )
    }

    companion object {
        private const val SHIZUKU_PERMISSION_CODE = 101
        private const val UPTIME_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
        private const val COMMAND_TIMEOUT_SECONDS = 90L
        private const val COMMAND_TIMEOUT_CODE = 124
        private const val ROOT_PROBE_TIMEOUT_SECONDS = 10L
        private const val ROOT_ID_COMMAND = "id -u"
        private const val KERNEL_SU_PATH = "/system/bin/su"
        private val SU_CANDIDATES = listOf(KERNEL_SU_PATH, "su")
        private const val SHIZUKU_CVE_SU = "/data/local/tmp/su"
        private const val SHIZUKU_CVE_SOCKET = "/data/local/tmp/temp_su.sock"
        private const val REBOOT_COMMAND =
            "sync; if svc power reboot || reboot; then " +
                    "echo UNROOT_REBOOT_REQUESTED; else echo UNROOT_FAIL:reboot:${'$'}?; fi"
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private data class CommandResult(val code: Int, val output: String)
}
