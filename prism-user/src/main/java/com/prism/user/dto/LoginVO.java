package com.prism.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 登录返回：token + 昵称 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String token;
    private String nickname;
}
