package com.example.utils.compress.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.utils.storage.MockCloudFileStorage;
import com.example.utils.storage.ObjectMetadata;

class ZipExtractAndPackServiceTest {

    private MockCloudFileStorage storage;
    private InMemoryZipExtractRecordRepository repository;
    private ZipExtractService extractService;
    private ZipPackService packService;

    @BeforeEach
    void setUp() {
        storage = new MockCloudFileStorage();
        repository = new InMemoryZipExtractRecordRepository();
        extractService = new ZipExtractService(storage, repository);
        packService = new ZipPackService(storage, repository);
    }

    // ------------------------------------------------------------------
    //  场景 1: 扁平 zip: 2 个文件
    // ------------------------------------------------------------------
    @Test
    @DisplayName("扁平 zip：解压 → 打包，内容保持不变")
    void flatZip_roundTrip() throws Exception {
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("a.txt", "hello".getBytes(StandardCharsets.UTF_8));
        src.put("b.json", "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        byte[] originalZip = makeZip(src);

        try (InputStream in = new ByteArrayInputStream(originalZip)) {
            ZipExtractService.ExtractResult r = extractService.extract("task-flat", in);
            assertEquals(2, r.fileCount());
            assertEquals(0, r.directoryCount());
            assertEquals(0, r.nestedZipCount());
        }

        byte[] repacked = packService.packToBytes("task-flat");
        assertZipContentEqualsIgnoringOrder(src, readZip(repacked));
    }

    // ------------------------------------------------------------------
    //  场景 2: 带空目录和嵌套目录
    // ------------------------------------------------------------------
    @Test
    @DisplayName("空目录必须被保留；嵌套目录结构完整还原")
    void emptyAndNestedDirectories_preserved() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("docs/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("docs/readme.txt"));
            zos.write("hi".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("empty-folder/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("a/b/c/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("a/b/c/deep.txt"));
            zos.write("deep".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (InputStream in = new ByteArrayInputStream(baos.toByteArray())) {
            extractService.extract("task-dir", in);
        }

        byte[] repacked = packService.packToBytes("task-dir");
        Map<String, byte[]> entries = readZipWithDirs(repacked);
        assertTrue(entries.containsKey("docs/"));
        assertTrue(entries.containsKey("docs/readme.txt"));
        assertTrue(entries.containsKey("empty-folder/"));
        assertTrue(entries.containsKey("a/b/c/"));
        assertTrue(entries.containsKey("a/b/c/deep.txt"));
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), entries.get("docs/readme.txt"));
        assertArrayEquals("deep".getBytes(StandardCharsets.UTF_8), entries.get("a/b/c/deep.txt"));
    }

    // ------------------------------------------------------------------
    //  场景 3: 不同目录下的重名文件
    // ------------------------------------------------------------------
    @Test
    @DisplayName("不同目录同名文件互不影响，两个独立的 storageKey")
    void duplicateNamesInDifferentDirs_handledIndependently() throws Exception {
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("dirA/same.txt", "A".getBytes(StandardCharsets.UTF_8));
        src.put("dirB/same.txt", "B".getBytes(StandardCharsets.UTF_8));
        src.put("dirC/sub/same.txt", "C".getBytes(StandardCharsets.UTF_8));
        byte[] originalZip = makeZip(src);

        try (InputStream in = new ByteArrayInputStream(originalZip)) {
            extractService.extract("task-dup", in);
        }

        List<ZipExtractRecord> fileRecords = repository.findByTaskId("task-dup").stream()
                .filter(r -> r.getEntryType() == EntryType.FILE)
                .collect(Collectors.toList());
        assertEquals(3, fileRecords.size());
        long distinctKeys = fileRecords.stream().map(ZipExtractRecord::getStorageKey).distinct().count();
        assertEquals(3, distinctKeys, "three duplicate-named files must map to 3 distinct storage keys");

        byte[] repacked = packService.packToBytes("task-dup");
        assertZipContentEqualsIgnoringOrder(src, readZip(repacked));
    }

    // ------------------------------------------------------------------
    //  场景 4: 嵌套 zip (二层嵌套)
    // ------------------------------------------------------------------
    @Test
    @DisplayName("嵌套 zip：识别为 NESTED_ZIP 节点，打包时原样还原")
    void nestedZip_roundTrip() throws Exception {
        Map<String, byte[]> innerFiles = new LinkedHashMap<>();
        innerFiles.put("inner-a.txt", "INNER-A".getBytes(StandardCharsets.UTF_8));
        innerFiles.put("sub/inner-b.txt", "INNER-B".getBytes(StandardCharsets.UTF_8));
        byte[] innerZip = makeZip(innerFiles);

        Map<String, byte[]> outerFiles = new LinkedHashMap<>();
        outerFiles.put("root.txt", "ROOT".getBytes(StandardCharsets.UTF_8));
        outerFiles.put("pack/nested.zip", innerZip);
        outerFiles.put("pack/sibling.txt", "SIBLING".getBytes(StandardCharsets.UTF_8));
        byte[] outerZip = makeZip(outerFiles);

        try (InputStream in = new ByteArrayInputStream(outerZip)) {
            ZipExtractService.ExtractResult r = extractService.extract("task-nested", in);
            assertEquals(1, r.nestedZipCount(), "exactly one nested zip detected");
            assertTrue(r.fileCount() >= 4, "root.txt + sibling.txt + inner-a.txt + inner-b.txt");
        }

        ZipExtractRecord nested = repository.findByTaskId("task-nested").stream()
                .filter(rec -> rec.getEntryType() == EntryType.NESTED_ZIP)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("nested.zip", nested.getEntryName());
        assertNull(nested.getStorageKey(), "NESTED_ZIP must not occupy cloud storage");
        assertTrue(nested.getFullPath().endsWith("pack/nested.zip"));

        ZipExtractRecord innerB = repository.findByTaskId("task-nested").stream()
                .filter(rec -> rec.getEntryType() == EntryType.FILE)
                .filter(rec -> rec.getFullPath().endsWith("!/sub/inner-b.txt"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("inner-b.txt", innerB.getEntryName());
        assertNotNull(innerB.getStorageKey());

        byte[] repacked = packService.packToBytes("task-nested");
        Map<String, byte[]> outerRead = readZip(repacked);
        assertArrayEquals("ROOT".getBytes(StandardCharsets.UTF_8), outerRead.get("root.txt"));
        assertArrayEquals("SIBLING".getBytes(StandardCharsets.UTF_8), outerRead.get("pack/sibling.txt"));

        byte[] repackedNested = outerRead.get("pack/nested.zip");
        assertNotNull(repackedNested, "nested.zip must appear in outer zip");
        Map<String, byte[]> innerRead = readZip(repackedNested);
        assertArrayEquals("INNER-A".getBytes(StandardCharsets.UTF_8), innerRead.get("inner-a.txt"));
        assertArrayEquals("INNER-B".getBytes(StandardCharsets.UTF_8), innerRead.get("sub/inner-b.txt"));
    }

    // ------------------------------------------------------------------
    //  场景 5: "解压 → 业务替换结果文件 → 打包" 典型使用姿势
    // ------------------------------------------------------------------
    @Test
    @DisplayName("替换 storageKey 指向结果文件后，打包会包含处理后的内容")
    void replaceStorageKey_packWithProcessedContent() throws Exception {
        Map<String, byte[]> src = new LinkedHashMap<>();
        src.put("report.txt", "raw".getBytes(StandardCharsets.UTF_8));
        src.put("folder/data.txt", "raw-data".getBytes(StandardCharsets.UTF_8));
        byte[] originalZip = makeZip(src);

        try (InputStream in = new ByteArrayInputStream(originalZip)) {
            extractService.extract("task-replace", in);
        }

        // 上传两份"处理后的结果文件"到存储，再把 record.storageKey 指向它们
        // 这正是 user query 里描述的典型业务流：
        //   > 我有另外的表可以找到原文件和处理后文件的映射关系，
        //   > 只要你实现了打包，我自己走映射更换文件即可
        String processedKey1 = "processed/report-processed";
        String processedKey2 = "processed/data-processed";
        byte[] processed1 = "PROCESSED-REPORT".getBytes(StandardCharsets.UTF_8);
        byte[] processed2 = "PROCESSED-DATA".getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(processed1)) {
            storage.upload(processedKey1, in, ObjectMetadata.of(ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN, processed1.length));
        }
        try (InputStream in = new ByteArrayInputStream(processed2)) {
            storage.upload(processedKey2, in, ObjectMetadata.of(ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN, processed2.length));
        }

        for (ZipExtractRecord r : repository.findByTaskId("task-replace")) {
            if (r.getEntryType() != EntryType.FILE) continue;
            if (r.getFullPath().equals("report.txt")) {
                r.setStorageKey(processedKey1);
            } else if (r.getFullPath().equals("folder/data.txt")) {
                r.setStorageKey(processedKey2);
            }
        }

        byte[] repacked = packService.packToBytes("task-replace");
        Map<String, byte[]> read = readZip(repacked);
        assertArrayEquals(processed1, read.get("report.txt"));
        assertArrayEquals(processed2, read.get("folder/data.txt"));
    }

    // ------------------------------------------------------------------
    //  场景 6: 任务不存在 / 同 task 重复解压不互相污染
    // ------------------------------------------------------------------
    @Test
    @DisplayName("未知 taskId 打包报错；多任务共存互不干扰")
    void multiTask_isolation() throws Exception {
        Map<String, byte[]> s1 = new HashMap<>();
        s1.put("one.txt", "1".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> s2 = new HashMap<>();
        s2.put("two.txt", "2".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = new ByteArrayInputStream(makeZip(s1))) {
            extractService.extract("t1", in);
        }
        try (InputStream in = new ByteArrayInputStream(makeZip(s2))) {
            extractService.extract("t2", in);
        }

        byte[] z1 = packService.packToBytes("t1");
        byte[] z2 = packService.packToBytes("t2");
        assertEquals("1", new String(readZip(z1).get("one.txt"), StandardCharsets.UTF_8));
        assertEquals("2", new String(readZip(z2).get("two.txt"), StandardCharsets.UTF_8));
        assertFalse(readZip(z1).containsKey("two.txt"));

        assertThrows(IOException.class, () -> packService.packToBytes("does-not-exist"));
    }

    // ------------------------------------------------------------------
    //  场景 7: 防御 zip-slip，路径里含 ".." 不会写到父目录
    // ------------------------------------------------------------------
    @Test
    @DisplayName("路径里含 '..' 会被归一化，不会逃逸")
    void zipSlip_sanitized() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("safe/../evil.txt"));
            zos.write("evil".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        try (InputStream in = new ByteArrayInputStream(baos.toByteArray())) {
            extractService.extract("task-slip", in);
        }
        List<ZipExtractRecord> files = repository.findByTaskId("task-slip").stream()
                .filter(r -> r.getEntryType() == EntryType.FILE)
                .collect(Collectors.toList());
        assertEquals(1, files.size());
        assertEquals("evil.txt", files.get(0).getFullPath(),
                "safe/../evil.txt must be normalised to evil.txt under the root");
    }

    // ------------------------------------------------------------------
    //  工具方法
    // ------------------------------------------------------------------
    private static byte[] makeZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private static Map<String, byte[]> readZip(byte[] data) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
                result.put(e.getName(), baos.toByteArray());
                zis.closeEntry();
            }
        }
        return result;
    }

    /** 读 zip，包括目录 entry，目录的 value = 长度 0 的 byte[]。 */
    private static Map<String, byte[]> readZipWithDirs(byte[] data) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    result.put(e.getName(), new byte[0]);
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
                result.put(e.getName(), baos.toByteArray());
                zis.closeEntry();
            }
        }
        return result;
    }

    private static void assertZipContentEqualsIgnoringOrder(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.size(), actual.size(), () -> "entry count mismatch, expect " + expected.keySet() + " got " + actual.keySet());
        for (Map.Entry<String, byte[]> e : expected.entrySet()) {
            byte[] got = actual.get(e.getKey());
            assertNotNull(got, "missing entry: " + e.getKey());
            assertArrayEquals(e.getValue(), got, "content mismatch for " + e.getKey());
        }
    }
}
