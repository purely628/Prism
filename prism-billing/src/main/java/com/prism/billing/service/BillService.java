package com.prism.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.prism.billing.entity.Bill;
import com.prism.billing.mapper.AppMapper;
import com.prism.billing.mapper.BillMapper;
import com.prism.billing.mapper.UsageStatsMapper;
import com.prism.billing.stats.BillVO;
import com.prism.billing.stats.MonthAgg;
import com.prism.billing.entity.App;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度账单：按应用聚合当月流水生成 t_bill（幂等 UPSERT，uk_app_month）。
 * 演示单价：prompt 0.002 元/1k tokens、completion 0.004 元/1k tokens。
 * 每月 1 号 03:00 自动出上月账；演示环境支持手动按月生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final BigDecimal PROMPT_PRICE_PER_1K = new BigDecimal("0.002");
    private static final BigDecimal COMPLETION_PRICE_PER_1K = new BigDecimal("0.004");

    private final UsageStatsMapper statsMapper;
    private final BillMapper billMapper;
    private final AppMapper appMapper;

    /** 每月 1 号 03:00 自动出上月账单 */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void generateLastMonthBill() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        int count = generate(YearMonth.of(lastMonth.getYear(), lastMonth.getMonthValue()));
        log.info("月度账单任务完成：{} 共生成 {} 条", lastMonth, count);
    }

    /** @param month 目标月（yyyy-MM 或 yyyyMM 均可） */
    public int generate(YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<MonthAgg> aggs = statsMapper.monthAggByApp(start, end);
        for (MonthAgg agg : aggs) {
            upsertBill(month, agg);
        }
        // 没有流水的月份也应出零账单？演示环境跳过（避免空账单刷屏），注释说明取舍
        return aggs.size();
    }

    public List<BillVO> listByMonth(String month) {
        String normalized = normalize(month);
        List<Bill> bills = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getBillMonth, normalized)
                .orderByDesc(Bill::getTotalTokens));
        List<BillVO> vos = new ArrayList<>();
        for (Bill bill : bills) {
            BillVO vo = new BillVO();
            vo.setId(bill.getId());
            vo.setAppId(bill.getAppId());
            App app = appMapper.selectById(bill.getAppId());
            vo.setAppName(app == null ? "app-" + bill.getAppId() : app.getAppName());
            vo.setBillMonth(bill.getBillMonth());
            vo.setTotalTokens(bill.getTotalTokens());
            vo.setCallCount(bill.getCallCount());
            vo.setAmount(bill.getAmount());
            vo.setStatus(bill.getStatus());
            vo.setCreatedAt(bill.getCreatedAt());
            vos.add(vo);
        }
        return vos;
    }

    private void upsertBill(YearMonth month, MonthAgg agg) {
        String billMonth = month.toString(); // yyyy-MM
        Bill existing = billMapper.selectOne(new LambdaQueryWrapper<Bill>()
                .eq(Bill::getAppId, agg.getAppId())
                .eq(Bill::getBillMonth, billMonth));
        BigDecimal amount = calcAmount(orZero(agg.getPromptTokens()), orZero(agg.getCompletionTokens()));
        Bill bill = existing == null ? new Bill() : existing;
        bill.setAppId(agg.getAppId());
        bill.setBillMonth(billMonth);
        bill.setTotalTokens(orZero(agg.getTotalTokens()));
        bill.setCallCount(orZero(agg.getCalls()));
        bill.setAmount(amount);
        bill.setStatus(1);
        if (existing == null) {
            billMapper.insert(bill);
        } else {
            billMapper.updateById(bill);
        }
    }

    /** prompt/completion 差异计价，4 位小数 */
    static BigDecimal calcAmount(long promptTokens, long completionTokens) {
        BigDecimal promptPart = BigDecimal.valueOf(promptTokens).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(PROMPT_PRICE_PER_1K);
        BigDecimal completionPart = BigDecimal.valueOf(completionTokens).divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(COMPLETION_PRICE_PER_1K);
        return promptPart.add(completionPart).setScale(4, RoundingMode.HALF_UP);
    }

    public static String normalize(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now().toString();
        }
        return month.contains("-") ? month : YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyyMM")).toString();
    }

    public static YearMonth parse(String month) {
        return YearMonth.parse(normalize(month));
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }
}
