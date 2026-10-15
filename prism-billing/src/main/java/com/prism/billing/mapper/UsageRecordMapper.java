package com.prism.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prism.billing.entity.UsageRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量流水 Mapper：单条走 BaseMapper（攒批降级路径），批量 INSERT 走 XML（insertBatch）。
 */
public interface UsageRecordMapper extends BaseMapper<UsageRecord> {

    /** 攒批多行 INSERT（UsageRecordMapper.xml），撞 uk_msg_id 唯一索引由上层降级处理 */
    int insertBatch(@Param("records") List<UsageRecord> records);

    /** 按月聚合各应用用量（出账 / 对账共用），走 (app_id, created_at) 索引 */
    @Select("SELECT app_id AS appId, " +
            "IFNULL(SUM(prompt_tokens), 0) AS promptTokens, " +
            "IFNULL(SUM(completion_tokens), 0) AS completionTokens, " +
            "IFNULL(SUM(total_tokens), 0) AS totalTokens, " +
            "COUNT(*) AS calls " +
            "FROM t_usage_record WHERE created_at >= #{start} AND created_at < #{end} " +
            "GROUP BY app_id")
    List<com.prism.billing.stats.MonthAgg> aggByApp(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);
}
