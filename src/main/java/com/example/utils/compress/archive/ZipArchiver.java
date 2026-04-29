package com.example.utils.compress.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ZIP 解压 / 打包服务。
 *
 * <p>注册为 Spring Bean（{@link Service @Service}），运行时由 Spring 注入两个空接口：
 * <ul>
 *     <li>{@link ArchiveStorage}：云存储 download / upload，业务侧实现</li>
 *     <li>{@link ArchiveEntryRepository}：解压记录表的查询，业务侧实现</li>
 * </ul>
 *
 * <h3>解压</h3>
 * <pre>
 *   try (InputStream in = multipartFile.getInputStream()) {
 *       List&lt;ArchiveEntry&gt; entries = zipArchiver.extract("task-001", in);
 *       // 业务侧：对 FILE 节点上传云存储 → 回填 storageKey → 批量入库
 *   }
 * </pre>
 * 解压只产出 list，<b>不</b>访问数据库、<b>不</b>访问云存储；上传 / 入库由业务侧自己串。
 *
 * <h3>打包（一站式）</h3>
 * <pre>
 *   String newZipKey = zipArchiver.pack("task-001");
 * </pre>
 * 内部：按 taskId 查解压记录表 → 按每个 FILE 节点的 storageKey 从云存储拉字节
 * → 还原原 zip 结构（含嵌套 zip / 空目录）→ 把组装好的新 zip 上传云存储 →
 * 返回新 zip 的 storageKey。
 *
 * <h3>边界场景</h3>
 * 都由本类内部处理，调用方不用关心：
 * <ul>
 *     <li><b>嵌套 zip</b>（文件名以 ".zip" 结尾）：自动递归展开 / 重新组装</li>
 *     <li><b>空文件夹</b>：保留为 {@link EntryType#DIRECTORY} 节点，打包时还原</li>
 *     <li><b>不同目录重名</b>：每个条目的 fullPath 不同，互不冲突</li>
 *     <li><b>zip-slip</b>：路径里的 ".." / "./" 在解析阶段归一化，不会逃出根</li>
 *     <li><b>zip-bomb</b>：单文件最大字节数限制（默认 1GB）</li>
 *     <li><b>非 UTF-8 文件名</b>（Windows 资源管理器中文 zip 是 GBK）：可指定 charset</li>
 * </ul>
 */
@Service
public class ZipArchiver {

    private static final Logger log = LoggerFactory.getLogger(ZipArchiver.class);

    /** 嵌套 zip 路径分隔符，例如 "outer.zip!/inner.txt"。 */
    public static final String NESTED_SEPARATOR = "!/";

    /** 单文件最大字节数（默认 1GB），防 zip-bomb。 */
    private static final long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 1024L;

    /**
     * 解析 zip entry 名字用的默认字符集。
     *
     * <p>按 zip 规范，只有当 entry 的 general-purpose bit 11 置位时才强制 UTF-8，
     * 否则编码未定义。Linux / macOS / 7-Zip 压的 zip 基本都是 UTF-8；
     * <b>Windows 资源管理器</b>"发送到 → 压缩文件夹"压出来的中文名是 GBK。
     * 用 UTF-8 读 GBK 字节会抛 {@code ZipException: MALFORMED}。
     */
    private static final Charset DEFAULT_ENTRY_CHARSET = StandardCharsets.UTF_8;

    /** 重新打包好的 zip 上传时使用的 contentType。 */
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    /** 缓冲区大小。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private final ArchiveStorage storage;
    private final ArchiveEntryRepository repository;

    /**
     * Spring 构造器注入。非 Spring 工程也可以直接 new。
     */
    @Autowired
    public ZipArchiver(ArchiveStorage storage, ArchiveEntryRepository repository) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    // =================================================================
    //  解压（不访问 DB / 云存储）
    // =================================================================

    /**
     * 解压一个 zip 流，返回扁平的 {@link ArchiveEntry} 列表。
     *
     * <p>FILE 节点的字节内容会填到 {@link ArchiveEntry#getContent()}，调用方负责
     * 后续上传云存储（拿到 storageKey 写回 entry，再 set content(null) 释放内存）和
     * 调 {@link ArchiveEntryRepository#batchInsert(List)} 落表。本方法不访问 DB / 云存储。
     *
     * <p><b>流的关闭</b>：{@code zipInput} 由调用方关闭，本方法不关。
     *
     * @param taskId   任务 ID，会写到每个 entry 上
     * @param zipInput zip 字节流（根 zip）
     * @return 扁平条目列表，顺序满足"父目录在子节点之前"
     * @throws IOException 读取 zip 失败 / 触发 zip-bomb 防护等
     */
    public List<ArchiveEntry> extract(String taskId, InputStream zipInput) throws IOException {
        return extract(taskId, zipInput, DEFAULT_ENTRY_CHARSET, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * 指定 entry 名字字符集的解压重载，用于 Windows GBK 中文名 zip 等场景。
     */
    public List<ArchiveEntry> extract(String taskId, InputStream zipInput, Charset charset) throws IOException {
        return extract(taskId, zipInput, charset, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * 完整参数版本。
     *
     * @param charset     entry 名字字符集（UTF-8 / GBK / Shift_JIS 等）
     * @param maxFileSize 单文件解压后最大字节数，防 zip-bomb
     */
    public List<ArchiveEntry> extract(String taskId, InputStream zipInput,
                                      Charset charset, long maxFileSize) throws IOException {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId is required");
        }
        Objects.requireNonNull(zipInput, "zipInput");
        Objects.requireNonNull(charset, "charset");
        if (maxFileSize <= 0) {
            throw new IllegalArgumentException("maxFileSize must be > 0");
        }

        List<ArchiveEntry> result = new ArrayList<>();
        ExtractContext ctx = new ExtractContext(taskId, charset, maxFileSize, result);
        extractStream(zipInput, null, 0, "", ctx);
        log.info("[ZipArchiver] extract done, taskId={}, totalEntries={}", taskId, result.size());
        return result;
    }

    // =================================================================
    //  打包（一站式：查表 → 下载 → 组 zip → 上传 → 返回新 key）
    // =================================================================

    /**
     * 按任务号一站式打包：
     * <ol>
     *     <li>用 {@code taskId} 调 {@link ArchiveEntryRepository#findByTaskId(String)} 查出全部解压记录</li>
     *     <li>对每个 {@link EntryType#FILE} 节点调 {@link ArchiveStorage#download(String)}
     *         按 storageKey 拉取字节</li>
     *     <li>按解压记录表的树形结构（含嵌套 zip / 空目录）还原成新 zip</li>
     *     <li>把新 zip 调 {@link ArchiveStorage#upload(byte[], String, String)} 写回云存储</li>
     *     <li>返回新 zip 的 storageKey 给调用方</li>
     * </ol>
     *
     * <p>业务侧如果做过"原文件 → 处理后结果文件"的映射替换，只需要在调用本方法之前
     * <b>把 DB 里对应行的 {@code storage_key} 改成结果文件的 key</b>；本方法只负责
     * 忠实地按表里的指向把每个文件塞回 zip。
     *
     * @param taskId 任务 ID
     * @return 打包后的新 zip 在云存储里的对象 key
     * @throws IOException 找不到记录、zip 组装失败等
     */
    public String pack(String taskId) throws IOException {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId is required");
        }

        List<ArchiveEntry> entries = repository.findByTaskId(taskId);
        if (entries == null || entries.isEmpty()) {
            throw new IOException("no extract records found for taskId=" + taskId);
        }

        downloadAllFileContents(entries);

        byte[] zipBytes = packToBytes(entries, DEFAULT_ENTRY_CHARSET);

        String newZipName = taskId + ".zip";
        String newZipKey = storage.upload(zipBytes, newZipName, ZIP_CONTENT_TYPE);
        log.info("[ZipArchiver] pack done, taskId={}, totalEntries={}, zipBytes={}B, newKey={}",
                taskId, entries.size(), zipBytes.length, newZipKey);
        return newZipKey;
    }

    /**
     * 对所有 {@link EntryType#FILE} 节点按 storageKey 从云存储拉字节，回填到
     * {@link ArchiveEntry#setContent(byte[])}，供后续打包使用。
     *
     * <p>逐条下载，简单清晰；如果业务侧需要并发或 batch，可在 {@link ArchiveStorage}
     * 实现里自己优化（例如内置一个连接池 + 异步并发）。
     */
    private void downloadAllFileContents(List<ArchiveEntry> entries) {
        for (ArchiveEntry entry : entries) {
            if (entry.getEntryType() != EntryType.FILE) {
                continue;
            }
            String key = entry.getStorageKey();
            if (key == null || key.isEmpty()) {
                throw new IllegalStateException("FILE entry has no storageKey, fullPath="
                        + entry.getFullPath() + " (taskId=" + entry.getTaskId() + ")");
            }
            byte[] data = storage.download(key);
            if (data == null) {
                throw new IllegalStateException("storage.download returned null, key=" + key);
            }
            entry.setContent(data);
        }
    }

    /** 把已经填好 content 的 entries 打成 zip 字节，封装一层 try-with-resources 释放 zos。 */
    private static byte[] packToBytes(List<ArchiveEntry> entries, Charset charset) throws IOException {
        Tree tree = Tree.build(entries);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, charset)) {
            writeChildren(zos, tree.rootChildren(), "", tree, charset);
        }
        return baos.toByteArray();
    }

    // =================================================================
    //  解压：递归核心
    // =================================================================

    /**
     * 递归解压一个 zip 流。
     *
     * @param in         当前 zip 的输入流，不关闭
     * @param parentPath 当前 zip 容器的 fullPath；根 zip 为 null
     * @param depth      当前 zip 内条目的深度
     * @param pathPrefix 当前层的路径前缀（根 zip 为空串，嵌套 zip 为 "outer.zip!/"）
     */
    private static void extractStream(InputStream in,
                                      String parentPath,
                                      int depth,
                                      String pathPrefix,
                                      ExtractContext ctx) throws IOException {
        ZipInputStream zis = new ZipInputStream(in, ctx.charset);

        // 本层已经产出过的目录：fullPath -> 对应节点
        // 既防止显式 "dir/" entry 与隐含推导出的目录重复落 list，又便于 O(1) 取父节点深度
        Map<String, ArchiveEntry> dirsInThisLevel = new HashMap<>();

        ZipEntry raw;
        while ((raw = zis.getNextEntry()) != null) {
            String relativePath = normalizePath(raw.getName());
            if (relativePath.isEmpty()) {
                zis.closeEntry();
                continue;
            }

            if (raw.isDirectory()) {
                ensureDirectoryChain(relativePath, parentPath, depth, pathPrefix,
                        dirsInThisLevel, ctx);
                zis.closeEntry();
                continue;
            }

            // 文件：先把中间目录链补齐，再产出文件节点本身
            String parentRelativeDir = parentOf(relativePath);
            String immediateParentPath = parentPath;
            int fileDepth = depth;
            if (!parentRelativeDir.isEmpty()) {
                ArchiveEntry lastDir = ensureDirectoryChain(parentRelativeDir, parentPath, depth,
                        pathPrefix, dirsInThisLevel, ctx);
                immediateParentPath = lastDir.getFullPath();
                fileDepth = lastDir.getDepth() + 1;
            }

            String entryName = baseName(relativePath);
            String fullPath = pathPrefix + relativePath;
            byte[] bytes = readAllBytes(zis, ctx.maxFileSize);

            if (isNestedZip(entryName)) {
                ArchiveEntry nested = buildEntry(ctx.taskId, entryName, fullPath,
                        immediateParentPath, EntryType.NESTED_ZIP, fileDepth, 0L, null);
                ctx.result.add(nested);

                try (InputStream nestedIn = new ByteArrayInputStream(bytes)) {
                    extractStream(nestedIn, fullPath, 0, fullPath + NESTED_SEPARATOR, ctx);
                }
            } else {
                ArchiveEntry file = buildEntry(ctx.taskId, entryName, fullPath,
                        immediateParentPath, EntryType.FILE, fileDepth, bytes.length, bytes);
                ctx.result.add(file);
            }
            zis.closeEntry();
        }
    }

    /**
     * 确保 {@code relativeDirPath}（形如 "a/b/c"）对应的整条目录链都已经在 result 里。
     * 缺失的按从上到下的顺序补齐，已存在的复用；返回最深那一级目录节点。
     *
     * <p>{@code dirsInThisLevel} 只维护"当前 zip 层内"的目录缓存，嵌套 zip 会在递归
     * 进去时创建一个新的 map，不污染外层。
     */
    private static ArchiveEntry ensureDirectoryChain(String relativeDirPath,
                                                     String rootParentPath,
                                                     int rootDepth,
                                                     String pathPrefix,
                                                     Map<String, ArchiveEntry> dirsInThisLevel,
                                                     ExtractContext ctx) {
        String[] parts = relativeDirPath.split("/");
        String currentParentPath = rootParentPath;
        int currentDepth = rootDepth;
        StringBuilder relativeAcc = new StringBuilder();
        ArchiveEntry lastDir = null;

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (relativeAcc.length() > 0) {
                relativeAcc.append('/');
            }
            relativeAcc.append(part);
            String fullPath = pathPrefix + relativeAcc;

            ArchiveEntry existing = dirsInThisLevel.get(fullPath);
            if (existing != null) {
                lastDir = existing;
                currentParentPath = fullPath;
                currentDepth = existing.getDepth() + 1;
                continue;
            }

            ArchiveEntry dir = buildEntry(ctx.taskId, part, fullPath, currentParentPath,
                    EntryType.DIRECTORY, currentDepth, 0L, null);
            ctx.result.add(dir);
            dirsInThisLevel.put(fullPath, dir);

            lastDir = dir;
            currentParentPath = fullPath;
            currentDepth++;
        }
        return lastDir;
    }

    private static ArchiveEntry buildEntry(String taskId, String name, String fullPath,
                                           String parentPath, EntryType type,
                                           int depth, long size, byte[] content) {
        ArchiveEntry e = new ArchiveEntry();
        e.setTaskId(taskId);
        e.setEntryName(name);
        e.setFullPath(fullPath);
        e.setParentPath(parentPath);
        e.setEntryType(type);
        e.setDepth(depth);
        e.setSize(size);
        e.setContent(content);
        return e;
    }

    // =================================================================
    //  打包：递归核心
    // =================================================================

    /** 把当前层的节点依次写进 {@code zos}。 */
    private static void writeChildren(ZipOutputStream zos,
                                      List<ArchiveEntry> children,
                                      String pathPrefix,
                                      Tree tree,
                                      Charset charset) throws IOException {
        for (ArchiveEntry node : children) {
            switch (node.getEntryType()) {
                case DIRECTORY:
                    writeDirectory(zos, node, pathPrefix, tree, charset);
                    break;
                case FILE:
                    writeFile(zos, node, pathPrefix);
                    break;
                case NESTED_ZIP:
                    writeNestedZip(zos, node, pathPrefix, tree, charset);
                    break;
                default:
                    throw new IOException("unknown entry type: " + node.getEntryType());
            }
        }
    }

    private static void writeDirectory(ZipOutputStream zos, ArchiveEntry node,
                                       String pathPrefix, Tree tree, Charset charset) throws IOException {
        String dirEntryName = pathPrefix + node.getEntryName() + "/";
        zos.putNextEntry(new ZipEntry(dirEntryName));
        zos.closeEntry();

        List<ArchiveEntry> kids = tree.childrenOf(node.getFullPath());
        if (!kids.isEmpty()) {
            writeChildren(zos, kids, dirEntryName, tree, charset);
        }
    }

    private static void writeFile(ZipOutputStream zos, ArchiveEntry node, String pathPrefix) throws IOException {
        byte[] content = node.getContent();
        if (content == null) {
            throw new IOException("FILE entry has no content, fullPath=" + node.getFullPath()
                    + "; expected to be filled by storage.download(storageKey)");
        }
        zos.putNextEntry(new ZipEntry(pathPrefix + node.getEntryName()));
        zos.write(content);
        zos.closeEntry();
    }

    private static void writeNestedZip(ZipOutputStream zos, ArchiveEntry node,
                                       String pathPrefix, Tree tree, Charset charset) throws IOException {
        List<ArchiveEntry> kids = tree.childrenOf(node.getFullPath());
        byte[] nestedZipBytes = packSubtree(kids, tree, charset);
        zos.putNextEntry(new ZipEntry(pathPrefix + node.getEntryName()));
        zos.write(nestedZipBytes);
        zos.closeEntry();
    }

    private static byte[] packSubtree(List<ArchiveEntry> children, Tree tree, Charset charset) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, charset)) {
            writeChildren(zos, children, "", tree, charset);
        }
        return baos.toByteArray();
    }

    // =================================================================
    //  路径 / IO 工具
    // =================================================================

    /**
     * 规范化 zip entry 路径：
     * <ul>
     *     <li>统一分隔符为 "/"</li>
     *     <li>去掉前导 "/"</li>
     *     <li>消除 "./" 与 "../"，防 zip-slip</li>
     *     <li>不保留末尾 "/"，类型语义由 {@link ZipEntry#isDirectory()} 判断</li>
     * </ul>
     */
    private static String normalizePath(String rawName) {
        String p = rawName.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        ArrayList<String> stack = new ArrayList<>();
        for (String part : p.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        return String.join("/", stack);
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
        if (name == null) {
            return false;
        }
        int n = name.length();
        return n >= 4 && name.regionMatches(true, n - 4, ".zip", 0, 4);
    }

    private static byte[] readAllBytes(ZipInputStream zis, long maxFileSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0L;
        int n;
        while ((n = zis.read(buf)) != -1) {
            total += n;
            if (total > maxFileSize) {
                throw new IOException("zip entry too large (> " + maxFileSize + " bytes), possible zip bomb");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // =================================================================
    //  内部辅助
    // =================================================================

    /** 解压过程中共享的可变上下文，避免到处传参。 */
    private static final class ExtractContext {
        final String taskId;
        final Charset charset;
        final long maxFileSize;
        final List<ArchiveEntry> result;

        ExtractContext(String taskId, Charset charset, long maxFileSize, List<ArchiveEntry> result) {
            this.taskId = taskId;
            this.charset = charset;
            this.maxFileSize = maxFileSize;
            this.result = result;
        }
    }

    /**
     * 把扁平 list 按 parentPath / fullPath 还原成树；只关心层级关系，同层条目不排序。
     */
    private static final class Tree {
        private final List<ArchiveEntry> roots = new ArrayList<>();
        private final Map<String, List<ArchiveEntry>> byParent = new HashMap<>();

        static Tree build(List<ArchiveEntry> entries) {
            Tree tree = new Tree();
            for (ArchiveEntry e : entries) {
                String pp = e.getParentPath();
                if (pp == null) {
                    tree.roots.add(e);
                } else {
                    tree.byParent.computeIfAbsent(pp, k -> new ArrayList<>()).add(e);
                }
            }
            return tree;
        }

        List<ArchiveEntry> rootChildren() {
            return roots;
        }

        List<ArchiveEntry> childrenOf(String fullPath) {
            List<ArchiveEntry> list = byParent.get(fullPath);
            return list == null ? Collections.emptyList() : list;
        }
    }
}
