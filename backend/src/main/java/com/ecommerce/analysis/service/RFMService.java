package com.ecommerce.analysis.service;

import com.ecommerce.analysis.entity.UserProfile;
import java.util.List;
import java.util.Map;

/**
 * RFM分析服务接口
 */
public interface RFMService {

    /**
     * 计算所有用户的RFM值
     */
    void calculateAllUserRFM();

    /**
     * 计算单个用户的RFM值
     */
    UserProfile calculateUserRFM(Long userId);

    /**
     * 执行K-Means聚类
     */
    void performKMeansClustering(int k);

    /**
     * 获取用户分群分布
     */
    List<Map<String, Object>> getUserGroupDistribution();

    /**
     * 获取聚类分布
     */
    List<Map<String, Object>> getClusterDistribution();

    /**
     * 获取RFM评分分布
     */
    List<Map<String, Object>> getRFMScoreDistribution();

    /**
     * 获取聚类中心
     */
    List<Map<String, Object>> getClusterCenters();

    /**
     * 获取高价值用户
     */
    List<UserProfile> getHighValueUsers(int limit);

    /**
     * 获取TOP用户（支持按分群筛选）
     */
    List<UserProfile> getTopUsersByGroup(String userGroup, int limit);
}
