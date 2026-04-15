package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.service.UserBehaviorService;
import com.ecommerce.analysis.vo.DashboardVO;
import com.ecommerce.analysis.vo.HotProductsPublicMetricsPageVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据分析控制器。
 */
@Api(tags = "数据分析")
@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    @Autowired
    private UserBehaviorService userBehaviorService;

    /**
     * 返回首页仪表盘所需的聚合统计数据。
     */
    @ApiOperation("获取仪表盘数据")
    @GetMapping("/dashboard")
    public Result<DashboardVO> getDashboard() {
        DashboardVO data = userBehaviorService.getDashboardData();
        return Result.success(data);
    }

    /**
     * 返回行为类型分布，用于饼图或柱状图展示。
     */
    @ApiOperation("获取行为类型分布")
    @GetMapping("/behavior-distribution")
    public Result<List<Map<String, Object>>> getBehaviorDistribution() {
        List<Map<String, Object>> data = userBehaviorService.getBehaviorTypeDistribution();
        return Result.success(data);
    }

    /**
     * 按日期区间返回用户行为趋势；缺省区间时由服务层自动回退到最新数据范围。
     */
    @ApiOperation("获取每日行为趋势")
    @GetMapping("/daily-trend")
    public Result<List<Map<String, Object>>> getDailyTrend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> data = userBehaviorService.getDailyBehaviorTrend(startDate, endDate);
        return Result.success(data);
    }

    /**
     * 返回按浏览量排序的热门商品。
     */
    @ApiOperation("获取热门商品(按浏览量)")
    @GetMapping("/hot-products/view")
    public Result<List<Map<String, Object>>> getHotProductsByView(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotProductsByView(limit);
        return Result.success(data);
    }

    /**
     * 返回按购买量排序的热门商品。
     */
    @ApiOperation("获取热门商品(按购买量)")
    @GetMapping("/hot-products/buy")
    public Result<List<Map<String, Object>>> getHotProductsByBuy(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotProductsByBuy(limit);
        return Result.success(data);
    }

    /**
     * 返回热门商品及其公网补充指标分页结果。
     */
    @ApiOperation("获取热门商品及京东公网评价补充指标")
    @GetMapping("/hot-products/public-metrics")
    public Result<HotProductsPublicMetricsPageVO> getHotProductsWithPublicMetrics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "true") boolean onlyWithMetrics) {
        HotProductsPublicMetricsPageVO data =
                userBehaviorService.getHotProductsWithPublicMetrics(page, pageSize, onlyWithMetrics);
        return Result.success(data);
    }

    /**
     * 返回热销商品的类目分布。
     */
    @ApiOperation("获取热门类目")
    @GetMapping("/hot-categories")
    public Result<List<Map<String, Object>>> getHotCategories(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotCategories(limit);
        return Result.success(data);
    }

    /**
     * 返回浏览、加购、收藏、购买的漏斗统计。
     */
    @ApiOperation("获取转化漏斗数据")
    @GetMapping("/conversion-funnel")
    public Result<Map<String, Object>> getConversionFunnel() {
        Map<String, Object> data = userBehaviorService.getConversionFunnel();
        return Result.success(data);
    }

    /**
     * 返回全天按小时聚合的行为分布。
     */
    @ApiOperation("获取每小时行为分布")
    @GetMapping("/hourly-distribution")
    public Result<List<Map<String, Object>>> getHourlyDistribution() {
        List<Map<String, Object>> data = userBehaviorService.getHourlyDistribution();
        return Result.success(data);
    }

    /**
     * 返回用户数、商品数、行为数等全局概览指标。
     */
    @ApiOperation("获取数据概览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> getOverview() {
        Map<String, Object> data = userBehaviorService.getDataOverview();
        return Result.success(data);
    }
}
