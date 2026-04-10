package com.ecommerce.analysis.common;

/**
 * 系统常量定义
 */
public class Constants {

    // 用户角色
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    // 用户状态
    public static final Integer STATUS_ENABLE = 1;
    public static final Integer STATUS_DISABLE = 0;

    // 行为类型
    public static final String BEHAVIOR_PV = "pv"; // 浏览
    public static final String BEHAVIOR_BUY = "buy"; // 购买
    public static final String BEHAVIOR_CART = "cart"; // 加入购物车
    public static final String BEHAVIOR_FAV = "fav"; // 收藏

    // Redis缓存前缀
    public static final String REDIS_TOKEN_PREFIX = "token:";
    public static final String REDIS_USER_PREFIX = "user:";
    public static final String REDIS_ANALYSIS_PREFIX = "analysis:";
    public static final String REDIS_PROFILE_PREFIX = "profile:";

    // Token相关
    public static final String TOKEN_HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    // 用户分群类型
    public static final String GROUP_HIGH_VALUE = "高价值用户";
    public static final String GROUP_POTENTIAL = "潜力用户";
    public static final String GROUP_NEW = "新用户";
    public static final String GROUP_SLEEPING = "沉睡用户";
    public static final String GROUP_LOST = "流失用户";
}
