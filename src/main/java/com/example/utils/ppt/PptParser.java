package com.example.utils.ppt;

import com.example.utils.ppt.model.PresentationDocument;
import com.example.utils.ppt.model.SlidePage;
import com.example.utils.ppt.model.SlideTable;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.SimpleShape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.TextRun;
import org.apache.poi.sl.usermodel.TextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PPT 解析器：把 PPT 输入流解析为 {@link PresentationDocument}。
 *
 * <p>设计目标是"可读性高、易理解"，因此故意写得啰嗦一些：
 * <ol>
 *   <li>使用 Apache POI 的通用接口 {@link SlideShow}，可同时兼容 .ppt 和 .pptx。</li>
 *   <li>每张幻灯片按形状（Shape）逐个遍历：
 *       <ul>
 *         <li>占位符为 Title 的 TextShape → 当前页标题；</li>
 *         <li>其他 TextShape → 加入正文 texts；</li>
 *         <li>TableShape → 提取为 {@link SlideTable}；</li>
 *         <li>GroupShape → 递归处理；</li>
 *         <li>图片等其它形状 → 暂时忽略。</li>
 *       </ul>
 *   </li>
 *   <li>章节大标题用一个简单启发式推断：当某页几乎只有标题（没有正文/表格），
 *       视为"章节封面页"，其后所有页都归到这个章节标题下，直到出现下一个章节封面页。</li>
 * </ol>
 */
public class PptParser {

    private static final Logger log = LoggerFactory.getLogger(PptParser.class);

    /**
     * 解析 PPT 输入流。
     *
     * <p>本方法不会关闭传入的流。
     *
     * @param pptStream .ppt 或 .pptx 文件的输入流
     * @return 解析得到的演示文稿数据结构
     * @throws IOException 读流或解析过程中失败
     */
    public PresentationDocument parse(InputStream pptStream) throws IOException {
        if (pptStream == null) {
            throw new IllegalArgumentException("pptStream 不能为 null");
        }

        PresentationDocument document = new PresentationDocument();
        List<SlidePage> pages = new ArrayList<>();

        // SlideShowFactory 会根据文件头自动识别 .ppt(HSLF) / .pptx(XSLF)。
        try (SlideShow<?, ?> slideShow = SlideShowFactory.create(pptStream)) {
            int pageNo = 0;
            String currentSectionTitle = null;

            for (Slide<?, ?> slide : slideShow.getSlides()) {
                pageNo++;
                SlidePage page = new SlidePage();
                page.setPageNo(pageNo);

                // 先尝试用 POI 提供的"标题"快捷方法；它会读取标题占位符的文本。
                String slideTitle = slide.getTitle();
                if (slideTitle != null && !slideTitle.trim().isEmpty()) {
                    page.setTitle(slideTitle.trim());
                }

                // 遍历当前页所有形状，把内容收集到 page 里。
                collectShapes(slide.getShapes(), page);

                // 判断是否为章节封面页：标题非空且没有任何正文 / 表格。
                boolean isSectionCover = isSectionCoverPage(page);
                page.setSectionCover(isSectionCover);

                if (isSectionCover) {
                    currentSectionTitle = page.getTitle();
                }
                page.setSectionTitle(currentSectionTitle);

                pages.add(page);
            }
        }

        document.setPages(pages);
        log.debug("PPT 解析完成，共 {} 页", document.getTotalPages());
        return document;
    }

    /** 遍历一组 Shape，把文本/表格收集到指定页中。GroupShape 会递归处理。 */
    private void collectShapes(Iterable<? extends Shape<?, ?>> shapes, SlidePage page) {
        for (Shape<?, ?> shape : shapes) {
            if (shape instanceof TableShape) {
                SlideTable table = extractTable((TableShape<?, ?>) shape);
                if (table.getRowCount() > 0) {
                    page.getTables().add(table);
                }
            } else if (shape instanceof TextShape) {
                handleTextShape((TextShape<?, ?>) shape, page);
            } else if (shape instanceof GroupShape) {
                // 组合形状里可能嵌套表格/文本，递归处理。
                collectShapes(((GroupShape<?, ?>) shape).getShapes(), page);
            }
            // 其它类型（图片、连接线等）按需求忽略。
        }
    }

    /** 处理一个文字形状：是标题占位符则填到 page.title，否则追加到 texts。 */
    private void handleTextShape(TextShape<?, ?> textShape, SlidePage page) {
        String text = readPlainText(textShape);
        if (text.isEmpty()) {
            return;
        }

        // 已经从 slide.getTitle() 拿到的标题会在这里被识别出来，避免重复加入正文。
        boolean alreadyTitle = page.getTitle() != null && page.getTitle().equals(text);

        if (isTitlePlaceholder(textShape) || alreadyTitle) {
            if (page.getTitle() == null || page.getTitle().isEmpty()) {
                page.setTitle(text);
            }
            // 标题不再被加入正文。
            return;
        }

        page.getTexts().add(text);
    }

    /** 把 TextShape 中所有段落和 run 拼接为带换行的纯文本。 */
    private String readPlainText(TextShape<?, ?> textShape) {
        StringBuilder sb = new StringBuilder();
        for (TextParagraph<?, ?, ?> paragraph : textShape) {
            StringBuilder line = new StringBuilder();
            for (TextRun run : paragraph.getTextRuns()) {
                String t = run.getRawText();
                if (t != null) {
                    line.append(t);
                }
            }
            String lineStr = line.toString().replace('\u000B', '\n').trim();
            if (!lineStr.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(lineStr);
            }
        }
        return sb.toString();
    }

    /** 判断一个 shape 是否处于"标题占位符"位置。 */
    private boolean isTitlePlaceholder(SimpleShape<?, ?> shape) {
        Placeholder placeholder = shape.getPlaceholder();
        if (placeholder == null) {
            return false;
        }
        return placeholder == Placeholder.TITLE
                || placeholder == Placeholder.CENTERED_TITLE;
    }

    /** 提取表格内容为二维字符串列表。 */
    private SlideTable extractTable(TableShape<?, ?> tableShape) {
        SlideTable table = new SlideTable();
        int rows = tableShape.getNumberOfRows();
        int cols = tableShape.getNumberOfColumns();
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>(cols);
            for (int c = 0; c < cols; c++) {
                TableCell<?, ?> cell = tableShape.getCell(r, c);
                row.add(cell == null ? "" : readPlainText(cell));
            }
            table.addRow(row);
        }
        return table;
    }

    /** 章节封面页判定：有标题、没有正文、也没有表格。 */
    private boolean isSectionCoverPage(SlidePage page) {
        boolean hasTitle = page.getTitle() != null && !page.getTitle().isEmpty();
        boolean hasBody = !page.getTexts().isEmpty();
        boolean hasTable = !page.getTables().isEmpty();
        return hasTitle && !hasBody && !hasTable;
    }
}
