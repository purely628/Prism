package com.prism.billing.ingest;

import com.prism.billing.entity.UsageRecord;

import java.util.List;

/** 用量流水持久化抽象（单测可 stub） */
public interface UsageRecordStore {

    void saveAll(List<UsageRecord> records);
}
