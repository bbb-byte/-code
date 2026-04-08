package com.ecommerce.analysis.vo;

import lombok.Data;
import java.util.Map;
import java.util.List;

/**
 * 仪表盘数据VO
 */
@Data
public class DashboardVO {

    /**
     * 用户总数
     */
    private Long totalUsers;

    /**
     * 商品总数
     */
    private Long totalProducts;

    /**
     * 类目总数
     */
    private Long totalCategories;

    /**
     * 行为记录总数
     */
    private Long totalBehaviors;

    /**
     * 浏览总次数
     */
    private Long totalViews;

    /**
     * 购买总次数
     */
    private Long totalBuys;

    /**
     * 加购总次数
     */
    private Long totalCarts;

    /**
     * 收藏总次数
     */
    private Long totalFavs;

    /**
     * 购买转化率
     */
    private Double conversionRate;

    /**
     * 行为类型分布
     */
    private List<Map<String, Object>> behaviorDistribution;

    /**
     * 热门商品TOP10
     */
    private List<Map<String, Object>> hotProducts;

    /**
     * 热门类目TOP10
     */
    private List<Map<String, Object>> hotCategories;

    /**
     * 用户分群分布
     */
    private List<Map<String, Object>> userGroupDistribution;

    /**
     * 每小时行为分布
     */
    private List<Map<String, Object>> hourlyDistribution;
}
