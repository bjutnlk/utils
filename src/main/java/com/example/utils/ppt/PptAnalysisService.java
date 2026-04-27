package com.example.utils.ppt;

import com.example.utils.ppt.model.PresentationDocument;
import com.example.utils.ppt.model.QaItem;
import com.example.utils.ppt.model.SlidePage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 整套流程的门面（Facade）。
 *
 * <p>典型调用：
 * <pre>{@code
 * PptAnalysisService service = new PptAnalysisService();
 *
 * try (InputStream pptIn = ...; OutputStream etOut = ...) {
 *     // 1. 解析得到 JSON
 *     PresentationDocument doc = service.parse(pptIn);
 *     String json = service.toJson(doc);
 *     // ↑ 这就是题目要求的"中间 JSON"，可以打印 / 保存 / 走业务逻辑
 *
 *     // 2. 遍历每页，调用大模型，并把结果写成 .et
 *     service.analyzeAndExportEt(doc, etOut);
 * }
 * }</pre>
 *
 * <p>你也可以单独使用每一步。
 */
public class PptAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PptAnalysisService.class);

    private final PptParser parser;
    private final PresentationJsonWriter jsonWriter;
    private final LlmClient llmClient;
    private final EtWriter etWriter;

    /** 默认依赖；适合大多数场景。 */
    public PptAnalysisService() {
        this(new PptParser(), new PresentationJsonWriter(), new LlmClient(), new EtWriter());
    }

    /** 全量自定义依赖（便于做单元测试或替换大模型实现）。 */
    public PptAnalysisService(PptParser parser,
                              PresentationJsonWriter jsonWriter,
                              LlmClient llmClient,
                              EtWriter etWriter) {
        this.parser = parser;
        this.jsonWriter = jsonWriter;
        this.llmClient = llmClient;
        this.etWriter = etWriter;
    }

    /** 把 PPT 流解析成结构化数据。 */
    public PresentationDocument parse(InputStream pptStream) throws IOException {
        return parser.parse(pptStream);
    }

    /** 把结构化数据序列化为 JSON 字符串。 */
    public String toJson(PresentationDocument document) {
        return jsonWriter.toJson(document);
    }

    /** 遍历每页，调用大模型，把所有页的结果聚合返回。 */
    public List<QaItem> analyze(PresentationDocument document) {
        List<QaItem> all = new ArrayList<>();
        if (document == null) {
            return all;
        }
        for (SlidePage page : document.getPages()) {
            List<QaItem> partial = llmClient.analyzePage(page);
            if (partial == null || partial.isEmpty()) {
                continue;
            }
            for (QaItem item : partial) {
                if (item == null) {
                    continue;
                }
                // 大模型如果没回填 pageNo，这里兜底补上。
                if (item.getPageNo() <= 0) {
                    item.setPageNo(page.getPageNo());
                }
                all.add(item);
            }
        }
        log.debug("大模型分析完成，共得到 {} 条问题记录", all.size());
        return all;
    }

    /** 把分析结果直接写到 .et 输出流中（一步到位）。 */
    public void analyzeAndExportEt(PresentationDocument document, OutputStream etOutputStream) throws IOException {
        List<QaItem> items = analyze(document);
        etWriter.write(items, etOutputStream);
    }
}
