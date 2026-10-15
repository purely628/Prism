package com.prism.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 上游模型渠道 t_model_channel */
@Data
@TableName("t_model_channel")
public class ModelChannelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String channelName;

    /** glm / deepseek / mock / openai-compatible */
    private String provider;

    /** 上游地址；mock:// 前缀表示内置模拟渠道 */
    private String baseUrl;

    private String apiKey;

    /** 上游真实模型名 */
    private String modelName;

    /** 对外模型名（客户端请求里的 model 值） */
    private String alias;

    /** 路由权重（同 alias 加权随机） */
    private Integer weight;

    /** 1 启用 0 禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
