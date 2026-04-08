package com.ecommerce.analysis.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.analysis.common.PageResult;
import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.dto.AdminCreateUserDTO;
import com.ecommerce.analysis.entity.User;
import com.ecommerce.analysis.service.UserService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

/**
 * 用户管理控制器
 */
@Api(tags = "用户管理")
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @ApiOperation("分页查询用户")
    @GetMapping("/page")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<User>> getPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role) {

        Page<User> page = new Page<>(current, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(username)) {
            wrapper.like(User::getUsername, username);
        }
        if (StringUtils.hasText(role)) {
            wrapper.eq(User::getRole, role);
        }
        wrapper.orderByDesc(User::getCreateTime);

        Page<User> result = userService.page(page, wrapper);

        // 隐藏密码
        result.getRecords().forEach(u -> u.setPassword(null));

        PageResult<User> pageResult = new PageResult<>(
                result.getRecords(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize());

        return Result.success(pageResult);
    }

    @ApiOperation("管理员创建用户")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> create(@Valid @RequestBody AdminCreateUserDTO dto) {
        try {
            userService.createUserByAdmin(dto);
            return Result.success("创建成功", null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @ApiOperation("获取用户详情")
    @GetMapping("/{id}")
    public Result<User> getById(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user != null) {
            user.setPassword(null);
            return Result.success(user);
        }
        return Result.error("用户不存在");
    }

    @ApiOperation("更新用户")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        user.setPassword(null); // 不更新密码
        boolean success = userService.updateById(user);
        return success ? Result.success() : Result.error("更新失败");
    }

    @ApiOperation("删除用户")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        boolean success = userService.removeById(id);
        return success ? Result.success() : Result.error("删除失败");
    }

    @ApiOperation("启用/禁用用户")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        boolean success = userService.updateById(user);
        return success ? Result.success() : Result.error("操作失败");
    }
}
