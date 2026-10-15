package com.prism.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prism.chat.entity.ModelChannel;

/**
 * 渠道表 Mapper（只读）。MyBatis-Plus 为阻塞 JDBC API，
 * 所有调用必须经 Mono.fromCallable(...).subscribeOn(boundedElastic) 包装（见 RedisChannelRepository）。
 */
public interface ModelChannelMapper extends BaseMapper<ModelChannel> {
}
