package com.example.utils.ppt;

/**
 * 演示文稿的输入格式。
 *
 * <p>不同格式底层采用 Apache POI 的不同实现：
 * <ul>
 *   <li>{@link #PPTX} —— Microsoft PowerPoint 2007+ 的 OOXML 格式（.pptx），
 *       由 {@code XMLSlideShow}（XSLF）解析。</li>
 *   <li>{@link #DPS}  —— WPS 演示的二进制格式（.dps），与传统 .ppt 同为
 *       OLE2 复合文档，由 {@code HSLFSlideShow}（HSLF）解析。</li>
 * </ul>
 */
public enum PptFormat {

    /** .pptx —— XMLSlideShow / XSLF */
    PPTX,

    /** .dps  —— HSLFSlideShow / HSLF */
    DPS
}
