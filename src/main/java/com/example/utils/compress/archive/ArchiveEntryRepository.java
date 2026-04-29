package com.example.utils.compress.archive;

import java.util.List;

/**
 * 归档条目的持久化接口（仅方法签名，<b>实现由业务工程自己提供</b>）。
 *
 * <p>业务侧可用 MyBatis / JPA / JDBC / 其它任一方案实现。实现类建议标注
 * {@link org.springframework.stereotype.Component @Component} /
 * {@link org.springframework.stereotype.Repository @Repository}，让
 * {@link ZipArchiver} 直接注入。
 *
 * <h3>表结构 {@code zip_extract_record}</h3>
 *
 * <p>用一张树形表同时表达"zip 的目录结构"和"每个文件在云存储的位置"。
 * 由于解压阶段还没有自增 id，父子关系 <b>用 full_path 而不是 parent_id 表达</b>，
 * 这样整个解压结果可以一次性批量插入，不需要回填 id。
 *
 * <pre>
 * CREATE TABLE zip_extract_record (
 *     id           BIGINT        PRIMARY KEY AUTO_INCREMENT,
 *     task_id      VARCHAR(64)   NOT NULL                  COMMENT '任务ID，一个zip一个taskId',
 *     entry_name   VARCHAR(255)  NOT NULL                  COMMENT '当前层名字，不含路径分隔符',
 *     full_path    VARCHAR(1024) NOT NULL                  COMMENT '相对根zip的完整路径，嵌套zip用"!/"分隔',
 *     parent_path  VARCHAR(1024) NULL                      COMMENT '父节点full_path；根层为NULL',
 *     entry_type   VARCHAR(16)   NOT NULL                  COMMENT 'FILE / DIRECTORY / NESTED_ZIP',
 *     depth        INT           NOT NULL DEFAULT 0        COMMENT '层级深度，根层=0',
 *     storage_key  VARCHAR(512)  NULL                      COMMENT '云存储对象key，仅FILE有值',
 *     size         BIGINT        NOT NULL DEFAULT 0        COMMENT '文件字节数',
 *     created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     UNIQUE KEY uk_task_path (task_id, full_path),
 *     KEY idx_task (task_id),
 *     KEY idx_task_parent (task_id, parent_path)
 * ) COMMENT 'zip解压记录，一个task对应一棵树';
 * </pre>
 *
 * <p>设计说明：
 * <ul>
 *     <li>{@code (task_id, full_path)} 唯一索引，天然处理"不同目录重名"。</li>
 *     <li>仅 {@link EntryType#FILE} 节点有 {@code storage_key}；
 *         {@code DIRECTORY} / {@code NESTED_ZIP} 为 {@code NULL}。</li>
 *     <li>嵌套 zip 用 {@code !/} 分隔（例：{@code pack/nested.zip!/inner/a.txt}）。</li>
 *     <li>不带排序字段：业务只要求目录结构完整，同层顺序不做承诺。</li>
 * </ul>
 */
public interface ArchiveEntryRepository {

    /**
     * 批量插入。建议在一个事务里完成整批；插入后是否回填 id 由实现决定。
     *
     * @param entries 解压产出的全部条目（含 FILE / DIRECTORY / NESTED_ZIP）
     */
    void batchInsert(List<ArchiveEntry> entries);

    /**
     * 按 taskId 查出全部条目，{@link ZipArchiver#pack(String)} 在打包时调用。
     * 返回顺序不做要求 —— 打包器内部按 {@code parentPath / fullPath} 还原树结构。
     *
     * @param taskId 任务ID
     * @return 条目列表，可能为空但不应为 null
     */
    List<ArchiveEntry> findByTaskId(String taskId);
}
