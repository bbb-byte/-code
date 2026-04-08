package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.Product;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品Mapper接口
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    @Insert("INSERT INTO product (item_id, name, brand, category_id, category_name, price, view_count, cart_count, fav_count, buy_count) " +
            "VALUES (#{itemId}, #{name}, #{brand}, #{categoryId}, #{categoryName}, #{price}, 0, 0, 0, 0) " +
            "ON DUPLICATE KEY UPDATE " +
            "name = CASE WHEN VALUES(name) IS NULL OR VALUES(name) = '' THEN name ELSE VALUES(name) END, " +
            "brand = CASE WHEN VALUES(brand) IS NULL OR VALUES(brand) = '' THEN brand ELSE VALUES(brand) END, " +
            "category_id = CASE WHEN VALUES(category_id) IS NULL THEN category_id ELSE VALUES(category_id) END, " +
            "category_name = CASE WHEN VALUES(category_name) IS NULL OR VALUES(category_name) = '' THEN category_name ELSE VALUES(category_name) END, " +
            "price = CASE WHEN VALUES(price) IS NULL OR VALUES(price) = 0 THEN price ELSE VALUES(price) END")
    int upsertMetadata(Product product);
}
