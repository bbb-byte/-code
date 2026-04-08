package com.ecommerce.analysis.vo;

import lombok.Data;

/**
 * 登录响应VO
 */
@Data
public class LoginVO {

    private Long userId;
    private String username;
    private String realName;
    private String avatar;
    private String role;
    private String token;
}
