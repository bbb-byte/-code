package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.service.DataImportService;
import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.service.RFMService;
import com.ecommerce.analysis.vo.ImportStatusVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.service.UserBehaviorService;
import java.io.File;
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
    public Result<Map<String, Object>> crawlData() {
        try {
            String workDir = System.getProperty("user.dir");
            if (workDir.endsWith("backend")) {
                workDir = workDir.substring(0, workDir.length() - 8);
            }

            String crawlerPath = workDir + "/crawler/ecommerce_crawler.py";
            String mappingPath = workDir + "/crawler/mappings/product_public_mapping.jd.sample.csv";
            String outputDir = workDir + "/crawler/output";
            String outputJsonFile = outputDir + "/jd_product_public_metrics.json";
            String outputFile = outputDir + "/jd_product_public_metrics.csv";
            File crawlerFile = new File(crawlerPath);
            if (!crawlerFile.exists()) {
                return Result.error("爬虫脚本不存在: " + crawlerPath);
            }

            // 构造命令，尝试 python3 或 python
            String pythonCmd = "python3";
            try {
                new ProcessBuilder(pythonCmd, "--version").start().waitFor();
            } catch (Exception e) {
                pythonCmd = "python";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    pythonCmd,
                    crawlerPath,
                    "--mapping", mappingPath,
                    "--output-dir", outputDir,
                    "--fixture-dir", workDir + "/crawler/fixtures",
                    "--sleep-seconds", "0");
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true); // 合并错误流

            Process process = pb.start();

            // 读取输出防止阻塞
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            int exitCode = finished ? process.exitValue() : -1;

            Map<String, Object> result = new HashMap<>();
            result.put("status", exitCode == 0 ? "完成" : (finished ? "失败" : "超时"));
            result.put("outputFile", outputFile);
            result.put("outputJsonFile", outputJsonFile);
            result.put("mappingFile", mappingPath);
            result.put("sourceFamily", "jd-public-comment-summary");
            result.put("targetPlatform", "jd");
            result.put("log", output.toString());

            if (exitCode != 0) {
                return Result.error("爬虫执行失败: " + output.toString());
            }

            int importedMappings = productPublicMetricService.importMappingsFromCsv(mappingPath);
            int importedRows = productPublicMetricService.importLatestMetricsFromCsv(outputFile);
            result.put("importedMappings", importedMappings);
            result.put("importedRows", importedRows);

            return Result.success("公网满意度采集成功", result);
        } catch (Exception e) {
            log.error("执行爬虫异常", e);
            return Result.error("执行爬虫异常: " + e.getMessage());
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
}
