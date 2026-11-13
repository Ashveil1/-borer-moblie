package com.example.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.example.data.BorerSettings
import com.example.data.ShellBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

data class ShellExecResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false
)

data class ProotDiagnostics(
    val arch: String,
    val termuxInstalled: Boolean,
    val termuxPermissionGranted: Boolean,
    val directProotPath: String?,
    val directRootfsPath: String?,
    val directReady: Boolean
)

data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val err: Int,
    val errMsg: String?
)

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val requestId = intent?.data?.lastPathSegment ?: return
        val deferred = UbuntuProotRuntime.pendingResults.remove(requestId) ?: return
        val bundle = intent.getBundleExtra("result")
        if (bundle == null) {
            deferred.complete(
                TermuxCommandResult(
                    stdout = "",
                    stderr = "",
                    exitCode = null,
                    err = -1,
                    errMsg = "No result bundle returned from Termux (check allow-external-apps=true in ~/.termux/termux.properties)."
                )
            )
            return
        }
        val hasExit = bundle.containsKey("exitCode")
        deferred.complete(
            TermuxCommandResult(
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = if (hasExit) bundle.getInt("exitCode") else null,
                err = bundle.getInt("err", 0),
                errMsg = bundle.getString("errmsg")
            )
        )
    }
}

object UbuntuProotRuntime {
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
    const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    internal val pendingResults = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    fun setupScript(distro: String = "ubuntu"): String {
        val safeDistro = sanitizeDistro(distro)
        return "pkg update -y && pkg install -y proot-distro && proot-distro install $safeDistro && mkdir -p ~/.termux && (grep -qxF 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties)"
    }

    fun inspectEnvironment(context: Context, settings: BorerSettings): ProotDiagnostics {
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch").orEmpty()
        val termuxInstalled = isPackageInstalled(context, TERMUX_PACKAGE)
        val permGranted = ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        val prootBin = resolveDirectProotBinary(context, settings)
        val rootfsDir = resolveDirectRootfsDir(context, settings)
        val validRootfs = rootfsDir?.takeIf { dir ->
            dir.isDirectory && (
                File(dir, "bin/bash").exists() ||
                    File(dir, "usr/bin/bash").exists() ||
                    File(dir, "bin/sh").exists()
                )
        }
        return ProotDiagnostics(
            arch = arch,
            termuxInstalled = termuxInstalled,
            termuxPermissionGranted = permGranted,
            directProotPath = prootBin?.absolutePath,
            directRootfsPath = validRootfs?.absolutePath,
            directReady = prootBin != null && validRootfs != null
        )
    }

    suspend fun exec(
        context: Context,
        command: String,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        settings: BorerSettings
    ): ShellExecResult = when (settings.shellBackend) {
        ShellBackend.ANDROID_SH -> execAndroidSh(command, cwd, env, timeoutMs, stdin)
        ShellBackend.TERMUX_PROOT -> execTermuxProot(context, command, env, timeoutMs, stdin, settings.prootDistro)
        ShellBackend.PROOT_DIRECT -> execDirectProot(context, command, cwd, env, timeoutMs, stdin, settings)
    }

    fun buildProcessBuilder(
        context: Context,
        command: String,
        cwd: File,
        env: Map<String, String>,
        settings: BorerSettings
    ): Result<ProcessBuilder> {
        return when (settings.shellBackend) {
            ShellBackend.ANDROID_SH -> {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(cwd)
                    .redirectErrorStream(true)
                env.forEach { (k, v) -> pb.environment()[k] = v }
                Result.success(pb)
            }
            ShellBackend.PROOT_DIRECT -> {
                val prootBin = resolveDirectProotBinary(context, settings)
                    ?: return Result.failure(IllegalStateException("no executable proot binary found"))
                val rootfs = resolveDirectRootfsDir(context, settings)?.takeIf { it.isDirectory }
                    ?: return Result.failure(IllegalStateException("Ubuntu rootfs not found for distro '${sanitizeDistro(settings.prootDistro)}'"))
                val prootTmp = File(context.filesDir, "proot-tmp").apply { mkdirs() }
                val cmdArgs = buildDirectProotCommand(prootBin, rootfs, cwd, command, env)
                val pb = ProcessBuilder(cmdArgs)
                    .directory(cwd)
                    .redirectErrorStream(true)
                pb.environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                Result.success(pb)
            }
            ShellBackend.TERMUX_PROOT -> {
                Result.failure(IllegalStateException("Termux proot-distro executes asynchronously via Termux RunCommandService"))
            }
        }
    }

    fun launchTermuxUbuntuSetup(context: Context, distro: String = "ubuntu"): Result<Unit> {
        if (!isPackageInstalled(context, TERMUX_PACKAGE)) {
            return Result.failure(IllegalStateException("Termux ($TERMUX_PACKAGE) is not installed on this device."))
        }
        if (ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(IllegalStateException("Termux RUN_COMMAND permission not granted."))
        }
        val script = "${setupScript(distro)}; echo ''; echo '[borer] Ubuntu setup finished. You can return to Borer.'; exec bash"
        return runCatching {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
                action = TERMUX_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startForegroundService(intent)
        }
    }

    private suspend fun runProcessWithTimeout(
        pb: ProcessBuilder,
        timeoutMs: Long,
        stdin: String?
    ): ShellExecResult = withContext(Dispatchers.IO) {
        val proc = pb.start()
        val readerDeferred = async(Dispatchers.IO) {
            runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
        }
        val stdinJob = async(Dispatchers.IO) {
            runCatching {
                if (stdin != null) {
                    proc.outputStream.bufferedWriter().use { it.write(stdin) }
                } else {
                    proc.outputStream.close()
                }
            }
        }
        val completed = withTimeoutOrNull(timeoutMs) {
            stdinJob.await()
            val exitCode = proc.waitFor()
            val output = readerDeferred.await()
            ShellExecResult(exitCode = exitCode, output = output, timedOut = false)
        }
        if (completed != null) return@withContext completed
        proc.destroyForcibly()
        runCatching { proc.waitFor(500, TimeUnit.MILLISECONDS) }
        val partial = withTimeoutOrNull(400) { readerDeferred.await() }.orEmpty()
        ShellExecResult(exitCode = null, output = partial, timedOut = true)
    }

    private suspend fun execAndroidSh(
        command: String,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?
    ): ShellExecResult = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwd)
                .redirectErrorStream(true)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            runProcessWithTimeout(pb, timeoutMs, stdin)
        } catch (t: Throwable) {
            ShellExecResult(exitCode = null, output = "[spawn error] ${t.message}")
        }
    }

    private suspend fun execDirectProot(
        context: Context,
        command: String,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        settings: BorerSettings
    ): ShellExecResult = withContext(Dispatchers.IO) {
        val pbRes = buildProcessBuilder(context, command, cwd, env, settings)
        val pb = pbRes.getOrElse { err ->
            return@withContext ShellExecResult(
                exitCode = null,
                output = "[proot error] ${err.message}. Select Termux proot-distro or configure valid proot/rootfs paths in Settings -> Runtime."
            )
        }
        try {
            runProcessWithTimeout(pb, timeoutMs, stdin)
        } catch (t: Throwable) {
            ShellExecResult(exitCode = null, output = "[proot spawn error] ${t.message}")
        }
    }

    private suspend fun execTermuxProot(
        context: Context,
        command: String,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        distro: String
    ): ShellExecResult = withContext(Dispatchers.IO) {
        if (!isPackageInstalled(context, TERMUX_PACKAGE)) {
            return@withContext ShellExecResult(
                exitCode = null,
                output = "[termux error] com.termux is not installed. Install Termux + proot-distro ubuntu or switch Shell Backend in Lab -> Config."
            )
        }
        if (ContextCompat.checkSelfPermission(context, TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ShellExecResult(
                exitCode = null,
                output = "[termux error] Missing permission com.termux.permission.RUN_COMMAND. Grant it in Lab -> Config."
            )
        }

        val safeDistro = sanitizeDistro(distro)
        val cmdB64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val stdinB64 = stdin?.let { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
        val envPairs = env.entries.joinToString(" ") { (k, v) ->
            val safeKey = k.replace(Regex("[^A-Za-z0-9_]"), "")
            val valB64 = Base64.encodeToString(v.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "$safeKey=\"\$(printf '%s' '$valB64' | base64 -d)\""
        }

        val innerUbuntuCmd = buildString {
            append("TMPF=\$(mktemp /tmp/.borer_cmd_XXXXXX.sh); ")
            append("trap 'rm -f \"\$TMPF\"' EXIT; ")
            append("printf '%s' '$cmdB64' | base64 -d > \"\$TMPF\"; ")
            if (stdinB64 != null) {
                append("printf '%s' '$stdinB64' | base64 -d | /bin/bash \"\$TMPF\"")
            } else {
                append("/bin/bash \"\$TMPF\"")
            }
        }
        val innerB64 = Base64.encodeToString(innerUbuntuCmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val outerScript = buildString {
            append("if ! command -v proot-distro >/dev/null 2>&1; then ")
            append("echo '[borer] proot-distro is not installed in Termux. Run: pkg install -y proot-distro && proot-distro install $safeDistro' >&2; ")
            append("exit 127; ")
            append("fi; ")
            append("proot-distro login $safeDistro -- /usr/bin/env -i ")
            append("HOME=/root TERM=dumb PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            if (envPairs.isNotBlank()) append("$envPairs ")
            append("/bin/bash -lc \"\$(printf '%s' '$innerB64' | base64 -d)\"")
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pendingResults[requestId] = deferred

        try {
            val cbIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                setPackage(context.packageName)
                data = Uri.parse("borer://termux-result/$requestId")
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestId.hashCode().absoluteValue,
                cbIntent,
                flags
            )

            val launchIntent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
                action = TERMUX_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", outerScript))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }

            try {
                context.startForegroundService(launchIntent)
            } catch (t: Throwable) {
                pendingResults.remove(requestId)
                return@withContext ShellExecResult(
                    exitCode = null,
                    output = "[termux dispatch error] ${t.message}. Ensure Termux is installed and allow-external-apps=true is set in ~/.termux/termux.properties."
                )
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                ShellExecResult(exitCode = null, output = "[timeout waiting for Termux proot-distro response after ${timeoutMs}ms]", timedOut = true)
            } else {
                val combined = buildString {
                    if (result.stdout.isNotBlank()) append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        if (isNotEmpty() && !endsWith("\n")) append('\n')
                        append(result.stderr)
                    }
                    if (!result.errMsg.isNullOrBlank()) {
                        if (isNotEmpty() && !endsWith("\n")) append('\n')
                        append("[termux] ${result.errMsg}")
                    }
                }
                ShellExecResult(exitCode = result.exitCode, output = combined, timedOut = false)
            }
        } finally {
            pendingResults.remove(requestId)
        }
    }

    private fun buildDirectProotCommand(
        prootBin: File,
        rootfs: File,
        cwd: File,
        command: String,
        env: Map<String, String>
    ): List<String> {
        val args = mutableListOf(
            prootBin.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${cwd.absolutePath}:/workspace",
            "-w", "/workspace",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "TERM=dumb",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        env.forEach { (k, v) -> args.add("$k=$v") }
        args.add("/bin/bash")
        args.add("-lc")
        args.add(command)
        return args
    }

    private fun resolveDirectProotBinary(context: Context, settings: BorerSettings): File? {
        val candidates = buildList {
            if (settings.prootBinaryPath.isNotBlank()) add(File(settings.prootBinaryPath.trim()))
            add(File(context.applicationInfo.nativeLibraryDir, "libproot.so"))
            add(File(context.applicationInfo.nativeLibraryDir, "proot"))
            add(File("/system/bin/proot"))
            add(File("/vendor/bin/proot"))
            add(File("/data/local/tmp/proot"))
        }
        return candidates.firstOrNull { it.exists() && it.isFile && it.canExecute() }
    }

    private fun resolveDirectRootfsDir(context: Context, settings: BorerSettings): File? {
        val distro = sanitizeDistro(settings.prootDistro)
        if (settings.prootRootfsPath.isNotBlank()) {
            return File(settings.prootRootfsPath.trim())
        }
        val local = File(context.filesDir, "proot-distro/installed-rootfs/$distro")
        if (local.isDirectory) return local
        val ext = context.getExternalFilesDir(null)?.let { File(it, "proot-distro/installed-rootfs/$distro") }
        if (ext != null && ext.isDirectory) return ext
        return local
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun sanitizeDistro(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9._-]"), "").ifBlank { "ubuntu" }
}
