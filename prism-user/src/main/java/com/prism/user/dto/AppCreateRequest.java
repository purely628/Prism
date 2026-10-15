package com.prism.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建应用入参 */
@Data
public class AppCreateRequest {

    @NotBlank(message = "应用名不能为空")
    @Size(max = 64, message = "应用名不能超过64个字符")
    private String appName;

    @NotNull(message = "月token配额不能为空")
    @Min(value = 0, message = "月token配额不能为负数")
    private Long monthlyQuota;

    @NotNull(message = "限流速率不能为空")
    @Min(value = 1, message = "限流速率至少为1次/秒")
    private Integer rateLimit;
}
