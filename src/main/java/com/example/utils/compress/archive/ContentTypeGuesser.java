package com.example.utils.compress.archive;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.example.utils.storage.ObjectMetadata;

/**
 * 根据文件名猜 contentType，仅用于上传云存储时填 {@link ObjectMetadata#getContentType()}。
 *
 * <p>和业务侧对文件类型的真正识别无关，只是为了让下游 / 浏览器行为更合理，
 * 猜错也不会影响字节流内容。
 */
public final class ContentTypeGuesser {

    private static final Map<String, String> BY_EXT = new HashMap<>();

    static {
        BY_EXT.put("txt", ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN);
        BY_EXT.put("log", ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN);
        BY_EXT.put("md", ObjectMetadata.CONTENT_TYPE_TEXT_PLAIN);
        BY_EXT.put("csv", "text/csv; charset=UTF-8");
        BY_EXT.put("json", ObjectMetadata.CONTENT_TYPE_JSON);
        BY_EXT.put("xml", "application/xml; charset=UTF-8");
        BY_EXT.put("zip", ObjectMetadata.CONTENT_TYPE_ZIP);
        BY_EXT.put("pdf", ObjectMetadata.CONTENT_TYPE_PDF);
        BY_EXT.put("png", ObjectMetadata.CONTENT_TYPE_PNG);
        BY_EXT.put("jpg", ObjectMetadata.CONTENT_TYPE_JPEG);
        BY_EXT.put("jpeg", ObjectMetadata.CONTENT_TYPE_JPEG);
        BY_EXT.put("gif", "image/gif");
        BY_EXT.put("bmp", "image/bmp");
        BY_EXT.put("webp", "image/webp");
        BY_EXT.put("docx", ObjectMetadata.CONTENT_TYPE_DOCX);
        BY_EXT.put("xlsx", ObjectMetadata.CONTENT_TYPE_XLSX);
        BY_EXT.put("doc", "application/msword");
        BY_EXT.put("xls", "application/vnd.ms-excel");
        BY_EXT.put("ppt", "application/vnd.ms-powerpoint");
        BY_EXT.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    private ContentTypeGuesser() {
    }

    public static String guess(String fileName) {
        if (fileName == null) {
            return ObjectMetadata.CONTENT_TYPE_OCTET_STREAM;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return ObjectMetadata.CONTENT_TYPE_OCTET_STREAM;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXT.getOrDefault(ext, ObjectMetadata.CONTENT_TYPE_OCTET_STREAM);
    }
}
