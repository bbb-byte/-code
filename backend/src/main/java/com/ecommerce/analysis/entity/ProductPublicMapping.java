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
 * 内部商品与公网商品页面的人工校验映射。
 */
@Data
@TableName("product_public_mapping")
public class ProductPublicMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    private String sourcePlatform;

    private String sourceProductId;

    private String sourceUrl;

    private String verifiedTitle;

    private BigDecimal mappingConfidence;

    private String verificationNote;

    private String evidenceNote;

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
