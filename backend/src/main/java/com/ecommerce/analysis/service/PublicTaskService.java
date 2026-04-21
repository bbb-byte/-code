package com.ecommerce.analysis.service;

import com.ecommerce.analysis.vo.PublicTaskStatusVO;

/**
 * 公网映射/满意度长任务服务。
 */
public interface PublicTaskService {

    String startCrawlTask(String mappingPath, String outputDir, String fixtureDir);

    String startAnalyzeTask(int clusterK);

    String startAttachedSearchCrawlTask(String candidatePath, String outputPath, String cdpUrl);

    String startRecallTask(String productPath, String outputPath, String fixtureDir, String sourceDataPath,
            String generatedProductPath, int topK, int maxProducts, String cdpUrl);

    String startScoreTask(String productPath, String candidatePath, String outputPath);

    PublicTaskStatusVO getTaskStatus(String taskId);

    boolean cancelTask(String taskId);
}