package com.example.utils.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件存储接口，底层可以对接任意一个云存储平台（OSS / S3 / COS / OBS / MinIO 等）。
 *
 * <p>设计要点：
 * <ul>
 *     <li>上传 / 下载全部使用流式 API，避免把整份文件读入内存，支持大文件。</li>
 *     <li>上传时必须带上 {@link ObjectMetadata}，由调用方指定 contentType、contentLength、
 *         以及可选的扩展元信息。字符、文档、zip、图片等本质上都只是 contentType 不同，
 *         接口不做类型感知，由调用方按业务语义指定。</li>
 *     <li>关于资源关闭（非常关键）：
 *         <ul>
 *             <li>上传时：{@code InputStream} 由 <b>调用方</b> 打开与关闭。实现类内部
 *                 只读取，不关闭入参流；调用方应使用 try-with-resources。</li>
 *             <li>下载时有两个重载：
 *                 <ul>
 *                     <li>{@link #download(String)} 返回 {@code InputStream}，这个流
 *                         由 <b>调用方</b> 负责关闭（推荐 try-with-resources）。</li>
 *                     <li>{@link #download(String, OutputStream)} 直接写入调用方的
 *                         {@code OutputStream}，方法内部 open 的网络连接 / 对象流由
 *                         实现类自己关闭；调用方只负责关闭自己传入的 {@code OutputStream}。</li>
 *                 </ul>
 *             </li>
 *         </ul>
 *     </li>
 *     <li>一般遵循"谁打开谁关闭"。</li>
 * </ul>
 */
public interface FileStorage {

    /**
     * 流式上传对象。
     *
     * @param key      对象在云存储上的 key（例如 "biz/2026/04/xxx.zip"）
     * @param in       待上传的输入流，<b>由调用方关闭</b>
     * @param metadata 对象元信息，必须包含 contentType；contentLength 建议提供
     *                 （部分云存储在未知 length 时会走分片上传）
     * @throws IOException 上传失败
     */
    void upload(String key, InputStream in, ObjectMetadata metadata) throws IOException;

    /**
     * 流式下载对象，返回输入流。
     *
     * <p><b>返回的 InputStream 必须由调用方关闭</b>，否则会泄漏底层网络连接。
     * 建议：
     * <pre>{@code
     * try (InputStream in = storage.download(key)) {
     *     // ... 读取 ...
     * }
     * }</pre>
     */
    InputStream download(String key) throws IOException;

    /**
     * 流式下载对象，直接写入调用方提供的 OutputStream。
     *
     * <p>方法内部打开的网络连接 / 对象流由实现类自己关闭；
     * 调用方只负责关闭自己传入的 {@code out}。
     *
     * @param key 对象 key
     * @param out 下载目标输出流，<b>由调用方关闭</b>
     */
    void download(String key, OutputStream out) throws IOException;
}
