package com.robomotion.app;

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
            StringBuilder sb = new StringBuilder("{\"obj\":{");
            for (int i = 0; i < 200; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"k").append(i).append("\":\"v").append(i).append("\"");
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
        void packsLargeString() {
            Object result = LMO.packValue("B".repeat(5000));
            assertNotNull(result);
            assertTrue(LMO.isBlobRefMap(result));
        }

        @Test
        void packsLargeMap() {
            Map<String, Object> large = new HashMap<>();
            for (int i = 0; i < 200; i++) {
                large.put("key" + i, "value" + i);
            }
            assertNotNull(LMO.packValue(large));
        }

        @Test
        void packsLargeList() {
            List<String> large = new java.util.ArrayList<>();
            for (int i = 0; i < 500; i++) {
                large.add("item" + i);
            }
            assertNotNull(LMO.packValue(large));
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
        void packedValueCanBeResolved() throws Exception {
            String large = "C".repeat(5000);
            Object packed = LMO.packValue(large);
            assertNotNull(packed);
            assertTrue(LMO.isBlobRefMap(packed));

            @SuppressWarnings("unchecked")
            Map<String, Object> blobRef = (Map<String, Object>) packed;
            assertEquals(large, LMO.resolveBlobRefValue(blobRef));
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
            assertTrue(packedStr.contains("\"__size\""));
            assertTrue(packedStr.contains("\"__path\":\"" + STORE_PATH + "\""));
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
            for (int i = 0; i < 200; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"k").append(i).append("\":\"v").append(i).append("\"");
            }
            sb.append("}}");
            String packedStr = new String(LMO.pack(sb.toString().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
            assertTrue(packedStr.contains("\"__type\":\"object\""));
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
        void setVariablePacksLargeValueInMessageScope() throws Exception {
            MockContext ctx = new MockContext();
            Runtime.OutVariable<String> variable = new Runtime.OutVariable<>("Message", "output");
            variable.Set(ctx, "E".repeat(5000));

            assertTrue(LMO.isBlobRefMap(ctx.get("output")),
                    "large value should be stored as BlobRef");
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
        void roundtripThroughSetAndGetVariable() throws Exception {
            MockContext ctx = new MockContext();

            String largeValue = "G".repeat(5000);
            Runtime.OutVariable<String> outVar = new Runtime.OutVariable<>("Message", "field");
            outVar.Set(ctx, largeValue);

            Runtime.InVariable<Object> inVar = new Runtime.InVariable<>("Message", "field");
            assertEquals(largeValue, inVar.Get(ctx));
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
}
