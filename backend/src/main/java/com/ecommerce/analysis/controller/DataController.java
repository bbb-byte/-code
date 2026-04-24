package com.ecommerce.analysis.controller;

import com.ecommerce.analysis.common.Result;
import com.ecommerce.analysis.dto.PublicMappingConfirmRequest;
import com.ecommerce.analysis.entity.ProductPublicMapping;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.service.DataImportService;
import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.service.PublicTaskService;
import com.ecommerce.analysis.service.UserBehaviorService;
import com.ecommerce.analysis.vo.ImportStatusVO;
import com.ecommerce.analysis.vo.PublicMappingScorePreviewVO;
import com.ecommerce.analysis.vo.PublicTaskStatusVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLSyntaxErrorException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据管理控制器。
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
    private UserBehaviorService userBehaviorService;

    @Autowired
    private ProductPublicMetricService productPublicMetricService;

    @Autowired
    private PublicTaskService publicTaskService;

    /**
     * 启动异步 CSV 导入任务；真正的行解析和批量写库在服务层后台执行。
     */
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

        dataImportService.importCsvData(filePath, batchSize, maxRows);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "导入任务已启动");
        result.put("filePath", filePath);
        result.put("batchSize", batchSize);
        result.put("maxRows", maxRows);
        return Result.success("导入任务已启动", result);
    }

    @ApiOperation("上传映射用 CSV 文件")
    @PostMapping("/mapping/upload")
    public Result<Map<String, Object>> uploadMappingFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请先选择要上传的 CSV 文件");
        }

        try {
            String originalFilename = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : "mapping.csv";
            String sanitizedFilename = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path uploadDir = Path.of(resolveWorkDir(), "runtime", "uploads", "mappings");
            Files.createDirectories(uploadDir);

            Path storedPath = uploadDir.resolve(System.currentTimeMillis() + "_" + sanitizedFilename);
            Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> result = new HashMap<>();
            result.put("serverPath", storedPath.toAbsolutePath().toString());
            result.put("originalFileName", file.getOriginalFilename());
            log.info("映射文件已上传: originalFileName={}, storedPath={}", file.getOriginalFilename(), storedPath);
            return Result.success("文件上传成功", result);
        } catch (IOException e) {
            log.error("保存映射上传文件失败", e);
            return Result.error("保存上传文件失败: " + e.getMessage());
        }
    }

    @ApiOperation("上传并导入 CSV 数据")
    @PostMapping("/import/upload")
    public Result<Map<String, Object>> uploadAndImportData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "5000") int batchSize,
            @RequestParam(defaultValue = "0") long maxRows) {

        if (file == null || file.isEmpty()) {
            return Result.error("请先选择要上传的 CSV 文件");
        }
        if (dataImportService.isImporting()) {
            return Result.error("已有导入任务正在执行");
        }

        try {
            String storedPath = storeUploadedImportFile(file);
            log.info("收到上传导入请求: originalFileName={}, storedPath={}, batchSize={}, maxRows={}",
                    file.getOriginalFilename(), storedPath, batchSize, maxRows);
            dataImportService.importCsvData(storedPath, batchSize, maxRows);

            Map<String, Object> result = new HashMap<>();
            result.put("message", "导入任务已启动");
            result.put("filePath", storedPath);
            result.put("originalFileName", file.getOriginalFilename());
            result.put("batchSize", batchSize);
            result.put("maxRows", maxRows);
            return Result.success("文件上传成功，导入任务已启动", result);
        } catch (IOException e) {
            log.error("保存上传导入文件失败", e);
            return Result.error("保存上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 查询当前导入任务的进度快照。
     */
    @ApiOperation("获取导入进度")
    @GetMapping("/import/progress")
    public Result<ImportStatusVO> getImportProgress() {
        return Result.success(dataImportService.getImportStatus());
    }

    /**
     * 请求停止当前导入任务，服务层会在安全位置响应停止标记。
     */
    @ApiOperation("停止导入")
    @PostMapping("/import/stop")
    public Result<Void> stopImport() {
        dataImportService.stopImport();
        return Result.success("正在停止导入", null);
    }

    /**
     * 启动公网满意度抓取任务。
     */
    @ApiOperation("执行数据抓取")
    @PostMapping("/crawl")
    public Result<Map<String, Object>> crawlData(
            @RequestParam(defaultValue = "") String mappingPath,
            @RequestParam(defaultValue = "crawler/output") String outputDir,
            @RequestParam(defaultValue = "") String fixtureDir) {
        String taskId = publicTaskService.startCrawlTask(mappingPath, outputDir, fixtureDir);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("公网满意度采集任务已启动", result);
    }

    /**
     * 复用用户当前打开的京东搜索页抓取指标，适合处理浏览器已登录场景。
     */
    @ApiOperation("附着当前已打开京东搜索页采集公网指标")
    @PostMapping("/crawl-attached-search")
    public Result<Map<String, Object>> crawlAttachedSearchData(
            @RequestParam(defaultValue = "crawler/output/recalled_candidates.browser.csv") String candidatePath,
            @RequestParam(defaultValue = "crawler/output/jd_search_browser_metrics_attached.csv") String outputPath,
            @RequestParam(defaultValue = "http://host.docker.internal:9223") String cdpUrl) {
        String taskId = publicTaskService.startAttachedSearchCrawlTask(candidatePath, outputPath, cdpUrl);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("附着搜索页公网指标采集任务已启动", result);
    }

    /**
     * 触发公网映射候选召回任务。
     */
    @ApiOperation("召回公网映射候选商品")
    @PostMapping("/public-mapping/recall")
    public Result<Map<String, Object>> recallPublicMappingCandidates(
            @RequestParam(defaultValue = "crawler/mappings/internal_products.sample.csv") String productPath,
            @RequestParam(defaultValue = "crawler/output/recalled_candidates.csv") String outputPath,
            @RequestParam(defaultValue = "") String fixtureDir,
            @RequestParam(defaultValue = "") String sourceDataPath,
            @RequestParam(defaultValue = "crawler/output/internal_products.auto.csv") String generatedProductPath,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "50") int maxProducts,
            @RequestParam(defaultValue = "") String cdpUrl) {
        String taskId = publicTaskService.startRecallTask(
                productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts, cdpUrl);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("公网映射候选召回任务已启动", result);
    }

    /**
     * 触发候选评分任务，输出给人工复核使用的分数文件。
     */
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

    /**
     * 查询公网相关后台任务的执行状态。
     */
    @ApiOperation("获取公网任务进度")
    @GetMapping("/public-task/progress")
    public Result<PublicTaskStatusVO> getPublicTaskProgress(@RequestParam String taskId) {
        return Result.success(publicTaskService.getTaskStatus(taskId));
    }

    /**
     * 请求取消公网后台任务。
     */
    @ApiOperation("取消公网任务")
    @PostMapping("/public-task/cancel")
    public Result<Map<String, Object>> cancelPublicTask(@RequestParam String taskId) {
        boolean success = publicTaskService.cancelTask(taskId);
        Map<String, Object> result = new HashMap<>();
        result.put("cancelled", success);
        result.put("taskId", taskId);
        return success
                ? Result.success("已发送取消信号，任务将在当前搜索完成后停止", result)
                : Result.error("无法取消任务：任务不存在或已结束");
    }

    /**
     * 分页预览评分结果，方便管理员在真正入库前复核映射质量。
     */
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

    /**
     * 将审核通过的公网映射批量确认入库。
     */
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

    /**
     * 查询最近确认过的公网映射，默认按平台 jd 返回。
     */
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
                return Result.success(
                        "公网映射表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql",
                        Collections.emptyList());
            }
            log.error("获取最近公网映射异常", e);
            return Result.error("获取最近公网映射异常: " + e.getMessage());
        }
    }

    /**
     * 撤销一条公网映射，并同步删除已落库的补充指标。
     */
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

    /**
     * 返回最近导入的原始行为记录，主要用于后台排查。
     */
    @ApiOperation("获取最新行为数据")
    @GetMapping("/latest")
    public Result<List<UserBehavior>> getLatestBehaviors(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(userBehaviorService.getLatestBehaviors(limit));
    }

    @ApiOperation("执行完整的数据分析流程")
    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyzeData(@RequestParam(defaultValue = "5") int clusterK) {
        return startAnalyzeTask(clusterK);
    }

    @ApiOperation("启动数据分析后台任务")
    @PostMapping("/analyze-task")
    public Result<Map<String, Object>> startAnalyzeTask(@RequestParam(defaultValue = "5") int clusterK) {
        String taskId = publicTaskService.startAnalyzeTask(clusterK);
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        return Result.success("数据分析任务已启动", result);
    }

    /**
     * 推导工作区根目录，兼容从 backend 子目录启动的情况。
     */
    private String resolveWorkDir() {
        String configured = System.getenv("PUBLIC_TASK_WORKSPACE_ROOT");
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim()).getAbsolutePath();
        }
        String workDir = System.getProperty("user.dir");
        if (workDir.endsWith("backend")) {
            workDir = workDir.substring(0, workDir.length() - 8);
        }
        return workDir;
    }

    /**
     * 将相对路径解析到工作区根目录下，保证前后端传参一致。
     */
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

    private String storeUploadedImportFile(MultipartFile file) throws IOException {
        String originalFilename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename()
                : "import.csv";
        String sanitizedFilename = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path uploadDir = Path.of(resolveWorkDir(), "runtime", "uploads", "imports");
        Files.createDirectories(uploadDir);

        Path storedPath = uploadDir.resolve(System.currentTimeMillis() + "_" + sanitizedFilename);
        Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);
        return storedPath.toAbsolutePath().toString();
    }

    /**
     * 统一识别“公网映射相关表不存在”的异常，便于返回更明确的引导信息。
     */
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
