package com.example.utils.ppt;

import com.example.utils.ppt.model.PresentationDocument;
import com.example.utils.ppt.model.QaItem;
import com.example.utils.ppt.model.SlidePage;
import com.example.utils.ppt.model.SlideTable;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptAnalysisServiceTest {

    @Test
    void parse_pptx_should_extract_pages_titles_texts_and_tables() throws Exception {
        byte[] pptxBytes = buildSamplePptx();

        PptAnalysisService service = new PptAnalysisService();
        PresentationDocument doc;
        try (ByteArrayInputStream in = new ByteArrayInputStream(pptxBytes)) {
            doc = service.parse(in);
        }

        assertEquals(3, doc.getTotalPages(), "总页数应为 3");

        SlidePage cover = doc.getPages().get(0);
        assertEquals(1, cover.getPageNo());
        assertEquals("第一章 概述", cover.getTitle());
        assertTrue(cover.isSectionCover(), "第 1 页只有标题，应被识别为章节封面");
        assertEquals("第一章 概述", cover.getSectionTitle());

        SlidePage content = doc.getPages().get(1);
        assertEquals(2, content.getPageNo());
        assertEquals("背景介绍", content.getTitle());
        // 同一个文本框内的多段会被合并为一条字符串（用换行分隔）。
        String body = String.join("\n", content.getTexts());
        assertTrue(body.contains("这是正文一行"));
        assertTrue(body.contains("这是正文第二行"));
        assertEquals("第一章 概述", content.getSectionTitle(),
                "正文页应继承上一张章节封面的标题");

        SlidePage tablePage = doc.getPages().get(2);
        assertEquals(1, tablePage.getTables().size());
        SlideTable table = tablePage.getTables().get(0);
        assertEquals(2, table.getRowCount());
        assertEquals(Arrays.asList("姓名", "年龄"), table.getRows().get(0));
        assertEquals(Arrays.asList("张三", "18"), table.getRows().get(1));

        // JSON 序列化能跑通且包含关键字段。
        String json = service.toJson(doc);
        assertNotNull(json);
        assertTrue(json.contains("\"pageNo\""));
        assertTrue(json.contains("\"sectionTitle\""));
        assertTrue(json.contains("背景介绍"));
        assertTrue(json.contains("张三"));
    }

    @Test
    void analyze_should_use_custom_llm_client_and_export_et() throws Exception {
        byte[] pptxBytes = buildSamplePptx();

        // 自定义一个"假"大模型，给每页固定生成一条问题记录。
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public List<QaItem> analyzePage(SlidePage page) {
                List<QaItem> list = new ArrayList<>();
                list.add(new QaItem(page.getPageNo(),
                        "Q-第" + page.getPageNo() + "页",
                        "R-第" + page.getPageNo() + "页"));
                return list;
            }
        };

        PptAnalysisService service = new PptAnalysisService(
                new PptParser(),
                new PresentationJsonWriter(),
                fakeLlm,
                new EtWriter());

        PresentationDocument doc;
        try (ByteArrayInputStream in = new ByteArrayInputStream(pptxBytes)) {
            doc = service.parse(in);
        }

        ByteArrayOutputStream etOut = new ByteArrayOutputStream();
        service.analyzeAndExportEt(doc, etOut);

        // 校验 .et 文件可被作为 .xls 解析，且内容正确。
        try (Workbook wb = new HSSFWorkbook(new ByteArrayInputStream(etOut.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("问题清单", sheet.getSheetName());

            Row header = sheet.getRow(0);
            assertEquals("页码", header.getCell(0).getStringCellValue());
            assertEquals("问题", header.getCell(1).getStringCellValue());
            assertEquals("原因", header.getCell(2).getStringCellValue());

            assertEquals(3, sheet.getLastRowNum(), "数据应有 3 行（每页一条）");
            for (int i = 1; i <= 3; i++) {
                Row row = sheet.getRow(i);
                assertEquals(i, (int) row.getCell(0).getNumericCellValue());
                assertEquals("Q-第" + i + "页", row.getCell(1).getStringCellValue());
                assertEquals("R-第" + i + "页", row.getCell(2).getStringCellValue());
            }
        }
    }

    /** 构造一个三页的 PPTX：封面页 / 正文页 / 表格页。 */
    private byte[] buildSamplePptx() throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            XSLFSlide page1 = ppt.createSlide();
            addTitle(page1, "第一章 概述");

            XSLFSlide page2 = ppt.createSlide();
            addTitle(page2, "背景介绍");
            addBody(page2, "这是正文一行\n这是正文第二行");

            XSLFSlide page3 = ppt.createSlide();
            addTitle(page3, "数据示例");
            XSLFTable table = page3.createTable(2, 2);
            table.setAnchor(new Rectangle(50, 200, 300, 100));
            XSLFTableRow row0 = table.getRows().get(0);
            row0.getCells().get(0).setText("姓名");
            row0.getCells().get(1).setText("年龄");
            XSLFTableRow row1 = table.getRows().get(1);
            row1.getCells().get(0).setText("张三");
            row1.getCells().get(1).setText("18");
            // POI 创建表格后实际单元格写入靠 setText
            XSLFTableCell cell00 = row0.getCells().get(0);
            cell00.clearText();
            cell00.setText("姓名");

            ppt.write(out);
            return out.toByteArray();
        }
    }

    /** 给一页加一个"标题占位符"文本。 */
    private void addTitle(XSLFSlide slide, String text) {
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(50, 50, 600, 50));
        titleBox.setPlaceholder(Placeholder.TITLE);
        titleBox.clearText();
        titleBox.setText(text);
    }

    /** 给一页加一个普通正文文本框（多行用 \n 分隔）。 */
    private void addBody(XSLFSlide slide, String text) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle(50, 120, 600, 200));
        box.clearText();
        for (String line : text.split("\n")) {
            box.addNewTextParagraph().addNewTextRun().setText(line);
        }
    }
}
