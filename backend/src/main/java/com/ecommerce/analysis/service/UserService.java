package com.ecommerce.analysis.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.analysis.dto.AdminCreateUserDTO;
import com.ecommerce.analysis.dto.LoginDTO;
import com.ecommerce.analysis.dto.RegisterDTO;
import com.ecommerce.analysis.entity.User;
import com.ecommerce.analysis.vo.LoginVO;

/**
 * 用户服务接口
 */
public interface UserService extends IService<User> {

    /**
     * 用户登录
     */
    LoginVO login(LoginDTO dto);

    /**
     * 用户注册
     */
    void register(RegisterDTO dto);

    /**
     * 管理员创建用户
     */
    void createUserByAdmin(AdminCreateUserDTO dto);

    /**
     * 根据用户名查询用户
     */
    User getByUsername(String username);

    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);
}
