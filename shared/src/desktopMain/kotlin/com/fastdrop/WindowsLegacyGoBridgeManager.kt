package com.fastdrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

internal data class BridgeFailure(val message: String)

internal interface LegacyGoBridgeLifecycle {
    suspend fun start()
    suspend fun stop()
    fun isRunning(): Boolean
    suspend fun awaitUnexpectedExit(): BridgeFailure
}

internal data class BridgeExecutableResolution(
    val executable: Path?,
    val searchedPaths: List<Path>
)

internal fun interface BridgeExecutableResolver {
    fun resolve(): BridgeExecutableResolution
}

internal class DefaultWindowsBridgeExecutableResolver(
    private val systemProperty: (String) -> String? = { System.getProperty(it) },
    private val environment: (String) -> String? = { System.getenv(it) },
    private val workingDirectory: Path = Paths.get(System.getProperty("user.dir")),
    private val applicationLocation: Path? = runCatching {
        Paths.get(WindowsLegacyGoBridgeManager::class.java.protectionDomain.codeSource.location.toURI())
    }.getOrNull(),
    private val isExecutableFile: (Path) -> Boolean = { Files.isRegularFile(it) }
) : BridgeExecutableResolver {
    override fun resolve(): BridgeExecutableResolution {
        val override = systemProperty(BRIDGE_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: environment(BRIDGE_PATH_ENV)?.takeIf { it.isNotBlank() }

        if (override != null) {
            val candidate = Paths.get(override).toAbsolutePath().normalize()
            return BridgeExecutableResolution(candidate.takeIf(isExecutableFile), listOf(candidate))
        }

        val candidates = linkedSetOf<Path>()
        systemProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { resources ->
            candidates.add(Paths.get(resources).resolve("bridge/windows/$BRIDGE_EXE"))
        }
        applicationLocation?.let { location ->
            val base = if (location.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true) {
                location.parent
            } else {
                location
            }
            if (base != null) {
                candidates.add(base.resolve(BRIDGE_EXE))
                candidates.add(base.resolve("bridge/windows/$BRIDGE_EXE"))
            }
        }

        candidates.add(workingDirectory.resolve(BRIDGE_EXE))
        candidates.add(workingDirectory.resolve("bridge/windows/$PUBLISH_RELATIVE_PATH"))
        candidates.add(workingDirectory.resolve("shared/bridge/windows/$PUBLISH_RELATIVE_PATH"))
        // Gradle :desktopApp:run may use desktopApp as its working directory.
        workingDirectory.parent?.let { projectRoot ->
            candidates.add(projectRoot.resolve("shared/bridge/windows/$PUBLISH_RELATIVE_PATH"))
        }

        val normalized = candidates.map { it.toAbsolutePath().normalize() }
        return BridgeExecutableResolution(normalized.firstOrNull(isExecutableFile), normalized)
    }

    companion object {
        const val BRIDGE_PATH_PROPERTY = "fastdrop.bridge.path"
        const val BRIDGE_PATH_ENV = "FASTDROP_BRIDGE_PATH"
        private const val BRIDGE_EXE = "FastDropBridge.exe"
        private const val PUBLISH_RELATIVE_PATH =
            "bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/$BRIDGE_EXE"
    }
}

internal interface BridgeChildProcess {
    fun isAlive(): Boolean
    suspend fun readStdout(onLine: (String) -> Unit)
    suspend fun readStderr(onLine: (String) -> Unit)
    suspend fun waitForExit(): Int
    suspend fun waitForExit(timeoutMillis: Long): Boolean
    suspend fun sendLine(line: String)
    fun exitCodeOrNull(): Int?
    fun destroy()
    fun destroyForcibly()
}

internal fun interface BridgeProcessFactory {
    fun start(executable: Path, arguments: List<String>): BridgeChildProcess
}

internal class JvmBridgeProcessFactory : BridgeProcessFactory {
    override fun start(executable: Path, arguments: List<String>): BridgeChildProcess {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(executable.parent?.toFile())
            .redirectErrorStream(false)
            .start()
        return JvmBridgeChildProcess(process)
    }
}

private class JvmBridgeChildProcess(private val process: Process) : BridgeChildProcess {
    private val stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)

    override fun isAlive(): Boolean = process.isAlive

    override suspend fun readStdout(onLine: (String) -> Unit) = withContext(Dispatchers.IO) {
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
    }

    override suspend fun readStderr(onLine: (String) -> Unit) = withContext(Dispatchers.IO) {
        process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach(onLine) }
    }

    override suspend fun waitForExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    override suspend fun waitForExit(timeoutMillis: Long): Boolean = withContext(Dispatchers.IO) {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) {
        stdin.write(line)
        stdin.newLine()
        stdin.flush()
    }

    override fun exitCodeOrNull(): Int? = runCatching { process.exitValue() }.getOrNull()
    override fun destroy() = process.destroy()
    override fun destroyForcibly() { process.destroyForcibly() }
}

internal open class BridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class BridgeNotFoundException(searchedPaths: List<Path>) : BridgeException(
    "FastDropBridge.exe was not found. Set FASTDROP_BRIDGE_PATH or publish the bridge. Searched: " +
        searchedPaths.joinToString()
)

internal class BridgeLaunchException(cause: Throwable) : BridgeException(
    "Could not launch FastDropBridge.exe: ${cause.message}",
    cause
)

internal class BridgeExitedBeforeReadyException(exitCode: Int) : BridgeException(
    "FastDropBridge.exe exited before Wi-Fi Direct was ready (exit code $exitCode)."
)

internal class BridgeReadinessTimeoutException(timeoutMillis: Long) : BridgeException(
    "Timed out after ${timeoutMillis}ms waiting for Publisher status: Started."
)

internal class BridgePublisherException(status: String) : BridgeException(
    "Wi-Fi Direct publisher entered status: $status."
)

internal class WindowsLegacyGoBridgeManager(
    private val executableResolver: BridgeExecutableResolver = DefaultWindowsBridgeExecutableResolver(),
    private val processFactory: BridgeProcessFactory = JvmBridgeProcessFactory(),
    private val readinessTimeoutMillis: Long = 20_000,
    private val gracefulStopTimeoutMillis: Long = 5_000,
    private val destroyTimeoutMillis: Long = 2_000,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStdout: (String) -> Unit = {},
    private val onStderr: (String) -> Unit = {}
) : LegacyGoBridgeLifecycle {
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile
    private var process: BridgeChildProcess? = null

    @Volatile
    private var ready = false

    @Volatile
    private var stopping = false

    private var readiness = CompletableDeferred<Unit>()
    private var unexpectedExit = CompletableDeferred<BridgeFailure>()
    private var monitorJobs: List<Job> = emptyList()

    override suspend fun start() {
        lifecycleMutex.withLock {
            if (ready && process?.isAlive() == true) return@withLock
            if (process != null) stopProcessLocked()

            val resolution = try {
                executableResolver.resolve()
            } catch (exception: Exception) {
                throw BridgeException("Could not resolve FastDropBridge.exe: ${exception.message}", exception)
            }
            val executable = resolution.executable ?: throw BridgeNotFoundException(resolution.searchedPaths)
            val child = try {
                processFactory.start(executable, listOf("spike_autonomous"))
            } catch (exception: Exception) {
                throw BridgeLaunchException(exception)
            }

            process = child
            ready = false
            stopping = false
            readiness = CompletableDeferred()
            unexpectedExit = CompletableDeferred()
            startMonitors(child)

            try {
                withTimeout(readinessTimeoutMillis) { readiness.await() }
                if (!child.isAlive()) {
                    throw BridgeExitedBeforeReadyException(child.exitCodeOrNull() ?: -1)
                }
                if (!ready) {
                    throw BridgeException("Wi-Fi Direct publisher stopped immediately after startup.")
                }
            } catch (timeout: TimeoutCancellationException) {
                stopProcessLocked()
                throw BridgeReadinessTimeoutException(readinessTimeoutMillis)
            } catch (exception: Exception) {
                stopProcessLocked()
                throw exception
            }
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        stopProcessLocked()
    }

    override fun isRunning(): Boolean = ready && process?.isAlive() == true

    override suspend fun awaitUnexpectedExit(): BridgeFailure = unexpectedExit.await()

    private fun startMonitors(child: BridgeChildProcess) {
        monitorJobs = listOf(
            scope.launch {
                monitorStream("stdout", { child.readStdout(it) }, onStdout)
            },
            scope.launch {
                monitorStream("stderr", { child.readStderr(it) }, onStderr)
            },
            scope.launch {
                val exitCode = child.waitForExit()
                if (!stopping) {
                    if (!readiness.isCompleted) {
                        readiness.completeExceptionally(BridgeExitedBeforeReadyException(exitCode))
                    } else {
                        ready = false
                        unexpectedExit.complete(
                            BridgeFailure("FastDropBridge.exe exited unexpectedly with code $exitCode.")
                        )
                    }
                }
            }
        )
    }

    private suspend fun monitorStream(
        streamName: String,
        consume: suspend ((String) -> Unit) -> Unit,
        report: (String) -> Unit
    ) {
        try {
            consume { line ->
                report(line)
                if (streamName == "stdout") handleBridgeLine(line)
            }
        } catch (exception: Exception) {
            if (!stopping && !readiness.isCompleted) {
                readiness.completeExceptionally(
                    BridgeException("Failed reading bridge $streamName: ${exception.message}", exception)
                )
            }
        }
    }

    private fun handleBridgeLine(line: String) {
        val match = PUBLISHER_STATUS.find(line) ?: return
        val status = match.groupValues[1]
        when {
            status.equals("Started", ignoreCase = true) -> {
                ready = true
                readiness.complete(Unit)
            }
            status.equals("Created", ignoreCase = true) -> Unit
            !stopping && !readiness.isCompleted -> {
                readiness.completeExceptionally(BridgePublisherException(status))
            }
            !stopping -> {
                ready = false
                unexpectedExit.complete(BridgeFailure("Wi-Fi Direct publisher entered status: $status."))
            }
        }
    }

    private suspend fun stopProcessLocked() {
        val child = process
        stopping = true
        ready = false

        try {
            if (child != null && child.isAlive()) {
                runCatching { child.sendLine("") }
                if (!runCatching { child.waitForExit(gracefulStopTimeoutMillis) }.getOrDefault(false)) {
                    child.destroy()
                    if (!runCatching { child.waitForExit(destroyTimeoutMillis) }.getOrDefault(false)) {
                        child.destroyForcibly()
                        runCatching { child.waitForExit(destroyTimeoutMillis) }
                    }
                }
                if (child.isAlive()) {
                    throw BridgeException("FastDropBridge.exe did not exit after the stop request.")
                }
            }
        } finally {
            monitorJobs.forEach { it.cancel() }
            monitorJobs = emptyList()
            process = null
        }
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        private val PUBLISHER_STATUS = Regex("Publisher status:\\s*([A-Za-z]+)")
    }
}
