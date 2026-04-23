package com.example.utils.compress.archive;

/**
 * 归档条目的类型。
 *
 * <p>解压时产出的每个 {@link ArchiveEntry} 都会落到这三种类型之一；
 * 打包时也只根据这个字段决定如何写回 zip。
 */
public enum EntryType {

    /**
     * 普通文件。解压出来后 {@code content} 字段持有它的字节内容；
     * 落表后 {@code storageKey} 指向它在云存储的对象 key。
     *
     * <p>业务侧的"原文件 → 处理后结果文件"映射只替换这种节点的 {@code storageKey}。
     */
    FILE,

    /**
     * 目录。包括 zip 里显式声明的空目录、以及为了表达层级关系而产生的中间目录。
     *
     * <p>既不占云存储，也不持有字节内容；打包时写一个 {@code xxx/} 的目录 entry 还原。
     */
    DIRECTORY,

    /**
     * 嵌套 zip 容器。自身不上传云存储、不落字节，只是一个"标记节点"，
     * 表示"这里需要一个 zip 文件，它的内容由我的子节点重新组装"。
     *
     * <p>打包时由子树重新打包成一份独立的 zip，再作为外层的一个 entry 写入。
     */
    NESTED_ZIP
}
