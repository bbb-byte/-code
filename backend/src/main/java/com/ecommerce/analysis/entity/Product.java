package com.ecommerce.analysis.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商品实体类
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 商品ID (数据集中的item_id)
     */
    private Long itemId;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品类目ID
     */
    private Long categoryId;

    /**
     * 商品类目名称
     */
    private String categoryName;

    /**
     * 浏览次数
     */
    private Integer viewCount;

    /**
     * 加购次数
     */
    private Integer cartCount;

    /**
     * 收藏次数
     */
    private Integer favCount;

    /**
     * 购买次数
     */
    private Integer buyCount;

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
