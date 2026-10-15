package com.prism.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.util.JwtUtil;
import com.prism.user.dto.LoginRequest;
import com.prism.user.dto.LoginVO;
import com.prism.user.dto.MeVO;
import com.prism.user.entity.UserEntity;
import com.prism.user.mapper.UserMapper;
import com.prism.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** token 有效期：12 小时 */
    private static final long TOKEN_TTL_MILLIS = 12L * 3600 * 1000;

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${prism.jwt.secret}")
    private String jwtSecret;

    @Override
    public LoginVO login(LoginRequest request) {
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.getUsername()));
        // 用户不存在/禁用/密码不符统一提示，避免账号枚举
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = JwtUtil.createToken(jwtSecret, user.getId(), user.getUsername(),
                TOKEN_TTL_MILLIS);
        return new LoginVO(token, user.getNickname());
    }

    @Override
    public MeVO me(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在或已注销");
        }
        return new MeVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }
}
