package com.ecommerce.analysis.service;

import java.io.IOException;

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
}
