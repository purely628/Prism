package com.prism.billing.ingest;

import com.prism.billing.entity.UsageRecord;
import com.prism.billing.mapper.UsageRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 批量落库：多行 INSERT 一次提交；
 * 极端场景（消息重复投递且幂等键已过期/多实例竞争）撞 uk_msg_id 唯一索引时，
 * 降级为逐条 insert 并忽略 DuplicateKey——重复消息被唯一索引静默吸收。
 * doBatch/doSingle 为 protected，供单测以子类桩替换验证降级逻辑。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MybatisUsageRecordStore implements UsageRecordStore {

    private final UsageRecordMapper usageRecordMapper;

    @Override
    public void saveAll(List<UsageRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            doBatch(records);
        } catch (DuplicateKeyException e) {
            log.warn("批量插入撞唯一索引（重复投递），降级逐条插入 {} 条", records.size());
            for (UsageRecord record : records) {
                try {
                    doSingle(record);
                } catch (DuplicateKeyException ignore) {
                    // DB 唯一索引是幂等最后防线：重复消息直接丢弃
                }
            }
        }
    }

    protected void doBatch(List<UsageRecord> records) {
        usageRecordMapper.insertBatch(records);
    }

    protected void doSingle(UsageRecord record) {
        usageRecordMapper.insert(record);
    }
}
