package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.UserBehavior;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 鐢ㄦ埛琛屼负Mapper鎺ュ彛
 */
@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {

        @Insert("INSERT IGNORE INTO user_behavior " +
                        "(event_id, user_id, item_id, category_id, behavior_type, behavior_time, behavior_date_time, unit_price, qty) " +
                        "VALUES (#{eventId}, #{userId}, #{itemId}, #{categoryId}, #{behaviorType}, #{behaviorTime}, #{behaviorDateTime}, #{unitPrice}, #{qty})")
        int insertIgnore(UserBehavior behavior);

        /**
         * 鑾峰彇琛屼负绫诲瀷缁熻
         */
        @Select("SELECT behavior_type, COUNT(*) as count FROM user_behavior GROUP BY behavior_type")
        List<Map<String, Object>> countByBehaviorType();

        /**
         * 鑾峰彇姣忔棩琛屼负缁熻
         */
        @Select("SELECT DATE(behavior_date_time) as date, behavior_type, COUNT(*) as count " +
                        "FROM user_behavior " +
                        "WHERE behavior_date_time BETWEEN #{startDate} AND DATE_ADD(#{endDate}, INTERVAL 1 DAY) " +
                        "GROUP BY DATE(behavior_date_time), behavior_type " +
                        "ORDER BY date")
        List<Map<String, Object>> getDailyBehaviorStats(@Param("startDate") String startDate,
                        @Param("endDate") String endDate);

        /**
         * 鑾峰彇鐑棬鍟嗗搧(鎸夋祻瑙堥噺)
         */
        @Select("SELECT hot.item_id, " +
                        "COALESCE(NULLIF(p.name, ''), CONCAT('鍟嗗搧#', hot.item_id)) as product_name, " +
                        "p.brand as brand, " +
                        "p.category_name as category_name, " +
                        "p.price as price, " +
                        "hot.view_count as view_count " +
                        "FROM (" +
                        "SELECT item_id, COUNT(*) as view_count " +
                        "FROM user_behavior " +
                        "WHERE behavior_type = 'pv' " +
                        "GROUP BY item_id " +
                        "ORDER BY view_count DESC " +
                        "LIMIT #{limit}" +
                        ") hot " +
                        "LEFT JOIN product p ON hot.item_id = p.item_id " +
                        "ORDER BY hot.view_count DESC")
        List<Map<String, Object>> getHotProductsByView(@Param("limit") int limit);

        /**
         * 鑾峰彇鐑棬鍟嗗搧(鎸夎喘涔伴噺)
         */
        @Select("SELECT ub.item_id, " +
                        "COALESCE(NULLIF(MAX(p.name), ''), CONCAT('鍟嗗搧#', ub.item_id)) as product_name, " +
                        "MAX(p.brand) as brand, " +
                        "MAX(p.category_name) as category_name, " +
                        "MAX(p.price) as price, " +
                        "COUNT(*) as buy_count " +
                        "FROM user_behavior ub " +
                        "LEFT JOIN product p ON ub.item_id = p.item_id " +
                        "WHERE ub.behavior_type = 'buy' " +
                        "GROUP BY ub.item_id ORDER BY buy_count DESC LIMIT #{limit}")
        List<Map<String, Object>> getHotProductsByBuy(@Param("limit") int limit);

        /**
         * 鑾峰彇鐑棬绫荤洰
         */
        @Select("SELECT ub.category_id, " +
                        "COALESCE(NULLIF(MAX(p.category_name), ''), CONCAT('绫荤洰#', ub.category_id)) as category_name, " +
                        "MAX(p.price) as price, " +
                        "COUNT(*) as count " +
                        "FROM user_behavior ub " +
                        "LEFT JOIN product p ON ub.item_id = p.item_id " +
                        "WHERE ub.behavior_type = 'buy' " +
                        "GROUP BY ub.category_id ORDER BY count DESC LIMIT #{limit}")
        List<Map<String, Object>> getHotCategories(@Param("limit") int limit);

        /**
         * 鑾峰彇鐢ㄦ埛琛屼负姹囨€?鐢ㄤ簬RFM璁＄畻锛孧鍊?= 危(unit_price * qty))
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
         * 鑾峰彇杞寲婕忔枟鏁版嵁锛堜笁姝ュ簭鍒楁紡鏂楋細娴忚鈫掑姞璐啋璐拱锛?
         * 姣忎竴姝ラ兘鏄墠涓€姝ョ殑瀛愰泦锛岀‘淇濊浆鍖栫巼涓嶄細瓒呰繃100%
         */
        @Select("SELECT " +
                        "SUM(has_pv) as pv_users, " +
                        "SUM(CASE WHEN has_pv = 1 AND has_cart = 1 THEN 1 ELSE 0 END) as cart_users, " +
                        "SUM(CASE WHEN has_cart = 1 AND has_buy = 1 THEN 1 ELSE 0 END) as buy_users " +
                        "FROM (" +
                        "  SELECT user_id, " +
                        "         MAX(CASE WHEN behavior_type = 'pv' THEN 1 ELSE 0 END) as has_pv, " +
                        "         MAX(CASE WHEN behavior_type = 'cart' THEN 1 ELSE 0 END) as has_cart, " +
                        "         MAX(CASE WHEN behavior_type = 'buy' THEN 1 ELSE 0 END) as has_buy " +
                        "  FROM user_behavior " +
                        "  WHERE behavior_type IN ('pv', 'cart', 'buy') " +
                        "  GROUP BY user_id" +
                        ") funnel")
        Map<String, Object> getConversionFunnel();

        /**
         * 鑾峰彇姣忓皬鏃惰涓哄垎甯?
         */
        @Select("SELECT HOUR(behavior_date_time) as hour, COUNT(*) as count " +
                        "FROM user_behavior " +
                        "GROUP BY HOUR(behavior_date_time) ORDER BY hour")
        List<Map<String, Object>> getHourlyDistribution();

        /**
         * 鑾峰彇鐢ㄦ埛鏁伴噺
         */
        @Select("SELECT COUNT(DISTINCT user_id) FROM user_behavior")
        Long countDistinctUsers();

        /**
         * 鑾峰彇鍟嗗搧鏁伴噺
         */
        @Select("SELECT COUNT(DISTINCT item_id) FROM user_behavior")
        Long countDistinctItems();

        /**
         * 鑾峰彇绫荤洰鏁伴噺
         */
        @Select("SELECT COUNT(DISTINCT category_id) FROM user_behavior")
        Long countDistinctCategories();

        /**
         * 鑾峰彇鏈€鏂板鍏ョ殑琛屼负璁板綍
         */
        @Select("SELECT * FROM user_behavior ORDER BY id DESC LIMIT #{limit}")
        List<UserBehavior> getLatestBehaviors(@Param("limit") int limit);

        /**
         * 鑾峰彇鏈€澶ц涓烘椂闂?
         */
        @Select("SELECT MAX(behavior_date_time) FROM user_behavior")
        java.time.LocalDateTime getMaxBehaviorDateTime();

        /**
         * 鑾峰彇鏈€鏂版暟鎹椂闂寸獥鍙?
         */
        @Select("SELECT DATE(MAX(behavior_date_time)) as end_date, " +
                        "DATE(DATE_SUB(MAX(behavior_date_time), INTERVAL 13 DAY)) as start_date " +
                        "FROM user_behavior")
        Map<String, Object> getLatestDateRange();
}
