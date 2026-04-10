package com.ecommerce.analysis.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.analysis.common.Constants;
import com.ecommerce.analysis.entity.UserProfile;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.mapper.UserProfileMapper;
import com.ecommerce.analysis.service.RFMService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.DoublePoint;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * RFM analysis service implementation.
 */
@Slf4j
@Service
public class RFMServiceImpl implements RFMService {

    private static final String ANALYSIS_BUSY_MESSAGE = "已有画像分析任务正在执行，请稍后重试";
    private static final String NON_CONVERTED_GROUP = "未转化用户";
    private static final long PROFILE_CACHE_TTL_MINUTES = 10;

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private AnalysisCacheService analysisCacheService;

    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    @Override
    @Transactional
    public void calculateAllUserRFM() {
        beginAnalysisTask("calculate-rfm");
        try {
            doCalculateAllUserRFM();
        } finally {
            finishAnalysisTask("calculate-rfm");
        }
    }

    private void doCalculateAllUserRFM() {
        log.info("开始计算所有用户的 RFM 值...");

        List<Map<String, Object>> summaryList = userBehaviorMapper.getUserBehaviorSummary();
        log.info("共获取到 {} 个用户的行为汇总数据", summaryList.size());

        List<UserProfile> buyerProfiles = new ArrayList<>();
        List<UserProfile> nonBuyerProfiles = new ArrayList<>();

        List<Integer> buyerRecency = new ArrayList<>();
        List<Integer> buyerFrequency = new ArrayList<>();
        List<BigDecimal> buyerMonetary = new ArrayList<>();
        LocalDateTime baseTime = resolveBaseTime(summaryList);

        for (Map<String, Object> summary : summaryList) {
            UserProfile profile = new UserProfile();
            profile.setUserId(((Number) summary.get("user_id")).longValue());

            int totalViews = ((Number) summary.get("total_views")).intValue();
            int totalCarts = ((Number) summary.get("total_carts")).intValue();
            int totalFavs = ((Number) summary.get("total_favs")).intValue();
            int totalBuys = ((Number) summary.get("total_buys")).intValue();

            profile.setTotalViews(totalViews);
            profile.setTotalCarts(totalCarts);
            profile.setTotalFavs(totalFavs);
            profile.setTotalBuys(totalBuys);

            Object lastActiveObj = summary.get("last_active_time");
            LocalDateTime lastActiveTime = null;
            if (lastActiveObj instanceof LocalDateTime) {
                lastActiveTime = (LocalDateTime) lastActiveObj;
            } else if (lastActiveObj instanceof java.sql.Timestamp) {
                lastActiveTime = ((java.sql.Timestamp) lastActiveObj).toLocalDateTime();
            }
            profile.setLastActiveTime(lastActiveTime);

            Object lastBuyObj = summary.get("last_buy_time");
            LocalDateTime lastBuyTime = null;
            if (lastBuyObj instanceof LocalDateTime) {
                lastBuyTime = (LocalDateTime) lastBuyObj;
            } else if (lastBuyObj instanceof java.sql.Timestamp) {
                lastBuyTime = ((java.sql.Timestamp) lastBuyObj).toLocalDateTime();
            }

            int recency = 999;
            if (lastBuyTime != null) {
                recency = (int) ChronoUnit.DAYS.between(lastBuyTime, baseTime);
                if (recency < 0) {
                    recency = 0;
                }
            }
            profile.setRecency(recency);

            profile.setFrequency(totalBuys);

            Object amountObj = summary.get("total_amount");
            BigDecimal monetary = BigDecimal.ZERO;
            if (amountObj != null) {
                if (amountObj instanceof BigDecimal) {
                    monetary = (BigDecimal) amountObj;
                } else if (amountObj instanceof Number) {
                    monetary = BigDecimal.valueOf(((Number) amountObj).doubleValue());
                }
            }
            profile.setMonetary(monetary);

            if (totalViews > 0) {
                BigDecimal conversionRate = BigDecimal.valueOf(totalBuys)
                        .divide(BigDecimal.valueOf(totalViews), 4, RoundingMode.HALF_UP);
                profile.setConversionRate(conversionRate);
            } else {
                profile.setConversionRate(BigDecimal.ZERO);
            }

            if (totalBuys > 0) {
                buyerProfiles.add(profile);
                buyerRecency.add(recency);
                buyerFrequency.add(totalBuys);
                buyerMonetary.add(monetary);
            } else {
                nonBuyerProfiles.add(profile);
            }
        }

        log.info("有购买行为的用户: {} 个，无购买行为的用户: {} 个",
                buyerProfiles.size(), nonBuyerProfiles.size());

        Collections.sort(buyerRecency);
        Collections.sort(buyerFrequency);
        Collections.sort(buyerMonetary);

        for (UserProfile profile : buyerProfiles) {
            int rScore = 5 - getQuintileScore(profile.getRecency(), buyerRecency) + 1;
            profile.setRScore(Math.max(1, Math.min(5, rScore)));

            int fScore = getQuintileScore(profile.getFrequency(), buyerFrequency);
            profile.setFScore(Math.max(1, Math.min(5, fScore)));

            int mScore = getQuintileScore(
                    profile.getMonetary().intValue(),
                    buyerMonetary.stream().map(BigDecimal::intValue).collect(Collectors.toList()));
            profile.setMScore(Math.max(1, Math.min(5, mScore)));

            int rfmScore = profile.getRScore() + profile.getFScore() + profile.getMScore();
            profile.setRfmScore(rfmScore);
            profile.setUserGroup(determineUserGroup(profile));
        }

        for (UserProfile profile : nonBuyerProfiles) {
            profile.setRScore(1);
            profile.setFScore(1);
            profile.setMScore(1);
            profile.setRfmScore(3);
            profile.setClusterId(-1);
            profile.setUserGroup(NON_CONVERTED_GROUP);
        }

        List<UserProfile> allProfiles = new ArrayList<>();
        allProfiles.addAll(buyerProfiles);
        allProfiles.addAll(nonBuyerProfiles);

        for (UserProfile profile : allProfiles) {
            userProfileMapper.upsertProfile(profile);
        }

        log.info("RFM 计算完成，共处理 {} 个用户（购买用户: {}，未转化用户: {}）",
                allProfiles.size(), buyerProfiles.size(), nonBuyerProfiles.size());
    }

    private LocalDateTime resolveBaseTime(List<Map<String, Object>> summaryList) {
        LocalDateTime maxActiveTime = summaryList.stream()
                .map(summary -> {
                    Object lastActiveObj = summary.get("last_active_time");
                    if (lastActiveObj instanceof LocalDateTime) {
                        return (LocalDateTime) lastActiveObj;
                    }
                    if (lastActiveObj instanceof java.sql.Timestamp) {
                        return ((java.sql.Timestamp) lastActiveObj).toLocalDateTime();
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
        return maxActiveTime.plusDays(1);
    }

    @Override
    public UserProfile calculateUserRFM(Long userId) {
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserProfile::getUserId, userId);
        return userProfileMapper.selectOne(wrapper);
    }

    @Override
    @Transactional
    public void performKMeansClustering(int k) {
        beginAnalysisTask("kmeans-clustering");
        try {
            doPerformKMeansClustering(k);
        } finally {
            finishAnalysisTask("kmeans-clustering");
        }
    }

    private void doPerformKMeansClustering(int k) {
        log.info("开始执行 K-Means 聚类，K={}", k);

        List<UserProfile> profiles = userProfileMapper.selectList(null);
        if (profiles.isEmpty()) {
            log.warn("没有用户画像数据，请先执行 RFM 计算");
            return;
        }

        List<UserProfile> profilesWithBuys = profiles.stream()
                .filter(p -> p.getTotalBuys() != null && p.getTotalBuys() > 0)
                .collect(Collectors.toList());

        log.info("有购买行为的用户数: {}，无购买行为的用户数: {}",
                profilesWithBuys.size(), profiles.size() - profilesWithBuys.size());

        profiles.stream()
                .filter(p -> p.getTotalBuys() == null || p.getTotalBuys() == 0)
                .forEach(p -> {
                    p.setClusterId(-1);
                    p.setUserGroup(NON_CONVERTED_GROUP);
                    userProfileMapper.updateById(p);
                });

        if (profilesWithBuys.size() < k) {
            log.warn("有购买行为的用户数({}) 小于 K({})，无法聚类", profilesWithBuys.size(), k);
            return;
        }

        List<DoublePoint> points = new ArrayList<>();
        Map<DoublePoint, Long> pointUserMap = new HashMap<>();

        for (UserProfile profile : profilesWithBuys) {
            double[] values = new double[] {
                    profile.getRScore() != null ? profile.getRScore() : 1,
                    profile.getFScore() != null ? profile.getFScore() : 1,
                    profile.getMScore() != null ? profile.getMScore() : 1
            };
            DoublePoint point = new DoublePoint(values);
            points.add(point);
            pointUserMap.put(point, profile.getUserId());
        }

        KMeansPlusPlusClusterer<DoublePoint> clusterer = new KMeansPlusPlusClusterer<>(k, 1000);
        List<CentroidCluster<DoublePoint>> clusters = clusterer.cluster(points);

        Map<Long, Integer> userClusterMap = new HashMap<>();
        for (int i = 0; i < clusters.size(); i++) {
            CentroidCluster<DoublePoint> cluster = clusters.get(i);
            for (DoublePoint point : cluster.getPoints()) {
                Long userId = pointUserMap.get(point);
                if (userId != null) {
                    userClusterMap.put(userId, i);
                }
            }
        }

        for (UserProfile profile : profilesWithBuys) {
            Integer clusterId = userClusterMap.get(profile.getUserId());
            if (clusterId != null) {
                profile.setClusterId(clusterId);
                userProfileMapper.updateById(profile);
            }
        }

        log.info("K-Means 聚类完成，共生成 {} 个簇", clusters.size());
    }

    private void beginAnalysisTask(String taskName) {
        if (!analysisRunning.compareAndSet(false, true)) {
            throw new IllegalStateException(ANALYSIS_BUSY_MESSAGE);
        }
        log.info("开始执行画像分析任务: {}", taskName);
    }

    private void finishAnalysisTask(String taskName) {
        analysisRunning.set(false);
        analysisCacheService.evictAllAnalyticsCaches();
        log.info("画像分析任务结束: {}", taskName);
    }

    @Override
    public List<Map<String, Object>> getUserGroupDistribution() {
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "group-distribution";
        return analysisCacheService.getOrLoad(cacheKey, PROFILE_CACHE_TTL_MINUTES,
                userProfileMapper::countByUserGroup);
    }

    @Override
    public List<Map<String, Object>> getClusterDistribution() {
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "cluster-distribution";
        return analysisCacheService.getOrLoad(cacheKey, PROFILE_CACHE_TTL_MINUTES,
                userProfileMapper::countByCluster);
    }

    @Override
    public List<Map<String, Object>> getRFMScoreDistribution() {
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "rfm-distribution";
        return analysisCacheService.getOrLoad(cacheKey, PROFILE_CACHE_TTL_MINUTES,
                userProfileMapper::getRfmScoreDistribution);
    }

    @Override
    public List<Map<String, Object>> getClusterCenters() {
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "cluster-centers";
        return analysisCacheService.getOrLoad(cacheKey, PROFILE_CACHE_TTL_MINUTES,
                userProfileMapper::getClusterCenters);
    }

    @Override
    public List<UserProfile> getHighValueUsers(int limit) {
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "high-value-users:" + limit;
        return analysisCacheService.getOrLoadList(cacheKey, PROFILE_CACHE_TTL_MINUTES, UserProfile.class,
                () -> userProfileMapper.getHighValueUsers(limit));
    }

    @Override
    public List<UserProfile> getTopUsersByGroup(String userGroup, int limit) {
        String normalizedGroup = userGroup == null ? "all" : userGroup;
        String cacheKey = Constants.REDIS_PROFILE_PREFIX + "top-users:" + normalizedGroup + ":" + limit;
        return analysisCacheService.getOrLoadList(cacheKey, PROFILE_CACHE_TTL_MINUTES, UserProfile.class,
                () -> userProfileMapper.getTopUsersByGroup(userGroup, limit));
    }

    private String determineUserGroup(UserProfile profile) {
        int r = profile.getRScore();
        int f = profile.getFScore();
        int m = profile.getMScore();
        int total = r + f + m;

        if (r >= 4 && f >= 4 && m >= 4) {
            return Constants.GROUP_HIGH_VALUE;
        } else if (r >= 4 && f >= 3) {
            return Constants.GROUP_POTENTIAL;
        } else if (total >= 10) {
            return Constants.GROUP_POTENTIAL;
        } else if (r <= 2 && f <= 2) {
            return Constants.GROUP_LOST;
        } else if (r <= 2) {
            return Constants.GROUP_SLEEPING;
        } else if (f <= 2 && m <= 2) {
            return Constants.GROUP_NEW;
        } else {
            return Constants.GROUP_POTENTIAL;
        }
    }

    private int getQuintileScore(int value, List<Integer> sortedList) {
        if (sortedList.isEmpty()) {
            return 1;
        }

        int size = sortedList.size();
        int q1 = sortedList.get(size / 5);
        int q2 = sortedList.get(size * 2 / 5);
        int q3 = sortedList.get(size * 3 / 5);
        int q4 = sortedList.get(size * 4 / 5);

        if (value <= q1) {
            return 1;
        }
        if (value <= q2) {
            return 2;
        }
        if (value <= q3) {
            return 3;
        }
        if (value <= q4) {
            return 4;
        }
        return 5;
    }
}
