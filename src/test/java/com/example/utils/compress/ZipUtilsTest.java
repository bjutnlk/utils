package com.example.utils.compress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipUtilsTest {

    @Test
    void zipAndUnzipDirectory(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("sub"));
        Files.write(src.resolve("a.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("sub/b.txt"), "world".getBytes(StandardCharsets.UTF_8));

        File zipFile = tmp.resolve("out.zip").toFile();
        ZipUtils.zip(src.toFile(), zipFile);
        assertTrue(zipFile.exists() && zipFile.length() > 0);

        Path out = tmp.resolve("out");
        List<Path> files = ZipUtils.unzip(zipFile, out.toFile());
        assertEquals(2, files.size());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(out.resolve("src/a.txt")));
        assertArrayEquals("world".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(out.resolve("src/sub/b.txt")));
    }

    @Test
    void zipMapAndUnzipToMap() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "AAA".getBytes(StandardCharsets.UTF_8));
        entries.put("dir/b.json", "{\"k\":1}".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipUtils.zip(entries, baos);

        Map<String, byte[]> back = ZipUtils.unzipToMap(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(2, back.size());
        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), back.get("a.txt"));
        assertArrayEquals("{\"k\":1}".getBytes(StandardCharsets.UTF_8), back.get("dir/b.json"));
    }

    @Test
    void unzipDetectsZipSlip(@TempDir Path tmp) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipUtils.zip(entries, baos);

        assertThrows(java.io.IOException.class,
                () -> ZipUtils.unzip(new ByteArrayInputStream(baos.toByteArray()), tmp.toFile()));
    }
}
