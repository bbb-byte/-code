package com.ecommerce.analysis.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.analysis.common.Constants;
import com.ecommerce.analysis.dto.AdminCreateUserDTO;
import com.ecommerce.analysis.dto.LoginDTO;
import com.ecommerce.analysis.dto.RegisterDTO;
import com.ecommerce.analysis.entity.User;
import com.ecommerce.analysis.mapper.UserMapper;
import com.ecommerce.analysis.service.UserService;
import com.ecommerce.analysis.utils.JwtUtil;
import com.ecommerce.analysis.vo.LoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现类。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 执行登录校验，并在校验通过后组装返回给前端的登录视图对象。
     */
    @Override
    public LoginVO login(LoginDTO dto) {
        // 先按用户名查出账号，再依次做密码和状态校验。
        User user = userMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        if (user.getStatus() != Constants.STATUS_ENABLE) {
            throw new RuntimeException("账户已被禁用");
        }

        // Token 中会携带用户主键、用户名和角色，供后续鉴权链路直接解析。
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        LoginVO vo = new LoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        vo.setToken(token);

        return vo;
    }

    /**
     * 注册普通用户账号，默认赋予用户角色和启用状态。
     */
    @Override
    public void register(RegisterDTO dto) {
        if (existsByUsername(dto.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRealName(dto.getRealName());
        user.setRole(Constants.ROLE_USER);
        user.setStatus(Constants.STATUS_ENABLE);

        save(user);
    }

    /**
     * 管理员创建用户时允许指定角色和启用状态，但仍会复用统一的密码加密逻辑。
     */
    @Override
    public void createUserByAdmin(AdminCreateUserDTO dto) {
        if (existsByUsername(dto.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }
        if (!Constants.ROLE_ADMIN.equals(dto.getRole()) && !Constants.ROLE_USER.equals(dto.getRole())) {
            throw new RuntimeException("角色类型不合法");
        }
        if (!Constants.STATUS_ENABLE.equals(dto.getStatus()) && !Constants.STATUS_DISABLE.equals(dto.getStatus())) {
            throw new RuntimeException("用户状态不合法");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRealName(dto.getRealName());
        user.setRole(dto.getRole());
        user.setStatus(dto.getStatus());

        save(user);
    }

    /**
     * 按用户名查询用户实体。
     */
    @Override
    public User getByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    /**
     * 判断用户名是否已经被占用。
     */
    @Override
    public boolean existsByUsername(String username) {
        return userMapper.countByUsername(username) > 0;
    }
}
