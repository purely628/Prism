package com.prism.user.controller;

import com.prism.common.result.Result;
import com.prism.user.dto.LoginRequest;
import com.prism.user.dto.LoginVO;
import com.prism.user.dto.MeVO;
import com.prism.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 / 当前用户（CONTRACTS §6.1）
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录（无需 JWT），返回 12h token + 昵称 */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /** 当前用户信息：X-User-Id 由网关 JWT 校验后附加 */
    @GetMapping("/me")
    public Result<MeVO> me(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.ok(authService.me(userId));
    }
}
