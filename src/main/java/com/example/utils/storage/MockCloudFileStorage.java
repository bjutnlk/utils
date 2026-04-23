package com.example.utils.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link FileStorage} 的 mock 实现：使用内存 {@link ConcurrentHashMap} 模拟云存储 bucket。
 *
 * <p>仅用于开发 / 单元测试，真实实现可以替换为 OSS / S3 / COS / OBS / MinIO 的 SDK 调用。
 * 核心行为（资源关闭语义）与真实实现保持一致：
 * <ul>
 *     <li>upload：不关闭入参 {@code in}，只读到 EOF。</li>
 *     <li>download(key)：返回一个新的 {@link ByteArrayInputStream}，由调用方关闭。</li>
 *     <li>download(key, out)：不关闭入参 {@code out}，方法内部自己打开的流自己关。</li>
 * </ul>
 */
public class MockCloudFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(MockCloudFileStorage.class);

    private static final int BUFFER_SIZE = 8 * 1024;

    /** key -> 存储条目。 */
    private final ConcurrentMap<String, StoredObject> bucket = new ConcurrentHashMap<>();

    @Override
    public void upload(String key, InputStream in, ObjectMetadata metadata) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.getContentType() == null || metadata.getContentType().isEmpty()) {
            throw new IllegalArgumentException("metadata.contentType is required");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        byte[] data = baos.toByteArray();

        bucket.put(key, new StoredObject(data, metadata));
        log.info("[mock-cloud] upload ok, key={}, contentType={}, size={}B",
                key, metadata.getContentType(), data.length);
    }

    @Override
    public InputStream download(String key) throws IOException {
        Objects.requireNonNull(key, "key");
        StoredObject obj = bucket.get(key);
        if (obj == null) {
            throw new IOException("object not found: " + key);
        }
        log.info("[mock-cloud] download(stream) key={}, size={}B", key, obj.data.length);
        return new ByteArrayInputStream(obj.data);
    }

    @Override
    public void download(String key, OutputStream out) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(out, "out");
        StoredObject obj = bucket.get(key);
        if (obj == null) {
            throw new IOException("object not found: " + key);
        }
        out.write(obj.data);
        out.flush();
        log.info("[mock-cloud] download(toStream) key={}, size={}B", key, obj.data.length);
    }

    /** 仅供测试 / 调试使用。 */
    public boolean exists(String key) {
        return bucket.containsKey(key);
    }

    /** 仅供测试 / 调试使用。 */
    public ObjectMetadata getMetadata(String key) {
        StoredObject obj = bucket.get(key);
        return obj == null ? null : obj.metadata;
    }

    private static final class StoredObject {
        final byte[] data;
        final ObjectMetadata metadata;

        StoredObject(byte[] data, ObjectMetadata metadata) {
            this.data = data;
            this.metadata = metadata;
        }
    }
}
