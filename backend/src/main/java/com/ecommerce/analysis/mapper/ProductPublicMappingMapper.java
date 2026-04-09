package com.ecommerce.analysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.analysis.entity.ProductPublicMapping;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品公网映射 Mapper。
 */
@Mapper
public interface ProductPublicMappingMapper extends BaseMapper<ProductPublicMapping> {

    @Insert("INSERT INTO product_public_mapping " +
            "(item_id, source_platform, source_product_id, source_url, verified_title, mapping_confidence, verification_note, evidence_note, verified_at) " +
            "VALUES (#{itemId}, #{sourcePlatform}, #{sourceProductId}, #{sourceUrl}, #{verifiedTitle}, #{mappingConfidence}, #{verificationNote}, #{evidenceNote}, #{verifiedAt}) " +
            "ON DUPLICATE KEY UPDATE " +
            "source_product_id = VALUES(source_product_id), " +
            "source_url = VALUES(source_url), " +
            "verified_title = VALUES(verified_title), " +
            "mapping_confidence = VALUES(mapping_confidence), " +
            "verification_note = VALUES(verification_note), " +
            "evidence_note = VALUES(evidence_note), " +
            "verified_at = VALUES(verified_at)")
    int upsert(ProductPublicMapping mapping);

    @Select("SELECT COUNT(*) FROM product_public_mapping WHERE item_id = #{itemId} AND source_platform = #{sourcePlatform}")
    int countByItemAndPlatform(@Param("itemId") Long itemId, @Param("sourcePlatform") String sourcePlatform);

    @Select("SELECT * FROM product_public_mapping " +
            "WHERE source_platform = #{sourcePlatform} " +
            "ORDER BY COALESCE(verified_at, update_time, create_time) DESC, id DESC " +
            "LIMIT #{limit}")
    List<ProductPublicMapping> selectLatestByPlatform(@Param("sourcePlatform") String sourcePlatform, @Param("limit") int limit);
}
