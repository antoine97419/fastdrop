package com.fastdrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsLegacyGoBridgeManagerTest {
    @Test
    fun launchesBridgeAndDetectsReadiness() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        val start = async { fixture.manager.start() }
        runCurrent()

        assertEquals(1, fixture.factory.startCount)
        assertEquals(listOf("spike_autonomous"), fixture.factory.arguments)
        fixture.process.stdout.send("Publisher status: Started")
        runCurrent()
        start.await()

        assertTrue(fixture.manager.isRunning())
        assertTrue(fixture.stdout.contains("Publisher status: Started"))
        fixture.manager.stop()
    }

    @Test
    fun doubleStartUsesSingleChildProcess() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        fixture.startReady()

        fixture.manager.start()

        assertEquals(1, fixture.factory.startCount)
        fixture.manager.stop()
    }

    @Test
    fun missingBridgeFailsBeforeProcessLaunch() = runTest {
        val fixture = BridgeFixture(
            dispatcher = StandardTestDispatcher(testScheduler),
            resolution = BridgeExecutableResolution(null, listOf(Paths.get("missing/FastDropBridge.exe")))
        )

        val result = runCatching { fixture.manager.start() }

        assertIs<BridgeNotFoundException>(result.exceptionOrNull())
        assertEquals(0, fixture.factory.startCount)
    }

    @Test
    fun launchFailureIsReportedClearly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = WindowsLegacyGoBridgeManager(
            executableResolver = BridgeExecutableResolver {
                BridgeExecutableResolution(TEST_EXE, listOf(TEST_EXE))
            },
            processFactory = BridgeProcessFactory { _, _ -> throw IOException("launch denied") },
            dispatcher = dispatcher
        )

        val result = runCatching { manager.start() }

        assertIs<BridgeLaunchException>(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("launch denied"))
    }

    @Test
    fun exitBeforeReadinessFailsStartup() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        val start = async { runCatching { fixture.manager.start() } }
        runCurrent()

        fixture.process.exit(23)
        runCurrent()

        assertIs<BridgeExitedBeforeReadyException>(start.await().exceptionOrNull())
        assertFalse(fixture.manager.isRunning())
    }

    @Test
    fun readinessTimeoutStopsChildProcess() = runTest {
        val fixture = BridgeFixture(
            dispatcher = StandardTestDispatcher(testScheduler),
            readinessTimeoutMillis = 1_000
        )
        val start = async { runCatching { fixture.manager.start() } }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        assertIs<BridgeReadinessTimeoutException>(start.await().exceptionOrNull())
        assertEquals(listOf(""), fixture.process.stdinLines)
        assertFalse(fixture.process.isAlive())
    }

    @Test
    fun publisherErrorBeforeReadinessFailsStartup() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        val start = async { runCatching { fixture.manager.start() } }
        runCurrent()

        fixture.process.stdout.send("Publisher status: Aborted")
        runCurrent()

        assertIs<BridgePublisherException>(start.await().exceptionOrNull())
        assertFalse(fixture.manager.isRunning())
    }

    @Test
    fun gracefulStopUsesBridgeStdinAndWaitsForExit() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        fixture.startReady()

        fixture.manager.stop()

        assertEquals(listOf(""), fixture.process.stdinLines)
        assertEquals(0, fixture.process.destroyCount)
        assertEquals(0, fixture.process.forceDestroyCount)
        assertFalse(fixture.manager.isRunning())
    }

    @Test
    fun unexpectedExitAfterReadinessIsReported() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        fixture.startReady()

        fixture.process.exit(9)
        runCurrent()

        val failure = fixture.manager.awaitUnexpectedExit()
        assertTrue(failure.message.contains("code 9"))
        assertFalse(fixture.manager.isRunning())
        fixture.manager.stop()
    }

    @Test
    fun publisherErrorAfterReadinessIsReported() = runTest {
        val fixture = BridgeFixture(StandardTestDispatcher(testScheduler))
        fixture.startReady()

        fixture.process.stdout.send("Publisher status: Aborted")
        runCurrent()

        val failure = fixture.manager.awaitUnexpectedExit()
        assertTrue(failure.message.contains("Aborted"))
        assertFalse(fixture.manager.isRunning())
        fixture.manager.stop()
    }

    @Test
    fun environmentOverrideSelectsExplicitBridgePath() {
        val override = Paths.get("C:/custom/FastDropBridge.exe").toAbsolutePath().normalize()
        val resolver = DefaultWindowsBridgeExecutableResolver(
            systemProperty = { null },
            environment = { name -> if (name == "FASTDROP_BRIDGE_PATH") override.toString() else null },
            workingDirectory = Paths.get("C:/project"),
            applicationLocation = null,
            isExecutableFile = { it == override }
        )

        val resolution = resolver.resolve()

        assertEquals(override, resolution.executable)
        assertEquals(listOf(override), resolution.searchedPaths)
    }

    @Test
    fun developmentPathIsResolvedFromProjectRoot() {
        val projectRoot = Paths.get("C:/project").toAbsolutePath().normalize()
        val expected = projectRoot.resolve(
            "shared/bridge/windows/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/FastDropBridge.exe"
        )
        val resolver = DefaultWindowsBridgeExecutableResolver(
            systemProperty = { null },
            environment = { null },
            workingDirectory = projectRoot,
            applicationLocation = null,
            isExecutableFile = { it == expected }
        )

        assertEquals(expected, resolver.resolve().executable)
    }

    private class BridgeFixture(
        dispatcher: CoroutineDispatcher,
        resolution: BridgeExecutableResolution = BridgeExecutableResolution(TEST_EXE, listOf(TEST_EXE)),
        readinessTimeoutMillis: Long = 5_000
    ) {
        val process = FakeBridgeProcess()
        val factory = FakeBridgeProcessFactory(process)
        val stdout = mutableListOf<String>()
        val manager = WindowsLegacyGoBridgeManager(
            executableResolver = BridgeExecutableResolver { resolution },
            processFactory = factory,
            readinessTimeoutMillis = readinessTimeoutMillis,
            gracefulStopTimeoutMillis = 500,
            destroyTimeoutMillis = 500,
            dispatcher = dispatcher,
            onStdout = { stdout += it }
        )

        suspend fun startReady() {
            val start = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                manager.start()
            }
            kotlinx.coroutines.yield()
            process.stdout.send("Publisher status: Started")
            kotlinx.coroutines.yield()
            start.await()
        }
    }

    private class FakeBridgeProcessFactory(private val process: FakeBridgeProcess) : BridgeProcessFactory {
        var startCount = 0
        var arguments: List<String> = emptyList()

        override fun start(executable: Path, arguments: List<String>): BridgeChildProcess {
            startCount++
            this.arguments = arguments
            return process
        }
    }

    private class FakeBridgeProcess : BridgeChildProcess {
        val stdout = Channel<String>(Channel.UNLIMITED)
        val stderr = Channel<String>(Channel.UNLIMITED)
        val stdinLines = mutableListOf<String>()
        var destroyCount = 0
        var forceDestroyCount = 0
        private val exitCode = CompletableDeferred<Int>()

        override fun isAlive(): Boolean = !exitCode.isCompleted
        override suspend fun readStdout(onLine: (String) -> Unit) {
            for (line in stdout) onLine(line)
        }
        override suspend fun readStderr(onLine: (String) -> Unit) {
            for (line in stderr) onLine(line)
        }
        override suspend fun waitForExit(): Int = exitCode.await()
        override suspend fun waitForExit(timeoutMillis: Long): Boolean =
            withTimeoutOrNull(timeoutMillis) { exitCode.await(); true } ?: false
        override suspend fun sendLine(line: String) {
            stdinLines += line
            exit(0)
        }
        override fun exitCodeOrNull(): Int? = if (exitCode.isCompleted) exitCode.getCompleted() else null
        override fun destroy() {
            destroyCount++
            exit(143)
        }
        override fun destroyForcibly() {
            forceDestroyCount++
            exit(137)
        }
        fun exit(code: Int) {
            exitCode.complete(code)
            stdout.close()
            stderr.close()
        }
    }

    companion object {
        private val TEST_EXE = Paths.get("C:/fastdrop/FastDropBridge.exe")
    }
}
