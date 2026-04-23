package com.example.utils.storage;

import com.example.utils.compress.ZipUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCloudFileStorageTest {

    @Test
    void uploadAndDownloadAsStream() throws Exception {
        FileStorage storage = new MockCloudFileStorage();
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);

        try (InputStream in = new ByteArrayInputStream(payload)) {
            storage.upload("biz/a.txt", in,
                    ObjectMetadata.builder(ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN)
                            .contentLength(payload.length)
                            .addUserMetadata("bizId", "1001")
                            .build());
        }

        try (InputStream in = storage.download("biz/a.txt");
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            assertArrayEquals(payload, baos.toByteArray());
        }
    }

    @Test
    void downloadIntoOutputStream() throws Exception {
        FileStorage storage = new MockCloudFileStorage();
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        try (InputStream in = new ByteArrayInputStream(payload)) {
            storage.upload("bin/x", in,
                    ObjectMetadata.of(ObjectMetadata.CONTENT_TYPE_OCTET_STREAM, payload.length));
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            storage.download("bin/x", baos);
            assertArrayEquals(payload, baos.toByteArray());
        }
    }

    @Test
    void downloadMissingThrows() {
        FileStorage storage = new MockCloudFileStorage();
        assertThrows(java.io.IOException.class, () -> storage.download("not-exists"));
    }

    /** 演示"解压 + 统一返回后再遍历落表"的使用场景（这里落表用上传替代）。 */
    @Test
    void unzipThenBatchUpload() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "A".getBytes(StandardCharsets.UTF_8));
        entries.put("b.json", "{}".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
        ZipUtils.zip(entries, zipOut);

        Map<String, byte[]> unzipped =
                ZipUtils.unzipToMap(new ByteArrayInputStream(zipOut.toByteArray()));
        assertEquals(2, unzipped.size());

        MockCloudFileStorage storage = new MockCloudFileStorage();
        for (Map.Entry<String, byte[]> e : unzipped.entrySet()) {
            String ct = e.getKey().endsWith(".json")
                    ? ObjectMetadata.CONTENT_TYPE_JSON
                    : ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN;
            try (InputStream in = new ByteArrayInputStream(e.getValue())) {
                storage.upload("unzipped/" + e.getKey(), in,
                        ObjectMetadata.of(ct, e.getValue().length));
            }
        }

        assertTrue(storage.exists("unzipped/a.txt"));
        assertTrue(storage.exists("unzipped/b.json"));
        assertEquals(ObjectMetadata.CONTENT_TYPE_JSON,
                storage.getMetadata("unzipped/b.json").getContentType());
    }
}
