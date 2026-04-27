package com.example.utils.ppt.model;

/**
 * 大模型对单页分析后给出的一条 "问题 - 原因" 记录。
 *
 * <p>最终会被写到 .et 文件中，每行就是一条 QaItem。
 */
public class QaItem {

    /** 来源页码（可选；写表格时会作为额外列展示，方便溯源）。 */
    private int pageNo;

    /** 问题描述。 */
    private String question;

    /** 原因/根因描述。 */
    private String reason;

    public QaItem() {
    }

    public QaItem(int pageNo, String question, String reason) {
        this.pageNo = pageNo;
        this.question = question;
        this.reason = reason;
    }

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
