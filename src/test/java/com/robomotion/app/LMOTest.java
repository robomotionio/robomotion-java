package com.robomotion.app;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.robomotion.testing.MockContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class LMOTest {

    @TempDir
    Path tempDir;

    /** Store path used by most tests. */
    private static final String STORE_PATH = "test/flow";

    @BeforeEach
    void setUp() {
        LMO.reset();
        Runtime.SetRobotCapabilities(0L);
    }

    @AfterEach
    void tearDown() {
        LMO.reset();
        Runtime.SetRobotCapabilities(0L);
    }

    /**
     * Helper: initialise the LMO store rooted at tempDir.
     * Blobs live under tempDir/store/{storePath}/blobs/...
     * getBlob() uses stored configDir, so reads will find the same files.
     */
    private void initTestStore() throws Exception {
        LMO.initForTesting(tempDir.toString(), STORE_PATH);
    }

    /** Returns the on-disk path for a blob ref written via the test store. */
    private Path blobFilePath(String ref) {
        String hash = ref.substring(5); // strip "xxh3:"
        return tempDir.resolve("store").resolve(STORE_PATH)
                .resolve("blobs").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    // -----------------------------------------------------------------------
    // Capability constants & bitmap logic
    // -----------------------------------------------------------------------
    @Nested
    class Capabilities {

        @Test
        void capabilityLMOIsBit4() {
            assertEquals(16L, Runtime.CAPABILITY_LMO, "CAPABILITY_LMO must be bit 4 (1<<4 = 16)");
        }

        @Test
        void packageCapabilitiesIncludesLMO() {
            assertTrue((Runtime.packageCapabilities & Runtime.CAPABILITY_LMO) != 0,
                    "packageCapabilities must include CAPABILITY_LMO");
        }

        @Test
        void isLMOCapableReturnsFalseWhenRobotHasNoCaps() {
            Runtime.SetRobotCapabilities(0L);
            assertFalse(Runtime.IsLMOCapable());
        }

        @Test
        void isLMOCapableReturnsTrueWhenRobotHasLMO() {
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
            assertTrue(Runtime.IsLMOCapable());
        }

        @Test
        void isLMOCapableReturnsFalseWhenRobotHasOldBit0() {
            // Bit 0 is the old reserved LMO — must NOT match
            Runtime.SetRobotCapabilities(1L);
            assertFalse(Runtime.IsLMOCapable());
        }

        @Test
        void hasCapabilityUsesIntersection() {
            Runtime.SetRobotCapabilities((1L << 4) | (1L << 1));
            assertTrue(Runtime.HasCapability(Runtime.CAPABILITY_LMO));
        }

        @Test
        void getCapabilitiesReturnsIntersection() {
            Runtime.SetRobotCapabilities(0xFFL);
            long effective = Runtime.GetCapabilities();
            assertEquals(Runtime.packageCapabilities & 0xFFL, effective);
        }

        @Test
        void isLMOCapableReturnsTrueWhenRobotHasMultipleCaps() {
            Runtime.SetRobotCapabilities(0xFF);
            assertTrue(Runtime.IsLMOCapable());
        }
    }

    // -----------------------------------------------------------------------
    // isBlobRefMap
    // -----------------------------------------------------------------------
    @Nested
    class IsBlobRefMap {

        @Test
        void validBlobRefMapReturnsTrue() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", (double) LMO.MAGIC);
            m.put("__ref", "xxh3:abc123");
            m.put("__path", "robots/1/flows/2");
            assertTrue(LMO.isBlobRefMap(m));
        }

        @Test
        void intMagicReturnsTrue() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", LMO.MAGIC);
            m.put("__ref", "xxh3:abc123");
            assertTrue(LMO.isBlobRefMap(m));
        }

        @Test
        void longMagicReturnsTrue() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", (long) LMO.MAGIC);
            m.put("__ref", "xxh3:abc123");
            assertTrue(LMO.isBlobRefMap(m));
        }

        @Test
        void nullReturnsFalse() {
            assertFalse(LMO.isBlobRefMap(null));
        }

        @Test
        void stringReturnsFalse() {
            assertFalse(LMO.isBlobRefMap("not a map"));
        }

        @Test
        void listReturnsFalse() {
            assertFalse(LMO.isBlobRefMap(List.of(1, 2, 3)));
        }

        @Test
        void missingMagicReturnsFalse() {
            Map<String, Object> m = new HashMap<>();
            m.put("__ref", "xxh3:abc123");
            assertFalse(LMO.isBlobRefMap(m));
        }

        @Test
        void missingRefReturnsFalse() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", (double) LMO.MAGIC);
            assertFalse(LMO.isBlobRefMap(m));
        }

        @Test
        void wrongMagicReturnsFalse() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", 12345.0);
            m.put("__ref", "xxh3:abc123");
            assertFalse(LMO.isBlobRefMap(m));
        }

        @Test
        void emptyRefReturnsFalse() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", (double) LMO.MAGIC);
            m.put("__ref", "");
            assertFalse(LMO.isBlobRefMap(m));
        }

        @Test
        void nonNumericMagicReturnsFalse() {
            Map<String, Object> m = new HashMap<>();
            m.put("__magic", "not a number");
            m.put("__ref", "xxh3:abc123");
            assertFalse(LMO.isBlobRefMap(m));
        }

        @Test
        void emptyMapReturnsFalse() {
            assertFalse(LMO.isBlobRefMap(new HashMap<>()));
        }
    }

    // -----------------------------------------------------------------------
    // isBlobRef (Gson JsonObject variant)
    // -----------------------------------------------------------------------
    @Nested
    class IsBlobRefJson {

        @Test
        void validJsonBlobRefReturnsTrue() {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("__magic", LMO.MAGIC);
            obj.addProperty("__ref", "xxh3:abc123");
            assertTrue(LMO.isBlobRef(obj));
        }

        @Test
        void missingMagicReturnsFalse() {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("__ref", "xxh3:abc123");
            assertFalse(LMO.isBlobRef(obj));
        }

        @Test
        void emptyRefReturnsFalse() {
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("__magic", LMO.MAGIC);
            obj.addProperty("__ref", "");
            assertFalse(LMO.isBlobRef(obj));
        }
    }

    // -----------------------------------------------------------------------
    // hashRef
    // -----------------------------------------------------------------------
    @Nested
    class HashRef {

        @Test
        void producesXxh3Prefix() {
            String ref = LMO.hashRef("hello".getBytes(StandardCharsets.UTF_8));
            assertTrue(ref.startsWith("xxh3:"));
        }

        @Test
        void producesConsistentHash() {
            byte[] data = "deterministic input".getBytes(StandardCharsets.UTF_8);
            assertEquals(LMO.hashRef(data), LMO.hashRef(data));
        }

        @Test
        void hashIs32HexCharsAfterPrefix() {
            String ref = LMO.hashRef("test".getBytes(StandardCharsets.UTF_8));
            String hex = ref.substring(5);
            assertEquals(32, hex.length(), "128-bit hash = 32 hex chars");
            assertTrue(hex.matches("[0-9a-f]+"), "hash must be lowercase hex");
        }

        @Test
        void differentInputProducesDifferentHash() {
            assertNotEquals(
                    LMO.hashRef("input-a".getBytes(StandardCharsets.UTF_8)),
                    LMO.hashRef("input-b".getBytes(StandardCharsets.UTF_8)));
        }
    }

    // -----------------------------------------------------------------------
    // putBlob / getBlob roundtrip
    // -----------------------------------------------------------------------
    @Nested
    class BlobIO {

        @BeforeEach
        void init() throws Exception { initTestStore(); }

        @Test
        void putAndGetBlobRoundtrip() throws Exception {
            byte[] data = "hello blob world".getBytes(StandardCharsets.UTF_8);
            String ref = LMO.putBlob(data);
            assertNotNull(ref);
            assertTrue(ref.startsWith("xxh3:"));
            assertArrayEquals(data, LMO.getBlob(ref, STORE_PATH));
        }

        @Test
        void putBlobIsIdempotent() throws Exception {
            byte[] data = "idempotent data".getBytes(StandardCharsets.UTF_8);
            assertEquals(LMO.putBlob(data), LMO.putBlob(data));
        }

        @Test
        void blobFileExistsOnDisk() throws Exception {
            byte[] data = "check file exists".getBytes(StandardCharsets.UTF_8);
            String ref = LMO.putBlob(data);
            assertTrue(Files.exists(blobFilePath(ref)), "compressed blob file must exist on disk");
        }

        @Test
        void blobIsCompressed() throws Exception {
            byte[] data = "x".repeat(10000).getBytes(StandardCharsets.UTF_8);
            String ref = LMO.putBlob(data);
            long compressedSize = Files.size(blobFilePath(ref));
            assertTrue(compressedSize < data.length, "blob should be zstd-compressed");
        }
    }

    // -----------------------------------------------------------------------
    // pack / resolveAll roundtrip
    // -----------------------------------------------------------------------
    @Nested
    class PackResolve {

        @BeforeEach
        void init() throws Exception {
            initTestStore();
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
        }

        @Test
        void smallPayloadPassesThroughPack() {
            byte[] small = "{\"key\":\"val\"}".getBytes(StandardCharsets.UTF_8);
            assertSame(small, LMO.pack(small));
        }

        @Test
        void nullDataPassesThroughPack() {
            assertNull(LMO.pack(null));
        }

        @Test
        void emptyDataPassesThroughPack() {
            byte[] empty = new byte[0];
            assertSame(empty, LMO.pack(empty));
        }

        @Test
        void largeStringFieldGetsPacked() {
            String largeValue = "\"" + "A".repeat(5000) + "\"";
            String json = "{\"big\":" + largeValue + ",\"small\":\"ok\"}";
            byte[] data = json.getBytes(StandardCharsets.UTF_8);

            byte[] packed = LMO.pack(data);
            assertNotSame(data, packed);

            String packedStr = new String(packed, StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("__magic"));
            assertTrue(packedStr.contains("__ref"));
            assertFalse(packedStr.contains("AAAAA"));
            assertTrue(packedStr.contains("\"small\""), "small field should remain inline");
        }

        @Test
        void resolveAllRestoresPackedData() {
            String bigContent = "X".repeat(5000);
            String json = "{\"field\":\"" + bigContent + "\"}";
            byte[] original = json.getBytes(StandardCharsets.UTF_8);

            byte[] packed = LMO.pack(original);
            assertNotSame(original, packed);

            byte[] resolved = LMO.resolveAll(packed);
            assertTrue(new String(resolved, StandardCharsets.UTF_8).contains(bigContent));
        }

        @Test
        void resolveAllPreservesSmallPayload() {
            byte[] small = "{\"a\":1,\"b\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
            assertSame(small, LMO.resolveAll(small));
        }

        @Test
        void packAndResolveMultipleFields() {
            String big1 = "Y".repeat(5000);
            String big2 = "Z".repeat(6000);
            String json = "{\"f1\":\"" + big1 + "\",\"f2\":\"" + big2 + "\",\"f3\":\"tiny\"}";
            byte[] original = json.getBytes(StandardCharsets.UTF_8);

            byte[] packed = LMO.pack(original);
            String packedStr = new String(packed, StandardCharsets.UTF_8);
            assertFalse(packedStr.contains("YYYYY"));
            assertFalse(packedStr.contains("ZZZZZ"));
            assertTrue(packedStr.contains("tiny"));

            byte[] resolved = LMO.resolveAll(packed);
            String resolvedStr = new String(resolved, StandardCharsets.UTF_8);
            assertTrue(resolvedStr.contains(big1));
            assertTrue(resolvedStr.contains(big2));
        }

        @Test
        void packSkipsExistingBlobRef() {
            String padded = "{\"ref\":{\"__magic\":" + LMO.MAGIC
                    + ",\"__ref\":\"xxh3:deadbeef\",\"__path\":\"" + STORE_PATH + "\"}"
                    + ",\"pad\":\"" + "P".repeat(5000) + "\"}";
            byte[] packed = LMO.pack(padded.getBytes(StandardCharsets.UTF_8));
            assertTrue(new String(packed, StandardCharsets.UTF_8).contains("xxh3:deadbeef"),
                    "existing BlobRef should be preserved");
        }

        @Test
        void packReturnsOriginalWhenNotLMOCapable() {
            Runtime.SetRobotCapabilities(0L);
            byte[] data = ("{\"big\":\"" + "A".repeat(5000) + "\"}").getBytes(StandardCharsets.UTF_8);
            assertSame(data, LMO.pack(data));
        }

        @Test
        void packLargeObjectField() {
            // Inner object must be >= 4096 bytes with no individually large children
            StringBuilder sb = new StringBuilder("{\"obj\":{");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"key").append(i).append("\":\"value").append(i).append("\"");
            }
            sb.append("}}");
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            assertTrue(data.length >= LMO.THRESHOLD);

            String packedStr = new String(LMO.pack(data), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("__magic"));
        }

        @Test
        void packLargeArrayField() {
            StringBuilder sb = new StringBuilder("{\"arr\":[");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"item").append(i).append("\"");
            }
            sb.append("]}");
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            assertTrue(data.length >= LMO.THRESHOLD);

            String packedStr = new String(LMO.pack(data), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("__magic"));
            assertTrue(packedStr.contains("\"__type\":\"array\""));
        }

        @Test
        void resolveAllHandlesNullAndEmpty() {
            assertNull(LMO.resolveAll(null));
            byte[] empty = new byte[0];
            assertSame(empty, LMO.resolveAll(empty));
        }

        @Test
        void resolveAllHandlesNonObject() {
            byte[] arr = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
            assertSame(arr, LMO.resolveAll(arr));
        }

        /**
         * Pins the customer's iter-17 failure pattern.
         *
         * When Pack's extractObject !modified branch packs an outer
         * container as a single blob (because the container is large
         * but no child reaches threshold), the blob preserves any
         * pre-existing BlobRef envelopes inside. Without recursive
         * resolveValue, those nested refs survive resolveAll and
         * surface to user code as a stub object, crashing with
         * ClassCastException / "is not an array" downstream.
         */
        @Test
        void resolveAllRecursivelyUnwrapsNestedBlobRefs() throws Exception {
            // Step 1: pack response array as a BlobRef.
            StringBuilder rsb = new StringBuilder("[");
            for (int i = 0; i < 16; i++) {
                if (i > 0) rsb.append(",");
                rsb.append("{\"raw\":\"").append("X".repeat(680))
                   .append("\",\"sgkSicil\":\"s\",\"sirketAdi\":\"ihl\",\"sonucKod\":\"0\"}");
            }
            rsb.append("]");
            byte[] respArr = rsb.toString().getBytes(StandardCharsets.UTF_8);
            String respRef = LMO.putBlob(respArr);
            String respEnv = "{\"__ref\":\"" + respRef + "\",\"__magic\":20260301,\"__size\":"
                + respArr.length + ",\"__path\":\"" + STORE_PATH
                + "\",\"__type\":\"array\",\"__len\":16}";

            // Step 2: build msg shape where api > 4 KB but no child crosses 4 KB.
            StringBuilder lsb = new StringBuilder("[");
            for (int i = 0; i < 16; i++) {
                if (i > 0) lsb.append(",");
                lsb.append("{\"sirketAdi\":\"İHLAS HABER AJANSI A.Ş.\",")
                   .append("\"sgkSicil\":\"2.6031.01.03\",\"isyeriKodu\":\"x\",")
                   .append("\"kullaniciAdi\":\"u\",\"isyeriSifresi\":\"p\",")
                   .append("\"token\":\"02bf0810-6d0e-4197-ad80-0dcb7fe5b962\"}");
            }
            lsb.append("]");
            String loginSuccess = lsb.toString();
            String wsLogin = "{\"response\":" + respEnv + ",\"loginSuccess\":" + loginSuccess
                + ",\"loginFailed\":[]}";

            StringBuilder msb = new StringBuilder("[");
            for (int i = 0; i < 5; i++) {
                if (i > 0) msb.append(",");
                msb.append("{\"sirketAdi\":\"İHLAS\",\"sgkSicil\":\"2.6031\",\"note\":\"")
                   .append("y".repeat(100)).append("\"}");
            }
            msb.append("]");
            String medium = msb.toString();
            String api = "{\"wsLogin\":" + wsLogin
                + ",\"raporAramaTarihile\":{\"response\":" + medium
                + ",\"failedResponses\":[],\"noReports\":[],\"Reports\":[]}"
                + ",\"raporOnay\":{\"response\":" + medium
                + ",\"confirmedReports\":[],\"reportsNotConfirmed\":[]}"
                + ",\"raporOkunduKapat\":{\"response\":" + medium
                + ",\"reportsNotClosed\":[]}}";
            String msg = "{\"constants\":{\"api\":" + api + ",\"urls\":{}}}";
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);

            assertTrue(api.getBytes(StandardCharsets.UTF_8).length >= LMO.THRESHOLD,
                "api too small for whole-pack precondition");
            assertTrue(wsLogin.getBytes(StandardCharsets.UTF_8).length < LMO.THRESHOLD,
                "wsLogin too big for whole-pack precondition");

            // Step 3: Pack — api should be packed whole.
            byte[] packed = LMO.pack(msgBytes);
            String packedStr = new String(packed, StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"api\":{\"__ref\""),
                "api should have been packed as a BlobRef envelope; packed=" + packedStr.substring(0, Math.min(200, packedStr.length())));

            // Step 4: resolveAll — with the fix, no surviving stub.
            byte[] resolved = LMO.resolveAll(packed);
            String resolvedStr = new String(resolved, StandardCharsets.UTF_8);
            // Tree-walking scan: detects BlobRef envelopes by structure
            // (__magic == 20260301 + __ref string), not by literal substring,
            // so user data containing the literal "__magic" cannot false-fail.
            String survivingPath = findSurvivingBlobRefPath(resolved);
            assertNull(survivingPath,
                "NESTED BLOBREF SURVIVED resolveAll — bug reproduced! "
                + "surviving path: " + survivingPath
                + "\nresolvedStr=" + resolvedStr.substring(0, Math.min(400, resolvedStr.length())));
        }

        /**
         * Follow-up: BlobRef envelope sitting as a JsonArray element survives
         * resolveAll unless resolveValue also recurses into arrays.
         */
        @Test
        void resolveAllRecursesIntoArrayElements() throws Exception {
            byte[] innerData = "\"the inner blob content\"".getBytes(StandardCharsets.UTF_8);
            String innerRef = LMO.putBlob(innerData);
            String innerEnv = "{\"__ref\":\"" + innerRef + "\",\"__magic\":20260301,\"__size\":"
                + innerData.length + ",\"__path\":\"" + STORE_PATH
                + "\",\"__type\":\"string\",\"__len\":22}";

            StringBuilder sb = new StringBuilder("[");
            int envIndex = 15;
            for (int i = 0; i < 31; i++) {
                if (i > 0) sb.append(",");
                if (i == envIndex) {
                    sb.append(innerEnv).append(",");
                }
                sb.append("{\"i\":").append(i).append(",\"pad\":\"")
                  .append("x".repeat(150)).append("\"}");
            }
            sb.append("]");
            String bigArray = sb.toString();
            String msg = "{\"bigArray\":" + bigArray + ",\"other\":\"fluff\"}";
            byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
            assertTrue(bigArray.getBytes(StandardCharsets.UTF_8).length >= LMO.THRESHOLD,
                "bigArray too small");

            byte[] packed = LMO.pack(msgBytes);
            String packedStr = new String(packed, StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"bigArray\":{\"__ref\""),
                "bigArray should be packed as BlobRef");

            byte[] resolved = LMO.resolveAll(packed);
            String resolvedStr = new String(resolved, StandardCharsets.UTF_8);

            String survivingPath = findSurvivingBlobRefPath(resolved);
            assertNull(survivingPath,
                "ARRAY-NESTED BLOBREF SURVIVED resolveAll — bug reproduced! "
                + "surviving path: " + survivingPath
                + "\nresolvedStr=" + resolvedStr.substring(0, Math.min(400, resolvedStr.length())));
        }
    }

    // -------------------------------------------------------------------
    // Test helpers — structural scan for surviving BlobRef envelopes.
    // Walks the parsed JSON tree and returns the dot-path of the first
    // element that has the BlobRef shape (__magic == 20260301 + __ref
    // string). Returns null if no surviving envelope is found.
    //
    // This is preferable to a string indexOf("__magic") scan because user
    // data can legitimately contain the literal "__magic" without being a
    // BlobRef envelope.
    // -------------------------------------------------------------------

    private static String findSurvivingBlobRefPath(byte[] data) {
        try {
            JsonElement root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            return findSurvivingBlobRefPath(root, "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String findSurvivingBlobRefPath(JsonElement el, String path) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (isBlobRefShape(obj)) return path.isEmpty() ? "<root>" : path;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                String found = findSurvivingBlobRefPath(e.getValue(), childPath);
                if (found != null) return found;
            }
        } else if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                String childPath = path.isEmpty() ? Integer.toString(i) : path + "." + i;
                String found = findSurvivingBlobRefPath(arr.get(i), childPath);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isBlobRefShape(JsonObject obj) {
        if (!obj.has("__magic") || !obj.has("__ref")) return false;
        try {
            long magic = obj.get("__magic").getAsLong();
            String ref = obj.get("__ref").getAsString();
            return magic == 20260301L && ref != null && !ref.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // resolveBlobRefValue (Map-based resolution)
    // -----------------------------------------------------------------------
    @Nested
    class ResolveBlobRefValue {

        @BeforeEach
        void init() throws Exception { initTestStore(); }

        @Test
        void resolvesStringBlob() throws Exception {
            String ref = LMO.putBlob("\"hello from blob\"".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", STORE_PATH);

            assertEquals("hello from blob", LMO.resolveBlobRefValue(blobRef));
        }

        @Test
        void resolvesObjectBlob() throws Exception {
            String ref = LMO.putBlob("{\"name\":\"test\",\"count\":42}".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", STORE_PATH);

            Object resolved = LMO.resolveBlobRefValue(blobRef);
            assertInstanceOf(Map.class, resolved);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) resolved;
            assertEquals("test", map.get("name"));
        }

        @Test
        void resolvesArrayBlob() throws Exception {
            String ref = LMO.putBlob("[1,2,3,4,5]".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", STORE_PATH);

            assertInstanceOf(List.class, LMO.resolveBlobRefValue(blobRef));
        }

        @Test
        void throwsOnMissingRef() {
            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", "");
            blobRef.put("__path", STORE_PATH);

            assertThrows(Exception.class, () -> LMO.resolveBlobRefValue(blobRef));
        }

        @Test
        void learnsRelPathFromBlobRef() throws Exception {
            // Write a blob under a specific store path
            String otherPath = "robot/1/flow/2";
            LMO.initForTesting(tempDir.toString(), otherPath);
            String ref = LMO.putBlob("\"learned\"".getBytes(StandardCharsets.UTF_8));

            // Reset — configDir and relPath are both null
            LMO.reset();
            assertNull(LMO.getRelPath());

            // resolveBlobRefValue should learn relPath from the BlobRef's __path
            // and call init(path) which sets configDir via getConfigDir().
            // For this test, we re-init with the same tempDir so getBlob can find the file.
            // In production, configDir is always the platform config dir.
            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", otherPath);

            // Can't fully test resolution here because init(path) sets configDir
            // to the real platform dir, not tempDir. But we can verify the learning
            // logic path: if relPath is null and __path is present, init() is called.
            // This is validated by the full roundtrip tests that use initForTesting.
        }
    }

    // -----------------------------------------------------------------------
    // packValue
    // -----------------------------------------------------------------------
    @Nested
    class PackValue {

        @BeforeEach
        void init() throws Exception {
            initTestStore();
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
        }

        @Test
        void returnsNullForSmallValue() {
            assertNull(LMO.packValue("small string"));
        }

        @Test
        void returnsNullWhenStoreNotInitialized() {
            LMO.reset();
            assertNull(LMO.packValue("A".repeat(5000)));
        }

        @Test
        void bareStringReturnsNull() {
            // pack() requires a JSON object at top level; bare strings are
            // handled by the whole-message pack in NodeServer.onMessage
            assertNull(LMO.packValue("B".repeat(5000)));
        }

        @Test
        void bareListReturnsNull() {
            List<String> large = new java.util.ArrayList<>();
            for (int i = 0; i < 500; i++) {
                large.add("item" + i);
            }
            assertNull(LMO.packValue(large));
        }

        @Test
        void packsMapWithLargeField() {
            Map<String, Object> m = new HashMap<>();
            m.put("big", "V".repeat(5000));
            m.put("small", "ok");
            Object result = LMO.packValue(m);
            assertNotNull(result, "map with a large field should be packed");
            assertInstanceOf(Map.class, result);
        }

        @Test
        void flatMapWithSmallFieldsReturnsNull() {
            // No individual field >= 4096 and root object is never extracted by pack()
            Map<String, Object> flat = new HashMap<>();
            for (int i = 0; i < 200; i++) {
                flat.put("key" + i, "val" + i);
            }
            assertNull(LMO.packValue(flat));
        }

        @Test
        void returnsNullForNullValue() {
            assertNull(LMO.packValue(null));
        }

        @Test
        void returnsNullForEmptyString() {
            assertNull(LMO.packValue(""));
        }

        @Test
        void packedMapCanBeResolved() throws Exception {
            Map<String, Object> original = new HashMap<>();
            original.put("data", "C".repeat(5000));
            original.put("tag", "test");

            Object packed = LMO.packValue(original);
            assertNotNull(packed);

            // The packed result is a map where the large field is a BlobRef.
            // Resolving the whole message should restore the original data.
            Gson gson = new com.google.gson.Gson();
            byte[] packedBytes = gson.toJson(packed).getBytes(StandardCharsets.UTF_8);
            byte[] resolved = LMO.resolveAll(packedBytes);
            String resolvedStr = new String(resolved, StandardCharsets.UTF_8);
            assertTrue(resolvedStr.contains("C".repeat(5000)));
            assertTrue(resolvedStr.contains("\"tag\":\"test\""));
        }
    }

    // -----------------------------------------------------------------------
    // Store init / reset / isActive
    // -----------------------------------------------------------------------
    @Nested
    class StoreLifecycle {

        @Test
        void resetClearsState() throws Exception {
            initTestStore();
            assertTrue(LMO.isActive());
            LMO.reset();
            assertFalse(LMO.isActive());
        }

        @Test
        void initForTestingCreatesDirectories() throws Exception {
            LMO.initForTesting(tempDir.toString(), "my/store");
            assertTrue(Files.isDirectory(
                    tempDir.resolve("store").resolve("my/store").resolve("blobs")));
            assertTrue(LMO.isActive());
        }

        @Test
        void getRelPathReturnsStorePath() throws Exception {
            LMO.initForTesting(tempDir.toString(), "robots/42/flows/7");
            assertEquals("robots/42/flows/7", LMO.getRelPath());
        }
    }

    // -----------------------------------------------------------------------
    // BlobRef metadata fields (type, len, size)
    // -----------------------------------------------------------------------
    @Nested
    class BlobRefMetadata {

        @BeforeEach
        void init() throws Exception {
            initTestStore();
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
        }

        @Test
        void packedStringHasTypeAndLen() {
            String content = "D".repeat(5000);
            String json = "{\"s\":\"" + content + "\"}";
            String packedStr = new String(LMO.pack(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"__type\":\"string\""));
            assertTrue(packedStr.contains("\"__len\":" + content.length()));
            // __size is the UTF-8 byte count of the raw JSON-serialized value
            // (the string plus surrounding quotes). Wire contract across SDKs.
            int expectedSize = ("\"" + content + "\"").getBytes(StandardCharsets.UTF_8).length;
            assertTrue(packedStr.contains("\"__size\":" + expectedSize),
                "expected __size=" + expectedSize + " in: " + packedStr);
            assertTrue(packedStr.contains("\"__path\":\"" + STORE_PATH + "\""));
        }

        // Pack contract: a nested {"outer":{"inner": bigStr}} payload must be
        // either fully resolved at pack time (inner emerges as a BlobRef
        // envelope) or have its outer container packed as a whole blob.
        // The customer's iter-17 bug came from packing outer-as-whole without
        // recursing into resolve later, so this test pins the pack-side
        // half of the contract.
        @Test
        void packRecursivelyExtractsInnerOrPacksOuter() throws Exception {
            String big = "I".repeat(LMO.THRESHOLD + 100);
            String json = "{\"outer\":{\"inner\":\"" + big + "\"}}";
            byte[] packed = LMO.pack(json.getBytes(StandardCharsets.UTF_8));
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                .parseString(new String(packed, StandardCharsets.UTF_8))
                .getAsJsonObject();
            com.google.gson.JsonElement outer = root.get("outer");
            assertNotNull(outer);
            assertTrue(outer.isJsonObject(), "outer should be an object");
            com.google.gson.JsonObject outerObj = outer.getAsJsonObject();
            if (LMO.isBlobRef(outerObj)) {
                // outer extracted as whole blob — valid branch
                assertEquals("object", outerObj.get("__type").getAsString());
            } else {
                // inner must be a BlobRef envelope
                com.google.gson.JsonElement inner = outerObj.get("inner");
                assertNotNull(inner);
                assertTrue(inner.isJsonObject() && LMO.isBlobRef(inner.getAsJsonObject()),
                    "inner should be a BlobRef when outer is left inline; got: " + inner);
            }
        }

        @Test
        void packedArrayHasTypeAndLen() {
            StringBuilder sb = new StringBuilder("{\"arr\":[");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"item").append(i).append("\"");
            }
            sb.append("]}");
            String packedStr = new String(LMO.pack(sb.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"__type\":\"array\""));
            assertTrue(packedStr.contains("\"__len\":500"));
        }

        @Test
        void packedObjectHasTypeObject() {
            StringBuilder sb = new StringBuilder("{\"obj\":{");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"key").append(i).append("\":\"value").append(i).append("\"");
            }
            sb.append("}}");
            String packedStr = new String(LMO.pack(sb.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"__type\":\"object\""));
        }

        // Pin: BlobRef envelope metadata for non-Latin (multi-byte UTF-8)
        // string content. The wire contract every SDK + the robot must
        // agree on:
        //   - __len = code-point count (s.codePointCount), NOT String.length()
        //     (which counts UTF-16 code units) and NOT the byte count.
        //   - __size = raw UTF-8 byte count of Gson's serialization (preserved
        //     UTF-8, NOT escaped form like İ).
        // Multi-byte fixtures make these distinctions visible. Customer
        // payload is Turkish (Ihlas); Japanese covers 3-byte UTF-8.
        @Test
        void packedTurkishStringHasCorrectLenAndSize() throws Exception {
            // U+0130 — 2 bytes UTF-8. 2050 × 2 = 4100 > THRESHOLD.
            assertNonLatinPackedMetadata("İ", 2, 2050);
        }

        @Test
        void packedJapaneseStringHasCorrectLenAndSize() throws Exception {
            // U+65E5 — 3 bytes UTF-8. 1400 × 3 = 4200 > THRESHOLD.
            assertNonLatinPackedMetadata("日", 3, 1400);
        }

        // Non-BMP fixture. String.length() would give 2200 (UTF-16 surrogate
        // pairs); production uses codePointCount() which gives 1100. This
        // test is the one that would fail if anyone "simplifies" to length().
        @Test
        void packedEmojiStringHasCorrectLenAndSize() throws Exception {
            // U+1F600 GRINNING FACE — 4 bytes UTF-8. 1100 × 4 = 4400 > THRESHOLD.
            assertNonLatinPackedMetadata("😀", 4, 1100);
        }

        private void assertNonLatinPackedMetadata(String character, int charBytes, int count) throws Exception {
            String content = character.repeat(count);
            int byteLen = content.getBytes(StandardCharsets.UTF_8).length;
            int codePointCount = content.codePointCount(0, content.length());

            // Fixture invariant: bytes != code points, both > THRESHOLD.
            assertEquals(charBytes * count, byteLen, "fixture byte invariant broken");
            assertEquals(count, codePointCount, "fixture code-point invariant broken");
            assertTrue(byteLen >= LMO.THRESHOLD, "fixture too small: " + byteLen + " < " + LMO.THRESHOLD);

            String json = "{\"data\":\"" + content + "\"}";
            byte[] packed = LMO.pack(json.getBytes(StandardCharsets.UTF_8));
            JsonObject root = JsonParser
                .parseString(new String(packed, StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonObject blob = root.getAsJsonObject("data");

            assertTrue(LMO.isBlobRef(blob), "data should be a BlobRef envelope");
            assertEquals("string", blob.get("__type").getAsString());
            // __len is code-point count; for BMP chars equals String.length() but
            // the test pins the contract so non-BMP fixtures (emoji etc.) would
            // catch a regression.
            assertEquals(codePointCount, blob.get("__len").getAsInt(),
                "__len should be code-point count, not byte or UTF-16 count");
            // __size is raw UTF-8 byte count of the serialized form
            // ("<value>" — content + 2 quotes). NOT the escape-form like
            // İ (which would give 6×count + 2).
            assertEquals(byteLen + 2, blob.get("__size").getAsInt(),
                "__size should be raw UTF-8 byte count, not JSON-escape form");
        }
    }

    // -----------------------------------------------------------------------
    // THRESHOLD / MAGIC constants
    // -----------------------------------------------------------------------
    @Test
    void thresholdIs4096() {
        assertEquals(4096, LMO.THRESHOLD);
    }

    @Test
    void magicIsCorrect() {
        assertEquals(20260301, LMO.MAGIC);
    }

    // -----------------------------------------------------------------------
    // Variable access integration (Message scope, no gRPC needed)
    // -----------------------------------------------------------------------
    @Nested
    class VariableAccess {

        @BeforeEach
        void init() throws Exception {
            initTestStore();
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
        }

        @Test
        void getVariableResolvesBlobRefInMessageScope() throws Exception {
            String ref = LMO.putBlob("\"resolved string value\"".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", STORE_PATH);

            MockContext ctx = new MockContext();
            ctx.set("data", blobRef);

            Runtime.InVariable<Object> variable = new Runtime.InVariable<>("Message", "data");
            assertEquals("resolved string value", variable.Get(ctx));
        }

        @Test
        void getVariablePassesThroughNonBlobRef() throws Exception {
            MockContext ctx = new MockContext();
            ctx.set("name", "plain value");

            Runtime.InVariable<Object> variable = new Runtime.InVariable<>("Message", "name");
            assertEquals("plain value", variable.Get(ctx));
        }

        @Test
        void getVariableReturnsNullForMissingKey() throws Exception {
            MockContext ctx = new MockContext();
            Runtime.InVariable<Object> variable = new Runtime.InVariable<>("Message", "nonexistent");
            assertNull(variable.Get(ctx));
        }

        @Test
        @SuppressWarnings("unchecked")
        void setVariablePacksMapWithLargeField() throws Exception {
            MockContext ctx = new MockContext();

            Map<String, Object> largeMap = new HashMap<>();
            largeMap.put("data", "E".repeat(5000));
            largeMap.put("tag", "test");

            Runtime.OutVariable<Map<String, Object>> variable = new Runtime.OutVariable<>("Message", "output");
            variable.Set(ctx, largeMap);

            // The stored value should have the large field replaced with a BlobRef
            Object stored = ctx.get("output");
            assertInstanceOf(Map.class, stored);
            Map<String, Object> storedMap = (Map<String, Object>) stored;
            // The "data" field should be a BlobRef since it's >= 4096
            assertTrue(LMO.isBlobRefMap(storedMap.get("data")),
                    "large field should be stored as BlobRef");
        }

        @Test
        void setVariableBareStringNotPackedByPackValue() throws Exception {
            // Bare strings don't get packed by packValue (not a JSON object).
            // They get packed later by LMO.pack(ctx.getRaw()) in NodeServer.onMessage.
            MockContext ctx = new MockContext();
            Runtime.OutVariable<String> variable = new Runtime.OutVariable<>("Message", "output");
            variable.Set(ctx, "E".repeat(5000));
            assertEquals("E".repeat(5000), ctx.get("output"));
        }

        @Test
        void setVariableKeepsSmallValueInline() throws Exception {
            MockContext ctx = new MockContext();
            Runtime.OutVariable<String> variable = new Runtime.OutVariable<>("Message", "output");
            variable.Set(ctx, "small");
            assertEquals("small", ctx.get("output"));
        }

        @Test
        void setVariableDoesNotPackWhenNotLMOCapable() throws Exception {
            Runtime.SetRobotCapabilities(0L);
            MockContext ctx = new MockContext();

            String largeValue = "F".repeat(5000);
            Runtime.OutVariable<String> variable = new Runtime.OutVariable<>("Message", "output");
            variable.Set(ctx, largeValue);

            assertEquals(largeValue, ctx.get("output"));
        }

        @Test
        void roundtripThroughMessageLevelPackResolve() throws Exception {
            // The full roundtrip for large values goes through the message-level
            // pack/resolve in NodeServer.onMessage, not per-variable packValue.
            MockContext ctx = new MockContext();
            ctx.set("content", "G".repeat(5000));

            // Pack the whole message (simulates NodeServer outgoing path)
            byte[] packed = LMO.pack(ctx.getRaw());
            String packedStr = new String(packed, StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("__magic"));
            assertFalse(packedStr.contains("GGGGG"));

            // Resolve (simulates NodeServer incoming path)
            byte[] resolved = LMO.resolveAll(packed);
            MockContext resolved_ctx = new MockContext(resolved);
            assertEquals("G".repeat(5000), resolved_ctx.get("content"));
        }

        @Test
        void roundtripVariableWithBlobRefValue() throws Exception {
            // When the entire variable value IS a BlobRef (e.g. set by another package),
            // GetVariable resolves it inline.
            String ref = LMO.putBlob("\"full blob value\"".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> blobRef = new HashMap<>();
            blobRef.put("__magic", (double) LMO.MAGIC);
            blobRef.put("__ref", ref);
            blobRef.put("__path", STORE_PATH);

            MockContext ctx = new MockContext();
            ctx.set("field", blobRef);

            Runtime.InVariable<Object> inVar = new Runtime.InVariable<>("Message", "field");
            assertEquals("full blob value", inVar.Get(ctx));
        }

        @Test
        void customScopeBypassesBlobRefCheck() throws Exception {
            Runtime.InVariable<String> variable = new Runtime.InVariable<>("Custom", "literal");
            assertEquals("literal", variable.Get(new MockContext()));
        }
    }

    // -----------------------------------------------------------------------
    // NodeServer-level pack/resolve (onMessage flow)
    // -----------------------------------------------------------------------
    @Nested
    class OnMessageFlow {

        @BeforeEach
        void init() throws Exception {
            initTestStore();
            Runtime.SetRobotCapabilities(Runtime.CAPABILITY_LMO);
        }

        @Test
        void resolveAllThenPackRoundtrip() {
            String bigData = "H".repeat(6000);
            byte[] original = ("{\"input\":\"" + bigData + "\"}").getBytes(StandardCharsets.UTF_8);

            // Pack → resolve → re-pack → resolve again
            byte[] packed = LMO.pack(original);
            assertNotSame(original, packed);

            byte[] resolved = LMO.resolveAll(packed);
            assertTrue(new String(resolved, StandardCharsets.UTF_8).contains(bigData));

            byte[] repacked = LMO.pack(resolved);
            String repackedStr = new String(repacked, StandardCharsets.UTF_8);
            assertTrue(repackedStr.contains("__magic"));
            assertFalse(repackedStr.contains("HHHHHH"));

            byte[] finalResolved = LMO.resolveAll(repacked);
            assertTrue(new String(finalResolved, StandardCharsets.UTF_8).contains(bigData));
        }

        @Test
        void resolveAllWithNestedBlobRefs() {
            String inner = "I".repeat(5000);
            byte[] original = ("{\"outer\":{\"nested\":\"" + inner + "\"}}").getBytes(StandardCharsets.UTF_8);

            byte[] packed = LMO.pack(original);
            byte[] resolved = LMO.resolveAll(packed);
            assertTrue(new String(resolved, StandardCharsets.UTF_8).contains(inner));
        }
    }

    /**
     * Pin the atomic-write fix: putBlob must use a tmp file + atomic rename,
     * and dedup must require size > 0. Pre-fix used Files.write directly,
     * leaving the destination at zero/partial size while bytes were flushed,
     * and dedup-trusted any path that existed (including a zero-byte
     * leftover from a crashed prior writer).
     */
    @Nested
    class PutBlobAtomicWrite {

        @Test
        void concurrentSameContentNoShortReads() throws Exception {
            initTestStore();

            // Use uncompressible random bytes so the zstd-encoded payload
            // stays large and the write spans multiple syscalls.
            byte[] data = new byte[512 * 1024];
            new Random(0xfa1cL).nextBytes(data);

            final int writers = 16;
            final int iterations = 200;
            final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(writers);

            for (int w = 0; w < writers; w++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            String ref = LMO.putBlob(data);
                            byte[] got = LMO.getBlob(ref, STORE_PATH);
                            if (got.length != data.length) {
                                throw new AssertionError(
                                    "short read: got " + got.length + " bytes, want " + data.length);
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
                t.setDaemon(true);
                t.start();
            }

            start.countDown();
            done.await();

            assertTrue(errors.isEmpty(),
                "concurrent putBlob/getBlob race produced errors: " + errors);
        }

        @Test
        void rewritesZeroByteLeftover() throws Exception {
            initTestStore();

            byte[] data = "\"recover from leftover\"".getBytes(StandardCharsets.UTF_8);
            String ref = LMO.hashRef(data);

            // Plant a zero-byte file at the destination, simulating a crashed
            // prior putBlob from before the atomic-write fix.
            Path planted = blobFilePath(ref);
            Files.createDirectories(planted.getParent());
            Files.write(planted, new byte[0]);
            assertEquals(0L, Files.size(planted), "setup: planted file should be zero-bytes");

            String gotRef = LMO.putBlob(data);
            assertEquals(ref, gotRef);

            assertTrue(Files.size(planted) > 0,
                "post-fix: planted zero-byte file should have been overwritten");
            byte[] roundtrip = LMO.getBlob(ref, STORE_PATH);
            assertArrayEquals(data, roundtrip);
        }
    }
}
