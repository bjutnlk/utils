package com.example.utils.compress.archive;

/**
 * 归档文件的云存储接口（仅方法签名，<b>实现由业务工程自己提供</b>）。
 *
 * <p>可对接 OSS / S3 / COS / OBS / MinIO 等任一云存储，本工程不提供默认实现。
 * 实现类建议标注 {@link org.springframework.stereotype.Component @Component} /
 * {@link org.springframework.stereotype.Service @Service}，让 {@link ZipArchiver}
 * 直接注入；非 Spring 工程也可以手动 new 出来传给 {@link ZipArchiver} 的构造器。
 *
 * <h3>使用时机</h3>
 * <ul>
 *     <li><b>解压后</b>：业务侧遍历 {@link ZipArchiver#extract(String, java.io.InputStream)}
 *         返回的 list，对每个 {@link EntryType#FILE} 节点调 {@link #upload(byte[], String, String)}，
 *         拿到 storageKey 写回 {@link ArchiveEntry#setStorageKey(String)}，
 *         再统一批量落库。</li>
 *     <li><b>打包时</b>：{@link ZipArchiver#pack(String)} 自己会调 {@link #download(String)}
 *         按 storageKey 拉取每个文件，最后再调一次 {@link #upload(byte[], String, String)}
 *         把组装好的新 zip 也存进云存储，并返回它的 storageKey。</li>
 * </ul>
 */
public interface ArchiveStorage {

    /**
     * 按对象 key 下载二进制内容。
     *
     * <p>实现要求：
     * <ul>
     *     <li>如果 key 不存在，抛运行时异常 / 自定义业务异常即可，{@link ZipArchiver}
     *         不会捕获，会原样冒泡给调用方。</li>
     *     <li>不要返回 {@code null}（语义不清晰）。</li>
     * </ul>
     *
     * @param storageKey 云存储对象 key
     * @return 完整字节内容
     */
    byte[] download(String storageKey);

    /**
     * 上传字节内容到云存储。
     *
     * <p>实现要求：
     * <ul>
     *     <li>由实现类生成并返回真正的 storageKey（建议 UUID + 业务前缀，
     *         避免不同业务 / 不同任务的对象互相覆盖）。</li>
     *     <li>{@code originalName} 仅供日志和 contentType 推断使用；
     *         实现可以选择把它放进对象 metadata 里，便于浏览器下载时显示原名。</li>
     * </ul>
     *
     * @param data         待上传的字节内容
     * @param originalName 原始文件名（含扩展名），用于日志、contentType 推断、
     *                     云存储对象 metadata 的 "Content-Disposition" 等
     * @param contentType  MIME 类型，例如 {@code application/zip}；可为 {@code null}
     *                     由实现自行兜底（一般兜成 {@code application/octet-stream}）
     * @return 上传后云存储分配的对象 key
     */
    String upload(byte[] data, String originalName, String contentType);
}
