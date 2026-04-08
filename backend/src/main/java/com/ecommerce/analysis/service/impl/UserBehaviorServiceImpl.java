package com.ecommerce.analysis.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.mapper.UserProfileMapper;
import com.ecommerce.analysis.service.UserBehaviorService;
import com.ecommerce.analysis.vo.DashboardVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户行为服务实现类
 */
@Service
public class UserBehaviorServiceImpl extends ServiceImpl<UserBehaviorMapper, UserBehavior>
        implements UserBehaviorService {

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Override
    public DashboardVO getDashboardData() {
        DashboardVO vo = new DashboardVO();

        // 获取基础统计数据
        vo.setTotalUsers(userBehaviorMapper.countDistinctUsers());
        vo.setTotalProducts(userBehaviorMapper.countDistinctItems());
        vo.setTotalCategories(userBehaviorMapper.countDistinctCategories());
        vo.setTotalBehaviors(count());

        // 获取行为类型统计
        List<Map<String, Object>> behaviorStats = userBehaviorMapper.countByBehaviorType();
        vo.setBehaviorDistribution(behaviorStats);

        // 计算各类型数量
        long totalViews = 0, totalBuys = 0, totalCarts = 0, totalFavs = 0;
        for (Map<String, Object> stat : behaviorStats) {
            String type = (String) stat.get("behavior_type");
            Long count = ((Number) stat.get("count")).longValue();
            switch (type) {
                case "pv":
                    totalViews = count;
                    break;
                case "buy":
                    totalBuys = count;
                    break;
                case "cart":
                    totalCarts = count;
                    break;
                case "fav":
                    totalFavs = count;
                    break;
            }
        }
        vo.setTotalViews(totalViews);
        vo.setTotalBuys(totalBuys);
        vo.setTotalCarts(totalCarts);
        vo.setTotalFavs(totalFavs);

        // 计算转化率 (购买用户数 / 浏览用户数)
        Map<String, Object> funnel = userBehaviorMapper.getConversionFunnel();
        if (funnel != null) {
            Long pvUsers = ((Number) funnel.get("pv_users")).longValue();
            Long buyUsers = ((Number) funnel.get("buy_users")).longValue();
            if (pvUsers > 0) {
                vo.setConversionRate(buyUsers * 100.0 / pvUsers);
            }
        }

        // 热门商品和类目
        vo.setHotProducts(userBehaviorMapper.getHotProductsByBuy(10));
        vo.setHotCategories(userBehaviorMapper.getHotCategories(10));

        // 用户分群分布
        vo.setUserGroupDistribution(userProfileMapper.countByUserGroup());

        // 每小时行为分布
        vo.setHourlyDistribution(userBehaviorMapper.getHourlyDistribution());

        return vo;
    }

    @Override
    public List<Map<String, Object>> getBehaviorTypeDistribution() {
        return userBehaviorMapper.countByBehaviorType();
    }

    @Override
    public List<Map<String, Object>> getDailyBehaviorTrend(String startDate, String endDate) {
        if (isBlank(startDate) || isBlank(endDate)) {
            Map<String, Object> latestDateRange = userBehaviorMapper.getLatestDateRange();
            if (latestDateRange == null || latestDateRange.get("start_date") == null || latestDateRange.get("end_date") == null) {
                return java.util.Collections.emptyList();
            }
            startDate = Objects.toString(latestDateRange.get("start_date"), null);
            endDate = Objects.toString(latestDateRange.get("end_date"), null);
        }
        return userBehaviorMapper.getDailyBehaviorStats(startDate, endDate);
    }

    @Override
    public List<Map<String, Object>> getHotProductsByView(int limit) {
        return userBehaviorMapper.getHotProductsByView(limit);
    }

    @Override
    public List<Map<String, Object>> getHotProductsByBuy(int limit) {
        return userBehaviorMapper.getHotProductsByBuy(limit);
    }

    @Override
    public List<Map<String, Object>> getHotCategories(int limit) {
        return userBehaviorMapper.getHotCategories(limit);
    }

    @Override
    public Map<String, Object> getConversionFunnel() {
        return userBehaviorMapper.getConversionFunnel();
    }

    @Override
    public List<Map<String, Object>> getHourlyDistribution() {
        return userBehaviorMapper.getHourlyDistribution();
    }

    @Override
    public Map<String, Object> getDataOverview() {
        Map<String, Object> overview = new HashMap<>();
        overview.put("totalUsers", userBehaviorMapper.countDistinctUsers());
        overview.put("totalProducts", userBehaviorMapper.countDistinctItems());
        overview.put("totalCategories", userBehaviorMapper.countDistinctCategories());
        overview.put("totalBehaviors", count());
        return overview;
    }

    @Override
    public List<UserBehavior> getLatestBehaviors(int limit) {
        return userBehaviorMapper.getLatestBehaviors(limit);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
