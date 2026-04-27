package com.example.utils.ppt;

import com.example.utils.ppt.model.QaItem;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * 把"问题-原因"列表写成 .et 文件。
 *
 * <p>WPS 表格的 .et 文件本质上兼容 Excel 二进制格式（HSSF / .xls），
 * 因此这里直接用 Apache POI 的 {@link HSSFWorkbook} 生成内容，
 * 调用方只需要把输出流的扩展名命名成 ".et" 即可被 WPS 表格识别。
 *
 * <p>表格格式：
 * <pre>
 * | 页码 | 问题 | 原因 |
 * </pre>
 */
public class EtWriter {

    private static final String SHEET_NAME = "问题清单";
    private static final String[] HEADERS = {"页码", "问题", "原因"};

    /**
     * 把传入的 QA 列表写到 outputStream 中（.et 文件内容）。
     *
     * <p>本方法不会关闭 outputStream。
     */
    public void write(List<QaItem> items, OutputStream outputStream) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("outputStream 不能为 null");
        }

        try (Workbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            writeHeaderRow(workbook, sheet);
            writeDataRows(sheet, items);
            autoSizeColumns(sheet, HEADERS.length);

            workbook.write(outputStream);
        }
    }

    private void writeHeaderRow(Workbook workbook, Sheet sheet) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void writeDataRows(Sheet sheet, List<QaItem> items) {
        if (items == null) {
            return;
        }
        int rowIdx = 1;
        for (QaItem item : items) {
            if (item == null) {
                continue;
            }
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(item.getPageNo());
            row.createCell(1).setCellValue(safe(item.getQuestion()));
            row.createCell(2).setCellValue(safe(item.getReason()));
        }
    }

    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
