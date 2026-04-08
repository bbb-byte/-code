package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户Mapper接口
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    User selectByUsername(String username);

    /**
     * 检查用户名是否存在
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username} AND deleted = 0")
    int countByUsername(String username);
}
