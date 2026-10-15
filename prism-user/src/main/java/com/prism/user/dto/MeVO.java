package com.prism.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 当前登录用户信息 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MeVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
}
