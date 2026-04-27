package com.example.utils.ppt;

import com.example.utils.ppt.model.QaItem;
import com.example.utils.ppt.model.SlidePage;

import java.util.Collections;
import java.util.List;

/**
 * 大模型调用客户端。
 *
 * <p>这里只做"占位"实现：真实接入时可改写 {@link #analyzePage(SlidePage)} 内部，
 * 例如改成 HTTP 调用或者 SDK 调用。
 *
 * <p>约定：传入一页的结构化数据，返回大模型识别出的"问题-原因"列表。
 * 一页可以返回 0 条、1 条或多条。返回格式后续可继续调整：
 * 调用方只关心 {@link QaItem} 三个字段（pageNo / question / reason）。
 */
public class LlmClient {

    /**
     * 调用大模型分析一页内容，并返回识别出的 "问题 - 原因" 列表。
     *
     * <p>当前实现：直接返回空列表（占位）。
     * 真实接入时，这里需要：
     * <ol>
     *   <li>把 {@code page} 拼成 prompt（可以直接序列化成 JSON 当输入）；</li>
     *   <li>调用大模型；</li>
     *   <li>把模型返回结构解析为若干个 {@link QaItem}（记得回填 pageNo）。</li>
     * </ol>
     */
    public List<QaItem> analyzePage(SlidePage page) {
        // TODO: 接入真实的大模型调用。
        return Collections.emptyList();
    }
}
