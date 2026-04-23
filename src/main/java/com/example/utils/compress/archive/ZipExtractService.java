package com.example.utils.compress.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.utils.storage.FileStorage;
import com.example.utils.storage.ObjectMetadata;

/**
 * 解压服务：传入一个 zip 的 InputStream + 任务 ID，把 zip 里的每个文件上传到云存储
 * 并在 {@link ZipExtractRecordRepository} 里落一行结构记录，最终一个任务对应一棵树。
 *
 * <p>支持的边界情况：
 * <ul>
 *     <li><b>嵌套 zip</b>：识别后递归展开，落一行 {@link EntryType#NESTED_ZIP} 容器 +
 *         其下子树；嵌套 zip 自身不上传云存储。</li>
 *     <li><b>空文件夹</b>：落一行 {@link EntryType#DIRECTORY}。</li>
 *     <li><b>不同目录重名</b>：每个节点的 {@code fullPath} 不同，天然不冲突；
 *         每个文件单独分配一个 UUID 型 {@code storageKey}。</li>
 *     <li><b>zip-slip / zip-bomb</b>：入参不落盘，纯字节流处理，天然无 zip-slip 风险；
 *         zip-bomb 由 {@link #maxFileSize} 限制单文件解压大小。</li>
 * </ul>
 *
 * <p>本服务不做事务控制。如果上游需要"解压失败全部回滚"，请在外层包事务 + 调用
 * {@link ZipExtractRecordRepository#deleteByTaskId(String)}，同时对已上传的云存储对象
 * 做补偿删除（或接受 GC 最终清理）。
 */
public class ZipExtractService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractService.class);

    /** 单文件最大字节数（默认 1GB），防 zip-bomb。 */
    private static final long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 1024L;

    /** 拷贝缓冲区。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private final FileStorage storage;
    private final ZipExtractRecordRepository repository;
    private final StorageKeyGenerator keyGenerator;
    private final long maxFileSize;

    public ZipExtractService(FileStorage storage,
                             ZipExtractRecordRepository repository) {
        this(storage, repository, StorageKeyGenerator.DEFAULT, DEFAULT_MAX_FILE_SIZE);
    }

    public ZipExtractService(FileStorage storage,
                             ZipExtractRecordRepository repository,
                             StorageKeyGenerator keyGenerator,
                             long maxFileSize) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("maxFileSize must be > 0");
        }
        this.maxFileSize = maxFileSize;
    }

    /**
     * 解压一个 zip 并落表。
     *
     * <p><b>注意</b>：入参 {@code zipInput} 由调用方关闭（遵循"谁打开谁关闭"）。
     *
     * @param taskId   任务 ID，不能为空。后续打包会用这个 ID 反查全部记录。
     * @param zipInput zip 二进制输入流（根 zip）
     * @return 本次新插入的全部记录（已回填 id）
     */
    public ExtractResult extract(String taskId, InputStream zipInput) throws IOException {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId is required");
        }
        Objects.requireNonNull(zipInput, "zipInput");

        ExtractResult result = new ExtractResult(taskId);
        extractStream(taskId, zipInput, null, 0, "", result);
        log.info("[ZipExtract] taskId={} totalRecords={} files={} dirs={} nestedZips={}",
                taskId, result.totalRecords(), result.fileCount(), result.directoryCount(), result.nestedZipCount());
        return result;
    }

    /**
     * 递归解压一个 zip 流。
     *
     * @param taskId   任务 ID
     * @param in       当前 zip 的输入流（调用方不关闭）
     * @param parentId 当前 zip 容器节点在表里的 id；根 zip 为 null
     * @param depth    当前 zip 内条目所在的深度
     * @param pathPrefix 前缀，比如 "outer.zip!/"，根 zip 为 ""
     * @param result   汇总结果
     */
    private void extractStream(String taskId,
                               InputStream in,
                               Long parentId,
                               int depth,
                               String pathPrefix,
                               ExtractResult result) throws IOException {
        ZipInputStream zis = new ZipInputStream(in);

        // 建一棵"父节点 id -> 路径 -> 目录 record"的映射，
        // 避免同一目录在 zip 里既有隐含（由 "a/b/c.txt" 推出来）又有显式 entry 时重复落库。
        Map<String, ZipExtractRecord> directoryCache = new HashMap<>();

        // 同 parent 下的 sort_order 递增计数
        Map<Long, Integer> sortCounters = new HashMap<>();

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String entryPath = normalizePath(entry.getName());
            if (entryPath.isEmpty()) {
                zis.closeEntry();
                continue;
            }

            if (entry.isDirectory()) {
                ensureDirectory(taskId, entryPath, parentId, depth, pathPrefix,
                        directoryCache, sortCounters, result);
                zis.closeEntry();
                continue;
            }

            // 为文件先确保所有父目录落库
            String parentDirPath = parentOf(entryPath);
            Long fileParentId = parentId;
            int fileDepth = depth;
            if (!parentDirPath.isEmpty()) {
                ZipExtractRecord dirRecord = ensureDirectory(taskId, parentDirPath, parentId, depth,
                        pathPrefix, directoryCache, sortCounters, result);
                fileParentId = dirRecord.getId();
                fileDepth = dirRecord.getDepth() + 1;
            }

            String name = baseName(entryPath);
            String fullPath = pathPrefix + entryPath;

            if (isNestedZip(name)) {
                byte[] bytes = readEntryBytes(zis);

                ZipExtractRecord nested = new ZipExtractRecord();
                nested.setTaskId(taskId);
                nested.setParentId(fileParentId);
                nested.setEntryName(name);
                nested.setFullPath(fullPath);
                nested.setEntryType(EntryType.NESTED_ZIP);
                nested.setDepth(fileDepth);
                nested.setSortOrder(nextSort(sortCounters, fileParentId));
                nested.setStorageKey(null);
                nested.setSize(0L);
                nested.setCreatedAt(Instant.now());
                repository.insert(nested);
                result.add(nested);

                try (InputStream nestedIn = new ByteArrayInputStream(bytes)) {
                    extractStream(taskId, nestedIn, nested.getId(), 0,
                            fullPath + "!/", result);
                }
            } else {
                String contentType = ContentTypeGuesser.guess(name);
                byte[] bytes = readEntryBytes(zis);

                String storageKey = keyGenerator.generate(taskId, fullPath, name);
                try (InputStream content = new ByteArrayInputStream(bytes)) {
                    storage.upload(storageKey, content,
                            ObjectMetadata.of(contentType, bytes.length));
                }

                ZipExtractRecord file = new ZipExtractRecord();
                file.setTaskId(taskId);
                file.setParentId(fileParentId);
                file.setEntryName(name);
                file.setFullPath(fullPath);
                file.setEntryType(EntryType.FILE);
                file.setDepth(fileDepth);
                file.setSortOrder(nextSort(sortCounters, fileParentId));
                file.setStorageKey(storageKey);
                file.setSize(bytes.length);
                file.setCreatedAt(Instant.now());
                repository.insert(file);
                result.add(file);
            }
            zis.closeEntry();
        }
    }

    /**
     * 确保给定路径 {@code dirPath}（如 "a/b/c"）对应的一串目录节点都在表里存在；
     * 返回最深那个目录节点。缺失的按顺序建立，已存在的（本次会话内）直接复用。
     */
    private ZipExtractRecord ensureDirectory(String taskId,
                                              String dirPath,
                                              Long rootParentId,
                                              int rootDepth,
                                              String pathPrefix,
                                              Map<String, ZipExtractRecord> cache,
                                              Map<Long, Integer> sortCounters,
                                              ExtractResult result) {
        ZipExtractRecord existing = cache.get(dirPath);
        if (existing != null) {
            return existing;
        }
        String[] parts = dirPath.split("/");
        Long parentId = rootParentId;
        int depth = rootDepth;
        StringBuilder acc = new StringBuilder();
        ZipExtractRecord last = null;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (acc.length() > 0) acc.append('/');
            acc.append(part);
            String accPath = acc.toString();

            ZipExtractRecord cached = cache.get(accPath);
            if (cached != null) {
                parentId = cached.getId();
                depth = cached.getDepth() + 1;
                last = cached;
                continue;
            }

            ZipExtractRecord dir = new ZipExtractRecord();
            dir.setTaskId(taskId);
            dir.setParentId(parentId);
            dir.setEntryName(part);
            dir.setFullPath(pathPrefix + accPath);
            dir.setEntryType(EntryType.DIRECTORY);
            dir.setDepth(depth);
            dir.setSortOrder(nextSort(sortCounters, parentId));
            dir.setStorageKey(null);
            dir.setSize(0L);
            dir.setCreatedAt(Instant.now());
            repository.insert(dir);
            result.add(dir);

            cache.put(accPath, dir);
            parentId = dir.getId();
            depth++;
            last = dir;
        }
        return last;
    }

    private int nextSort(Map<Long, Integer> sortCounters, Long parentId) {
        Integer cur = sortCounters.get(parentId);
        int next = (cur == null ? 0 : cur + 1);
        sortCounters.put(parentId, next);
        return next;
    }

    private byte[] readEntryBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0L;
        int n;
        while ((n = zis.read(buf)) != -1) {
            total += n;
            if (total > maxFileSize) {
                throw new IOException("entry too large (> " + maxFileSize + " bytes), possible zip bomb");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /** 统一用 "/"，去掉前导 "/"，解析 "./" 与 "../"。 */
    private static String normalizePath(String name) {
        String p = name.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);

        String[] parts = p.split("/");
        // 保留尾部的 "/" 语义（表示目录），以便调用方能识别
        boolean trailingSlash = p.endsWith("/");
        java.util.ArrayList<String> stack = new java.util.ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                continue;
            }
            stack.add(part);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        if (trailingSlash && sb.length() > 0) sb.append('/');
        // 目录返回时去掉末尾 "/"，让上层用 entry.isDirectory() 语义统一；
        // 这里保留 trailing slash 只为给调用方做参考，实际内部 ensureDirectory 会按段处理
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** 约定：后缀为 ".zip"（不区分大小写）即视为嵌套 zip。 */
    private static boolean isNestedZip(String name) {
        if (name == null) return false;
        int n = name.length();
        return n >= 4 && name.regionMatches(true, n - 4, ".zip", 0, 4);
    }

    /**
     * 解压结果汇总。
     */
    public static class ExtractResult {
        private final String taskId;
        private final LinkedHashMap<Long, ZipExtractRecord> records = new LinkedHashMap<>();
        private int fileCount;
        private int directoryCount;
        private int nestedZipCount;

        ExtractResult(String taskId) {
            this.taskId = taskId;
        }

        void add(ZipExtractRecord r) {
            records.put(r.getId(), r);
            switch (r.getEntryType()) {
                case FILE: fileCount++; break;
                case DIRECTORY: directoryCount++; break;
                case NESTED_ZIP: nestedZipCount++; break;
                default: break;
            }
        }

        public String getTaskId() {
            return taskId;
        }

        public java.util.Collection<ZipExtractRecord> getRecords() {
            return java.util.Collections.unmodifiableCollection(records.values());
        }

        public int totalRecords() {
            return records.size();
        }

        public int fileCount() {
            return fileCount;
        }

        public int directoryCount() {
            return directoryCount;
        }

        public int nestedZipCount() {
            return nestedZipCount;
        }
    }
}
