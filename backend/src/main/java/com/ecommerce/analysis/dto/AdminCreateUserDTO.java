package com.ecommerce.analysis.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建用户请求DTO
 */
@Data
public class AdminCreateUserDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度3-20个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度6-20个字符")
    private String password;

    private String email;

    private String phone;

    private String realName;

    @NotBlank(message = "角色不能为空")
    private String role;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
