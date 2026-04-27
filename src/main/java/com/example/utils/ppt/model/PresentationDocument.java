package com.example.utils.ppt.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 整篇 PPT 解析后的根结构。
 *
 * <p>它就是一个页面列表的容器。整体序列化为 JSON 后，
 * 调用方只要遍历 pages 即可拿到每一页的内容。
 */
public class PresentationDocument {

    /** 总页数（冗余字段，方便阅读 JSON）。 */
    private int totalPages;

    /** 所有页面，按顺序排列。 */
    private List<SlidePage> pages = new ArrayList<>();

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public List<SlidePage> getPages() {
        return pages;
    }

    public void setPages(List<SlidePage> pages) {
        this.pages = (pages == null) ? new ArrayList<>() : pages;
        this.totalPages = this.pages.size();
    }
}
