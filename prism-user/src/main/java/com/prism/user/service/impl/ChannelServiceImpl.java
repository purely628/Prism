package com.prism.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.prism.common.constant.RedisKeys;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.user.dto.ChannelSaveRequest;
import com.prism.user.dto.ChannelVO;
import com.prism.user.entity.ModelChannelEntity;
import com.prism.user.mapper.ModelChannelMapper;
import com.prism.user.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelServiceImpl implements ChannelService {

    private final ModelChannelMapper channelMapper;
    private final StringRedisTemplate redis;

    @Override
    public List<ChannelVO> list() {
        return channelMapper.selectList(Wrappers.<ModelChannelEntity>lambdaQuery()
                        .orderByAsc(ModelChannelEntity::getId))
                .stream()
                .map(ChannelVO::of)
                .toList();
    }

    @Override
    public ChannelVO create(ChannelSaveRequest request) {
        ModelChannelEntity channel = new ModelChannelEntity();
        apply(channel, request);
        channel.setStatus(1);
        channelMapper.insert(channel);
        evictChannelCache();
        log.info("channel created: id={}, alias={}", channel.getId(), channel.getAlias());
        return ChannelVO.of(channel);
    }

    @Override
    public ChannelVO update(long id, ChannelSaveRequest request) {
        ModelChannelEntity channel = require(id);
        apply(channel, request);
        channelMapper.updateById(channel);
        evictChannelCache();
        log.info("channel updated: id={}", id);
        return ChannelVO.of(channel);
    }

    @Override
    public void updateStatus(long id, int status) {
        require(id);
        channelMapper.update(null, Wrappers.<ModelChannelEntity>lambdaUpdate()
                .eq(ModelChannelEntity::getId, id)
                .set(ModelChannelEntity::getStatus, status));
        evictChannelCache();
        log.info("channel status changed: id={}, status={}", id, status);
    }

    @Override
    public void delete(long id) {
        require(id);
        channelMapper.deleteById(id);
        evictChannelCache();
        log.info("channel deleted: id={}", id);
    }

    /** 任何渠道变更后失效启用渠道缓存（chat 服务 60s TTL + 主动失效双保险） */
    private void evictChannelCache() {
        redis.delete(RedisKeys.CHANNEL_CACHE);
    }

    private void apply(ModelChannelEntity channel, ChannelSaveRequest request) {
        channel.setChannelName(request.getChannelName().trim());
        channel.setProvider(request.getProvider().trim());
        channel.setBaseUrl(request.getBaseUrl().trim());
        channel.setApiKey(request.getApiKey() == null ? "" : request.getApiKey().trim());
        channel.setModelName(request.getModelName().trim());
        channel.setAlias(request.getAlias().trim());
        channel.setWeight(request.getWeight());
    }

    private ModelChannelEntity require(long id) {
        ModelChannelEntity channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        return channel;
    }
}
