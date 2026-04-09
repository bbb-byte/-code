package com.ecommerce.analysis.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品公网满意度指标最新快照。
 */
@Data
@TableName("product_public_metric")
public class ProductPublicMetric {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    private String sourcePlatform;

    private String sourceProductId;

    private String sourceUrl;

    /**
     * 好评率百分比，例如 97.27 表示 97.27%。
     */
    private BigDecimal positiveRate;

    private Long reviewCount;

    private BigDecimal shopScore;

    private String ratingText;

    private String crawlStatus;

    private String crawlMessage;

    @TableField("raw_payload")
    private String rawPayload;

    private LocalDateTime crawledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
