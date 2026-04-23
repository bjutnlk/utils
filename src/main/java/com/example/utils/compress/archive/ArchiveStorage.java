package com.example.utils.compress.archive;

import java.util.List;

/**
 * 归档文件的云存储接口（仅方法签名，<b>实现由业务工程自己提供</b>）。
 *
 * <p>之所以单独抽一个接口，而不是复用已有的
 * {@link com.example.utils.storage.FileStorage FileStorage}：
 * <ul>
 *     <li>这里只关心"给一批 {@link ArchiveEntry} 做批量上传 / 下载"这一件事，
 *         单条流式 API 太细粒度；</li>
 *     <li>业务侧通常希望一个事务里一次把整包上传完，有些云存储 SDK 也支持 batch；</li>
 *     <li>底层仍然可以由实现类委托给 {@link com.example.utils.storage.FileStorage}。</li>
 * </ul>
 *
 * <h3>使用时机</h3>
 * <ol>
 *     <li>解压完拿到 {@code List<ArchiveEntry>} 后，对 {@link EntryType#FILE} 节点
 *         调 {@link #uploadAll(List)}，实现类内部把每个 entry 的 {@code content}
 *         上传到真实云存储，并回填 {@code storageKey}、清空 {@code content}。</li>
 *     <li>打包前按 taskId 从 DB 查出全部 entry 后，对 FILE 节点调
 *         {@link #downloadAll(List)}，实现类从云存储把字节拉回来回填到 {@code content}，
 *         再交给 {@link ZipArchiver#pack(List, java.io.OutputStream)}。</li>
 * </ol>
 *
 * <p>业务工程实现这个接口时可以对接 OSS / S3 / COS / OBS / MinIO 等任一云存储，
 * 本工程 <b>不提供</b>默认实现。
 */
public interface ArchiveStorage {

    /**
     * 批量上传。
     *
     * <p>实现要点（给实现者的约定）：
     * <ul>
     *     <li>只处理 {@link EntryType#FILE} 节点，其它类型直接跳过；</li>
     *     <li>上传成功后把对象 key 写回 {@link ArchiveEntry#setStorageKey(String)}；</li>
     *     <li>建议上传完毕后调用 {@link ArchiveEntry#setContent(byte[]) setContent(null)}
     *         及时释放内存；</li>
     *     <li>同一个 entry 多次调用本方法应是幂等的（可通过 {@code storageKey != null} 跳过）。</li>
     * </ul>
     *
     * @param entries 归档条目列表，方法内部原地修改 storageKey / content
     */
    void uploadAll(List<ArchiveEntry> entries);

    /**
     * 批量下载。按每个 FILE 节点的 {@link ArchiveEntry#getStorageKey()} 拉取字节，
     * 回填到 {@link ArchiveEntry#setContent(byte[])}，供打包使用。
     *
     * <p>DIRECTORY / NESTED_ZIP 节点直接跳过。
     *
     * @param entries 归档条目列表，方法内部原地回填 content
     */
    void downloadAll(List<ArchiveEntry> entries);
}
