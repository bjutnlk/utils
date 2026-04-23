package com.example.utils.compress.archive;

import java.util.UUID;

/**
 * 生成云存储对象 key 的策略。
 *
 * <p>默认按 {@code taskId + "/" + UUID + 扩展名} 生成；调用方可传入自定义策略。
 * 不直接用原始路径作为 key，原因：
 * <ul>
 *     <li>原始路径里可能包含空格 / 中文 / 特殊字符，部分云存储 key 命名规范很严。</li>
 *     <li>嵌套 zip 路径会包含 "!/"，在多数 SDK 里会被归一化，产生不可预期的行为。</li>
 *     <li>UUID 天然避免"不同目录重名"之间的碰撞。</li>
 * </ul>
 */
@FunctionalInterface
public interface StorageKeyGenerator {

    /**
     * @param taskId   任务 ID
     * @param fullPath 原始路径（含嵌套分隔 "!/"），仅用于提示，可忽略
     * @param entryName 原始文件名（用于提取后缀）
     * @return 云存储上的对象 key
     */
    String generate(String taskId, String fullPath, String entryName);

    /** 默认策略：{@code taskId + "/" + UUID + <后缀>}。 */
    StorageKeyGenerator DEFAULT = (taskId, fullPath, entryName) -> {
        String suffix = "";
        int dot = entryName == null ? -1 : entryName.lastIndexOf('.');
        if (dot >= 0 && dot < entryName.length() - 1) {
            suffix = entryName.substring(dot);
        }
        return taskId + "/" + UUID.randomUUID().toString().replace("-", "") + suffix;
    };
}
