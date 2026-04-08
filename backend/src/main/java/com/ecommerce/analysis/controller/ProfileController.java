package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.entity.UserProfile;
import com.ecommerce.analysis.service.RFMService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户画像控制器
 */
@Api(tags = "用户画像")
@RestController
@RequestMapping("/profile")
public class ProfileController {

    @Autowired
    private RFMService rfmService;

    @ApiOperation("计算所有用户RFM值")
    @PostMapping("/calculate-rfm")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> calculateRFM() {
        try {
            rfmService.calculateAllUserRFM();
            return Result.success("RFM计算完成", null);
        } catch (Exception e) {
            return Result.error("RFM计算失败: " + e.getMessage());
        }
    }

    @ApiOperation("执行K-Means聚类")
    @PostMapping("/kmeans-clustering")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> performClustering(@RequestParam(defaultValue = "5") int k) {
        try {
            rfmService.performKMeansClustering(k);
            return Result.success("聚类完成", null);
        } catch (Exception e) {
            return Result.error("聚类失败: " + e.getMessage());
        }
    }

    @ApiOperation("获取用户分群分布")
    @GetMapping("/group-distribution")
    public Result<List<Map<String, Object>>> getGroupDistribution() {
        List<Map<String, Object>> data = rfmService.getUserGroupDistribution();
        return Result.success(data);
    }

    @ApiOperation("获取聚类分布")
    @GetMapping("/cluster-distribution")
    public Result<List<Map<String, Object>>> getClusterDistribution() {
        List<Map<String, Object>> data = rfmService.getClusterDistribution();
        return Result.success(data);
    }

    @ApiOperation("获取RFM评分分布")
    @GetMapping("/rfm-distribution")
    public Result<List<Map<String, Object>>> getRFMDistribution() {
        List<Map<String, Object>> data = rfmService.getRFMScoreDistribution();
        return Result.success(data);
    }

    @ApiOperation("获取聚类中心")
    @GetMapping("/cluster-centers")
    public Result<List<Map<String, Object>>> getClusterCenters() {
        List<Map<String, Object>> data = rfmService.getClusterCenters();
        return Result.success(data);
    }

    @ApiOperation("获取高价值用户")
    @GetMapping("/high-value-users")
    public Result<List<UserProfile>> getHighValueUsers(
            @RequestParam(defaultValue = "20") int limit) {
        List<UserProfile> data = rfmService.getHighValueUsers(limit);
        return Result.success(data);
    }

    @ApiOperation("获取TOP用户（支持按分群筛选）")
    @GetMapping("/top-users")
    public Result<List<UserProfile>> getTopUsers(
            @RequestParam(defaultValue = "all") String group,
            @RequestParam(defaultValue = "20") int limit) {
        List<UserProfile> data = rfmService.getTopUsersByGroup(group, limit);
        return Result.success(data);
    }

    @ApiOperation("获取单个用户画像")
    @GetMapping("/user/{userId}")
    public Result<UserProfile> getUserProfile(@PathVariable Long userId) {
        UserProfile profile = rfmService.calculateUserRFM(userId);
        if (profile != null) {
            return Result.success(profile);
        }
        return Result.error("未找到用户画像");
    }
}
