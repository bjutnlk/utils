package com.example.utils.compress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP 压缩 / 解压缩工具类（纯工具方法，不涉及任何存储/落表逻辑）。
 *
 * <p>能力：
 * <ul>
 *     <li>将若干文件 / 目录打包成 zip（磁盘路径 或 写入到指定 OutputStream）</li>
 *     <li>将内存里的若干条目 (name -> bytes) 打包成 zip</li>
 *     <li>解压 zip 到磁盘目录</li>
 *     <li>解压 zip 为内存映射 (entryName -> bytes)，方便上层拿到后再统一遍历做存储 / 落表</li>
 * </ul>
 *
 * <p>所有方法都不会在内部吞掉资源，凡是方法 <b>内部自己 open 的流</b>，方法内部负责关闭；
 * 凡是 <b>调用方传入的流</b>，调用方自己负责关闭（遵循"谁打开谁关闭"原则）。
 */
public final class ZipUtils {

    private static final Logger log = LoggerFactory.getLogger(ZipUtils.class);

    /** 默认缓冲区大小。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    /** zip bomb 防护：单条目最大解压后大小（默认 1GB，可按需调整）。 */
    private static final long MAX_ENTRY_SIZE = 1024L * 1024L * 1024L;

    private ZipUtils() {
    }

    // ================================ 压缩 ================================

    /**
     * 将一个文件或目录压缩到指定的 zip 文件。
     *
     * @param source  源文件或目录
     * @param zipFile 目标 zip 文件（父目录需存在或由方法自动创建）
     */
    public static void zip(File source, File zipFile) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(zipFile, "zipFile");
        if (!source.exists()) {
            throw new IOException("source not exists: " + source.getAbsolutePath());
        }

        File parent = zipFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create parent dir: " + parent.getAbsolutePath());
        }

        try (OutputStream fos = new FileOutputStream(zipFile);
             OutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            doZip(source, source.getName(), zos);
        }
    }

    /**
     * 将一个文件或目录压缩到指定的 OutputStream。
     *
     * <p><b>注意</b>：传入的 {@code out} 由调用方负责关闭，本方法只会 finish 但不会 close。
     */
    public static void zip(File source, OutputStream out) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(out, "out");
        if (!source.exists()) {
            throw new IOException("source not exists: " + source.getAbsolutePath());
        }
        ZipOutputStream zos = new ZipOutputStream(out);
        doZip(source, source.getName(), zos);
        zos.finish();
    }

    /**
     * 将内存里的若干条目 (entryName -> bytes) 打包成 zip 写入到 OutputStream。
     *
     * <p><b>注意</b>：传入的 {@code out} 由调用方负责关闭，本方法只会 finish 但不会 close。
     */
    public static void zip(Map<String, byte[]> entries, OutputStream out) throws IOException {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(out, "out");

        ZipOutputStream zos = new ZipOutputStream(out);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            byte[] data = e.getValue() == null ? new byte[0] : e.getValue();
            zos.putNextEntry(new ZipEntry(name));
            zos.write(data);
            zos.closeEntry();
        }
        zos.finish();
    }

    private static void doZip(File file, String entryName, ZipOutputStream zos) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                zos.putNextEntry(new ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/"));
                zos.closeEntry();
                return;
            }
            for (File child : children) {
                doZip(child, entryName + "/" + child.getName(), zos);
            }
            return;
        }

        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

    // ================================ 解压 ================================

    /**
     * 解压 zip 文件到指定目录（磁盘）。
     *
     * @return 解压出来的全部文件路径列表（不包含目录）
     */
    public static List<Path> unzip(File zipFile, File targetDir) throws IOException {
        Objects.requireNonNull(zipFile, "zipFile");
        Objects.requireNonNull(targetDir, "targetDir");
        if (!zipFile.exists()) {
            throw new IOException("zip not exists: " + zipFile.getAbsolutePath());
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("cannot create targetDir: " + targetDir.getAbsolutePath());
        }

        List<Path> result = new ArrayList<>();
        Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();

        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                Path outPath = resolveSafely(targetRoot, entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                    continue;
                }
                Path parent = outPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (InputStream in = zf.getInputStream(entry);
                     OutputStream out = new BufferedOutputStream(Files.newOutputStream(outPath))) {
                    copyWithLimit(in, out);
                }
                result.add(outPath);
            }
        }
        return result;
    }

    /**
     * 从 InputStream 读取 zip 内容并解压到目标目录。
     *
     * <p><b>注意</b>：传入的 {@code in} 由调用方负责关闭。
     */
    public static List<Path> unzip(InputStream in, File targetDir) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(targetDir, "targetDir");
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("cannot create targetDir: " + targetDir.getAbsolutePath());
        }

        List<Path> result = new ArrayList<>();
        Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            Path outPath = resolveSafely(targetRoot, entry.getName());
            if (entry.isDirectory()) {
                Files.createDirectories(outPath);
                zis.closeEntry();
                continue;
            }
            Path parent = outPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outPath))) {
                copyWithLimit(zis, out);
            }
            zis.closeEntry();
            result.add(outPath);
        }
        return result;
    }

    /**
     * 将 zip 解压为内存映射 (entryName -> bytes)。
     *
     * <p>适合"解压之后再统一遍历做存储 / 落表"的场景；不涉及任何数据落地。
     *
     * <p><b>注意</b>：传入的 {@code in} 由调用方负责关闭。
     */
    public static Map<String, byte[]> unzipToMap(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        Map<String, byte[]> result = new LinkedHashMap<>();

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                zis.closeEntry();
                continue;
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            copyWithLimit(zis, baos);
            result.put(entry.getName(), baos.toByteArray());
            zis.closeEntry();
        }
        return result;
    }

    // ================================ 私有辅助 ================================

    /** 防御 zip-slip：entryName 不能逃出 targetRoot。 */
    private static Path resolveSafely(Path targetRoot, String entryName) throws IOException {
        Path resolved = targetRoot.resolve(entryName).normalize();
        if (!resolved.startsWith(targetRoot)) {
            throw new IOException("illegal zip entry (zip slip detected): " + entryName);
        }
        return resolved;
    }

    /** 带 zip-bomb 防护的流拷贝。 */
    private static void copyWithLimit(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_ENTRY_SIZE) {
                throw new IOException("zip entry too large, possible zip bomb, size > " + MAX_ENTRY_SIZE);
            }
            out.write(buf, 0, n);
        }
    }

    // ================================ 便捷 API ================================

    /** 便捷方法：按字符串路径压缩。 */
    public static void zip(String sourcePath, String zipPath) throws IOException {
        zip(Paths.get(sourcePath).toFile(), Paths.get(zipPath).toFile());
    }

    /** 便捷方法：按字符串路径解压。 */
    public static List<Path> unzip(String zipPath, String targetDir) throws IOException {
        return unzip(Paths.get(zipPath).toFile(), Paths.get(targetDir).toFile());
    }
}
