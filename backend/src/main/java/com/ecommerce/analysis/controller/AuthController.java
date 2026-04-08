package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.dto.LoginDTO;
import com.ecommerce.analysis.dto.RegisterDTO;
import com.ecommerce.analysis.service.UserService;
import com.ecommerce.analysis.vo.LoginVO;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@Api(tags = "认证管理")
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserService userService;

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

    @ApiOperation("检查用户名是否可用")
    @GetMapping("/check-username")
    public Result<Boolean> checkUsername(@RequestParam String username) {
        boolean exists = userService.existsByUsername(username);
        return Result.success(!exists);
    }
}
