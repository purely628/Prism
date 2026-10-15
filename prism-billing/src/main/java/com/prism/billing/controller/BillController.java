package com.prism.billing.controller;

import com.prism.billing.service.BillService;
import com.prism.billing.stats.BillVO;
import com.prism.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账单查询（契约 §6.3）
 */
@RestController
@RequestMapping("/api/bills")
@RequiredArgsConstructor
public class BillController {

    private final BillService billService;

    @GetMapping
    public Result<List<BillVO>> list(@RequestParam(defaultValue = "") String month) {
        return Result.ok(billService.listByMonth(month));
    }
}
