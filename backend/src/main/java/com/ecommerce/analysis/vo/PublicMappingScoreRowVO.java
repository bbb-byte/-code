package com.ecommerce.analysis.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 公网映射评分预览行。
 */
@Data
public class PublicMappingScoreRowVO {

    private Long itemId;

    private String brand;

    private String categoryName;

    private BigDecimal internalPrice;

    private String sourcePlatform;

    private String sourceProductId;

    private String sourceUrl;

    private String publicTitle;

    private String publicBrand;

    private String publicCategory;

    private BigDecimal publicPrice;

    private BigDecimal brandScore;

    private BigDecimal categoryScore;

    private BigDecimal priceScore;

    private BigDecimal titleScore;

    private BigDecimal evidenceScore;

    private BigDecimal totalScore;

    private String recommendedAction;

    private String scoreReason;

    private String verifiedTitle;

    private BigDecimal mappingConfidence;

    private String verificationNote;

    private String evidenceNote;
}
