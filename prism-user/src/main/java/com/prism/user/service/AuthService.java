package com.prism.user.service;

import com.prism.user.dto.LoginRequest;
import com.prism.user.dto.LoginVO;
import com.prism.user.dto.MeVO;

/**
 * 登录与当前用户信息
 */
public interface AuthService {

    /** 用户名密码登录，成功返回 12h JWT */
    LoginVO login(LoginRequest request);

    /** 按 X-User-Id 查询当前用户 */
    MeVO me(Long userId);
}
