package com.robomotion.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic Windows-only repros for the share-mode race that
 * LMO.moveAtomicWithRetry / LMO.readBlobWithRetry defend against.
 *
 * <p>On Windows, MoveFileEx fails with AccessDeniedException when the
 * destination is held open by another process without FILE_SHARE_DELETE,
 * and CreateFile (used by Files.readAllBytes) fails with
 * AccessDeniedException / sharing violation under the same condition.
 * POSIX rename(2) has neither race.
 *
 * <p>Java's java.io.FileInputStream opens with
 * FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, so an
 * in-process holder won't trigger the bug. We spawn a PowerShell child
 * that opens the file via [System.IO.File]::Open(..., FileShare.None),
 * which maps to dwShareMode=0 in CreateFileW. The child blocks on
 * stdin until the test releases it.
 */
@EnabledOnOs(OS.WINDOWS)
class LMOAtomicWindowsTest {

    @TempDir
    Path tempDir;

    private static final String STORE_PATH = "test/flow";

    @BeforeEach
    void setUp() throws Exception {
        LMO.reset();
        Runtime.SetRobotCapabilities(0L);
        LMO.initForTesting(tempDir.toString(), STORE_PATH);
    }

    @AfterEach
    void tearDown() {
        LMO.reset();
        Runtime.SetRobotCapabilities(0L);
    }

    private Path blobFilePath(String ref) {
        String hash = ref.substring(5); // strip "xxh3:"
        return tempDir.resolve("store").resolve(STORE_PATH)
                .resolve("blobs").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    /**
     * Spawn a PowerShell process that opens {@code p} with FileShare.None
     * (dwShareMode=0) and blocks until it receives a line on stdin.
     * Returns the process; caller must invoke {@link #releaseHolder} to free it.
     */
    private Process spawnExclusiveHolder(Path p) throws IOException {
        String pathLiteral = p.toAbsolutePath().toString();
        // Path is safe inside single quotes — TempDir paths contain no apostrophes.
        String script =
            "$ErrorActionPreference = 'Stop'; " +
            "$h = [System.IO.File]::Open('" + pathLiteral + "', 'Open', 'Read', 'None'); " +
            "Write-Output 'OPEN'; " +
            "[void][Console]::In.ReadLine(); " +
            "$h.Close()";
        ProcessBuilder pb = new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (!"OPEN".equals(line)) {
            proc.destroyForcibly();
            throw new IOException("exclusive holder failed to start; first line: " + line);
        }
        return proc;
    }

    private void releaseHolder(Process proc) throws Exception {
        proc.getOutputStream().write("\r\n".getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().flush();
        proc.getOutputStream().close();
        proc.waitFor();
    }

    @Test
    void putBlobRetriesPastSharingViolation() throws Exception {
        byte[] data = "payload for putBlob retry test".getBytes(StandardCharsets.UTF_8);
        String expectedRef = LMO.hashRef(data);
        Path target = blobFilePath(expectedRef);
        Files.createDirectories(target.getParent());

        // Plant a zero-byte file so putBlob's dedup short-circuit (size > 0)
        // doesn't fire and we go through the rename path.
        Files.write(target, new byte[0]);

        Process holder = spawnExclusiveHolder(target);
        try {
            // Sanity: plain Files.move with ATOMIC_MOVE fails immediately while holder is open.
            // FileSystemException covers both AccessDeniedException (ERROR_ACCESS_DENIED) and
            // the bare FileSystemException Java emits for ERROR_SHARING_VIOLATION.
            Path sanitySrc = target.resolveSibling(target.getFileName().toString() + ".sanity-src");
            Files.write(sanitySrc, new byte[]{1, 2, 3});
            assertThrows(FileSystemException.class, () ->
                Files.move(sanitySrc, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));
            Files.deleteIfExists(sanitySrc);

            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { result.set(LMO.putBlob(data)); }
                catch (Throwable t) { err.set(t); }
            }, "putBlob-worker");
            worker.start();

            // Hold long enough that the worker definitely reaches Files.move
            // (compress + tmp write takes >2ms in CI). Post-fix retries with
            // backoff up to 8ms cap and succeeds within ~8ms after release;
            // pre-fix surfaces AccessDeniedException immediately.
            Thread.sleep(30);
            releaseHolder(holder);
            holder = null;

            worker.join(5000);
            assertFalse(worker.isAlive(), "putBlob hung past expected retry window");
            assertNull(err.get(), () -> "putBlob failed: " + err.get());
            assertEquals(expectedRef, result.get());

            byte[] roundtrip = LMO.getBlob(result.get(), STORE_PATH);
            assertArrayEquals(data, roundtrip);
        } finally {
            if (holder != null) {
                holder.destroyForcibly();
            }
        }
    }

    @Test
    void getBlobRetriesPastSharingViolation() throws Exception {
        byte[] data = "payload for getBlob retry test".getBytes(StandardCharsets.UTF_8);
        String ref = LMO.putBlob(data);
        Path blobPath = blobFilePath(ref);

        Process holder = spawnExclusiveHolder(blobPath);
        try {
            // Sanity: plain Files.readAllBytes fails immediately while holder is open.
            // FileSystemException covers both AccessDeniedException and the bare
            // FileSystemException Java emits for ERROR_SHARING_VIOLATION.
            assertThrows(FileSystemException.class, () -> Files.readAllBytes(blobPath));

            AtomicReference<byte[]> result = new AtomicReference<>();
            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { result.set(LMO.getBlob(ref, STORE_PATH)); }
                catch (Throwable t) { err.set(t); }
            }, "getBlob-worker");
            worker.start();

            Thread.sleep(30);
            releaseHolder(holder);
            holder = null;

            worker.join(5000);
            assertFalse(worker.isAlive(), "getBlob hung past expected retry window");
            assertNull(err.get(), () -> "getBlob failed: " + err.get());
            assertArrayEquals(data, result.get());
        } finally {
            if (holder != null) {
                holder.destroyForcibly();
            }
        }
    }
}
