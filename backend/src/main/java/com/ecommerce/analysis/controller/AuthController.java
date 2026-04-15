package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.dto.LoginDTO;
import com.ecommerce.analysis.dto.RegisterDTO;
import com.ecommerce.analysis.service.UserService;
import com.ecommerce.analysis.vo.LoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 认证控制器。
 */
@Api(tags = "认证管理")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    /**
     * 校验账号密码并返回登录态信息。
     */
    @ApiOperation("用户登录")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        try {
            LoginVO vo = userService.login(dto);
            return Result.success("登录成功", vo);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 创建普通用户账号。
     */
    @ApiOperation("用户注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        try {
            userService.register(dto);
            return Result.success("注册成功", null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 检查用户名是否已被占用，前端注册页会用它做实时校验。
     */
    @ApiOperation("检查用户名是否可用")
    @GetMapping("/check-username")
    public Result<Boolean> checkUsername(@RequestParam String username) {
        boolean exists = userService.existsByUsername(username);
        return Result.success(!exists);
    }
}
