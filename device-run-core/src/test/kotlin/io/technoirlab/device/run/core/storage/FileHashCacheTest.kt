package io.technoirlab.device.run.core.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class FileHashCacheTest {
    private val hashedFiles = ConcurrentLinkedQueue<Path>()
    private var beforeHashing: () -> Unit = {}
    private val cache = FileHashCache { file ->
        hashedFiles.add(file)
        beforeHashing()
        file.readText()
    }

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `hashes a file version once`() {
        val file = (tempDir / "hello").apply { writeText("a") }
        cache.retrieveOrGenerateHash(file)

        val hash = cache.retrieveOrGenerateHash(file)

        assertThat(hash).isEqualTo("a")
        assertThat(hashedFiles).containsExactly(file)
    }

    @Test
    fun `hashes different files separately`() {
        val file1 = (tempDir / "hello").apply { writeText("hello") }
        val file2 = (tempDir / "world").apply { writeText("world") }

        val hash1 = cache.retrieveOrGenerateHash(file1)
        val hash2 = cache.retrieveOrGenerateHash(file2)

        assertThat(hash1).isEqualTo("hello")
        assertThat(hash2).isEqualTo("world")
        assertThat(hashedFiles).containsExactly(file1, file2)
    }

    @Test
    fun `hashes a modified file again`() {
        val file = (tempDir / "test").apply { writeText("a") }
        // Need to set last modified manually, as the test may run too quick for
        // the timestamp to change.
        file.setLastModifiedTime(FileTime.fromMillis(2000000))
        val previousHash = cache.retrieveOrGenerateHash(file)
        file.writeText("b")
        file.setLastModifiedTime(FileTime.fromMillis(2000001))

        val hash = cache.retrieveOrGenerateHash(file)

        assertThat(previousHash).isEqualTo("a")
        assertThat(hash).isEqualTo("b")
        assertThat(hashedFiles).containsExactly(file, file)
    }

    @Test
    fun `concurrent requests for a file version share one hash computation`() {
        val file = (tempDir / "test").apply { writeText("a") }
        val hashing = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeHashing = {
            hashing.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Hashing was not released" }
        }
        val hashes = ConcurrentLinkedQueue<String>()
        val first = thread { hashes.add(cache.retrieveOrGenerateHash(file)) }
        check(hashing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Hashing did not start" }
        val second = thread { hashes.add(cache.retrieveOrGenerateHash(file)) }
        awaitWaiting(second)

        release.countDown()
        first.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        second.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

        assertThat(hashes).containsExactly("a", "a")
        assertThat(hashedFiles).containsExactly(file)
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.state !in WAITING_STATES) {
            check(System.nanoTime() < deadline) { "${thread.name} did not wait for the hash" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        private const val TIMEOUT_SECONDS = 10L
        private val WAITING_STATES = setOf(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }
}
