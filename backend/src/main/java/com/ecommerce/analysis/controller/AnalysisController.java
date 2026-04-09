package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.service.UserBehaviorService;
import com.ecommerce.analysis.vo.DashboardVO;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据分析控制器
 */
@Api(tags = "数据分析")
@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    @Autowired
    private UserBehaviorService userBehaviorService;

    @ApiOperation("获取仪表盘数据")
    @GetMapping("/dashboard")
    public Result<DashboardVO> getDashboard() {
        DashboardVO data = userBehaviorService.getDashboardData();
        return Result.success(data);
    }

    @ApiOperation("获取行为类型分布")
    @GetMapping("/behavior-distribution")
    public Result<List<Map<String, Object>>> getBehaviorDistribution() {
        List<Map<String, Object>> data = userBehaviorService.getBehaviorTypeDistribution();
        return Result.success(data);
    }

    @ApiOperation("获取每日行为趋势")
    @GetMapping("/daily-trend")
    public Result<List<Map<String, Object>>> getDailyTrend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> data = userBehaviorService.getDailyBehaviorTrend(startDate, endDate);
        return Result.success(data);
    }

    @ApiOperation("获取热门商品(按浏览量)")
    @GetMapping("/hot-products/view")
    public Result<List<Map<String, Object>>> getHotProductsByView(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotProductsByView(limit);
        return Result.success(data);
    }

    @ApiOperation("获取热门商品(按购买量)")
    @GetMapping("/hot-products/buy")
    public Result<List<Map<String, Object>>> getHotProductsByBuy(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotProductsByBuy(limit);
        return Result.success(data);
    }

    @ApiOperation("获取热门商品及京东公开评价补充指标")
    @GetMapping("/hot-products/public-metrics")
    public Result<List<Map<String, Object>>> getHotProductsWithPublicMetrics(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotProductsWithPublicMetrics(limit);
        return Result.success(data);
    }

    @ApiOperation("获取热门类目")
    @GetMapping("/hot-categories")
    public Result<List<Map<String, Object>>> getHotCategories(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> data = userBehaviorService.getHotCategories(limit);
        return Result.success(data);
    }

    @ApiOperation("获取转化漏斗数据")
    @GetMapping("/conversion-funnel")
    public Result<Map<String, Object>> getConversionFunnel() {
        Map<String, Object> data = userBehaviorService.getConversionFunnel();
        return Result.success(data);
    }

    @ApiOperation("获取每小时行为分布")
    @GetMapping("/hourly-distribution")
    public Result<List<Map<String, Object>>> getHourlyDistribution() {
        List<Map<String, Object>> data = userBehaviorService.getHourlyDistribution();
        return Result.success(data);
    }

    @ApiOperation("获取数据概览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> getOverview() {
        Map<String, Object> data = userBehaviorService.getDataOverview();
        return Result.success(data);
    }
}
