package com.robomotion.app;

import com.github.luben.zstd.Zstd;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.openhft.hashing.LongTupleHashFunction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * LMO manages a content-addressed, zstd-compressed blob store on disk.
 * Mirrors the Go implementation in robomotion-deskbot/runtime/lmo/store.go.
 *
 * Provides both read (resolve BlobRefs) and write (pack large fields as blobs).
 */
public class LMO {

    public static final int MAGIC = 20260301;
    public static final int THRESHOLD = 4096; // 4KB

    private static final LongTupleHashFunction xxh128 = LongTupleHashFunction.xx128();

    // Store state — lazily initialised on first use.
    // configDir is stored once at init time, matching Go's Store.configDir field.
    private static String configDir;
    private static String root;
    private static String relPath;

    /**
     * Initialise the store with the given relative store path.
     * root = configDir/store/{storePath}
     */
    static synchronized void init(String storePath) throws Exception {
        relPath = storePath;
        configDir = getConfigDir();
        root = Paths.get(configDir, "store", relPath).toString();

        Path blobDir = Paths.get(root, "blobs");
        Files.createDirectories(blobDir);
    }

    /**
     * Lazily initialise the store using lmo_store_path from GetRobotInfo().
     * The path is an opaque string computed by the deskbot runtime.
     */
    @SuppressWarnings("unchecked")
    private static synchronized void ensureInit() {
        if (root != null) {
            return;
        }
        try {
            Map<String, Object> info = Runtime.GetRobotInfo();
            Object storePath = info.get("lmo_store_path");
            if (storePath == null || storePath.toString().isEmpty()) {
                return;
            }
            init(storePath.toString());
        } catch (Exception e) {
            System.err.println("lmo: failed to init store: " + e.getMessage());
        }
    }

    /**
     * Returns true if the store has been initialised.
     */
    static boolean isActive() {
        return root != null;
    }

    // --- Pack (write) side ---

    /**
     * Walks the JSON payload, extracts fields >= Threshold as blobs, and replaces
     * them with BlobRef markers. Returns the original data if nothing was extracted.
     */
    public static byte[] pack(byte[] data) {
        if (data == null || data.length == 0 || data.length < THRESHOLD) {
            return data;
        }
        if (!Runtime.IsLMOCapable()) {
            return data;
        }
        ensureInit();
        if (root == null) {
            return data;
        }

        try {
            String json = new String(data, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return data;
            }

            JsonObject obj = parsed.getAsJsonObject();
            boolean modified = false;

            for (Map.Entry<String, JsonElement> entry : obj.entrySet().toArray(new Map.Entry[0])) {
                JsonElement value = entry.getValue();
                JsonElement extracted = extractField(value);
                if (extracted != null) {
                    obj.add(entry.getKey(), extracted);
                    modified = true;
                }
            }

            if (!modified) {
                return data;
            }
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("lmo: pack error: " + e.getMessage());
            return data;
        }
    }

    /**
     * Processes a single JSON value for extraction.
     * Returns the replacement element if changed, null otherwise.
     */
    private static JsonElement extractField(JsonElement value) throws Exception {
        // Already a BlobRef — passthrough
        if (value.isJsonObject() && isBlobRef(value.getAsJsonObject())) {
            return null;
        }

        // Object: recurse into children
        if (value.isJsonObject()) {
            return extractObject(value.getAsJsonObject());
        }

        // Array or scalar: extract if large (use byte length to match Go's len())
        byte[] rawBytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length >= THRESHOLD) {
            return buildBlobRefElement(rawBytes, value);
        }

        return null;
    }

    /**
     * Recurses into a JSON object, extracting large leaves.
     */
    private static JsonElement extractObject(JsonObject obj) throws Exception {
        byte[] rawBytes = obj.toString().getBytes(StandardCharsets.UTF_8);

        // If the whole object is small, skip (use byte length to match Go's len())
        if (rawBytes.length < THRESHOLD) {
            return null;
        }

        boolean modified = false;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet().toArray(new Map.Entry[0])) {
            JsonElement child = entry.getValue();
            JsonElement extracted = extractField(child);
            if (extracted != null) {
                obj.add(entry.getKey(), extracted);
                modified = true;
            }
        }

        if (!modified) {
            // Object is large but no individual children were large enough.
            // Extract the whole object as a blob.
            return buildBlobRefElement(rawBytes, obj);
        }

        return obj;
    }

    /**
     * Stores data as a blob and returns a BlobRef JSON element.
     */
    private static JsonElement buildBlobRefElement(byte[] rawBytes, JsonElement value) throws Exception {
        String ref = putBlob(rawBytes);

        JsonObject br = new JsonObject();
        br.addProperty("__ref", ref);
        br.addProperty("__magic", MAGIC);
        br.addProperty("__size", rawBytes.length);
        br.addProperty("__path", relPath);

        if (value.isJsonArray()) {
            br.addProperty("__type", "array");
            br.addProperty("__len", value.getAsJsonArray().size());
        } else if (value.isJsonObject()) {
            br.addProperty("__type", "object");
        } else if (value.isJsonPrimitive()) {
            JsonPrimitive prim = value.getAsJsonPrimitive();
            if (prim.isString()) {
                br.addProperty("__type", "string");
                String s = prim.getAsString();
                br.addProperty("__len", s.codePointCount(0, s.length()));
            } else if (prim.isNumber()) {
                br.addProperty("__type", "number");
            } else if (prim.isBoolean()) {
                br.addProperty("__type", "boolean");
            }
        }

        return br;
    }

    // --- Resolve (read) side ---

    /**
     * Walks the JSON payload and replaces every BlobRef with its decompressed
     * blob data. Returns the original data unchanged if no BlobRefs are found.
     */
    public static byte[] resolveAll(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }

        try {
            String json = new String(data, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return data;
            }

            JsonObject root = parsed.getAsJsonObject();
            boolean modified = false;

            for (Map.Entry<String, JsonElement> entry : root.entrySet().toArray(new Map.Entry[0])) {
                JsonElement value = entry.getValue();
                JsonElement resolved = resolveValue(value);
                if (resolved != null) {
                    root.add(entry.getKey(), resolved);
                    modified = true;
                }
            }

            if (!modified) {
                return data;
            }
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * Resolves a single JSON value. Returns the resolved element if changed, null otherwise.
     */
    private static JsonElement resolveValue(JsonElement value) {
        // Recurse into arrays so a BlobRef envelope sitting as an array
        // element (e.g. user code did arr.add(msg.alreadyPackedField) and
        // arr later crossed the LMO threshold and got packed whole) is
        // also unwrapped. Without this the inner envelope surfaces to
        // user code as a stub object and crashes with ClassCastException.
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            boolean modified = false;
            for (int i = 0; i < arr.size(); i++) {
                JsonElement resolved = resolveValue(arr.get(i));
                if (resolved != null) {
                    arr.set(i, resolved);
                    modified = true;
                }
            }
            return modified ? arr : null;
        }

        if (!value.isJsonObject()) {
            return null;
        }

        JsonObject obj = value.getAsJsonObject();

        if (isBlobRef(obj)) {
            try {
                JsonElement resolved = resolveRef(obj);
                // Recurse into the resolved content so nested BlobRef
                // envelopes (left by pack's extractObject !modified
                // whole-pack branch on an outer container) are also
                // unwrapped. Without this, the inner ref surfaces to
                // user code as a stub object and crashes with
                // ClassCastException downstream.
                JsonElement nested = resolveValue(resolved);
                return nested != null ? nested : resolved;
            } catch (Exception e) {
                System.err.println("lmo: failed to resolve blob: " + e.getMessage());
                return null;
            }
        }

        // Recurse into nested objects
        boolean modified = false;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet().toArray(new Map.Entry[0])) {
            JsonElement child = entry.getValue();
            JsonElement resolved = resolveValue(child);
            if (resolved != null) {
                obj.add(entry.getKey(), resolved);
                modified = true;
            }
        }

        return modified ? obj : null;
    }

    // --- Blob I/O ---

    /**
     * Stores data as a zstd-compressed blob and returns its XXH3-128 ref.
     *
     * <p>If a non-empty blob already exists at the target path, dedup skips
     * writing.
     *
     * <p>Atomic write: compressed bytes go to a unique tmp file in the same
     * directory then are moved onto the final path with ATOMIC_MOVE
     * (rename(2) on POSIX, MoveFileEx on Windows). On filesystems that don't
     * support atomic moves (e.g. some FUSE/SMB volumes) we fall back to a
     * non-atomic REPLACE_EXISTING move. This eliminates the race where a
     * concurrent reader observes the file mid-write and either dedup-skips
     * with partial bytes or reads truncated content — surfacing upstream as a
     * zstd "unexpected EOF" decompress error.
     *
     * <p>Dedup verifies the existing file is non-empty before trusting it. A
     * zero-byte file (left by a prior crash before this fix, or by an
     * unrelated tool) is rewritten rather than honored.
     */
    static String putBlob(byte[] data) throws Exception {
        String ref = hashRef(data);

        Path p = blobPath(ref);
        if (Files.exists(p) && Files.size(p) > 0) {
            return ref; // already exists, non-empty — trust it
        }

        Path dir = p.getParent();
        Files.createDirectories(dir);
        byte[] compressed = Zstd.compress(data);

        Path tmp = Files.createTempFile(dir, ".tmp-", "");
        try {
            Files.write(tmp, compressed);
            try {
                moveAtomicWithRetry(tmp, p);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (FUSE/SMB on Windows, certain network
                // mounts on Linux) reject ATOMIC_MOVE. Best-effort fallback
                // to REPLACE_EXISTING. Note this is NOT atomically safe on
                // all FUSE implementations — REPLACE_EXISTING may delete
                // then create on some FUSE drivers, leaving a brief window
                // where the destination doesn't exist or is partial. It is
                // still narrower than the pre-fix in-place write, but cannot
                // give a hard atomicity guarantee on these filesystems.
                // Customers running on local NTFS/ext4/APFS get full atomicity
                // via the primary ATOMIC_MOVE path above and never reach this.
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            } catch (AccessDeniedException e) {
                // Windows-specific: another writer beat us to the destination
                // and readers hold it open without FILE_SHARE_DELETE, so
                // MoveFileEx cannot replace it. Content is addressed by hash,
                // so a non-empty file at p has the same bytes we'd write —
                // accept as dedup win.
                if (Files.exists(p) && Files.size(p) > 0) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best-effort */ }
                    return ref;
                }
                throw e;
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best-effort */ }
            throw e;
        }

        return ref;
    }

    /**
     * Windows-specific share-violation retry for atomic move.
     *
     * <p>On Windows MoveFileEx fails with two distinct errors when the
     * destination is held open by another process without FILE_SHARE_DELETE:
     * ERROR_ACCESS_DENIED (mapped to AccessDeniedException) when the
     * incompatible share modes deny the implicit delete, and
     * ERROR_SHARING_VIOLATION (mapped to plain FileSystemException since
     * Java has no dedicated subclass) when the destination is mid-CreateFile.
     * POSIX rename(2) has no such race. We retry both with short exponential
     * backoff so transient share violations succeed without surfacing as
     * putBlob errors. AtomicMoveNotSupportedException is excluded so the
     * caller's REPLACE_EXISTING fallback still fires.
     */
    private static void moveAtomicWithRetry(Path tmp, Path p) throws IOException {
        final int maxAttempts = 16;
        long backoffMicros = 100;
        IOException lastErr = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                throw e;
            } catch (FileSystemException e) {
                lastErr = e;
                try { Thread.sleep(backoffMicros / 1000, (int) ((backoffMicros % 1000) * 1000)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                backoffMicros = Math.min(backoffMicros * 2, 8000);
            }
        }
        throw lastErr;
    }

    /**
     * Reads and decompresses a blob identified by its ref from the given store path.
     * Uses the stored configDir (set once at init), matching Go's Store.configDir field.
     */
    static byte[] getBlob(String ref, String storePath) throws Exception {
        String hash = ref.startsWith("xxh3:") ? ref.substring(5) : ref;
        String dir = hash.substring(0, 2);
        String file = hash.substring(2);

        Path blobFile = Paths.get(configDir, "store", storePath, "blobs", dir, file);
        byte[] compressed = readBlobWithRetry(blobFile);
        return Zstd.decompress(compressed, (int) Zstd.decompressedSize(compressed));
    }

    /**
     * Windows-specific share-violation retry for blob reads.
     *
     * <p>On Windows Files.readAllBytes can fail with AccessDeniedException
     * (ERROR_ACCESS_DENIED) when a writer holds the file with restrictive
     * share modes, and with plain FileSystemException (ERROR_SHARING_VIOLATION)
     * when an external process holds the file with FILE_SHARE_NONE — typical
     * of antivirus scanners and indexing services. Both are transient. POSIX
     * has no such window. NoSuchFileException is excluded so a real "blob not
     * present" surfaces immediately.
     */
    private static byte[] readBlobWithRetry(Path p) throws IOException {
        final int maxAttempts = 16;
        long backoffMicros = 100;
        IOException lastErr = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                return Files.readAllBytes(p);
            } catch (NoSuchFileException e) {
                throw e;
            } catch (FileSystemException e) {
                lastErr = e;
                try { Thread.sleep(backoffMicros / 1000, (int) ((backoffMicros % 1000) * 1000)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                backoffMicros = Math.min(backoffMicros * 2, 8000);
            }
        }
        throw lastErr;
    }

    // --- Helpers ---

    /**
     * Returns true if the JSON object is a BlobRef marker.
     */
    static boolean isBlobRef(JsonObject obj) {
        JsonElement magic = obj.get("__magic");
        JsonElement ref = obj.get("__ref");
        if (magic == null || ref == null) {
            return false;
        }
        try {
            return magic.getAsInt() == MAGIC
                    && ref.isJsonPrimitive()
                    && !ref.getAsString().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves a BlobRef by reading and decompressing the blob from disk.
     */
    private static JsonElement resolveRef(JsonObject blobRef) throws Exception {
        String ref = blobRef.get("__ref").getAsString();
        String path = blobRef.get("__path").getAsString();
        byte[] blob = getBlob(ref, path);
        String json = new String(blob, StandardCharsets.UTF_8);
        return JsonParser.parseString(json);
    }

    /**
     * Computes the blob file path: root/blobs/{hash[:2]}/{hash[2:]}
     */
    private static Path blobPath(String ref) {
        String hash = ref.startsWith("xxh3:") ? ref.substring(5) : ref;
        return Paths.get(root, "blobs", hash.substring(0, 2), hash.substring(2));
    }

    /**
     * Computes the XXH3-128 hash of data and returns "xxh3:" + hex.
     * Byte order matches the Go implementation (big-endian Hi then Lo).
     */
    static String hashRef(byte[] data) {
        long[] result = xxh128.hashBytes(data);
        // result[0] = low 64 bits, result[1] = high 64 bits
        // Go serialises as Hi (big-endian) then Lo (big-endian)
        long hi = result[1];
        long lo = result[0];
        return "xxh3:" + String.format("%016x%016x", hi, lo);
    }

    // --- Map-based BlobRef helpers (for variable access) ---

    /**
     * Checks if a Map value is a BlobRef marker.
     * Mirrors Go's lmo.IsBlobRefMap().
     */
    @SuppressWarnings("unchecked")
    public static boolean isBlobRefMap(Object val) {
        if (!(val instanceof Map)) {
            return false;
        }
        Map<String, Object> m = (Map<String, Object>) val;
        Object magic = m.get("__magic");
        Object ref = m.get("__ref");
        if (magic == null || ref == null) {
            return false;
        }
        long magicVal;
        if (magic instanceof Number) {
            magicVal = ((Number) magic).longValue();
        } else {
            return false;
        }
        String refStr = ref.toString();
        return magicVal == MAGIC && !refStr.isEmpty();
    }

    /**
     * Resolves a BlobRef from a Map value.
     * Reads the blob via __ref/__path, deserializes, and learns relPath from first BlobRef.
     * Mirrors Go's ResolveBlobRefValue().
     */
    @SuppressWarnings("unchecked")
    public static Object resolveBlobRefValue(Map<String, Object> m) throws Exception {
        String ref = m.get("__ref") != null ? m.get("__ref").toString() : "";
        String path = m.get("__path") != null ? m.get("__path").toString() : "";
        if (ref.isEmpty()) {
            throw new Exception("lmo: missing __ref");
        }

        // Learn relPath from the first BlobRef we encounter.
        if (relPath == null && !path.isEmpty()) {
            init(path);
        }

        byte[] blob = getBlob(ref, path);
        String json = new String(blob, StandardCharsets.UTF_8);
        Gson gson = new Gson();
        return gson.fromJson(json, Object.class);
    }

    /**
     * Marshals a value to JSON and packs it into the blob store if it exceeds
     * the threshold. Returns the packed BlobRef map if packed, or null if the
     * value is small enough to send inline.
     * Mirrors Go's PackValue().
     */
    public static Object packValue(Object value) {
        if (root == null || relPath == null) {
            return null;
        }

        try {
            Gson gson = new Gson();
            String json = gson.toJson(value);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);

            if (data.length < THRESHOLD) {
                return null;
            }

            byte[] packed = pack(data);
            if (packed == data) {
                // pack() returned the same reference — nothing was packed
                return null;
            }

            String packedJson = new String(packed, StandardCharsets.UTF_8);
            Object result = gson.fromJson(packedJson, Object.class);

            // Check if the result itself is a BlobRef
            if (isBlobRefMap(result)) {
                return result;
            }

            // Pack may have replaced children but not the root
            if (packed.length < data.length) {
                return result;
            }

            return null;
        } catch (Exception e) {
            System.err.println("lmo: packValue error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the platform-specific config directory.
     */
    static String getConfigDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return home + "\\AppData\\Local\\Robomotion";
        }
        return home + "/.config/robomotion";
    }

    // --- Test helpers (package-private) ---

    /**
     * Initialise the store with an explicit config directory and store path (for testing).
     * Mirrors Go's NewStore(configDir) + SetRelPath(storePath).
     */
    static synchronized void initForTesting(String testConfigDir, String storePath) throws Exception {
        configDir = testConfigDir;
        relPath = storePath;
        root = Paths.get(configDir, "store", relPath).toString();
        Path blobDir = Paths.get(root, "blobs");
        Files.createDirectories(blobDir);
    }

    /**
     * Reset store state (for testing).
     */
    static synchronized void reset() {
        configDir = null;
        root = null;
        relPath = null;
    }

    /**
     * Returns the current relPath (for testing).
     */
    static String getRelPath() {
        return relPath;
    }
}
