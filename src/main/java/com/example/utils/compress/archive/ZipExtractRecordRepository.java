package com.example.utils.compress.archive;

import java.util.List;

/**
 * 解压记录仓储接口。
 *
 * <p>工程里不强绑定具体 ORM，使用方可以用 MyBatis / JPA / JDBC 任意一种实现落地；
 * 本工程提供一个 {@link InMemoryZipExtractRecordRepository} 用于本地开发 / 单测。
 *
 * <p>注意：{@link #insert(ZipExtractRecord)} 必须在调用后回填 {@code id}，
 * 因为子节点的 {@code parentId} 会依赖父节点的自增主键。
 */
public interface ZipExtractRecordRepository {

    /**
     * 插入一条记录，实现类需回填自增主键到 {@code record.id}。
     */
    void insert(ZipExtractRecord record);

    /**
     * 按 taskId 查询全部记录，顺序不做保证（打包时会自行按 parent + sortOrder 重建）。
     */
    List<ZipExtractRecord> findByTaskId(String taskId);

    /**
     * 按 taskId 删除全部记录（重跑 / 失败清理时使用，可选实现）。
     */
    default int deleteByTaskId(String taskId) {
        throw new UnsupportedOperationException("deleteByTaskId not implemented");
    }
}
