package com.prism.billing.controller;

import com.prism.billing.service.BillService;
import com.prism.billing.service.ReconcileService;
import com.prism.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部运维接口（网关拒绝外部 /internal 请求，仅内网/直连调试）。
 * 对账另有每日 02:30 定时任务；账单另有每月 1 号 03:00 定时任务。
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final ReconcileService reconcileService;
    private final BillService billService;

    /** 手动触发对账：{diffCount} */
    @PostMapping("/usage/reconcile")
    public Result<Map<String, Object>> reconcile() {
        int diffCount = reconcileService.reconcileCurrentMonth();
        return Result.ok(Map.of("diffCount", diffCount));
    }

    /** 手动出账：body {"month":"yyyy-MM"}，缺省当月 */
    @PostMapping("/bills/generate")
    public Result<Map<String, Object>> generateBill(@RequestBody(required = false) Map<String, String> body) {
        String month = body == null ? null : body.get("month");
        int count = billService.generate(BillService.parse(month == null ? "" : month));
        return Result.ok(Map.of("month", BillService.normalize(month), "count", count));
    }
}
