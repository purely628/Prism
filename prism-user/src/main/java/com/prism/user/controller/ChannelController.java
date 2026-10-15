package com.prism.user.controller;

import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import com.prism.user.dto.ChannelSaveRequest;
import com.prism.user.dto.ChannelStatusRequest;
import com.prism.user.dto.ChannelVO;
import com.prism.user.entity.UserEntity;
import com.prism.user.mapper.UserMapper;
import com.prism.user.service.ChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型渠道管理（仅 ADMIN，CONTRACTS §6.1）：按 X-User-Id 查库校验角色
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final UserMapper userMapper;

    @GetMapping
    public Result<List<ChannelVO>> list(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        requireAdmin(userId);
        return Result.ok(channelService.list());
    }

    @PostMapping
    public Result<ChannelVO> create(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                    @RequestBody @Valid ChannelSaveRequest request) {
        requireAdmin(userId);
        return Result.ok(channelService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ChannelVO> update(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                    @PathVariable("id") Long id,
                                    @RequestBody @Valid ChannelSaveRequest request) {
        requireAdmin(userId);
        return Result.ok(channelService.update(id, request));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                     @PathVariable("id") Long id,
                                     @RequestBody @Valid ChannelStatusRequest request) {
        requireAdmin(userId);
        channelService.updateStatus(id, request.getStatus());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                               @PathVariable("id") Long id) {
        requireAdmin(userId);
        channelService.delete(id);
        return Result.ok();
    }

    /** ADMIN 角色校验：以库中 role 为准，不信任请求头里的角色信息 */
    private void requireAdmin(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
