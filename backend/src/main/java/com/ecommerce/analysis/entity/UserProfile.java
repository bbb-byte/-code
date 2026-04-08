package com.ecommerce.analysis.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户画像实体类
 * 基于RFM模型构建
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID (关联user_behavior表的user_id)
     */
    private Long userId;

    /**
     * Recency - 最近一次消费距今天数
     */
    private Integer recency;

    /**
     * Frequency - 消费频率(购买次数)
     */
    private Integer frequency;

    /**
     * Monetary - 消费金额(基于真实价格字段聚合)
     */
    private BigDecimal monetary;

    /**
     * R评分 (1-5)
     */
    @JsonProperty("rScore")
    private Integer rScore;

    /**
     * F评分 (1-5)
     */
    @JsonProperty("fScore")
    private Integer fScore;

    /**
     * M评分 (1-5)
     */
    @JsonProperty("mScore")
    private Integer mScore;

    /**
     * RFM总分
     */
    private Integer rfmScore;

    /**
     * 用户分群标签
     */
    private String userGroup;

    /**
     * 聚类簇编号
     */
    private Integer clusterId;

    /**
     * 总浏览次数
     */
    private Integer totalViews;

    /**
     * 总加购次数
     */
    private Integer totalCarts;

    /**
     * 总收藏次数
     */
    private Integer totalFavs;

    /**
     * 总购买次数
     */
    private Integer totalBuys;

    /**
     * 购买转化率
     */
    private BigDecimal conversionRate;

    /**
     * 最后活跃时间
     */
    private LocalDateTime lastActiveTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
