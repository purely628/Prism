package com.prism.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 修改应用月配额入参 */
@Data
public class AppQuotaRequest {

    @NotNull(message = "月token配额不能为空")
    @Min(value = 0, message = "月token配额不能为负数")
    private Long monthlyQuota;
}
