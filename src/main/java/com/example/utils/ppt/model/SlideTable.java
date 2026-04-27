package com.example.utils.ppt.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个 PPT 页面里的一张表格。
 *
 * <p>结构非常朴素：用二维列表保存每个单元格的纯文本内容，
 * rows.get(i).get(j) 即表示第 i 行第 j 列的文字。
 * 不携带任何样式信息，方便直接序列化为 JSON。
 */
public class SlideTable {

    /** 表格的所有行；每行是一个由单元格文本组成的列表。 */
    private List<List<String>> rows = new ArrayList<>();

    public List<List<String>> getRows() {
        return rows;
    }

    public void setRows(List<List<String>> rows) {
        this.rows = (rows == null) ? new ArrayList<>() : rows;
    }

    /** 追加一行（便于解析时逐行写入）。 */
    public void addRow(List<String> row) {
        if (row == null) {
            this.rows.add(Collections.emptyList());
        } else {
            this.rows.add(row);
        }
    }

    /** 行数。 */
    public int getRowCount() {
        return rows.size();
    }

    /** 取最大列数（不同行的列数可能不同，取最大值方便后续渲染）。 */
    public int getMaxColumnCount() {
        int max = 0;
        for (List<String> row : rows) {
            if (row != null && row.size() > max) {
                max = row.size();
            }
        }
        return max;
    }
}
