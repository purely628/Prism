package com.prism.user.dto;

import com.prism.user.entity.ModelChannelEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;

/** 渠道视图 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChannelVO {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Long id;
    private String channelName;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String alias;
    private Integer weight;
    private Integer status;
    private String createdAt;

    public static ChannelVO of(ModelChannelEntity c) {
        return new ChannelVO(
                c.getId(),
                c.getChannelName(),
                c.getProvider(),
                c.getBaseUrl(),
                c.getApiKey(),
                c.getModelName(),
                c.getAlias(),
                c.getWeight(),
                c.getStatus(),
                c.getCreatedAt() == null ? null : c.getCreatedAt().format(FMT));
    }
}
