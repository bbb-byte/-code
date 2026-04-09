package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.dto.PublicMappingConfirmRequest;
import com.ecommerce.analysis.entity.ProductPublicMapping;
import com.ecommerce.analysis.service.DataImportService;
import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.service.PublicTaskService;
import com.ecommerce.analysis.service.RFMService;
import com.ecommerce.analysis.vo.ImportStatusVO;
import com.ecommerce.analysis.vo.PublicMappingScorePreviewVO;
import com.ecommerce.analysis.vo.PublicMappingScoreRowVO;
import com.ecommerce.analysis.vo.PublicTaskStatusVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.service.UserBehaviorService;
import java.io.File;
import java.sql.SQLSyntaxErrorException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据管理控制器
 */
@Slf4j
@Api(tags = "数据管理")
@RestController
@RequestMapping("/data")
@PreAuthorize("hasRole('ADMIN')")
public class DataController {

    @Autowired
    private DataImportService dataImportService;

    @Autowired
    private RFMService rfmService;

    @Autowired
    private UserBehaviorService userBehaviorService;

    @Autowired
    private ProductPublicMetricService productPublicMetricService;

    @Autowired
    private PublicTaskService publicTaskService;

    @ApiOperation("导入CSV数据")
    @PostMapping("/import")
    public Result<Map<String, Object>> importData(
            @RequestParam String filePath,
            @RequestParam(defaultValue = "5000") int batchSize,
            @RequestParam(defaultValue = "0") long maxRows) {

        log.info("接收到导入请求: filePath={}, batchSize={}, maxRows={}", filePath, batchSize, maxRows);

        if (dataImportService.isImporting()) {
            log.warn("导入任务已在运行中");
            return Result.error("已有导入任务正在执行");
        }

        // 异步执行导入
        log.info("启动异步导入任务...");
        dataImportService.importCsvData(filePath, batchSize, maxRows);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "导入任务已启动");
        result.put("filePath", filePath);
        result.put("batchSize", batchSize);
        result.put("maxRows", maxRows);

        return Result.success("导入任务已启动", result);
    }

    @ApiOperation("获取导入进度")
    @GetMapping("/import/progress")
    public Result<ImportStatusVO> getImportProgress() {
        return Result.success(dataImportService.getImportStatus());
    }

    @ApiOperation("停止导入")
    @PostMapping("/import/stop")
    public Result<Void> stopImport() {
        dataImportService.stopImport();
        return Result.success("正在停止导入", null);
    }

    @ApiOperation("执行数据爬取")
    @PostMapping("/crawl")
    public Result<Map<String, Object>> crawlData(
            @RequestParam(defaultValue = "crawler/mappings/product_public_mapping.jd.sample.csv") String mappingPath,
            @RequestParam(defaultValue = "crawler/output") String outputDir,
            @RequestParam(defaultValue = "crawler/fixtures") String fixtureDir) {
        String taskId = publicTaskService.startCrawlTask(mappingPath, outputDir, fixtureDir);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("公网满意度采集任务已启动", result);
    }

    @ApiOperation("召回公网映射候选商品")
    @PostMapping("/public-mapping/recall")
    public Result<Map<String, Object>> recallPublicMappingCandidates(
            @RequestParam(defaultValue = "crawler/mappings/internal_products.sample.csv") String productPath,
            @RequestParam(defaultValue = "crawler/output/recalled_candidates.csv") String outputPath,
            @RequestParam(defaultValue = "") String fixtureDir,
            @RequestParam(defaultValue = "") String sourceDataPath,
            @RequestParam(defaultValue = "crawler/output/internal_products.auto.csv") String generatedProductPath,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "50") int maxProducts) {
        String taskId = publicTaskService.startRecallTask(
                productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("公网映射候选召回任务已启动", result);
    }

    @ApiOperation("计算公网映射候选分数")
    @PostMapping("/public-mapping/score")
    public Result<Map<String, Object>> scorePublicMappingCandidates(
            @RequestParam(defaultValue = "crawler/mappings/internal_products.sample.csv") String productPath,
            @RequestParam(defaultValue = "crawler/output/recalled_candidates.csv") String candidatePath,
            @RequestParam(defaultValue = "crawler/output/recalled_candidate_scores.csv") String outputPath) {
        String taskId = publicTaskService.startScoreTask(productPath, candidatePath, outputPath);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("公网映射评分任务已启动", result);
    }

    @ApiOperation("获取公网任务进度")
    @GetMapping("/public-task/progress")
    public Result<PublicTaskStatusVO> getPublicTaskProgress(@RequestParam String taskId) {
        return Result.success(publicTaskService.getTaskStatus(taskId));
    }

    @ApiOperation("预览公网映射评分结果")
    @GetMapping("/public-mapping/score-preview")
    public Result<PublicMappingScorePreviewVO> previewPublicMappingScore(
            @RequestParam(defaultValue = "crawler/output/recalled_candidate_scores.csv") String scorePath,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        try {
            return Result.success(productPublicMetricService.previewScoreRows(resolveWorkspacePath(scorePath), page, pageSize));
        } catch (Exception e) {
            log.error("读取映射评分预览异常", e);
            return Result.error("读取映射评分预览异常: " + e.getMessage());
        }
    }

    @ApiOperation("确认公网映射并入库")
    @PostMapping("/public-mapping/confirm")
    public Result<Map<String, Object>> confirmPublicMappings(@RequestBody PublicMappingConfirmRequest request) {
        try {
            int confirmedRows = productPublicMetricService.confirmMappings(request.getRows());
            Map<String, Object> result = new HashMap<>();
            result.put("confirmedRows", confirmedRows);
            result.put("requestedRows", request.getRows() == null ? 0 : request.getRows().size());
            return Result.success("公网映射确认入库完成", result);
        } catch (Exception e) {
            if (isPublicMetricSchemaMissing(e)) {
                return Result.error("公网映射表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql 或重新初始化数据库");
            }
            log.error("确认公网映射异常", e);
            return Result.error("确认公网映射异常: " + e.getMessage());
        }
    }

    @ApiOperation("获取最近确认的公网映射")
    @GetMapping("/public-mapping/latest")
    public Result<List<ProductPublicMapping>> getLatestPublicMappings(
            @RequestParam(defaultValue = "jd") String sourcePlatform,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return Result.success(productPublicMetricService.listLatestMappings(sourcePlatform, limit));
        } catch (Exception e) {
            if (isPublicMetricSchemaMissing(e)) {
                log.warn("公网映射表不存在，降级返回空列表: {}", e.getMessage());
                return Result.success("公网映射表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql", Collections.emptyList());
            }
            log.error("获取最近公网映射异常", e);
            return Result.error("获取最近公网映射异常: " + e.getMessage());
        }
    }

    @ApiOperation("撤销公网映射")
    @PostMapping("/public-mapping/remove")
    public Result<Map<String, Object>> removePublicMapping(@RequestParam Long id) {
        try {
            boolean removed = productPublicMetricService.removeMapping(id);
            if (!removed) {
                return Result.error("未找到可撤销的公网映射");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("removed", true);
            result.put("id", id);
            return Result.success("公网映射已撤销", result);
        } catch (Exception e) {
            if (isPublicMetricSchemaMissing(e)) {
                return Result.error("公网映射表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql 或重新初始化数据库");
            }
            log.error("撤销公网映射异常", e);
            return Result.error("撤销公网映射异常: " + e.getMessage());
        }
    }

    @ApiOperation("获取最新行为数据")
    @GetMapping("/latest")
    public Result<List<UserBehavior>> getLatestBehaviors(
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(userBehaviorService.getLatestBehaviors(limit));
    }

    @ApiOperation("执行完整的数据分析流程")
    @PostMapping("/analyze")
    public Result<Void> analyzeData(@RequestParam(defaultValue = "5") int clusterK) {
        try {
            // 1. 计算RFM
            rfmService.calculateAllUserRFM();

            // 2. 执行聚类
            rfmService.performKMeansClustering(clusterK);

            return Result.success("数据分析完成", null);
        } catch (Exception e) {
            return Result.error("数据分析失败: " + e.getMessage());
        }
    }

    private String resolveWorkDir() {
        String workDir = System.getProperty("user.dir");
        if (workDir.endsWith("backend")) {
            workDir = workDir.substring(0, workDir.length() - 8);
        }
        return workDir;
    }

    private String resolveWorkspacePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return path;
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        return new File(resolveWorkDir(), path).getAbsolutePath();
    }

    private boolean isPublicMetricSchemaMissing(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if ((normalized.contains("product_public_mapping") || normalized.contains("product_public_metric"))
                        && (normalized.contains("doesn't exist")
                        || normalized.contains("does not exist")
                        || normalized.contains("unknown table"))) {
                    return true;
                }
            }
            if (current instanceof SQLSyntaxErrorException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
