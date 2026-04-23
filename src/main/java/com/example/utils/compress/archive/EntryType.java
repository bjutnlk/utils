package com.example.utils.compress.archive;

/**
 * 解压记录的条目类型。
 *
 * <ul>
 *     <li>{@link #FILE}：普通文件。会把内容上传到云存储，{@code storageKey} 必填。</li>
 *     <li>{@link #DIRECTORY}：目录（可能为空）。不上传云存储，{@code storageKey} 为 {@code null}。
 *         用于还原空文件夹。</li>
 *     <li>{@link #NESTED_ZIP}：嵌套 zip 容器。解压时递归展开，其内部条目作为子节点记录；
 *         打包时由子节点重新打成一个 zip，再作为外层 zip 的一个 entry 写入。
 *         嵌套 zip 本身 <b>不上传</b>云存储（{@code storageKey = null}），
 *         因为子节点内容（及其可能被替换后的结果文件）才是真相。</li>
 * </ul>
 */
public enum EntryType {
    FILE,
    DIRECTORY,
    NESTED_ZIP
}
