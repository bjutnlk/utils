package com.example.utils.compress.archive;

import java.time.Instant;
import java.util.Objects;

/**
 * 解压记录表实体。
 *
 * <p>一张表同时承载"zip 的目录树结构"和"叶子文件在云存储上的位置"。
 * 每一行代表原 zip 里的一个节点：文件 / 目录 / 嵌套 zip 容器。
 *
 * <p>表结构（逻辑视图，物理建表语句见 {@code README.md}）：
 * <pre>
 * zip_extract_record
 * ┌───────────────┬────────────────────────────────────────────────────────┐
 * │ 字段          │ 含义                                                   │
 * ├───────────────┼────────────────────────────────────────────────────────┤
 * │ id            │ 主键                                                   │
 * │ task_id       │ 任务 ID，一个 zip 一个 task_id，也是查询 / 打包的入口  │
 * │ parent_id     │ 父节点 id，根节点为 null                               │
 * │ entry_name    │ 当前层名字（单层，不含路径分隔符）                     │
 * │ full_path     │ 相对根 zip 的完整路径，嵌套 zip 用 "!/" 分隔           │
 * │ entry_type    │ FILE / DIRECTORY / NESTED_ZIP                          │
 * │ depth         │ 层级深度，根层下的节点 = 0                             │
 * │ sort_order    │ 同 parent 下的原始顺序，保证打包后顺序稳定             │
 * │ storage_key   │ 云存储对象 key；仅 FILE 有值                           │
 * │ size          │ 文件字节数；DIRECTORY / NESTED_ZIP 为 0                │
 * │ created_at    │ 创建时间                                               │
 * └───────────────┴────────────────────────────────────────────────────────┘
 * 索引建议：
 *   - (task_id)                ：按任务捞全部
 *   - (task_id, parent_id)     ：按父节点捞子节点
 *   - (task_id, full_path) UK  ：同任务内全路径唯一（天然解决"不同目录重名"）
 * </pre>
 *
 * <p>针对题目里常见的"边界情况"的处理约定：
 * <ul>
 *     <li><b>嵌套 zip</b>：展开为 {@link EntryType#NESTED_ZIP} 节点 + 其下子树。
 *         打包时由子树重新组装成一个 zip，再作为外层的一个 entry 写入。</li>
 *     <li><b>空文件夹</b>：落一行 {@link EntryType#DIRECTORY}。
 *         打包时显式 {@code putNextEntry} 一个 "dir/" 的目录条目还原。</li>
 *     <li><b>不同目录重名</b>：因为 {@code full_path} 不同，落表互不冲突；
 *         每个文件都有自己独立的 {@code storage_key}。</li>
 * </ul>
 */
public class ZipExtractRecord {

    private Long id;
    private String taskId;
    private Long parentId;
    private String entryName;
    private String fullPath;
    private EntryType entryType;
    private int depth;
    private int sortOrder;
    private String storageKey;
    private long size;
    private Instant createdAt;

    public ZipExtractRecord() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getEntryName() {
        return entryName;
    }

    public void setEntryName(String entryName) {
        this.entryName = entryName;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZipExtractRecord)) return false;
        ZipExtractRecord that = (ZipExtractRecord) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ZipExtractRecord{" +
                "id=" + id +
                ", taskId='" + taskId + '\'' +
                ", parentId=" + parentId +
                ", fullPath='" + fullPath + '\'' +
                ", entryType=" + entryType +
                ", storageKey='" + storageKey + '\'' +
                ", size=" + size +
                '}';
    }
}
