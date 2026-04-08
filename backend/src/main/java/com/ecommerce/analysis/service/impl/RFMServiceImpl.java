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
import java.util.*;
import java.util.stream.Collectors;

/**
 * RFM分析服务实现类
 */
@Slf4j
@Service
public class RFMServiceImpl implements RFMService {

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    // 基准时间点(数据集最大时间 2017-12-03)
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2017, 12, 4, 0, 0, 0);

    @Override
    @Transactional
    public void calculateAllUserRFM() {
        log.info("开始计算所有用户的RFM值...");

        // 获取用户行为汇总数据
        List<Map<String, Object>> summaryList = userBehaviorMapper.getUserBehaviorSummary();
        log.info("共获取到 {} 个用户的行为汇总数据", summaryList.size());

        // 分离有购买和无购买的用户
        List<UserProfile> buyerProfiles = new ArrayList<>();
        List<UserProfile> nonBuyerProfiles = new ArrayList<>();

        // 仅收集有购买行为的用户的RFM值，用于计算分位数基线
        List<Integer> buyerRecency = new ArrayList<>();
        List<Integer> buyerFrequency = new ArrayList<>();
        List<BigDecimal> buyerMonetary = new ArrayList<>();

        for (Map<String, Object> summary : summaryList) {
            UserProfile profile = new UserProfile();
            profile.setUserId(((Number) summary.get("user_id")).longValue());

            // 行为统计
            int totalViews = ((Number) summary.get("total_views")).intValue();
            int totalCarts = ((Number) summary.get("total_carts")).intValue();
            int totalFavs = ((Number) summary.get("total_favs")).intValue();
            int totalBuys = ((Number) summary.get("total_buys")).intValue();

            profile.setTotalViews(totalViews);
            profile.setTotalCarts(totalCarts);
            profile.setTotalFavs(totalFavs);
            profile.setTotalBuys(totalBuys);

            // 最后活跃时间
            Object lastActiveObj = summary.get("last_active_time");
            LocalDateTime lastActiveTime = null;
            if (lastActiveObj instanceof LocalDateTime) {
                lastActiveTime = (LocalDateTime) lastActiveObj;
            } else if (lastActiveObj instanceof java.sql.Timestamp) {
                lastActiveTime = ((java.sql.Timestamp) lastActiveObj).toLocalDateTime();
            }
            profile.setLastActiveTime(lastActiveTime);

            // 计算R - 最近购买距今天数（使用最后购买时间而非最后活跃时间）
            Object lastBuyObj = summary.get("last_buy_time");
            LocalDateTime lastBuyTime = null;
            if (lastBuyObj instanceof LocalDateTime) {
                lastBuyTime = (LocalDateTime) lastBuyObj;
            } else if (lastBuyObj instanceof java.sql.Timestamp) {
                lastBuyTime = ((java.sql.Timestamp) lastBuyObj).toLocalDateTime();
            }

            int recency = 999; // 默认值（未购买用户）
            if (lastBuyTime != null) {
                recency = (int) ChronoUnit.DAYS.between(lastBuyTime, BASE_TIME);
                if (recency < 0)
                    recency = 0;
            }
            profile.setRecency(recency);

            // 计算F - 消费频率(购买次数)
            profile.setFrequency(totalBuys);

            // 计算M - 消费金额(使用真实交易金额)
            Object amountObj = summary.get("total_amount");
            BigDecimal monetary = BigDecimal.ZERO;
            if (amountObj != null) {
                if (amountObj instanceof BigDecimal) {
                    monetary = (BigDecimal) amountObj;
                } else if (amountObj instanceof Number) {
                    monetary = BigDecimal.valueOf(((Number) amountObj).doubleValue());
                }
            }
            // 如果没有真实金额数据，则用购买次数*模拟单价
            if (monetary.compareTo(BigDecimal.ZERO) == 0 && totalBuys > 0) {
                monetary = BigDecimal.valueOf(totalBuys * 35.0); // 模拟平均客单价35元
            }
            profile.setMonetary(monetary);

            // 计算转化率
            if (totalViews > 0) {
                BigDecimal conversionRate = BigDecimal.valueOf(totalBuys)
                        .divide(BigDecimal.valueOf(totalViews), 4, RoundingMode.HALF_UP);
                profile.setConversionRate(conversionRate);
            } else {
                profile.setConversionRate(BigDecimal.ZERO);
            }

            // 按是否有购买行为分组
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

        // 仅基于有购买行为的用户计算分位数
        Collections.sort(buyerRecency);
        Collections.sort(buyerFrequency);
        Collections.sort(buyerMonetary);

        // 对有购买行为的用户计算RFM评分
        for (UserProfile profile : buyerProfiles) {
            // R评分 (越近越好，所以反向计算)
            int rScore = 5 - getQuintileScore(profile.getRecency(), buyerRecency) + 1;
            profile.setRScore(Math.max(1, Math.min(5, rScore)));

            // F评分 (越多越好)
            int fScore = getQuintileScore(profile.getFrequency(), buyerFrequency);
            profile.setFScore(Math.max(1, Math.min(5, fScore)));

            // M评分 (越多越好)
            int mScore = getQuintileScore(profile.getMonetary().intValue(),
                    buyerMonetary.stream().map(BigDecimal::intValue).collect(Collectors.toList()));
            profile.setMScore(Math.max(1, Math.min(5, mScore)));

            // RFM总分
            int rfmScore = profile.getRScore() + profile.getFScore() + profile.getMScore();
            profile.setRfmScore(rfmScore);

            // 根据RFM值确定用户分群
            profile.setUserGroup(determineUserGroup(profile));
        }

        // 无购买行为的用户: 标记为未转化用户，RFM各项评1分
        for (UserProfile profile : nonBuyerProfiles) {
            profile.setRScore(1);
            profile.setFScore(1);
            profile.setMScore(1);
            profile.setRfmScore(3);
            profile.setClusterId(-1);
            profile.setUserGroup("未转化用户");
        }

        // 合并所有用户并批量保存
        List<UserProfile> allProfiles = new ArrayList<>();
        allProfiles.addAll(buyerProfiles);
        allProfiles.addAll(nonBuyerProfiles);

        for (UserProfile profile : allProfiles) {
            // 检查是否已存在
            LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserProfile::getUserId, profile.getUserId());
            UserProfile existing = userProfileMapper.selectOne(wrapper);

            if (existing != null) {
                profile.setId(existing.getId());
                userProfileMapper.updateById(profile);
            } else {
                userProfileMapper.insert(profile);
            }
        }

        log.info("RFM计算完成，共处理 {} 个用户（购买用户: {}, 未转化: {}）",
                allProfiles.size(), buyerProfiles.size(), nonBuyerProfiles.size());
    }

    @Override
    public UserProfile calculateUserRFM(Long userId) {
        // 获取用户的RFM画像
        LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserProfile::getUserId, userId);
        return userProfileMapper.selectOne(wrapper);
    }

    @Override
    @Transactional
    public void performKMeansClustering(int k) {
        log.info("开始执行K-Means聚类，K={}", k);

        // 获取所有用户画像
        List<UserProfile> profiles = userProfileMapper.selectList(null);
        if (profiles.isEmpty()) {
            log.warn("没有用户画像数据，请先执行RFM计算");
            return;
        }

        // 过滤：只对有购买行为的用户进行聚类
        List<UserProfile> profilesWithBuys = profiles.stream()
                .filter(p -> p.getTotalBuys() != null && p.getTotalBuys() > 0)
                .collect(Collectors.toList());

        log.info("有购买行为的用户数: {}，无购买用户数: {}",
                profilesWithBuys.size(), profiles.size() - profilesWithBuys.size());

        // 无购买用户标记为“未转化” (clusterId = -1)
        profiles.stream()
                .filter(p -> p.getTotalBuys() == null || p.getTotalBuys() == 0)
                .forEach(p -> {
                    p.setClusterId(-1);
                    p.setUserGroup("未转化用户");
                    userProfileMapper.updateById(p);
                });

        if (profilesWithBuys.size() < k) {
            log.warn("有购买的用户数({}) 小于 K({})，无法聚类", profilesWithBuys.size(), k);
            return;
        }

        // 准备聚类数据点
        List<DoublePoint> points = new ArrayList<>();
        Map<DoublePoint, Long> pointUserMap = new HashMap<>();

        for (UserProfile profile : profilesWithBuys) {
            // 使用RFM评分作为聚类特征
            double[] values = new double[] {
                    profile.getRScore() != null ? profile.getRScore() : 1,
                    profile.getFScore() != null ? profile.getFScore() : 1,
                    profile.getMScore() != null ? profile.getMScore() : 1
            };
            DoublePoint point = new DoublePoint(values);
            points.add(point);
            pointUserMap.put(point, profile.getUserId());
        }

        // 执行K-Means++聚类
        KMeansPlusPlusClusterer<DoublePoint> clusterer = new KMeansPlusPlusClusterer<>(k, 1000);
        List<CentroidCluster<DoublePoint>> clusters = clusterer.cluster(points);

        // 更新用户的聚类标签
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

        // 批量更新
        for (UserProfile profile : profilesWithBuys) {
            Integer clusterId = userClusterMap.get(profile.getUserId());
            if (clusterId != null) {
                profile.setClusterId(clusterId);
                userProfileMapper.updateById(profile);
            }
        }

        log.info("K-Means聚类完成，共生成 {} 个簇", clusters.size());
    }

    @Override
    public List<Map<String, Object>> getUserGroupDistribution() {
        return userProfileMapper.countByUserGroup();
    }

    @Override
    public List<Map<String, Object>> getClusterDistribution() {
        return userProfileMapper.countByCluster();
    }

    @Override
    public List<Map<String, Object>> getRFMScoreDistribution() {
        return userProfileMapper.getRfmScoreDistribution();
    }

    @Override
    public List<Map<String, Object>> getClusterCenters() {
        return userProfileMapper.getClusterCenters();
    }

    @Override
    public List<UserProfile> getHighValueUsers(int limit) {
        return userProfileMapper.getHighValueUsers(limit);
    }

    @Override
    public List<UserProfile> getTopUsersByGroup(String userGroup, int limit) {
        return userProfileMapper.getTopUsersByGroup(userGroup, limit);
    }

    /**
     * 根据RFM值确定用户分群
     */
    private String determineUserGroup(UserProfile profile) {
        int r = profile.getRScore();
        int f = profile.getFScore();
        int m = profile.getMScore();
        int total = r + f + m;

        // 根据RFM组合判断用户类型
        if (r >= 4 && f >= 4 && m >= 4) {
            return Constants.GROUP_HIGH_VALUE; // 高价值用户
        } else if (r >= 4 && f >= 3) {
            return Constants.GROUP_POTENTIAL; // 潜力用户
        } else if (total >= 10) {
            return Constants.GROUP_POTENTIAL; // 潜力用户
        } else if (r <= 2 && f <= 2) {
            return Constants.GROUP_LOST; // 流失用户
        } else if (r <= 2) {
            return Constants.GROUP_SLEEPING; // 沉睡用户
        } else if (f <= 2 && m <= 2) {
            return Constants.GROUP_NEW; // 新用户
        } else {
            return Constants.GROUP_POTENTIAL; // 潜力用户
        }
    }

    /**
     * 计算五分位评分
     */
    private int getQuintileScore(int value, List<Integer> sortedList) {
        if (sortedList.isEmpty())
            return 1;

        int size = sortedList.size();
        int q1 = sortedList.get(size / 5);
        int q2 = sortedList.get(size * 2 / 5);
        int q3 = sortedList.get(size * 3 / 5);
        int q4 = sortedList.get(size * 4 / 5);

        if (value <= q1)
            return 1;
        if (value <= q2)
            return 2;
        if (value <= q3)
            return 3;
        if (value <= q4)
            return 4;
        return 5;
    }
}
