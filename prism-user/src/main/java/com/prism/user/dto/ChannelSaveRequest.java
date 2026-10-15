package com.prism.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 渠道新建/编辑入参（status 单独接口切换） */
@Data
public class ChannelSaveRequest {

    @NotBlank(message = "渠道名不能为空")
    @Size(max = 64, message = "渠道名不能超过64个字符")
    private String channelName;

    @NotBlank(message = "提供商不能为空")
    @Size(max = 32, message = "提供商不能超过32个字符")
    private String provider;

    @NotBlank(message = "上游地址不能为空")
    @Size(max = 255, message = "上游地址不能超过255个字符")
    private String baseUrl;

    @Size(max = 255, message = "上游密钥不能超过255个字符")
    private String apiKey;

    @NotBlank(message = "上游模型名不能为空")
    private String modelName;

    @NotBlank(message = "对外模型别名不能为空")
    private String alias;

    @NotNull(message = "路由权重不能为空")
    @Min(value = 1, message = "路由权重至少为1")
    private Integer weight;
}
