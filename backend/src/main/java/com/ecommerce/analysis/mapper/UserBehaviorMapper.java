package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.UserBehavior;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 用户行为Mapper接口
 */
@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {

        /**
         * 获取行为类型统计 (优化：统计最近活跃的一天)
         */
        @Select("SELECT behavior_type, COUNT(*) as count FROM user_behavior " +
                        "WHERE behavior_date_time >= (SELECT DATE(MAX(behavior_date_time)) FROM user_behavior) " +
                        "GROUP BY behavior_type")
        List<Map<String, Object>> countByBehaviorType();

        /**
         * 获取每日行为统计
         */
        @Select("SELECT DATE(behavior_date_time) as date, behavior_type, COUNT(*) as count " +
                        "FROM user_behavior " +
                        "WHERE behavior_date_time BETWEEN #{startDate} AND #{endDate} " +
                        "GROUP BY DATE(behavior_date_time), behavior_type " +
                        "ORDER BY date")
        List<Map<String, Object>> getDailyBehaviorStats(@Param("startDate") String startDate,
                        @Param("endDate") String endDate);

        /**
         * 获取热门商品(按浏览量)
         */
        @Select("SELECT item_id, COUNT(*) as view_count " +
                        "FROM user_behavior WHERE behavior_type = 'pv' " +
                        "GROUP BY item_id ORDER BY view_count DESC LIMIT #{limit}")
        List<Map<String, Object>> getHotProductsByView(@Param("limit") int limit);

        /**
         * 获取热门商品(按购买量)
         */
        @Select("SELECT item_id, COUNT(*) as buy_count " +
                        "FROM user_behavior WHERE behavior_type = 'buy' " +
                        "GROUP BY item_id ORDER BY buy_count DESC LIMIT #{limit}")
        List<Map<String, Object>> getHotProductsByBuy(@Param("limit") int limit);

        /**
         * 获取热门类目
         */
        @Select("SELECT category_id, COUNT(*) as count " +
                        "FROM user_behavior WHERE behavior_type = 'buy' " +
                        "GROUP BY category_id ORDER BY count DESC LIMIT #{limit}")
        List<Map<String, Object>> getHotCategories(@Param("limit") int limit);

        /**
         * 获取用户行为汇总(用于RFM计算，M值 = Σ(unit_price * qty))
         */
        @Select("SELECT user_id, " +
                        "SUM(CASE WHEN behavior_type = 'pv' THEN 1 ELSE 0 END) as total_views, " +
                        "SUM(CASE WHEN behavior_type = 'cart' THEN 1 ELSE 0 END) as total_carts, " +
                        "SUM(CASE WHEN behavior_type = 'fav' THEN 1 ELSE 0 END) as total_favs, " +
                        "SUM(CASE WHEN behavior_type = 'buy' THEN 1 ELSE 0 END) as total_buys, " +
                        "COALESCE(SUM(CASE WHEN behavior_type = 'buy' THEN unit_price * COALESCE(qty, 1) ELSE 0 END), 0) as total_amount, "
                        +
                        "MAX(behavior_date_time) as last_active_time, " +
                        "MAX(CASE WHEN behavior_type = 'buy' THEN behavior_date_time ELSE NULL END) as last_buy_time " +
                        "FROM user_behavior GROUP BY user_id")
        List<Map<String, Object>> getUserBehaviorSummary();

        /**
         * 获取转化漏斗数据（三步序列漏斗：浏览→加购→购买）
         * 每一步都是前一步的子集，确保转化率不会超过100%
         */
        @Select("SELECT " +
                        "(SELECT COUNT(DISTINCT user_id) FROM user_behavior WHERE behavior_type = 'pv') as pv_users, " +
                        "(SELECT COUNT(DISTINCT user_id) FROM user_behavior " +
                        " WHERE behavior_type = 'cart' " +
                        " AND user_id IN (SELECT DISTINCT user_id FROM user_behavior WHERE behavior_type = 'pv')) as cart_users, "
                        +
                        "(SELECT COUNT(DISTINCT user_id) FROM user_behavior " +
                        " WHERE behavior_type = 'buy' " +
                        " AND user_id IN (SELECT DISTINCT user_id FROM user_behavior WHERE behavior_type = 'cart')) as buy_users")
        Map<String, Object> getConversionFunnel();

        /**
         * 获取每小时行为分布 (优化：优先展示最近活跃的一天，如果没有则展示历史汇总)
         */
        @Select("SELECT HOUR(behavior_date_time) as hour, COUNT(*) as count " +
                        "FROM user_behavior " +
                        "WHERE behavior_date_time >= (SELECT DATE(MAX(behavior_date_time)) FROM user_behavior) " +
                        "GROUP BY HOUR(behavior_date_time) ORDER BY hour")
        List<Map<String, Object>> getHourlyDistribution();

        /**
         * 获取用户数量
         */
        @Select("SELECT COUNT(DISTINCT user_id) FROM user_behavior")
        Long countDistinctUsers();

        /**
         * 获取商品数量
         */
        @Select("SELECT COUNT(DISTINCT item_id) FROM user_behavior")
        Long countDistinctItems();

        /**
         * 获取类目数量
         */
        @Select("SELECT COUNT(DISTINCT category_id) FROM user_behavior")
        Long countDistinctCategories();

        /**
         * 获取最新导入的行为记录
         */
        @Select("SELECT * FROM user_behavior ORDER BY id DESC LIMIT #{limit}")
        List<UserBehavior> getLatestBehaviors(@Param("limit") int limit);
}
