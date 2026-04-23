package com.example.utils.compress.archive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于内存的 {@link ZipExtractRecordRepository} 实现，线程安全。
 *
 * <p>仅用于本地开发与单测。生产环境请替换为 DB 实现。
 */
public class InMemoryZipExtractRecordRepository implements ZipExtractRecordRepository {

    private final AtomicLong idSeq = new AtomicLong(0L);

    private final ConcurrentHashMap<Long, ZipExtractRecord> store = new ConcurrentHashMap<>();

    @Override
    public void insert(ZipExtractRecord record) {
        long id = idSeq.incrementAndGet();
        record.setId(id);
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(Instant.now());
        }
        store.put(id, record);
    }

    @Override
    public List<ZipExtractRecord> findByTaskId(String taskId) {
        List<ZipExtractRecord> list = new ArrayList<>();
        for (ZipExtractRecord r : store.values()) {
            if (taskId.equals(r.getTaskId())) {
                list.add(r);
            }
        }
        return list;
    }

    @Override
    public int deleteByTaskId(String taskId) {
        int count = 0;
        for (ZipExtractRecord r : new ArrayList<>(store.values())) {
            if (taskId.equals(r.getTaskId())) {
                store.remove(r.getId());
                count++;
            }
        }
        return count;
    }
}
