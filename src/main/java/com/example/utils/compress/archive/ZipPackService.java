package com.example.utils.compress.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.utils.storage.FileStorage;

/**
 * 打包服务：按 {@code taskId} 反查 {@link ZipExtractRecordRepository} 的全部记录，
 * 从 {@link FileStorage} 拉取每个叶子文件（可能已经被业务侧替换成"处理后的结果文件"），
 * 按原始 zip 的结构（含嵌套 zip、空目录）重新打成一个 zip。
 *
 * <p>约定：调用方如果做过"原文件 → 结果文件"的映射替换，只需要在调用前把表里对应记录的
 * {@code storageKey} 改成结果文件的 key；本服务只负责忠实地按表结构把文件塞回 zip。
 *
 * <p>输出：
 * <ul>
 *     <li>{@link #pack(String, OutputStream)}：直接写入调用方 OutputStream（推荐，流式）。</li>
 *     <li>{@link #packToBytes(String)}：打成字节数组返回（小 zip 场景用）。</li>
 * </ul>
 *
 * <p>注意事项：
 * <ul>
 *     <li>嵌套 zip 的子树会在内存里先打成 {@code byte[]} 再作为一个 entry 写入外层。
 *         如果嵌套 zip 内容特别大，可以考虑在外部做分级流式处理，但一般业务里嵌套 zip
 *         体积可控，这里优先简单可靠。</li>
 *     <li>{@code NESTED_ZIP} / {@code DIRECTORY} 不拉取云存储，不消耗网络流量。</li>
 *     <li>同 parent 下的条目按 {@link ZipExtractRecord#getSortOrder()} 稳定排序，
 *         保证多次打包结果顺序一致。</li>
 * </ul>
 */
public class ZipPackService {

    private static final Logger log = LoggerFactory.getLogger(ZipPackService.class);

    private static final int BUFFER_SIZE = 8 * 1024;

    private final FileStorage storage;
    private final ZipExtractRecordRepository repository;

    public ZipPackService(FileStorage storage, ZipExtractRecordRepository repository) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 按 taskId 打包并写入到调用方的 OutputStream。
     *
     * <p><b>注意</b>：{@code out} 由调用方关闭，方法内部只 {@code finish}。
     */
    public void pack(String taskId, OutputStream out) throws IOException {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId is required");
        }
        Objects.requireNonNull(out, "out");

        List<ZipExtractRecord> all = repository.findByTaskId(taskId);
        if (all.isEmpty()) {
            throw new IOException("no extract records for taskId: " + taskId);
        }

        Tree tree = buildTree(all);

        ZipOutputStream zos = new ZipOutputStream(out);
        writeChildren(zos, tree.roots, "", tree);
        zos.finish();
        log.info("[ZipPack] taskId={} totalRecords={} rootChildren={}",
                taskId, all.size(), tree.roots.size());
    }

    /** 打包为字节数组返回。 */
    public byte[] packToBytes(String taskId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pack(taskId, baos);
        return baos.toByteArray();
    }

    /**
     * 把当前层（children）依次写入 zos；遇到 NESTED_ZIP 就递归打一个子 zip 再以 entry 形式写入。
     *
     * @param zos       当前层的 ZipOutputStream
     * @param children  当前层要写入的节点集合（已按 sortOrder 排好）
     * @param pathPrefix 当前层相对 zos 根的路径前缀（末尾含 "/"，根层为 ""）
     * @param tree      整棵树
     */
    private void writeChildren(ZipOutputStream zos,
                               List<ZipExtractRecord> children,
                               String pathPrefix,
                               Tree tree) throws IOException {
        for (ZipExtractRecord node : children) {
            String name = node.getEntryName();
            List<ZipExtractRecord> kids = tree.childrenOf(node.getId());

            switch (node.getEntryType()) {
                case DIRECTORY: {
                    String dirEntryName = pathPrefix + name + "/";
                    // 目录 entry：无论是否有子节点都写一个，处理"空目录"；
                    // 非空目录写一个目录 entry 也是合法的，Zip 规范允许。
                    zos.putNextEntry(new ZipEntry(dirEntryName));
                    zos.closeEntry();
                    if (!kids.isEmpty()) {
                        writeChildren(zos, kids, dirEntryName, tree);
                    }
                    break;
                }
                case FILE: {
                    String fileEntryName = pathPrefix + name;
                    zos.putNextEntry(new ZipEntry(fileEntryName));
                    if (node.getStorageKey() == null) {
                        throw new IOException("FILE record has null storageKey, id=" + node.getId()
                                + ", path=" + node.getFullPath());
                    }
                    try (InputStream in = storage.download(node.getStorageKey())) {
                        copyStream(in, zos);
                    }
                    zos.closeEntry();
                    break;
                }
                case NESTED_ZIP: {
                    String nestedEntryName = pathPrefix + name;
                    // 把嵌套 zip 的子树独立打包，再作为外层一个 entry 写入
                    byte[] nestedZipBytes = packSubtreeToBytes(kids, tree);
                    zos.putNextEntry(new ZipEntry(nestedEntryName));
                    zos.write(nestedZipBytes);
                    zos.closeEntry();
                    break;
                }
                default:
                    throw new IOException("unknown entry type: " + node.getEntryType());
            }
        }
    }

    /** 把嵌套 zip 的子树打成一份独立的 zip 字节。 */
    private byte[] packSubtreeToBytes(List<ZipExtractRecord> children, Tree tree) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);
        writeChildren(zos, children, "", tree);
        zos.finish();
        zos.close();
        return baos.toByteArray();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    // ================================ 树结构 ================================

    /**
     * 把扁平的 {@link ZipExtractRecord} 列表还原成树。
     *
     * <p>为了在同层稳定排序，使用每个节点的 {@link ZipExtractRecord#getSortOrder()}；
     * 若 {@code sortOrder} 相等则 fall back 到 {@code id}。
     */
    private static Tree buildTree(List<ZipExtractRecord> all) {
        Tree tree = new Tree();
        Map<Long, List<ZipExtractRecord>> byParent = new HashMap<>();
        for (ZipExtractRecord r : all) {
            Long pid = r.getParentId();
            if (pid == null) {
                tree.roots.add(r);
            } else {
                byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(r);
            }
        }
        Comparator<ZipExtractRecord> cmp = Comparator
                .comparingInt(ZipExtractRecord::getSortOrder)
                .thenComparing(r -> r.getId() == null ? Long.MAX_VALUE : r.getId());
        tree.roots.sort(cmp);
        for (List<ZipExtractRecord> list : byParent.values()) {
            list.sort(cmp);
        }
        tree.byParent = byParent;
        return tree;
    }

    private static class Tree {
        List<ZipExtractRecord> roots = new ArrayList<>();
        Map<Long, List<ZipExtractRecord>> byParent = new HashMap<>();

        List<ZipExtractRecord> childrenOf(Long id) {
            List<ZipExtractRecord> list = byParent.get(id);
            return list == null ? java.util.Collections.emptyList() : list;
        }
    }
}
