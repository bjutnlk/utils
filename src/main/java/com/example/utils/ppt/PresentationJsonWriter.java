package com.example.utils.ppt;

import com.example.utils.ppt.model.PresentationDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 把 {@link PresentationDocument} 渲染成 JSON 字符串。
 *
 * <p>独立成一个工具类，避免把 Jackson 的细节散布在业务代码里。
 */
public class PresentationJsonWriter {

    private final ObjectMapper objectMapper;

    public PresentationJsonWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** 输出格式化（带缩进）的 JSON 字符串。 */
    public String toJson(PresentationDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 PresentationDocument 为 JSON 失败", e);
        }
    }
}
