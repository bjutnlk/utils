package com.example.utils.ppt.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析后的单张幻灯片。
 *
 * <p>字段都是纯数据，序列化成 JSON 后就是一页的描述：
 * <ul>
 *   <li>{@link #pageNo} —— 页码，从 1 开始</li>
 *   <li>{@link #title} —— 当前页的标题（占位符为 Title 的文本）</li>
 *   <li>{@link #sectionTitle} —— 所属"章节"的大标题，根据章节封面页推断</li>
 *   <li>{@link #sectionCover} —— 是否为章节封面页（启发式：几乎只有标题）</li>
 *   <li>{@link #texts} —— 正文文本块列表（除标题以外的所有文字）</li>
 *   <li>{@link #tables} —— 当前页的表格列表</li>
 * </ul>
 */
public class SlidePage {

    private int pageNo;
    private String title;
    private String sectionTitle;
    private boolean sectionCover;
    private List<String> texts = new ArrayList<>();
    private List<SlideTable> tables = new ArrayList<>();

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public boolean isSectionCover() {
        return sectionCover;
    }

    public void setSectionCover(boolean sectionCover) {
        this.sectionCover = sectionCover;
    }

    public List<String> getTexts() {
        return texts;
    }

    public void setTexts(List<String> texts) {
        this.texts = (texts == null) ? new ArrayList<>() : texts;
    }

    public List<SlideTable> getTables() {
        return tables;
    }

    public void setTables(List<SlideTable> tables) {
        this.tables = (tables == null) ? new ArrayList<>() : tables;
    }
}
