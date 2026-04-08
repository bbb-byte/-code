package com.ecommerce.analysis.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户行为记录实体类
 * 对应淘宝用户行为数据集
 */
@Data
@TableName("user_behavior")
public class UserBehavior {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事件唯一ID (用于去重，格式: user_id_item_id_type_time 的hash)
     */
    private String eventId;

    /**
     * 用户ID (数据集中的user_id)
     */
    private Long userId;

    /**
     * 商品ID (数据集中的item_id)
     */
    private Long itemId;

    /**
     * 商品类目ID (数据集中的category_id)
     */
    private Long categoryId;

    /**
     * 行为类型: pv-浏览, buy-购买, cart-加购, fav-收藏
     */
    private String behaviorType;

    /**
     * 行为时间戳 (Unix时间戳)
     */
    private Long behaviorTime;

    /**
     * 行为日期时间 (从时间戳转换)
     */
    private LocalDateTime behaviorDateTime;

    /**
     * 商品单价 (仅buy行为有值，来自爬虫获取)
     */
    private BigDecimal unitPrice;

    /**
     * 购买数量 (默认1)
     */
    private Integer qty;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
