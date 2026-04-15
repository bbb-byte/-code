package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.service.PublicTaskService;
import com.ecommerce.analysis.vo.PublicTaskStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Service
public class PublicTaskServiceImpl implements PublicTaskService {

    private static final long PYTHON_SCRIPT_TIMEOUT_SECONDS = 600;
    private static final long NODE_SCRIPT_TIMEOUT_SECONDS = 1200;
    @Autowired
    private ProductPublicMetricService productPublicMetricService;

    @Autowired
    private TaskExecutor taskExecutor;

    private final Map<String, PublicTaskStatusVO> tasks = new ConcurrentHashMap<>();

    /**
     * 启动公网满意度抓取后台任务。
     */
    @Override
    public String startCrawlTask(String mappingPath, String outputDir, String fixtureDir) {
        String taskId = createTask("crawl", "公网满意度采集任务已启动");
        taskExecutor.execute(() -> runCrawlTask(taskId, mappingPath, outputDir, fixtureDir));
        return taskId;
    }

    /**
     * 启动“附着当前搜索页”的公网指标抓取任务。
     */
    @Override
    public String startAttachedSearchCrawlTask(String candidatePath, String outputPath, String cdpUrl) {
        String taskId = createTask("crawl_attached_search", "附着搜索页公网指标采集任务已启动");
        taskExecutor.execute(() -> runAttachedSearchCrawlTask(taskId, candidatePath, outputPath, cdpUrl));
        return taskId;
    }

    /**
     * 启动公网候选召回任务。
     */
    @Override
    public String startRecallTask(String productPath, String outputPath, String fixtureDir, String sourceDataPath,
            String generatedProductPath, int topK, int maxProducts) {
        String taskId = createTask("recall", "公网映射候选召回任务已启动");
        taskExecutor.execute(() -> runRecallTask(taskId, productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts));
        return taskId;
    }

    /**
     * 启动公网候选评分任务。
     */
    @Override
    public String startScoreTask(String productPath, String candidatePath, String outputPath) {
        String taskId = createTask("score", "公网映射评分任务已启动");
        taskExecutor.execute(() -> runScoreTask(taskId, productPath, candidatePath, outputPath));
        return taskId;
    }

    /**
     * 查询任务状态；若任务不存在则返回一个“missing”占位对象，而不是抛异常。
     */
    @Override
    public PublicTaskStatusVO getTaskStatus(String taskId) {
        PublicTaskStatusVO status = tasks.get(taskId);
        if (status == null) {
            PublicTaskStatusVO missing = new PublicTaskStatusVO();
            missing.setTaskId(taskId);
            missing.setTaskType("unknown");
            missing.setRunning(false);
            missing.setProgress(0D);
            missing.setStatus("missing");
            missing.setMessage("任务不存在或已过期");
            return missing;
        }
        return status;
    }

    /**
     * 通过写取消信号文件请求后台脚本自行终止。
     */
    @Override
    public boolean cancelTask(String taskId) {
        PublicTaskStatusVO status = tasks.get(taskId);
        if (status == null || !status.isRunning()) {
            return false;
        }
        // 写入取消信号文件，Python 脚本会在下一个循环检测并退出
        try {
            String workDir = resolveWorkDir();
            Path signalPath = Paths.get(workDir, "crawler", "output", ".cancel_signal");
            Files.createDirectories(signalPath.getParent());
            Files.write(signalPath, "cancel".getBytes());
            log.info("已写入取消信号文件: {}", signalPath);
            status.setMessage("正在取消任务...");
            return true;
        } catch (IOException e) {
            log.error("写入取消信号文件失败", e);
            return false;
        }
    }

    /**
     * 创建一条新的任务状态记录并放入内存任务表。
     */
    private String createTask(String taskType, String message) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        PublicTaskStatusVO status = new PublicTaskStatusVO();
        status.setTaskId(taskId);
        status.setTaskType(taskType);
        status.setRunning(true);
        status.setProgress(1D);
        status.setStatus("running");
        status.setMessage(message);
        status.setStartedAt(System.currentTimeMillis());
        tasks.put(taskId, status);
        return taskId;
    }

    /**
     * 执行公网满意度抓取任务，并在脚本成功后把结果回写到数据库。
     */
    private void runCrawlTask(String taskId, String mappingPath, String outputDir, String fixtureDir) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            String workDir = resolveWorkDir();
            String crawlerPath = workDir + "/crawler/ecommerce_crawler.py";
            String resolvedOutputDir = resolveWorkspacePath(outputDir);
            String resolvedFixtureDir = fixtureDir == null || fixtureDir.trim().isEmpty()
                    ? ""
                    : resolveWorkspacePath(fixtureDir);
            String resolvedMappingPath = mappingPath == null || mappingPath.trim().isEmpty()
                    ? productPublicMetricService.exportMappingsToCsv("jd", resolvedOutputDir)
                    : resolveWorkspacePath(mappingPath);
            String outputJsonFile = resolvedOutputDir + "/jd_product_public_metrics.json";
            String outputFile = resolvedOutputDir + "/jd_product_public_metrics.csv";

            updateProgress(status, 10D, "开始执行公网满意度采集脚本");
            List<String> crawlArgs = new ArrayList<>(Arrays.asList(
                    "--mapping", resolvedMappingPath,
                    "--output-dir", resolvedOutputDir,
                    "--sleep-seconds", "0"));
            if (!resolvedFixtureDir.isEmpty()) {
                crawlArgs.add("--fixture-dir");
                crawlArgs.add(resolvedFixtureDir);
            }
            ScriptExecution scriptExecution = runPythonScript(
                    workDir,
                    crawlerPath,
                    crawlArgs.toArray(new String[0]));
            status.setLog(scriptExecution.output);
            if (scriptExecution.exitCode != 0) {
                failTask(status, "爬虫执行失败: " + scriptExecution.output);
                return;
            }

            updateProgress(status, 75D, "开始回写映射与满意度快照");
            int importedMappings = productPublicMetricService.importMappingsFromCsv(resolvedMappingPath);
            int importedRows = productPublicMetricService.importLatestMetricsFromCsv(outputFile);

            Map<String, Object> result = new HashMap<>();
            result.put("outputFile", outputFile);
            result.put("outputJsonFile", outputJsonFile);
            result.put("mappingFile", resolvedMappingPath);
            result.put("fixtureDir", resolvedFixtureDir);
            result.put("targetPlatform", "jd");
            result.put("importedMappings", importedMappings);
            result.put("importedRows", importedRows);
            completeTask(status, "公网满意度采集完成", result);
        } catch (Throwable e) {
            if (isPublicMetricSchemaMissing(e)) {
                failTask(status, "公网满意度相关数据表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql 或重新初始化数据库");
                return;
            }
            log.error("公网满意度采集任务异常", e);
            failTask(status, "执行公网满意度采集异常: " + e.getMessage());
        }
    }

    /**
     * 执行公网候选召回任务；当传入原始行为文件时会先生成内部商品快照。
     */
    private void runRecallTask(String taskId, String productPath, String outputPath, String fixtureDir, String sourceDataPath,
            String generatedProductPath, int topK, int maxProducts) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            String workDir = resolveWorkDir();
            String scriptPath = workDir + "/crawler/mapping_candidate_recall.py";
            String snapshotScriptPath = workDir + "/crawler/build_internal_products_snapshot.py";
            String effectiveProductPath = resolveWorkspacePath(productPath);
            String resolvedOutputPath = resolveWorkspacePath(outputPath);
            String resolvedFixtureDir = resolveWorkspacePath(fixtureDir);
            String resolvedSourceDataPath = sourceDataPath == null ? "" : sourceDataPath.trim();
            String resolvedGeneratedProductPath = resolveWorkspacePath(generatedProductPath);

            if (!resolvedSourceDataPath.isEmpty()) {
                updateProgress(status, 15D, "开始生成商品快照");
                String effectiveSourceDataPath = resolveWorkspacePath(resolvedSourceDataPath);
                ScriptExecution snapshotExecution = runPythonScript(
                        workDir,
                        snapshotScriptPath,
                        "--input", effectiveSourceDataPath,
                        "--output", resolvedGeneratedProductPath);
                status.setLog(snapshotExecution.output);
                if (snapshotExecution.exitCode != 0) {
                    failTask(status, "商品快照生成失败: " + snapshotExecution.output);
                    return;
                }
                effectiveProductPath = resolvedGeneratedProductPath;
            }

            updateProgress(status, 60D, "开始召回公网候选商品");
            List<String> args = new ArrayList<>(Arrays.asList(
                    "--products", effectiveProductPath,
                    "--output", resolvedOutputPath,
                    "--top-k", String.valueOf(topK),
                    "--max-products", String.valueOf(maxProducts)));
            if (resolvedFixtureDir != null && !resolvedFixtureDir.trim().isEmpty()) {
                args.add("--fixture-dir");
                args.add(resolvedFixtureDir);
            }
            ScriptExecution scriptExecution = runPythonScript(workDir, scriptPath, args.toArray(new String[0]));
            status.setLog(mergeLogs(status.getLog(), scriptExecution.output));
            if (scriptExecution.exitCode != 0) {
                failTask(status, "候选召回失败: " + scriptExecution.output);
                return;
            }

            long candidateRows = countDataRows(resolvedOutputPath);
            if (candidateRows <= 0) {
                failTask(status, "候选召回未产出有效数据。当前候选文件只有表头，通常是页面结构变化、被风控拦截，或当前数据源无法召回到可解析结果。");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("productPath", effectiveProductPath);
            result.put("sourceDataPath", resolvedSourceDataPath.isEmpty() ? null : resolveWorkspacePath(resolvedSourceDataPath));
            result.put("generatedProductPath", resolvedSourceDataPath.isEmpty() ? null : resolvedGeneratedProductPath);
            result.put("outputFile", resolvedOutputPath);
            result.put("fixtureDir", resolvedFixtureDir);
            result.put("topK", topK);
            result.put("maxProducts", maxProducts);
            result.put("candidateRows", candidateRows);
            result.put("recallMode", "jd_search");
            completeTask(status, "公网映射候选召回完成", result);
        } catch (Throwable e) {
            log.error("公网映射候选召回任务异常", e);
            failTask(status, "执行候选召回异常: " + e.getMessage());
        }
    }

    /**
     * 执行公网候选评分任务。
     */
    private void runScoreTask(String taskId, String productPath, String candidatePath, String outputPath) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            String workDir = resolveWorkDir();
            String scriptPath = workDir + "/crawler/mapping_scorer.py";
            String resolvedOutputPath = resolveWorkspacePath(outputPath);
            updateProgress(status, 15D, "开始计算公网映射候选分数");
            ScriptExecution scriptExecution = runPythonScript(
                    workDir,
                    scriptPath,
                    "--products", resolveWorkspacePath(productPath),
                    "--candidates", resolveWorkspacePath(candidatePath),
                    "--output", resolvedOutputPath);
            status.setLog(scriptExecution.output);
            if (scriptExecution.exitCode != 0) {
                failTask(status, "映射评分失败: " + scriptExecution.output);
                return;
            }

            long scoreRows = countDataRows(resolvedOutputPath);
            if (scoreRows <= 0) {
                failTask(status, "映射评分未产出有效数据。请先确认候选文件不是空文件，再重新执行评分。");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("productPath", resolveWorkspacePath(productPath));
            result.put("candidateFile", resolveWorkspacePath(candidatePath));
            result.put("outputFile", resolvedOutputPath);
            result.put("scoreRows", scoreRows);
            completeTask(status, "公网映射评分完成", result);
        } catch (Throwable e) {
            log.error("公网映射评分任务异常", e);
            failTask(status, "执行映射评分异常: " + e.getMessage());
        }
    }

    /**
     * 调用前端 Node 脚本复用浏览器当前页面抓取搜索结果指标。
     */
    private void runAttachedSearchCrawlTask(String taskId, String candidatePath, String outputPath, String cdpUrl) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            String workDir = resolveWorkDir();
            String frontendDir = workDir + "/frontend";
            String scriptPath = frontendDir + "/scripts/jd-search-browser-metrics.mjs";
            String resolvedCandidatePath = resolveWorkspacePath(candidatePath);
            String resolvedOutputPath = resolveWorkspacePath(outputPath);

            updateProgress(status, 15D, "开始附着当前已打开京东搜索页");
            ScriptExecution scriptExecution = runNodeScript(
                    frontendDir,
                    scriptPath,
                    "--candidates", toWorkspaceRelative(workDir, resolvedCandidatePath),
                    "--output", toWorkspaceRelative(workDir, resolvedOutputPath),
                    "--max-products", "1",
                    "--cdp-url", defaultIfBlank(cdpUrl, "http://127.0.0.1:9222"),
                    "--use-current-page", "true");
            status.setLog(scriptExecution.output);
            if (scriptExecution.exitCode != 0) {
                failTask(status, "附着搜索页采集失败: " + scriptExecution.output);
                return;
            }

            long metricRows = countDataRows(resolvedOutputPath);
            if (metricRows <= 0) {
                failTask(status, "附着搜索页采集未产出有效数据");
                return;
            }

            int importedRows = productPublicMetricService.importLatestMetricsFromCsv(resolvedOutputPath);
            Map<String, Object> result = new HashMap<>();
            result.put("candidateFile", resolvedCandidatePath);
            result.put("outputFile", resolvedOutputPath);
            result.put("targetPlatform", "jd");
            result.put("importedRows", importedRows);
            result.put("metricRows", metricRows);
            completeTask(status, "附着搜索页公网指标采集完成", result);
        } catch (Throwable e) {
            if (isPublicMetricSchemaMissing(e)) {
                failTask(status, "公网满意度相关数据表不存在，请先执行 backend/src/main/resources/sql/upgrade_archive_dataset.sql 或重新初始化数据库");
                return;
            }
            log.error("附着搜索页公网指标采集任务异常", e);
            failTask(status, "执行附着搜索页公网指标采集异常: " + e.getMessage());
        }
    }

    private void updateProgress(PublicTaskStatusVO status, double progress, String message) {
        status.setProgress(progress);
        status.setMessage(message);
        status.setStatus("running");
    }

    private void completeTask(PublicTaskStatusVO status, String message, Map<String, Object> result) {
        status.setRunning(false);
        status.setProgress(100D);
        status.setStatus("success");
        status.setMessage(message);
        status.setFinishedAt(System.currentTimeMillis());
        status.setResult(result);
    }

    private void failTask(PublicTaskStatusVO status, String message) {
        status.setRunning(false);
        status.setStatus("failed");
        status.setMessage(message);
        status.setFinishedAt(System.currentTimeMillis());
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

    private String resolvePythonCommand() {
        return "C:\\Users\\86186\\anaconda3\\python.exe";
    }

    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private ScriptExecution runPythonScript(String workDir, String scriptPath, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolvePythonCommand());
        command.add(scriptPath);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(PYTHON_SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int exitCode = finished ? process.exitValue() : -1;
        if (!finished) {
            process.destroyForcibly();
        }
        return new ScriptExecution(exitCode, finished, output.toString());
    }

    private String resolveNodeCommand() {
        String nodeCmd = "node";
        try {
            new ProcessBuilder(nodeCmd, "--version").start().waitFor();
        } catch (Exception e) {
            nodeCmd = "node.exe";
        }
        return nodeCmd;
    }

    private ScriptExecution runNodeScript(String workDir, String scriptPath, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveNodeCommand());
        command.add(scriptPath);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(NODE_SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int exitCode = finished ? process.exitValue() : -1;
        if (!finished) {
            process.destroyForcibly();
        }
        return new ScriptExecution(exitCode, finished, output.toString());
    }

    private long countDataRows(String csvPath) throws IOException {
        Path path = Paths.get(csvPath);
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(path)) {
            return Math.max(0, lines.count() - 1);
        }
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


    private String toWorkspaceRelative(String workDir, String absolutePath) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            return absolutePath;
        }
        Path workPath = Paths.get(workDir).toAbsolutePath().normalize();
        Path targetPath = Paths.get(absolutePath).toAbsolutePath().normalize();
        if (!targetPath.startsWith(workPath)) {
            return absolutePath;
        }
        return workPath.relativize(targetPath).toString().replace("\\", "/");
    }

    private String mergeLogs(String first, String second) {
        if (first == null || first.trim().isEmpty()) {
            return second;
        }
        if (second == null || second.trim().isEmpty()) {
            return first;
        }
        return first + "\n" + second;
    }

    private static class ScriptExecution {
        private final int exitCode;
        private final boolean finished;
        private final String output;

        private ScriptExecution(int exitCode, boolean finished, String output) {
            this.exitCode = exitCode;
            this.finished = finished;
            this.output = output;
        }
    }
}
