package com.prism.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prism.billing.entity.Bill;
import com.prism.billing.stats.BillVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BillMapper extends BaseMapper<Bill> {

    /**
     * UPSERT：uk_app_month 冲突则更新（重复出账覆盖旧值）。
     * 出账动作本身将 status 置 1（已出账）。
     */
    @Insert("INSERT INTO t_bill (app_id, bill_month, total_tokens, call_count, amount, status) " +
            "VALUES (#{appId}, #{billMonth}, #{totalTokens}, #{callCount}, #{amount}, 1) " +
            "ON DUPLICATE KEY UPDATE " +
            "total_tokens = VALUES(total_tokens), call_count = VALUES(call_count), " +
            "amount = VALUES(amount), status = 1")
    int upsert(Bill bill);

    /** 账单列表（带应用名；演示环境共库直读 t_app，生产应服务间调用 user 服务） */
    @Select("SELECT b.id, b.app_id AS appId, a.app_name AS appName, b.bill_month AS billMonth, " +
            "b.total_tokens AS totalTokens, b.call_count AS callCount, b.amount, b.status, b.created_at AS createdAt " +
            "FROM t_bill b LEFT JOIN t_app a ON a.id = b.app_id " +
            "WHERE b.bill_month = #{month} ORDER BY b.total_tokens DESC")
    List<BillVO> listByMonthWithAppName(@Param("month") String month);
}
