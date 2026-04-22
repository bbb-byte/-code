package com.ecommerce.analysis.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.analysis.common.Constants;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.mapper.UserProfileMapper;
import com.ecommerce.analysis.service.UserBehaviorService;
import com.ecommerce.analysis.vo.DashboardVO;
import com.ecommerce.analysis.vo.HotProductsPublicMetricsPageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户行为服务实现类。
 */
@Service
public class UserBehaviorServiceImpl extends ServiceImpl<UserBehaviorMapper, UserBehavior>
        implements UserBehaviorService {

    private static final String JD_PLATFORM = "jd";
    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private ProductPublicMetricMapper productPublicMetricMapper;

    @Autowired
    private AnalysisCacheService analysisCacheService;

    @Value("${app.cache.analysis-ttl-minutes:120}")
    private long analysisCacheTtlMinutes;

    @Value("${app.cache.hot-products-ttl-minutes:180}")
    private long hotProductsCacheTtlMinutes;

    /**
     * 加载首页仪表盘数据，并通过统一缓存服务降低聚合查询频率。
     */
    @Override
    public DashboardVO getDashboardData() {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "dashboard";
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes, DashboardVO.class,
                this::loadDashboardData);
    }

    /**
     * 读取行为类型分布。
     */
    @Override
    public List<Map<String, Object>> getBehaviorTypeDistribution() {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "behavior-distribution";
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes,
                userBehaviorMapper::countByBehaviorType);
    }

    /**
     * 返回每日行为趋势；未传时间区间时自动回退到库中可用的最新范围。
     */
    @Override
    public List<Map<String, Object>> getDailyBehaviorTrend(String startDate, String endDate) {
        String normalizedStartDate = startDate;
        String normalizedEndDate = endDate;
        if (isBlank(normalizedStartDate) || isBlank(normalizedEndDate)) {
            Map<String, Object> latestDateRange = userBehaviorMapper.getLatestDateRange();
            if (latestDateRange == null || latestDateRange.get("start_date") == null
                    || latestDateRange.get("end_date") == null) {
                return java.util.Collections.emptyList();
            }
            normalizedStartDate = Objects.toString(latestDateRange.get("start_date"), null);
            normalizedEndDate = Objects.toString(latestDateRange.get("end_date"), null);
        }
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "daily-trend:" + normalizedStartDate + ":" + normalizedEndDate;
        String finalStartDate = normalizedStartDate;
        String finalEndDate = normalizedEndDate;
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes,
                () -> userBehaviorMapper.getDailyBehaviorStats(finalStartDate, finalEndDate));
    }

    /**
     * 按浏览量返回热门商品。
     */
    @Override
    public List<Map<String, Object>> getHotProductsByView(int limit) {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "hot-products:view:" + limit;
        return analysisCacheService.getOrLoad(cacheKey, hotProductsCacheTtlMinutes,
                () -> userBehaviorMapper.getHotProductsByView(limit));
    }

    /**
     * 按购买量返回热门商品。
     */
    @Override
    public List<Map<String, Object>> getHotProductsByBuy(int limit) {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "hot-products:buy:" + limit;
        return analysisCacheService.getOrLoad(cacheKey, hotProductsCacheTtlMinutes,
                () -> userBehaviorMapper.getHotProductsByBuy(limit));
    }

    /**
     * 返回热门商品与公网补充指标的分页聚合结果。
     */
    @Override
    public HotProductsPublicMetricsPageVO getHotProductsWithPublicMetrics(int page, int pageSize, boolean onlyWithMetrics, String scope) {
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize <= 0 ? 10 : Math.min(pageSize, 50);
        int offset = (safePage - 1) * safePageSize;
        String normalizedScope = normalizeMetricScope(scope);
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "hot-products:public-metrics:" + JD_PLATFORM + ":" + normalizedScope + ":" + onlyWithMetrics + ":" + safePage + ":" + safePageSize;
        return analysisCacheService.getOrLoad(cacheKey, hotProductsCacheTtlMinutes,
                HotProductsPublicMetricsPageVO.class,
                () -> loadHotProductsPublicMetricsPage(offset, safePage, safePageSize, onlyWithMetrics, normalizedScope));
    }

    /**
     * 读取热门类目分布。
     */
    @Override
    public List<Map<String, Object>> getHotCategories(int limit) {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "hot-categories:" + limit;
        return analysisCacheService.getOrLoad(cacheKey, hotProductsCacheTtlMinutes,
                () -> userBehaviorMapper.getHotCategories(limit));
    }

    /**
     * 读取转化漏斗统计。
     */
    @Override
    public Map<String, Object> getConversionFunnel() {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "conversion-funnel";
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes,
                userBehaviorMapper::getConversionFunnel);
    }

    /**
     * 读取每小时行为分布。
     */
    @Override
    public List<Map<String, Object>> getHourlyDistribution() {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "hourly-distribution";
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes,
                userBehaviorMapper::getHourlyDistribution);
    }

    /**
     * 返回全局概览信息，用于后台概览卡片展示。
     */
    @Override
    public Map<String, Object> getDataOverview() {
        String cacheKey = Constants.REDIS_ANALYSIS_PREFIX + "overview";
        return analysisCacheService.getOrLoad(cacheKey, analysisCacheTtlMinutes, () -> {
            Map<String, Object> overview = new HashMap<>();
            overview.put("totalUsers", userBehaviorMapper.countDistinctUsers());
            overview.put("totalProducts", userBehaviorMapper.countDistinctItems());
            overview.put("totalCategories", userBehaviorMapper.countDistinctCategories());
            overview.put("totalBehaviors", count());
            return overview;
        });
    }

    /**
     * 读取最近导入的行为记录。
     */
    @Override
    public List<UserBehavior> getLatestBehaviors(int limit) {
        return userBehaviorMapper.getLatestBehaviors(limit);
    }

    /**
     * 将多种统计结果组装为仪表盘 VO。
     */
    private DashboardVO loadDashboardData() {
        DashboardVO vo = new DashboardVO();

        vo.setTotalUsers(userBehaviorMapper.countDistinctUsers());
        vo.setTotalProducts(userBehaviorMapper.countDistinctItems());
        vo.setTotalCategories(userBehaviorMapper.countDistinctCategories());
        vo.setTotalBehaviors(count());

        List<Map<String, Object>> behaviorStats = userBehaviorMapper.countByBehaviorType();
        vo.setBehaviorDistribution(behaviorStats);

        // SQL 返回的是按行为类型分组后的计数，需要在这里回填到固定字段。
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
                default:
                    break;
            }
        }
        vo.setTotalViews(totalViews);
        vo.setTotalBuys(totalBuys);
        vo.setTotalCarts(totalCarts);
        vo.setTotalFavs(totalFavs);

        Map<String, Object> funnel = userBehaviorMapper.getConversionFunnel();
        if (funnel != null) {
            Long pvUsers = ((Number) funnel.get("pv_users")).longValue();
            Long buyUsers = ((Number) funnel.get("buy_users")).longValue();
            if (pvUsers > 0) {
                vo.setConversionRate(buyUsers * 100.0 / pvUsers);
            }
        }

        vo.setHotProducts(userBehaviorMapper.getHotProductsByBuy(10));
        vo.setHotCategories(userBehaviorMapper.getHotCategories(10));
        vo.setUserGroupDistribution(userProfileMapper.countByUserGroup());
        vo.setHourlyDistribution(userBehaviorMapper.getHourlyDistribution());
        return vo;
    }

    /**
     * 执行带分页参数的公网补充指标查询。
     */
    private HotProductsPublicMetricsPageVO loadHotProductsPublicMetricsPage(int offset, int page, int pageSize,
            boolean onlyWithMetrics, String scope) {
        HotProductsPublicMetricsPageVO result = new HotProductsPublicMetricsPageVO();
        result.setPage(page);
        result.setPageSize(pageSize);
        if ("all".equals(scope)) {
            result.setTotal(productPublicMetricMapper.countAllProductsWithPublicMetrics(onlyWithMetrics, JD_PLATFORM));
            result.setRows(productPublicMetricMapper.getAllProductsWithPublicMetrics(offset, pageSize, onlyWithMetrics, JD_PLATFORM));
        } else {
            result.setTotal(productPublicMetricMapper.countHotProductsWithPublicMetrics(onlyWithMetrics, JD_PLATFORM));
            result.setRows(productPublicMetricMapper.getHotProductsWithPublicMetrics(offset, pageSize, onlyWithMetrics, JD_PLATFORM));
        }
        return result;
    }

    private String normalizeMetricScope(String scope) {
        if (scope == null) {
            return "hot";
        }
        String normalized = scope.trim().toLowerCase();
        return "all".equals(normalized) ? "all" : "hot";
    }

    /**
     * 判空工具，避免引入额外依赖。
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
