package com.example.utils.compress.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * ZIP 解压 / 打包工具：纯内存处理，<b>不</b>依赖数据库、<b>不</b>依赖云存储。
 *
 * <p>上层业务典型流程：
 * <pre>
 *   // ===== 解压 =====
 *   List&lt;ArchiveEntry&gt; entries = ZipArchiver.extract(taskId, zipInputStream);
 *   // 1) 对每个 FILE entry 把 content 上传云存储，拿到 key 写回 storageKey，并清空 content
 *   // 2) 把整份 entries 批量 insert 到 zip_extract_record 表
 *
 *   // ===== 打包（业务侧更换 storageKey 指向"处理后的结果文件"后） =====
 *   List&lt;ArchiveEntry&gt; entries = repo.findByTaskId(taskId);
 *   // 对每个 FILE entry 从云存储下载字节 → 填到 entry.content
 *   ZipArchiver.pack(entries, outputStream);
 * </pre>
 *
 * <p>边界场景都由本类内部处理，调用方不需要关心：
 * <ul>
 *     <li><b>嵌套 zip</b>（文件名以 ".zip" 结尾）：自动识别并递归展开；
 *         打包时由子树重新组装成一份独立的 zip 再作为外层一个 entry 写入。</li>
 *     <li><b>空文件夹</b>：保留为 {@link EntryType#DIRECTORY} 节点，打包时还原。</li>
 *     <li><b>不同目录重名</b>：每个条目的 {@code fullPath} 不同，互不冲突。</li>
 *     <li><b>zip-slip</b>：路径里的 {@code ..} / {@code ./} 在解析阶段归一化，
 *         不会逃出根。</li>
 *     <li><b>zip-bomb</b>：单文件最大字节数限制（默认 1GB）。</li>
 * </ul>
 *
 * <p>输出顺序：
 * <ul>
 *     <li>解压结果按 zip 里 entry 的自然读取顺序返回；父目录必定出现在其子节点之前。</li>
 *     <li>打包时按传入 list 的顺序写入（除嵌套 zip 的子树会被单独递归打包）；
 *         不做额外排序 —— 只要保证"目录结构完整"即可，和用户需求一致。</li>
 * </ul>
 */
public final class ZipArchiver {

    /** 嵌套 zip 路径分隔符，例如 "outer.zip!/inner.txt"。 */
    public static final String NESTED_SEPARATOR = "!/";

    /** 单文件最大字节数（默认 1GB），防 zip-bomb。 */
    private static final long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 1024L;

    /**
     * 解析 zip entry 名字用的默认字符集。
     *
     * <p>按 zip 规范，只有当 entry 的 general-purpose bit 11 置位时才强制 UTF-8，
     * 否则编码未定义。实践中：
     * <ul>
     *     <li>Linux / macOS / 7-Zip / 各类编程语言压出来的 zip：基本都是 UTF-8，没问题。</li>
     *     <li><b>Windows 资源管理器</b> "发送到 → 压缩文件夹" 压出来的中文名：
     *         在简体中文系统上是 GBK，日文系统上是 Shift_JIS，等等。</li>
     * </ul>
     *
     * <p>用 UTF-8 读 GBK 的字节会抛 {@code ZipException: MALFORMED}，这时应通过
     * {@link #extract(String, InputStream, Charset, long)} 显式指定 GBK。
     */
    private static final Charset DEFAULT_ENTRY_CHARSET = StandardCharsets.UTF_8;

    /** 缓冲区大小。 */
    private static final int BUFFER_SIZE = 8 * 1024;

    private ZipArchiver() {
    }

    // =================================================================
    //  解压
    // =================================================================

    /**
     * 解压一个 zip 流，返回一份扁平的 {@link ArchiveEntry} 列表。
     *
     * <p>所有节点的 {@link ArchiveEntry#getTaskId() taskId} 都会被设置为入参 {@code taskId}；
     * FILE 节点的字节内容会填到 {@link ArchiveEntry#getContent()}，调用方自己决定
     * 什么时候上传云存储、什么时候置空这个字段。
     *
     * <p>默认用 UTF-8 解析 entry 名字。如果上游是 Windows 资源管理器压的包并且里面
     * 有中文名，请用 {@link #extract(String, InputStream, Charset)} 显式传
     * {@code Charset.forName("GBK")}，否则 JDK 会抛 {@code ZipException: MALFORMED}。
     *
     * <p><b>流的关闭</b>：{@code zipInput} 由调用方关闭，本方法不关。
     *
     * @param taskId    任务 ID，会写到每个 entry 上，非空
     * @param zipInput  zip 字节流（根 zip），非空
     * @return          扁平的条目列表，顺序满足"父目录在子节点之前"
     * @throws IOException 读取 zip 失败 / 触发 zip-bomb 防护等
     */
    public static List<ArchiveEntry> extract(String taskId, InputStream zipInput) throws IOException {
        return extract(taskId, zipInput, DEFAULT_ENTRY_CHARSET, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * 指定 entry 名字的字符集。用于兼容 Windows 资源管理器压缩的 GBK 中文名 zip
     * （默认 UTF-8 解析时会抛 {@code ZipException: MALFORMED}）。
     *
     * @param charset 常见取值：{@link StandardCharsets#UTF_8} /
     *                {@code Charset.forName("GBK")} / {@code Charset.forName("Shift_JIS")}
     */
    public static List<ArchiveEntry> extract(String taskId, InputStream zipInput, Charset charset) throws IOException {
        return extract(taskId, zipInput, charset, DEFAULT_MAX_FILE_SIZE);
    }

    /**
     * 完整参数版本。
     *
     * @param charset     entry 名字字符集，避免中文名报 MALFORMED
     * @param maxFileSize 单文件解压后最大字节数，防 zip-bomb
     */
    public static List<ArchiveEntry> extract(String taskId,
                                             InputStream zipInput,
                                             Charset charset,
                                             long maxFileSize) throws IOException {
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
        return result;
    }

    /**
     * 递归解压一个 zip 流。
     *
     * @param in          当前 zip 的输入流，不关闭
     * @param parentPath  当前 zip 容器的 fullPath；根 zip 为 null
     * @param depth       当前 zip 内条目的深度
     * @param pathPrefix  当前层的路径前缀（根 zip 为空串，嵌套 zip 为 "outer.zip!/"）
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
     * <p>{@code dirsInThisLevel} 只维护"当前 zip 层内"的目录缓存，嵌套 zip 会在递归进去时
     * 创建一个新的 map，不污染外层。
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
    //  打包
    // =================================================================

    /**
     * 把一组 {@link ArchiveEntry} 按原目录结构打包写入 {@code out}。
     *
     * <p>调用方职责：
     * <ul>
     *     <li>所有 {@link EntryType#FILE} 节点的 {@link ArchiveEntry#getContent()}
     *         必须已经填好（业务侧提前从云存储批量下载回填）；</li>
     *     <li>{@link EntryType#DIRECTORY} / {@link EntryType#NESTED_ZIP} 节点不需要 content。</li>
     * </ul>
     *
     * <p>父子关系通过每个 entry 的 {@link ArchiveEntry#getParentPath()} / {@link ArchiveEntry#getFullPath()}
     * 表达。传入列表的顺序不重要，本方法内部会按树形结构自行组织，但 <b>不做排序</b> ——
     * 只保证目录结构完整，不保证同层条目顺序和原 zip 一致。
     *
     * <p>entry 名字默认用 UTF-8 写出。如果下游解压方一定要 GBK（比如最终用户
     * 在老版本 Windows 上解压中文文件名 zip），用 {@link #pack(List, OutputStream, Charset)}
     * 显式指定。
     *
     * <p><b>流的关闭</b>：{@code out} 由调用方关闭，本方法只 {@code finish}。
     */
    public static void pack(List<ArchiveEntry> entries, OutputStream out) throws IOException {
        pack(entries, out, DEFAULT_ENTRY_CHARSET);
    }

    /**
     * 指定 entry 名字字符集的打包重载。
     *
     * @param charset 写入 entry 名字用的字符集，常见：UTF-8 / GBK
     */
    public static void pack(List<ArchiveEntry> entries, OutputStream out, Charset charset) throws IOException {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(charset, "charset");
        if (entries.isEmpty()) {
            throw new IOException("entries is empty, nothing to pack");
        }

        Tree tree = Tree.build(entries);
        ZipOutputStream zos = new ZipOutputStream(out, charset);
        writeChildren(zos, tree.rootChildren(), "", tree, charset);
        zos.finish();
    }

    /**
     * 把当前层的节点依次写进 {@code zos}。
     *
     * @param zos        当前层的 ZipOutputStream
     * @param children   当前层要写入的节点
     * @param pathPrefix 当前层相对 {@code zos} 根的路径前缀（末尾含 "/"，根层为 ""）
     * @param tree       整棵树，用于递归取子节点
     * @param charset    嵌套 zip 打包时沿用的字符集
     */
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

    private static void writeFile(ZipOutputStream zos, ArchiveEntry node,
                                  String pathPrefix) throws IOException {
        byte[] content = node.getContent();
        if (content == null) {
            throw new IOException("FILE entry has no content, fullPath=" + node.getFullPath()
                    + "; please download from storage and fill entry.content before pack()");
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
        ZipOutputStream zos = new ZipOutputStream(baos, charset);
        writeChildren(zos, children, "", tree, charset);
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    // =================================================================
    //  工具方法
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

    /**
     * 解压过程中共享的可变上下文，避免到处传参。
     */
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
     * 把扁平 list 按 parentPath / fullPath 还原成树。
     *
     * <p>打包时用，只关心层级关系，同层条目不排序 —— 用户需求明确"不需要排序"。
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
