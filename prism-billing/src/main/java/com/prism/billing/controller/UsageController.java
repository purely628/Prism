package com.prism.billing.controller;

import com.prism.billing.entity.UsageRecord;
import com.prism.billing.service.UsageQueryService;
import com.prism.common.result.PageResult;
import com.prism.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用量查询（契约 §6.3）。鉴权在网关完成，本服务信任 X-User-Id 内网头；
 * 演示环境未做应用归属校验，生产应按 X-User-Id 过滤其名下应用。
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageQueryService usageQueryService;

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(usageQueryService.summary(days));
    }

    @GetMapping("/page")
    public Result<PageResult<UsageRecord>> page(@RequestParam(required = false) Long appId,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(usageQueryService.page(appId, pageNum, pageSize));
    }

    @GetMapping("/quota")
    public Result<Map<String, Object>> quota(@RequestParam long appId) {
        return Result.ok(usageQueryService.quota(appId));
    }
}
