package com.ecommerce.analysis.service;

import com.ecommerce.analysis.vo.PublicMappingScoreRowVO;
import com.ecommerce.analysis.entity.ProductPublicMapping;

import java.io.IOException;
import java.util.List;

/**
 * 商品公网满意度指标导入服务。
 */
public interface ProductPublicMetricService {

    /**
     * 从人工核验映射 CSV 中导入/更新商品公网映射。
     */
    int importMappingsFromCsv(String filePath) throws IOException;

    /**
     * 从爬虫生成的 CSV 中导入/更新最新商品公网满意度快照。
     */
    int importLatestMetricsFromCsv(String filePath) throws IOException;

    /**
     * 读取映射评分结果，用于页面预览。
     */
    List<PublicMappingScoreRowVO> previewScoreRows(String filePath) throws IOException;

    /**
     * 将页面确认过的评分结果写入正式映射表。
     */
    int confirmMappings(List<PublicMappingScoreRowVO> rows);

    /**
     * 获取最近确认入库的公网映射。
     */
    List<ProductPublicMapping> listLatestMappings(String sourcePlatform, int limit);

    /**
     * 撤销指定公网映射，并清理对应快照。
     */
    boolean removeMapping(Long id);
}
