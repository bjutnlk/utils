package com.example.utils.compress.archive;

import java.util.Objects;

/**
 * 归档条目：一个统一的 DTO，贯穿"解压 → 落库 → 云存储 → 查询 → 打包"全流程。
 *
 * <p>之所以只用一个类，而不是分"解压结果 DTO"和"数据库实体"两套：
 * <ul>
 *     <li>两者字段高度重合，如果分成两个类，上游每走一步都要做一次手工 copy，
 *         很容易漏字段。</li>
 *     <li>业务侧拿到解压结果后只需要做两件事：
 *         <ol>
 *             <li>对 {@link EntryType#FILE} 节点把 {@link #content} 上传云存储，
 *                 拿到 key 写回 {@link #storageKey}，同时把 {@code content} 置空节省内存；</li>
 *             <li>把整份 list 批量 insert 到 {@code zip_extract_record} 表。</li>
 *         </ol>
 *         两步都是"原地改字段"，不需要转换实体。</li>
 *     <li>打包时也是反过来：按 taskId 查出 list → 对 FILE 节点批量下载云存储填回
 *         {@link #content} → 直接喂给 {@code ZipArchiver.pack}。</li>
 * </ul>
 *
 * <h3>父子关系为什么用 {@link #parentPath} 而不是 {@code parentId}？</h3>
 * 解压阶段还没落库，没有自增 id。用路径表达父子关系可以让解压结果
 * "一次性批量 insert"，不需要分多个事务或者回填 id。
 *
 * <p>{@link #fullPath} 在同一个 taskId 内天然唯一（zip 规范决定），
 * 可以作为业务唯一键；{@link #parentPath} 就是 "fullPath 去掉最后一段"。
 *
 * <p>嵌套 zip 的路径分隔符为 "{@code !/}"，和常见 Java 工具（URL、war inside jar）的
 * 习惯一致，便于 debug。例如：
 * <pre>
 *   outer/pack/nested.zip         ← NESTED_ZIP
 *   outer/pack/nested.zip!/a.txt  ← 嵌套 zip 里的 FILE
 *   outer/pack/nested.zip!/sub    ← 嵌套 zip 里的 DIRECTORY
 * </pre>
 */
public class ArchiveEntry {

    /** 主键。解压阶段为 {@code null}，数据库插入时回填。 */
    private Long id;

    /** 任务 ID，一个 zip 对应一个 taskId，也是"打包"时的查询入口。 */
    private String taskId;

    /** 当前节点的层级名（单层，不含 "/"），例如 "readme.txt"。 */
    private String entryName;

    /** 当前节点相对根 zip 的完整路径，嵌套 zip 用 "!/" 分隔。同一 taskId 内唯一。 */
    private String fullPath;

    /** 父节点的 {@code fullPath}；根层节点为 {@code null}。 */
    private String parentPath;

    /** 类型：文件 / 目录 / 嵌套 zip。 */
    private EntryType entryType;

    /** 层级深度，根层节点为 0。仅用于排查 / 展示，不参与业务逻辑。 */
    private int depth;

    /**
     * 云存储对象 key。仅 {@link EntryType#FILE} 有值；
     * 解压刚产出时为 {@code null}，由业务侧上传云存储后回填。
     */
    private String storageKey;

    /** 文件字节数。非 FILE 类型为 0。 */
    private long size;

    /**
     * 仅 {@link EntryType#FILE} 持有：解压得到的原始字节内容。
     *
     * <p>生命周期很短：解压产出 → 业务侧上传云存储 → 立即置 {@code null}。
     * 一定 <b>不要</b> 把这个字段落库，它只是解压和上传云存储之间的内存载体。
     */
    private transient byte[] content;

    public ArchiveEntry() {
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

    public String getParentPath() {
        return parentPath;
    }

    public void setParentPath(String parentPath) {
        this.parentPath = parentPath;
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

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArchiveEntry)) return false;
        ArchiveEntry that = (ArchiveEntry) o;
        return Objects.equals(taskId, that.taskId) && Objects.equals(fullPath, that.fullPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, fullPath);
    }

    @Override
    public String toString() {
        return "ArchiveEntry{" +
                "taskId='" + taskId + '\'' +
                ", type=" + entryType +
                ", fullPath='" + fullPath + '\'' +
                ", size=" + size +
                ", storageKey='" + storageKey + '\'' +
                '}';
    }
}
