package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 用户画像Mapper接口
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 获取用户分群统计
     */
    @Select("SELECT user_group, COUNT(*) as count FROM user_profile GROUP BY user_group")
    List<Map<String, Object>> countByUserGroup();

    /**
     * 获取聚类分布统计（排除未聚类和未转化用户，仅保留有效K-Means簇）
     */
    @Select("SELECT cluster_id, COUNT(*) as count FROM user_profile WHERE cluster_id >= 0 GROUP BY cluster_id ORDER BY cluster_id")
    List<Map<String, Object>> countByCluster();

    /**
     * 获取RFM评分分布
     */
    @Select("SELECT rfm_score, COUNT(*) as count FROM user_profile GROUP BY rfm_score ORDER BY rfm_score")
    List<Map<String, Object>> getRfmScoreDistribution();

    /**
     * 获取高价值用户
     */
    @Select("SELECT * FROM user_profile WHERE user_group = '高价值用户' ORDER BY rfm_score DESC LIMIT #{limit}")
    List<UserProfile> getHighValueUsers(@Param("limit") int limit);

    /**
     * 获取TOP用户（支持按分群筛选）
     */
    @Select("<script>" +
            "SELECT * FROM user_profile " +
            "<if test=\"userGroup != null and userGroup != '' and userGroup != 'all'\"> WHERE user_group = #{userGroup} </if>"
            +
            "ORDER BY rfm_score DESC, total_buys DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<UserProfile> getTopUsersByGroup(@Param("userGroup") String userGroup, @Param("limit") int limit);

    /**
     * 获取聚类中心(各簇的RFM均值)（排除未聚类和未转化用户，仅保留有效K-Means簇）
     */
    @Select("SELECT cluster_id, " +
            "AVG(recency) as avg_recency, " +
            "AVG(frequency) as avg_frequency, " +
            "AVG(monetary) as avg_monetary " +
            "FROM user_profile WHERE cluster_id >= 0 GROUP BY cluster_id ORDER BY cluster_id")
    List<Map<String, Object>> getClusterCenters();
}
