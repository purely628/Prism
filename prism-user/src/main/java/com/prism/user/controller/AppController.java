package com.prism.user.controller;

import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import com.prism.user.dto.AppCreateRequest;
import com.prism.user.dto.AppQuotaRequest;
import com.prism.user.dto.AppStatusRequest;
import com.prism.user.dto.AppVO;
import com.prism.user.service.AppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用管理（CONTRACTS §6.1）：X-User-Id 由网关 JWT 校验后附加
 */
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    @GetMapping
    public Result<List<AppVO>> list(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.ok(appService.listByUser(requireUser(userId)));
    }

    @PostMapping
    public Result<AppVO> create(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                @RequestBody @Valid AppCreateRequest request) {
        return Result.ok(appService.create(requireUser(userId), request));
    }

    @PostMapping("/{id}/resetKey")
    public Result<String> resetKey(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                   @PathVariable("id") Long id) {
        return Result.ok(appService.resetKey(id, requireUser(userId)));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                     @PathVariable("id") Long id,
                                     @RequestBody @Valid AppStatusRequest request) {
        appService.updateStatus(id, requireUser(userId), request.getStatus());
        return Result.ok();
    }

    @PostMapping("/{id}/quota")
    public Result<Void> updateQuota(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                    @PathVariable("id") Long id,
                                    @RequestBody @Valid AppQuotaRequest request) {
        appService.updateQuota(id, requireUser(userId), request.getMonthlyQuota());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                               @PathVariable("id") Long id) {
        appService.delete(id, requireUser(userId));
        return Result.ok();
    }

    /** 直连服务（未经网关）时无 X-User-Id 头 → 统一 401 */
    static long requireUser(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
