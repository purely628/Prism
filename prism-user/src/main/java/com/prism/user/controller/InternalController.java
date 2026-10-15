package com.prism.user.controller;

import com.prism.common.result.Result;
import com.prism.user.dto.AppAuthInfoVO;
import com.prism.user.service.AppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内网互调接口（仅网关/服务直连访问，网关拒绝一切外部 /internal 请求）
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final AppService appService;

    /**
     * 网关 app hash miss 时回源：返回鉴权字段并回填 Redis hash。
     * 查无此 key 时 data 为 null（HTTP 200，避免网关把业务 miss 当 5xx）。
     */
    @GetMapping("/apps/bykey")
    public Result<AppAuthInfoVO> appByKey(@RequestParam("appKey") String appKey) {
        AppAuthInfoVO info = appService.findAuthInfoByKey(appKey);
        if (info == null) {
            log.info("app lookup miss, appKey={}***", appKey.substring(0, Math.min(12, appKey.length())));
        }
        return Result.ok(info);
    }
}
