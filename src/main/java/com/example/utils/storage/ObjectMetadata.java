package com.example.utils.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传对象的元信息。上传时必须指定 {@link #getContentType()}，
 * 因为字符 / 文档 / zip / 图片等在云存储上表现只是 MIME 类型不同，
 * 接口侧不需要做类型感知——由调用方按业务语义指定。
 */
public class ObjectMetadata {

    /** 常见 content-type 常量，方便调用方直接使用。 */
    public static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    public static final String CONTENT_TYPE_TEXT_PLAIN   = "text/plain; charset=UTF-8";
    public static final String CONTENT_TYPE_JSON         = "application/json; charset=UTF-8";
    public static final String CONTENT_TYPE_ZIP          = "application/zip";
    public static final String CONTENT_TYPE_PDF          = "application/pdf";
    public static final String CONTENT_TYPE_PNG          = "image/png";
    public static final String CONTENT_TYPE_JPEG         = "image/jpeg";
    public static final String CONTENT_TYPE_DOCX         = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String CONTENT_TYPE_XLSX         = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final String contentType;
    private final long contentLength;
    private final Map<String, String> userMetadata;

    private ObjectMetadata(Builder b) {
        this.contentType = b.contentType;
        this.contentLength = b.contentLength;
        this.userMetadata = b.userMetadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(b.userMetadata));
    }

    public String getContentType() {
        return contentType;
    }

    /** 未知时返回 -1。 */
    public long getContentLength() {
        return contentLength;
    }

    public Map<String, String> getUserMetadata() {
        return userMetadata;
    }

    public static Builder builder(String contentType) {
        return new Builder(contentType);
    }

    /** 便捷构造。 */
    public static ObjectMetadata of(String contentType, long contentLength) {
        return builder(contentType).contentLength(contentLength).build();
    }

    public static class Builder {
        private final String contentType;
        private long contentLength = -1L;
        private Map<String, String> userMetadata;

        private Builder(String contentType) {
            if (contentType == null || contentType.isEmpty()) {
                throw new IllegalArgumentException("contentType is required");
            }
            this.contentType = contentType;
        }

        public Builder contentLength(long len) {
            this.contentLength = len;
            return this;
        }

        public Builder addUserMetadata(String k, String v) {
            if (this.userMetadata == null) {
                this.userMetadata = new LinkedHashMap<>();
            }
            this.userMetadata.put(k, v);
            return this;
        }

        public ObjectMetadata build() {
            return new ObjectMetadata(this);
        }
    }
}
