package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.service.ProductPublicMetricService;
import com.ecommerce.analysis.service.PublicTaskService;
import com.ecommerce.analysis.service.RFMService;
import com.ecommerce.analysis.vo.PublicTaskStatusVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
    private static final List<String> PYTHON_COMMAND_CANDIDATES = Arrays.asList("python3", "python", "python.exe");
    private static final String PUBLIC_TASK_PYTHON_ENV = "PUBLIC_TASK_PYTHON";
    private static final String PUBLIC_TASK_CDP_URL_ENV = "PUBLIC_TASK_CDP_URL";
    private static final String PUBLIC_TASK_WORKER_URL_ENV = "PUBLIC_TASK_WORKER_URL";
    private static final String PUBLIC_TASK_WORKSPACE_ROOT_ENV = "PUBLIC_TASK_WORKSPACE_ROOT";
    private static final String PUBLIC_TASK_BROWSER_PATH_ENV = "PUBLIC_TASK_BROWSER_PATH";
    private static final String DEFAULT_PUBLIC_TASK_CDP_URL = "http://host.docker.internal:9223";
    private static final List<String> PUBLIC_TASK_ENV_KEYS = Arrays.asList(
            PUBLIC_TASK_PYTHON_ENV,
            PUBLIC_TASK_CDP_URL_ENV,
            PUBLIC_TASK_BROWSER_PATH_ENV,
            "PUBLIC_TASK_BROWSER_CHANNEL",
            "PUBLIC_TASK_BROWSER_PROFILE_DIR");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private ProductPublicMetricService productPublicMetricService;

    @Autowired
    private RFMService rfmService;

    @Autowired
    private TaskExecutor taskExecutor;

    private final Map<String, PublicTaskStatusVO> tasks = new ConcurrentHashMap<>();

    @Override
    public String startCrawlTask(String mappingPath, String outputDir, String fixtureDir) {
        String taskId = createTask("crawl", "公网满意度采集任务已启动");
        taskExecutor.execute(() -> runCrawlTask(taskId, mappingPath, outputDir, fixtureDir));
        return taskId;
    }

    @Override
    public String startAnalyzeTask(int clusterK) {
        String taskId = createTask("analyze", "数据分析任务已启动");
        taskExecutor.execute(() -> runAnalyzeTask(taskId, clusterK));
        return taskId;
    }

    @Override
    public String startAttachedSearchCrawlTask(String candidatePath, String outputPath, String cdpUrl) {
        String taskId = createTask("crawl_attached_search", "附着搜索页公网指标采集任务已启动");
        taskExecutor.execute(() -> runAttachedSearchCrawlTask(taskId, candidatePath, outputPath, cdpUrl));
        return taskId;
    }

    @Override
    public String startRecallTask(String productPath, String outputPath, String fixtureDir, String sourceDataPath,
            String generatedProductPath, int topK, int maxProducts, String cdpUrl) {
        String taskId = createTask("recall", "公网映射候选召回任务已启动");
        taskExecutor.execute(() -> runRecallTask(taskId, productPath, outputPath, fixtureDir, sourceDataPath, generatedProductPath, topK, maxProducts, cdpUrl));
        return taskId;
    }

    @Override
    public String startScoreTask(String productPath, String candidatePath, String outputPath) {
        String taskId = createTask("score", "公网映射评分任务已启动");
        taskExecutor.execute(() -> runScoreTask(taskId, productPath, candidatePath, outputPath));
        return taskId;
    }

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

    @Override
    public boolean cancelTask(String taskId) {
        PublicTaskStatusVO status = tasks.get(taskId);
        if (status == null || !status.isRunning()) {
            return false;
        }
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
            clearCancelSignal(resolvedOutputDir);

            updateProgress(status, 10D, "开始执行公网满意度采集脚本");
            List<String> crawlArgs = new ArrayList<>(Arrays.asList(
                    "--mapping", resolvedMappingPath,
                    "--output-dir", resolvedOutputDir,
                    "--cdp-url", resolveDefaultCdpUrl(),
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
            String generatedProductPath, int topK, int maxProducts, String cdpUrl) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            String workDir = resolveWorkDir();
            String frontendDir = workDir + "/frontend";
            String nodeScriptPath = frontendDir + "/scripts/jd-search-browser-recall.mjs";
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
                    "--products", toWorkspaceRelative(workDir, effectiveProductPath),
                    "--output", toWorkspaceRelative(workDir, resolvedOutputPath),
                    "--top-k", String.valueOf(topK),
                    "--max-products", String.valueOf(maxProducts)));

            // CDP 地址优先级：前端传入 > 环境变量 PUBLIC_TASK_CDP_URL
            String effectiveCdpUrl = defaultIfBlank(cdpUrl, resolveDefaultCdpUrl());
            if (effectiveCdpUrl != null && !effectiveCdpUrl.trim().isEmpty()) {
                // 补全协议头
                String normalizedCdpUrl = effectiveCdpUrl.trim();
                if (!normalizedCdpUrl.startsWith("http://") && !normalizedCdpUrl.startsWith("https://")) {
                    normalizedCdpUrl = "http://" + normalizedCdpUrl;
                }
                args.add("--cdp-url");
                args.add(normalizedCdpUrl);
            } else {
                args.add("--headless");
                args.add("true");
            }
            if (resolvedFixtureDir != null && !resolvedFixtureDir.trim().isEmpty()) {
                args.add("--fixture-dir");
                args.add(toWorkspaceRelative(workDir, resolvedFixtureDir));
            }
            ScriptExecution scriptExecution = runNodeScript(frontendDir, nodeScriptPath, args.toArray(new String[0]));
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
            result.put("recallMode", "jd_browser_search");
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
                    "--cdp-url", defaultIfBlank(cdpUrl, resolveDefaultCdpUrl()),
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

    /**
     * 更新任务进度与提示文案。
     */
    private void runAnalyzeTask(String taskId, int clusterK) {
        PublicTaskStatusVO status = tasks.get(taskId);
        try {
            updateProgress(status, 15D, "正在计算用户 RFM 指标");
            rfmService.calculateAllUserRFM();

            updateProgress(status, 70D, "正在执行 K-Means 聚类分析");
            rfmService.performKMeansClustering(clusterK);

            Map<String, Object> result = new HashMap<>();
            result.put("clusterK", clusterK);
            completeTask(status, "数据分析完成", result);
        } catch (Throwable e) {
            log.error("数据分析任务异常", e);
            failTask(status, "数据分析失败: " + e.getMessage());
        }
    }

    private void updateProgress(PublicTaskStatusVO status, double progress, String message) {
        status.setProgress(progress);
        status.setMessage(message);
        status.setStatus("running");
    }

    /**
     * 把任务状态切换为成功完成，并附带结果负载。
     */
    private void completeTask(PublicTaskStatusVO status, String message, Map<String, Object> result) {
        status.setRunning(false);
        status.setProgress(100D);
        status.setStatus("success");
        status.setMessage(message);
        status.setFinishedAt(System.currentTimeMillis());
        status.setResult(result);
    }

    /**
     * 把任务状态切换为失败。
     */
    private void failTask(PublicTaskStatusVO status, String message) {
        status.setRunning(false);
        status.setStatus("failed");
        status.setMessage(message);
        status.setFinishedAt(System.currentTimeMillis());
    }

    /**
     * 解析工作区根目录，兼容从 backend 子目录启动服务。
     */
    private String resolveWorkDir() {
        String configured = System.getenv(PUBLIC_TASK_WORKSPACE_ROOT_ENV);
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
     * 把相对路径解析到工作区根目录下。
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

    /**
     * 返回执行 Python 脚本所使用的解释器路径。
     */
    private String resolvePythonCommand() {
        String configured = System.getenv(PUBLIC_TASK_PYTHON_ENV);
        if (configured != null && !configured.trim().isEmpty()) {
            if (!isCommandAvailable(configured.trim(), "--version")) {
                throw new IllegalStateException(
                        "Configured Python interpreter is not available: " + configured.trim()
                                + ". Please check environment variable " + PUBLIC_TASK_PYTHON_ENV + ".");
            }
            return configured.trim();
        }
        for (String candidate : PYTHON_COMMAND_CANDIDATES) {
            if (isCommandAvailable(candidate, "--version")) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No Python interpreter found. Please set environment variable " + PUBLIC_TASK_PYTHON_ENV
                        + " or install python3/python in the current runtime.");
    }

    /**
     * 字符串为空时使用兜底值。
     */
    private String defaultIfBlank(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private String resolveDefaultCdpUrl() {
        String configured = System.getenv(PUBLIC_TASK_CDP_URL_ENV);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return DEFAULT_PUBLIC_TASK_CDP_URL;
    }

    /**
     * 执行 Python 脚本并收集标准输出。
     */
    private ScriptExecution runPythonScript(String workDir, String scriptPath, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolvePythonCommand(resolvePublicTaskWorkerUrl() != null));
        command.add(scriptPath);
        command.addAll(Arrays.asList(args));
        return runCommand(workDir, command, PYTHON_SCRIPT_TIMEOUT_SECONDS);
    }

    /**
     * 返回可用的 Node 命令名。
     */
    private String resolveNodeCommand(boolean preferWorkerRuntime) {
        if (preferWorkerRuntime) {
            return "node";
        }
        String nodeCmd = "node";
        if (!isCommandAvailable(nodeCmd, "--version")) {
            nodeCmd = "node.exe";
        }
        return nodeCmd;
    }

    private boolean isCommandAvailable(String command, String... args) {
        List<String> checkCommand = new ArrayList<>();
        checkCommand.add(command);
        checkCommand.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(checkCommand)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行 Node 脚本并收集标准输出。
     */
    private ScriptExecution runNodeScript(String workDir, String scriptPath, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveNodeCommand(resolvePublicTaskWorkerUrl() != null));
        command.add(scriptPath);
        command.addAll(Arrays.asList(args));

        return runCommand(workDir, command, NODE_SCRIPT_TIMEOUT_SECONDS);
    }

    private String resolvePythonCommand(boolean preferWorkerRuntime) {
        if (preferWorkerRuntime) {
            String configured = System.getenv(PUBLIC_TASK_PYTHON_ENV);
            if (configured != null && !configured.trim().isEmpty()) {
                return configured.trim();
            }
            return PYTHON_COMMAND_CANDIDATES.get(0);
        }
        return resolvePythonCommand();
    }

    private ScriptExecution runCommand(String workDir, List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        String workerUrl = resolvePublicTaskWorkerUrl();
        if (workerUrl != null) {
            assertWorkerHealthy(workerUrl);
            return runCommandViaWorker(workerUrl, workDir, command, timeoutSeconds);
        }
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

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        int exitCode = finished ? process.exitValue() : -1;
        if (!finished) {
            process.destroyForcibly();
        }
        return new ScriptExecution(exitCode, finished, output.toString());
    }

    private String resolvePublicTaskWorkerUrl() {
        String configured = System.getenv(PUBLIC_TASK_WORKER_URL_ENV);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        return configured.trim().replaceAll("/+$", "");
    }

    private void clearCancelSignal(String outputDir) {
        try {
            Path signalPath = Paths.get(outputDir, ".cancel_signal");
            Files.deleteIfExists(signalPath);
        } catch (IOException e) {
            log.warn("Failed to clear public task cancel signal under {}", outputDir, e);
        }
    }

    private void assertWorkerHealthy(String workerUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(workerUrl + "/health");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = readStreamAsString(responseStream);
            if (statusCode >= 400) {
                throw new IOException(
                        "Public task worker is unavailable. Health check returned HTTP " + statusCode + ": " + responseBody);
            }
        } catch (IOException e) {
            throw new IOException(
                    "Failed to reach public task worker at " + workerUrl
                            + ". Please confirm docker-compose has started service public-task-worker and "
                            + PUBLIC_TASK_WORKER_URL_ENV + " is correct.",
                    e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private ScriptExecution runCommandViaWorker(String workerUrl, String workDir, List<String> command, long timeoutSeconds)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(workerUrl + "/execute");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(15000L, timeoutSeconds * 1000L + 15000L)));
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            Map<String, Object> payload = new HashMap<>();
            payload.put("command", command);
            payload.put("workDir", workDir);
            payload.put("timeoutSeconds", timeoutSeconds);
            payload.put("env", collectPublicTaskEnv());

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(OBJECT_MAPPER.writeValueAsBytes(payload));
            }

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = readStreamAsString(responseStream);
            if (statusCode >= 400) {
                throw new IOException("Public task worker request failed with HTTP " + statusCode + ": " + responseBody);
            }
            WorkerExecutionResponse response = OBJECT_MAPPER.readValue(responseBody, WorkerExecutionResponse.class);
            if (!response.finished) {
                return new ScriptExecution(
                        response.exitCode,
                        false,
                        buildTimeoutMessage(command, timeoutSeconds, response.output));
            }
            return new ScriptExecution(response.exitCode, response.finished, response.output == null ? "" : response.output);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Map<String, String> collectPublicTaskEnv() {
        Map<String, String> env = new HashMap<>();
        for (String key : PUBLIC_TASK_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                env.put(key, value.trim());
            }
        }
        return env;
    }

    private String buildTimeoutMessage(List<String> command, long timeoutSeconds, String output) {
        StringBuilder builder = new StringBuilder();
        builder.append("Command timed out after ").append(timeoutSeconds).append(" seconds: ")
                .append(String.join(" ", command));
        if (output != null && !output.trim().isEmpty()) {
            builder.append("\n").append(output.trim());
        }
        return builder.toString();
    }

    private String readStreamAsString(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString();
        }
    }

    /**
     * 统计 CSV 数据行数，自动扣除表头。
     */
    private long countDataRows(String csvPath) throws IOException {
        Path path = Paths.get(csvPath);
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(path)) {
            return Math.max(0, lines.count() - 1);
        }
    }

    /**
     * 识别公网映射相关表缺失的异常。
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


    /**
     * 将工作区内的绝对路径转换成相对路径，便于交给 Node 脚本消费。
     */
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

    /**
     * 合并多段脚本输出，保留先前日志。
     */
    private String mergeLogs(String first, String second) {
        if (first == null || first.trim().isEmpty()) {
            return second;
        }
        if (second == null || second.trim().isEmpty()) {
            return first;
        }
        return first + "\n" + second;
    }

    /**
     * 脚本执行结果快照。
     */
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

    private static class WorkerExecutionResponse {
        public int exitCode;
        public boolean finished;
        public String output;
    }
}
