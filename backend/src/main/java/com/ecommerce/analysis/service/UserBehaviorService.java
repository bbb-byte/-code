package com.ecommerce.analysis.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.vo.DashboardVO;

import java.util.List;
import java.util.Map;

/**
 * 用户行为服务接口
 */
public interface UserBehaviorService extends IService<UserBehavior> {

    /**
     * 获取仪表盘数据
     */
    DashboardVO getDashboardData();

    /**
     * 获取行为类型分布
     */
    List<Map<String, Object>> getBehaviorTypeDistribution();

    /**
     * 获取每日行为趋势
     */
    List<Map<String, Object>> getDailyBehaviorTrend(String startDate, String endDate);

    /**
     * 获取热门商品(按浏览量)
     */
    List<Map<String, Object>> getHotProductsByView(int limit);

    /**
     * 获取热门商品(按购买量)
     */
    List<Map<String, Object>> getHotProductsByBuy(int limit);

    /**
     * 获取热门商品及京东公开评价补充指标。
     */
    List<Map<String, Object>> getHotProductsWithPublicMetrics(int limit);

    /**
     * 获取热门类目
     */
    List<Map<String, Object>> getHotCategories(int limit);

    /**
     * 获取转化漏斗数据
     */
    Map<String, Object> getConversionFunnel();

    /**
     * 获取每小时行为分布
     */
    List<Map<String, Object>> getHourlyDistribution();

    /**
     * 获取数据概览
     */
    Map<String, Object> getDataOverview();

    /**
     * 获取最新行为数据
     */
    List<UserBehavior> getLatestBehaviors(int limit);
}
