package com.prism.user.service;

import com.prism.user.dto.ChannelSaveRequest;
import com.prism.user.dto.ChannelVO;

import java.util.List;

/**
 * 模型渠道管理（ADMIN）：任何变更后 DEL prism:channel:cache 通知 chat 服务失效
 */
public interface ChannelService {

    List<ChannelVO> list();

    ChannelVO create(ChannelSaveRequest request);

    ChannelVO update(long id, ChannelSaveRequest request);

    void updateStatus(long id, int status);

    void delete(long id);
}
