package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.Product;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.mapper.ProductMapper;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.service.DataImportService;
import com.ecommerce.analysis.vo.ImportStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据导入服务实现类（基于JDBC批处理优化）
 */
@Slf4j
@Service
public class DataImportServiceImpl implements DataImportService {

    private static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ARCHIVE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<DateTimeFormatter> FLEXIBLE_TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    private final AtomicBoolean importing = new AtomicBoolean(false);
    private final AtomicLong acceptedRows = new AtomicLong(0);
    private final AtomicLong processedRows = new AtomicLong(0);
    private final AtomicLong totalRows = new AtomicLong(0);
    private final AtomicLong insertedRows = new AtomicLong(0);
    private final AtomicLong skippedRows = new AtomicLong(0);
    private final AtomicLong inFileDuplicateRows = new AtomicLong(0);
    private final AtomicLong dbDuplicateRows = new AtomicLong(0);
    private final AtomicLong parseErrorRows = new AtomicLong(0);
    private final AtomicLong unsupportedBehaviorRows = new AtomicLong(0);
    private final AtomicLong preprocessedRows = new AtomicLong(0);
    private final AtomicLong defaultedPriceRows = new AtomicLong(0);
    private final AtomicLong defaultedQtyRows = new AtomicLong(0);
    private volatile boolean stopFlag = false;
    private volatile String currentFilePath;
    private volatile String currentFormat;
    private volatile String statusMessage = "暂无导入任务";
    private volatile Long startedAt;
    private volatile Long finishedAt;

    @Override
    @Async
    public void importCsvData(String filePath, int batchSize, long maxRows) {
        if (importing.get()) {
            log.warn("已有导入任务正在执行");
            return;
        }

        importing.set(true);
        resetImportStatus(filePath, maxRows);

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false);
                BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            UserBehaviorMapper behaviorMapper = sqlSession.getMapper(UserBehaviorMapper.class);
            ProductMapper productMapper = sqlSession.getMapper(ProductMapper.class);
            Map<Long, ProductSnapshot> syncedProducts = new HashMap<>();
            Set<String> seenEventIds = new HashSet<>();

            long pendingInsertCount = 0;
            long startTime = startedAt == null ? System.currentTimeMillis() : startedAt.longValue();
            String line = readFirstDataLine(reader);

            if (line == null) {
                log.warn("导入文件为空: {}", filePath);
                statusMessage = "导入文件为空";
                return;
            }

            DatasetSpec datasetSpec;
            try {
                datasetSpec = resolveDatasetSpec(line);
            } catch (IllegalArgumentException e) {
                log.error("不支持的数据格式，仅支持 archive 主数据、crawler 7 列样本或带标准表头的指定文件: {}", filePath);
                statusMessage = "不支持的数据格式，仅支持 archive 主数据、crawler 7 列样本或带标准表头的指定文件";
                return;
            }
            currentFormat = datasetSpec.getDisplayName();

            if (datasetSpec.hasHeader()) {
                line = reader.readLine();
            }

            SamplingPlan samplingPlan = buildSamplingPlan(filePath, datasetSpec.format, maxRows);
            if (samplingPlan != null) {
                totalRows.set(samplingPlan.getPlannedRows());
                statusMessage = "正在按日期平衡抽样导入 " + datasetSpec.getDisplayName() + " 数据（自动预处理已启用）";
                log.info("启用 archive 按日期平衡抽样导入: totalDataRows={}, plannedRows={}, coveredDates={}",
                        samplingPlan.totalRows,
                        samplingPlan.getPlannedRows(),
                        samplingPlan.getCoveredDateCount());
            } else {
                statusMessage = "正在导入 " + datasetSpec.getDisplayName() + " 数据（自动预处理已启用）";
            }
            log.info("开始导入 {} 数据文件: {}", datasetSpec.getDisplayName(), filePath);

            while (line != null && !stopFlag) {
                if (!line.trim().isEmpty()) {
                    if (samplingPlan != null) {
                        String dateKey = extractArchiveDateKey(line);
                        if (dateKey != null && !samplingPlan.shouldImport(dateKey)) {
                            line = reader.readLine();
                            continue;
                        }
                    }

                    if (maxRows > 0 && acceptedRows.get() >= maxRows) {
                        break;
                    }

                    try {
                        ParsedRow parsedRow = parseCsvLine(line, datasetSpec);
                        processedRows.incrementAndGet();

                        if (!parsedRow.isValid()) {
                            skippedRows.incrementAndGet();
                            parseErrorRows.incrementAndGet();
                            if (parsedRow.failureReason == ParseFailureReason.UNSUPPORTED_BEHAVIOR) {
                                unsupportedBehaviorRows.incrementAndGet();
                            }
                            line = reader.readLine();
                            continue;
                        }

                        if (parsedRow.defaultedPrice) {
                            defaultedPriceRows.incrementAndGet();
                        }
                        if (parsedRow.defaultedQty) {
                            defaultedQtyRows.incrementAndGet();
                        }
                        if (parsedRow.preprocessed) {
                            preprocessedRows.incrementAndGet();
                        }

                        if (!seenEventIds.add(parsedRow.behavior.getEventId())) {
                            inFileDuplicateRows.incrementAndGet();
                            skippedRows.incrementAndGet();
                            line = reader.readLine();
                            continue;
                        }

                        behaviorMapper.insertIgnore(parsedRow.behavior);
                        pendingInsertCount++;
                        acceptedRows.incrementAndGet();

                        syncProductMetadata(productMapper, syncedProducts, parsedRow.product);

                        if (pendingInsertCount > 0 && pendingInsertCount % batchSize == 0) {
                            BatchImportStats batchStats = flushBatch(sqlSession, pendingInsertCount);
                            insertedRows.addAndGet(batchStats.insertedRows);
                            dbDuplicateRows.addAndGet(batchStats.duplicateRows);
                            skippedRows.addAndGet(batchStats.duplicateRows);
                            pendingInsertCount = 0;

                            long inserted = insertedRows.get();
                            if (inserted > 0 && inserted % 100000 == 0) {
                                log.info("已导入 {} 条记录...", inserted);
                            }
                        }
                    } catch (Exception e) {
                        skippedRows.incrementAndGet();
                        parseErrorRows.incrementAndGet();
                        log.warn("解析第 {} 条记录失败: {}", processedRows.get(), e.getMessage());
                    }
                }

                line = reader.readLine();
            }

            if (pendingInsertCount > 0) {
                BatchImportStats batchStats = flushBatch(sqlSession, pendingInsertCount);
                insertedRows.addAndGet(batchStats.insertedRows);
                dbDuplicateRows.addAndGet(batchStats.duplicateRows);
                skippedRows.addAndGet(batchStats.duplicateRows);
            } else {
                sqlSession.commit();
            }

            long duration = System.currentTimeMillis() - startTime;
            statusMessage = stopFlag ? "导入已停止" : buildCompletionMessage();
            log.info("数据导入结束，成功 {} 条，文件内去重 {} 条，数据库去重 {} 条，解析失败 {} 条，耗时 {} 秒",
                    insertedRows.get(),
                    inFileDuplicateRows.get(),
                    dbDuplicateRows.get(),
                    parseErrorRows.get(),
                    duration / 1000);
        } catch (Exception e) {
            statusMessage = "数据导入失败: " + e.getMessage();
            log.error("数据导入失败: {}", e.getMessage(), e);
        } finally {
            finishedAt = System.currentTimeMillis();
            importing.set(false);
        }
    }

    @Override
    public ImportStatusVO getImportStatus() {
        ImportStatusVO status = new ImportStatusVO();
        status.setImporting(importing.get());
        status.setProgress(calculateProgress());
        status.setTotalRows(totalRows.get());
        status.setProcessedRows(processedRows.get());
        status.setInsertedRows(insertedRows.get());
        status.setSkippedRows(skippedRows.get());
        status.setInFileDuplicateRows(inFileDuplicateRows.get());
        status.setDbDuplicateRows(dbDuplicateRows.get());
        status.setParseErrorRows(parseErrorRows.get());
        status.setUnsupportedBehaviorRows(unsupportedBehaviorRows.get());
        status.setPreprocessedRows(preprocessedRows.get());
        status.setDefaultedPriceRows(defaultedPriceRows.get());
        status.setDefaultedQtyRows(defaultedQtyRows.get());
        status.setFilePath(currentFilePath);
        status.setFormat(currentFormat);
        status.setMessage(statusMessage);
        status.setStartedAt(startedAt);
        status.setFinishedAt(finishedAt);
        return status;
    }

    @Override
    public boolean isImporting() {
        return importing.get();
    }

    @Override
    public void stopImport() {
        stopFlag = true;
        statusMessage = "正在停止导入";
        log.info("正在停止导入...");
    }

    private void resetImportStatus(String filePath, long maxRows) {
        stopFlag = false;
        processedRows.set(0);
        acceptedRows.set(0);
        totalRows.set(maxRows > 0 ? maxRows : 0);
        insertedRows.set(0);
        skippedRows.set(0);
        inFileDuplicateRows.set(0);
        dbDuplicateRows.set(0);
        parseErrorRows.set(0);
        unsupportedBehaviorRows.set(0);
        preprocessedRows.set(0);
        defaultedPriceRows.set(0);
        defaultedQtyRows.set(0);
        currentFilePath = filePath;
        currentFormat = null;
        statusMessage = "导入任务已启动";
        startedAt = System.currentTimeMillis();
        finishedAt = null;
    }

    private double calculateProgress() {
        long total = totalRows.get();
        if (total > 0) {
            if (!importing.get() && finishedAt != null) {
                return 100D;
            }
            return Math.min(100D, (double) acceptedRows.get() / total * 100);
        }
        return importing.get() ? 0D : 100D;
    }

    private SamplingPlan buildSamplingPlan(String filePath, DatasetFormat format, long maxRows) throws Exception {
        if (format != DatasetFormat.ARCHIVE || maxRows <= 0) {
            return null;
        }

        SamplingMetadata samplingMetadata = scanSamplingMetadata(filePath, format);
        if (samplingMetadata.totalRows <= 0) {
            return null;
        }

        if (samplingMetadata.totalRows <= maxRows) {
            totalRows.set(samplingMetadata.totalRows);
            return null;
        }

        return SamplingPlan.fromDateCounts(samplingMetadata.dateRowCounts, maxRows);
    }

    private SamplingMetadata scanSamplingMetadata(String filePath, DatasetFormat format) throws Exception {
        try (BufferedReader counterReader = new BufferedReader(new FileReader(filePath))) {
            String firstLine = readFirstDataLine(counterReader);
            if (firstLine == null) {
                return new SamplingMetadata(0, Collections.emptyMap());
            }

            long count = 0;
            Map<String, Long> dateRowCounts = new LinkedHashMap<>();
            if (!isHeaderLine(firstLine, format)) {
                count = 1;
                registerSamplingLine(dateRowCounts, firstLine, format);
            }

            String currentLine;
            while ((currentLine = counterReader.readLine()) != null) {
                if (!currentLine.trim().isEmpty()) {
                    count++;
                    registerSamplingLine(dateRowCounts, currentLine, format);
                }
            }
            return new SamplingMetadata(count, dateRowCounts);
        }
    }

    private void registerSamplingLine(Map<String, Long> dateRowCounts, String line, DatasetFormat format) {
        if (format != DatasetFormat.ARCHIVE) {
            return;
        }
        String dateKey = extractArchiveDateKey(line);
        if (dateKey == null) {
            return;
        }
        dateRowCounts.merge(dateKey, 1L, Long::sum);
    }

    private String extractArchiveDateKey(String line) {
        if (line == null) {
            return null;
        }
        int commaIndex = line.indexOf(',');
        String eventTime = commaIndex >= 0 ? line.substring(0, commaIndex).trim() : line.trim();
        if (eventTime.length() < 10) {
            return null;
        }
        return eventTime.substring(0, 10);
    }

    private String readFirstDataLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private DatasetSpec resolveDatasetSpec(String line) {
        DatasetFormat format = detectFormat(line);
        FlexibleColumnMapping mapping = format == DatasetFormat.MANUAL ? resolveFlexibleColumnMapping(line) : null;
        return new DatasetSpec(format, mapping, isHeaderLine(line, format));
    }

    ParsedRow parseCsvLine(String line, DatasetFormat format) {
        return parseCsvLine(line, new DatasetSpec(format, null, false));
    }

    ParsedRow parseCsvLine(String line, DatasetSpec datasetSpec) {
        if (datasetSpec.format == DatasetFormat.CRAWLED) {
            return parseCrawledCsvLine(line);
        }
        if (datasetSpec.format == DatasetFormat.MANUAL) {
            return parseManualCsvLine(line, datasetSpec.mapping);
        }
        return parseArchiveCsvLine(line);
    }

    ParsedRow parseArchiveCsvLine(String line) {
        String[] rawParts = line.split(",", -1);
        String[] parts = sanitizeCsvParts(rawParts);
        if (parts.length < 9) {
            return ParsedRow.failure(ParseFailureReason.INVALID_COLUMN_COUNT);
        }

        try {
            NormalizedValue behaviorType = normalizeBehaviorValue(parts[1]);
            if (behaviorType.value == null) {
                return ParsedRow.failure(ParseFailureReason.UNSUPPORTED_BEHAVIOR);
            }

            Long itemId = parsePositiveLong(parts[2]);
            Long categoryId = parsePositiveLong(parts[3]);
            Long userId = parsePositiveLong(parts[7]);
            String session = emptyToNull(parts[8]);
            if (itemId == null || categoryId == null || userId == null) {
                return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
            }

            ResolvedTimestamp resolvedTimestamp = parseEventTime(parts[0], DatasetFormat.ARCHIVE);
            if (resolvedTimestamp == null) {
                return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
            }
            boolean defaultedPrice = isMissingOrInvalidDecimal(parts, 6);
            BigDecimal unitPrice = parseDecimal(parts, 6);
            boolean preprocessed = hasSanitizationChange(rawParts, parts)
                    || behaviorType.preprocessed
                    || resolvedTimestamp.preprocessed
                    || defaultedPrice;

            UserBehavior behavior = buildBaseBehavior(userId, itemId, categoryId, behaviorType.value, resolvedTimestamp.timestamp);
            behavior.setBehaviorDateTime(resolvedTimestamp.eventTime);
            behavior.setUnitPrice(unitPrice);
            behavior.setQty(1);
            behavior.setEventId(generateMd5(userId + "_" + itemId + "_" + behaviorType.value + "_" + resolvedTimestamp.timestamp + "_" + session));

            Product product = buildProduct(itemId, categoryId, parts[4], parts[5], unitPrice);
            return ParsedRow.success(behavior, product, defaultedPrice, false, preprocessed);
        } catch (Exception e) {
            return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
        }
    }

    ParsedRow parseCrawledCsvLine(String line) {
        String[] rawParts = line.split(",", -1);
        String[] parts = sanitizeCsvParts(rawParts);
        if (parts.length < 7) {
            return ParsedRow.failure(ParseFailureReason.INVALID_COLUMN_COUNT);
        }

        try {
            Long userId = parsePositiveLong(parts[0]);
            Long itemId = parsePositiveLong(parts[1]);
            Long categoryId = parsePositiveLong(parts[2]);
            NormalizedValue behaviorType = normalizeBehaviorValue(parts[3]);
            if (behaviorType.value == null) {
                return ParsedRow.failure(ParseFailureReason.UNSUPPORTED_BEHAVIOR);
            }
            if (userId == null || itemId == null || categoryId == null) {
                return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
            }

            ResolvedTimestamp resolvedTimestamp = parseEventTime(parts[4], DatasetFormat.CRAWLED);
            if (resolvedTimestamp == null) {
                return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
            }
            boolean defaultedPrice = isMissingOrInvalidDecimal(parts, 5);
            BigDecimal unitPrice = parseDecimal(parts, 5);
            Integer qtyValue = parsePositiveInteger(parts, 6);
            boolean defaultedQty = qtyValue == null;
            int qty = defaultedQty ? 1 : qtyValue.intValue();
            boolean preprocessed = hasSanitizationChange(rawParts, parts)
                    || behaviorType.preprocessed
                    || resolvedTimestamp.preprocessed
                    || defaultedPrice
                    || defaultedQty;

            UserBehavior behavior = buildBaseBehavior(userId, itemId, categoryId, behaviorType.value, resolvedTimestamp.timestamp);
            behavior.setBehaviorDateTime(resolvedTimestamp.eventTime);
            behavior.setUnitPrice(unitPrice);
            behavior.setQty(qty);
            behavior.setEventId(generateMd5(userId + "_" + itemId + "_" + behaviorType.value + "_" + resolvedTimestamp.timestamp));

            Product product = buildFallbackProduct(itemId, categoryId, unitPrice);
            return ParsedRow.success(behavior, product, defaultedPrice, defaultedQty, preprocessed);
        } catch (Exception e) {
            return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
        }
    }

    ParsedRow parseManualCsvLine(String line, FlexibleColumnMapping mapping) {
        if (mapping == null) {
            return ParsedRow.failure(ParseFailureReason.INVALID_COLUMN_COUNT);
        }

        String[] rawParts = line.split(",", -1);
        String[] parts = sanitizeCsvParts(rawParts);
        if (parts.length <= mapping.getRequiredMaxIndex()) {
            return ParsedRow.failure(ParseFailureReason.INVALID_COLUMN_COUNT);
        }

        try {
            Long userId = parsePositiveLong(getValue(parts, mapping.userIdIndex));
            Long itemId = parsePositiveLong(getValue(parts, mapping.itemIdIndex));
            Long categoryId = parsePositiveLong(getValue(parts, mapping.categoryIdIndex));
            NormalizedValue behaviorType = normalizeBehaviorValue(getValue(parts, mapping.behaviorTypeIndex));
            ResolvedTimestamp resolvedTimestamp = parseEventTime(getValue(parts, mapping.timestampIndex), DatasetFormat.MANUAL);
            if (userId == null || itemId == null || categoryId == null || behaviorType.value == null || resolvedTimestamp == null) {
                return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
            }

            boolean defaultedPrice = isMissingOrInvalidDecimal(parts, mapping.unitPriceIndex);
            BigDecimal unitPrice = parseDecimal(parts, mapping.unitPriceIndex);
            Integer qtyValue = parsePositiveInteger(parts, mapping.qtyIndex);
            boolean defaultedQty = qtyValue == null;
            int qty = defaultedQty ? 1 : qtyValue.intValue();

            String categoryCode = getValue(parts, mapping.categoryCodeIndex);
            String brand = getValue(parts, mapping.brandIndex);
            String session = getValue(parts, mapping.sessionIndex);
            boolean preprocessed = hasSanitizationChange(rawParts, parts)
                    || behaviorType.preprocessed
                    || resolvedTimestamp.preprocessed
                    || defaultedPrice
                    || defaultedQty;

            UserBehavior behavior = buildBaseBehavior(userId, itemId, categoryId, behaviorType.value, resolvedTimestamp.timestamp);
            behavior.setBehaviorDateTime(resolvedTimestamp.eventTime);
            behavior.setUnitPrice(unitPrice);
            behavior.setQty(qty);
            behavior.setEventId(generateMd5(userId + "_" + itemId + "_" + behaviorType.value + "_" + resolvedTimestamp.timestamp + "_" + session));

            Product product = buildProduct(itemId, categoryId, categoryCode, brand, unitPrice);
            if (product == null) {
                product = buildFallbackProduct(itemId, categoryId, unitPrice);
            }
            return ParsedRow.success(behavior, product, defaultedPrice, defaultedQty, preprocessed);
        } catch (Exception e) {
            return ParsedRow.failure(ParseFailureReason.INVALID_VALUE);
        }
    }

    private Product buildProduct(Long itemId, Long categoryId, String categoryCode, String brand, BigDecimal price) {
        String brandValue = emptyToNull(brand);
        String categoryLabel = emptyToNull(categoryCode);
        BigDecimal productPrice = normalizePrice(price);

        if (brandValue == null && categoryLabel == null && productPrice == null) {
            return null;
        }

        Product product = new Product();
        product.setItemId(itemId);
        product.setCategoryId(categoryId);
        product.setCategoryName(categoryLabel);
        product.setBrand(brandValue);
        product.setPrice(productPrice);
        product.setName(buildProductDisplayName(itemId, brandValue));
        return product;
    }

    private Product buildFallbackProduct(Long itemId, Long categoryId, BigDecimal price) {
        Product product = new Product();
        product.setItemId(itemId);
        product.setCategoryId(categoryId);
        product.setPrice(normalizePrice(price));
        product.setName(buildProductDisplayName(itemId, null));
        return product;
    }

    private void syncProductMetadata(ProductMapper productMapper,
            Map<Long, ProductSnapshot> syncedProducts,
            Product product) {
        if (product == null) {
            return;
        }

        ProductSnapshot current = syncedProducts.get(product.getItemId());
        ProductSnapshot incoming = ProductSnapshot.from(product);
        if (current != null && !current.shouldUpdate(incoming)) {
            return;
        }

        productMapper.upsertMetadata(product);
        syncedProducts.put(product.getItemId(), current == null ? incoming : current.merge(incoming));
    }

    private UserBehavior buildBaseBehavior(Long userId, Long itemId, Long categoryId, String behaviorType, long timestamp) {
        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setItemId(itemId);
        behavior.setCategoryId(categoryId);
        behavior.setBehaviorType(behaviorType);
        behavior.setBehaviorTime(timestamp);
        behavior.setBehaviorDateTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), SYSTEM_ZONE));
        behavior.setEventId(generateMd5(userId + "_" + itemId + "_" + behaviorType + "_" + timestamp));
        behavior.setUnitPrice(BigDecimal.ZERO);
        behavior.setQty(1);
        return behavior;
    }

    DatasetFormat detectFormat(String line) {
        String normalized = line == null ? "" : line.trim().toLowerCase();
        if (normalized.startsWith("event_time,event_type,product_id") || isArchiveDataLine(normalized)) {
            return DatasetFormat.ARCHIVE;
        }
        if (normalized.startsWith("user_id,item_id,category_id,behavior_type,timestamp")
                || isCrawledDataLine(normalized)) {
            return DatasetFormat.CRAWLED;
        }
        if (resolveFlexibleColumnMapping(line) != null) {
            return DatasetFormat.MANUAL;
        }
        throw new IllegalArgumentException("unsupported_dataset_format");
    }

    private boolean isHeaderLine(String line, DatasetFormat format) {
        String normalized = line == null ? "" : line.trim().toLowerCase();
        if (format == DatasetFormat.CRAWLED) {
            return normalized.startsWith("user_id,item_id,category_id,behavior_type,timestamp");
        }
        if (format == DatasetFormat.MANUAL) {
            return resolveFlexibleColumnMapping(line) != null;
        }
        return normalized.startsWith("event_time,event_type,product_id");
    }

    private boolean isArchiveDataLine(String normalized) {
        String[] parts = normalized.split(",", -1);
        return parts.length >= 9
                && parts[0].matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} utc")
                && normalizeBehaviorType(parts[1]) != null;
    }

    private boolean isCrawledDataLine(String normalized) {
        String[] parts = normalized.split(",", -1);
        return parts.length >= 7
                && isLongLike(parts[0])
                && isLongLike(parts[1])
                && isLongLike(parts[2])
                && normalizeBehaviorType(parts[3]) != null
                && isLongLike(parts[4]);
    }

    private String normalizeBehaviorType(String rawType) {
        String normalized = rawType == null ? "" : rawType.trim().toLowerCase();
        switch (normalized) {
            case "pv":
            case "view":
                return "pv";
            case "buy":
            case "purchase":
                return "buy";
            case "cart":
                return "cart";
            case "fav":
            case "favorite":
                return "fav";
            default:
                return null;
        }
    }

    private NormalizedValue normalizeBehaviorValue(String rawType) {
        String sanitized = sanitizeCsvToken(rawType);
        String normalized = normalizeBehaviorType(sanitized);
        if (normalized == null) {
            return new NormalizedValue(null, false);
        }
        return new NormalizedValue(normalized, !normalized.equals(sanitized));
    }

    private String buildProductDisplayName(Long itemId, String brand) {
        return brand == null ? "商品#" + itemId : brand + " #" + itemId;
    }

    private BigDecimal parseDecimal(String[] parts, int index) {
        if (index < 0) {
            return BigDecimal.ZERO;
        }
        if (parts.length <= index || parts[index].trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(parts[index].trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal normalizePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return price;
    }

    private Long parsePositiveLong(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(rawValue.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePositiveInteger(String[] parts, int index) {
        if (index < 0) {
            return null;
        }
        if (parts.length <= index || parts[index].trim().isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(parts[index].trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isMissingOrInvalidDecimal(String[] parts, int index) {
        if (index < 0) {
            return true;
        }
        if (parts.length <= index || parts[index].trim().isEmpty()) {
            return true;
        }
        try {
            new BigDecimal(parts[index].trim());
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private boolean isLongLike(String value) {
        return value != null && value.trim().matches("-?\\d+");
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String[] sanitizeCsvParts(String[] rawParts) {
        String[] sanitized = new String[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            sanitized[i] = sanitizeCsvToken(rawParts[i]);
        }
        return sanitized;
    }

    private boolean hasSanitizationChange(String[] rawParts, String[] sanitizedParts) {
        if (rawParts.length != sanitizedParts.length) {
            return true;
        }
        for (int i = 0; i < rawParts.length; i++) {
            if (!rawParts[i].equals(sanitizedParts[i])) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeCsvToken(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\uFEFF", "").trim();
        if (cleaned.length() >= 2) {
            char firstChar = cleaned.charAt(0);
            char lastChar = cleaned.charAt(cleaned.length() - 1);
            if ((firstChar == '"' && lastChar == '"') || (firstChar == '\'' && lastChar == '\'')) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
            }
        }
        return cleaned;
    }

    private ResolvedTimestamp parseEventTime(String rawValue, DatasetFormat format) {
        String value = sanitizeCsvToken(rawValue);
        if (value.isEmpty()) {
            return null;
        }

        if (value.matches("\\d{13}")) {
            return buildResolvedTimestamp(Long.parseLong(value) / 1000, true);
        }
        if (value.matches("\\d{10}")) {
            return buildResolvedTimestamp(Long.parseLong(value), format != DatasetFormat.CRAWLED);
        }

        if (value.endsWith(" UTC")) {
            try {
                LocalDateTime utcTime = LocalDateTime.parse(value.replace(" UTC", ""), ARCHIVE_TIME_FORMATTER);
                long timestamp = utcTime.toEpochSecond(ZoneOffset.UTC);
                return buildResolvedTimestamp(timestamp, false);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }

        try {
            Instant instant = Instant.parse(value);
            return buildResolvedTimestamp(instant.getEpochSecond(), true);
        } catch (DateTimeParseException ignored) {
            // fall through
        }

        for (DateTimeFormatter formatter : FLEXIBLE_TIME_FORMATTERS) {
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(value, formatter);
                long timestamp = localDateTime.atZone(SYSTEM_ZONE).toEpochSecond();
                return buildResolvedTimestamp(timestamp, true);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }

        return null;
    }

    private ResolvedTimestamp buildResolvedTimestamp(long timestamp, boolean preprocessed) {
        if (timestamp <= 0) {
            return null;
        }
        LocalDateTime eventTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), SYSTEM_ZONE);
        return new ResolvedTimestamp(timestamp, eventTime, preprocessed);
    }

    FlexibleColumnMapping resolveFlexibleColumnMapping(String headerLine) {
        String[] rawHeaders = headerLine == null ? new String[0] : headerLine.split(",", -1);
        if (rawHeaders.length < 5) {
            return null;
        }

        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < rawHeaders.length; i++) {
            String normalizedHeader = normalizeHeaderName(rawHeaders[i]);
            if (!normalizedHeader.isEmpty()) {
                indices.putIfAbsent(normalizedHeader, i);
            }
        }

        Integer userIdIndex = findFirstIndex(indices, "user_id", "userid", "uid");
        Integer itemIdIndex = findFirstIndex(indices, "item_id", "itemid", "product_id", "productid", "goods_id", "goodsid");
        Integer categoryIdIndex = findFirstIndex(indices, "category_id", "categoryid", "cate_id", "cateid");
        Integer behaviorTypeIndex = findFirstIndex(indices, "behavior_type", "behaviortype", "event_type", "eventtype", "behavior", "action");
        Integer timestampIndex = findFirstIndex(indices, "timestamp", "event_time", "eventtime", "behavior_time", "behaviortime", "time", "datetime");
        if (userIdIndex == null || itemIdIndex == null || categoryIdIndex == null || behaviorTypeIndex == null || timestampIndex == null) {
            return null;
        }

        Integer unitPriceIndex = findFirstIndex(indices, "unit_price", "unitprice", "price", "amount");
        Integer qtyIndex = findFirstIndex(indices, "qty", "quantity", "count", "num");
        Integer categoryCodeIndex = findFirstIndex(indices, "category_code", "categorycode", "category_name", "categoryname");
        Integer brandIndex = findFirstIndex(indices, "brand", "brand_name", "brandname");
        Integer sessionIndex = findFirstIndex(indices, "user_session", "usersession", "session", "session_id", "sessionid");

        return new FlexibleColumnMapping(
                userIdIndex,
                itemIdIndex,
                categoryIdIndex,
                behaviorTypeIndex,
                timestampIndex,
                unitPriceIndex == null ? -1 : unitPriceIndex,
                qtyIndex == null ? -1 : qtyIndex,
                categoryCodeIndex == null ? -1 : categoryCodeIndex,
                brandIndex == null ? -1 : brandIndex,
                sessionIndex == null ? -1 : sessionIndex);
    }

    private Integer findFirstIndex(Map<String, Integer> indices, String... aliases) {
        for (String alias : aliases) {
            Integer index = indices.get(alias);
            if (index != null) {
                return index;
            }
        }
        return null;
    }

    private String normalizeHeaderName(String rawHeader) {
        return sanitizeCsvToken(rawHeader)
                .toLowerCase()
                .replace('-', '_')
                .replace(" ", "")
                .replace(".", "");
    }

    private String getValue(String[] parts, int index) {
        if (index < 0 || parts.length <= index) {
            return null;
        }
        return emptyToNull(parts[index]);
    }

    private String buildCompletionMessage() {
        long cleanedRows = preprocessedRows.get();
        if (cleanedRows <= 0) {
            return "导入完成";
        }
        return "导入完成，自动预处理 " + cleanedRows + " 条";
    }

    private String generateMd5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private BatchImportStats flushBatch(SqlSession sqlSession, long pendingInsertCount) {
        long insertedCount = 0;
        long duplicateCount = 0;
        long accountedCount = 0;

        for (BatchResult batchResult : sqlSession.flushStatements()) {
            int[] updateCounts = batchResult.getUpdateCounts();
            if (updateCounts == null) {
                continue;
            }
            for (int updateCount : updateCounts) {
                accountedCount++;
                if (updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO) {
                    insertedCount++;
                } else if (updateCount == 0) {
                    duplicateCount++;
                }
            }
        }
        sqlSession.commit();

        if (accountedCount < pendingInsertCount) {
            insertedCount += pendingInsertCount - accountedCount;
        }
        return new BatchImportStats(insertedCount, duplicateCount);
    }

    enum DatasetFormat {
        ARCHIVE("archive"),
        CRAWLED("crawler"),
        MANUAL("指定文件");

        private final String displayName;

        DatasetFormat(String displayName) {
            this.displayName = displayName;
        }

        String getDisplayName() {
            return displayName;
        }
    }

    static class ParsedRow {
        final UserBehavior behavior;
        final Product product;
        final ParseFailureReason failureReason;
        final boolean defaultedPrice;
        final boolean defaultedQty;
        final boolean preprocessed;

        ParsedRow(UserBehavior behavior, Product product, ParseFailureReason failureReason, boolean defaultedPrice, boolean defaultedQty, boolean preprocessed) {
            this.behavior = behavior;
            this.product = product;
            this.failureReason = failureReason;
            this.defaultedPrice = defaultedPrice;
            this.defaultedQty = defaultedQty;
            this.preprocessed = preprocessed;
        }

        static ParsedRow success(UserBehavior behavior, Product product, boolean defaultedPrice, boolean defaultedQty, boolean preprocessed) {
            return new ParsedRow(behavior, product, null, defaultedPrice, defaultedQty, preprocessed);
        }

        static ParsedRow failure(ParseFailureReason failureReason) {
            return new ParsedRow(null, null, failureReason, false, false, false);
        }

        boolean isValid() {
            return behavior != null;
        }
    }

    enum ParseFailureReason {
        INVALID_COLUMN_COUNT,
        INVALID_VALUE,
        UNSUPPORTED_BEHAVIOR
    }

    static class BatchImportStats {
        final long insertedRows;
        final long duplicateRows;

        BatchImportStats(long insertedRows, long duplicateRows) {
            this.insertedRows = insertedRows;
            this.duplicateRows = duplicateRows;
        }
    }

    static class DatasetSpec {
        final DatasetFormat format;
        final FlexibleColumnMapping mapping;
        final boolean header;

        DatasetSpec(DatasetFormat format, FlexibleColumnMapping mapping, boolean header) {
            this.format = format;
            this.mapping = mapping;
            this.header = header;
        }

        boolean hasHeader() {
            return header;
        }

        String getDisplayName() {
            return format.getDisplayName();
        }
    }

    static class FlexibleColumnMapping {
        final int userIdIndex;
        final int itemIdIndex;
        final int categoryIdIndex;
        final int behaviorTypeIndex;
        final int timestampIndex;
        final int unitPriceIndex;
        final int qtyIndex;
        final int categoryCodeIndex;
        final int brandIndex;
        final int sessionIndex;

        FlexibleColumnMapping(int userIdIndex,
                int itemIdIndex,
                int categoryIdIndex,
                int behaviorTypeIndex,
                int timestampIndex,
                int unitPriceIndex,
                int qtyIndex,
                int categoryCodeIndex,
                int brandIndex,
                int sessionIndex) {
            this.userIdIndex = userIdIndex;
            this.itemIdIndex = itemIdIndex;
            this.categoryIdIndex = categoryIdIndex;
            this.behaviorTypeIndex = behaviorTypeIndex;
            this.timestampIndex = timestampIndex;
            this.unitPriceIndex = unitPriceIndex;
            this.qtyIndex = qtyIndex;
            this.categoryCodeIndex = categoryCodeIndex;
            this.brandIndex = brandIndex;
            this.sessionIndex = sessionIndex;
        }

        int getRequiredMaxIndex() {
            return Arrays.stream(new int[] { userIdIndex, itemIdIndex, categoryIdIndex, behaviorTypeIndex, timestampIndex })
                    .max()
                    .orElse(-1);
        }
    }

    static class NormalizedValue {
        final String value;
        final boolean preprocessed;

        NormalizedValue(String value, boolean preprocessed) {
            this.value = value;
            this.preprocessed = preprocessed;
        }
    }

    static class ResolvedTimestamp {
        final long timestamp;
        final LocalDateTime eventTime;
        final boolean preprocessed;

        ResolvedTimestamp(long timestamp, LocalDateTime eventTime, boolean preprocessed) {
            this.timestamp = timestamp;
            this.eventTime = eventTime;
            this.preprocessed = preprocessed;
        }
    }

    static class ProductSnapshot {
        private final String brand;
        private final String categoryName;
        private final BigDecimal price;

        ProductSnapshot(String brand, String categoryName, BigDecimal price) {
            this.brand = brand;
            this.categoryName = categoryName;
            this.price = price;
        }

        static ProductSnapshot from(Product product) {
            return new ProductSnapshot(product.getBrand(), product.getCategoryName(), product.getPrice());
        }

        boolean shouldUpdate(ProductSnapshot incoming) {
            return (isBlank(brand) && !isBlank(incoming.brand))
                    || (isBlank(categoryName) && !isBlank(incoming.categoryName))
                    || ((price == null || price.compareTo(BigDecimal.ZERO) <= 0)
                            && incoming.price != null
                            && incoming.price.compareTo(BigDecimal.ZERO) > 0);
        }

        ProductSnapshot merge(ProductSnapshot incoming) {
            return new ProductSnapshot(
                    isBlank(brand) ? incoming.brand : brand,
                    isBlank(categoryName) ? incoming.categoryName : categoryName,
                    (price == null || price.compareTo(BigDecimal.ZERO) <= 0) ? incoming.price : price);
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }

    static class SamplingMetadata {
        final long totalRows;
        final Map<String, Long> dateRowCounts;

        SamplingMetadata(long totalRows, Map<String, Long> dateRowCounts) {
            this.totalRows = totalRows;
            this.dateRowCounts = dateRowCounts;
        }
    }

    static class SamplingPlan {
        private final long totalRows;
        private final long plannedRows;
        private final Map<String, DaySamplingPlan> dayPlans;

        SamplingPlan(long totalRows, long plannedRows, Map<String, DaySamplingPlan> dayPlans) {
            this.totalRows = totalRows;
            this.plannedRows = plannedRows;
            this.dayPlans = dayPlans;
        }

        static SamplingPlan fromDateCounts(Map<String, Long> dateRowCounts, long targetRows) {
            Map<String, Long> quotas = allocateDailyTargets(dateRowCounts, targetRows);
            Map<String, DaySamplingPlan> dayPlans = new HashMap<>();
            long totalRows = 0;
            long plannedRows = 0;

            for (Map.Entry<String, Long> entry : dateRowCounts.entrySet()) {
                long dayTotalRows = entry.getValue();
                totalRows += dayTotalRows;

                long dayTargetRows = quotas.getOrDefault(entry.getKey(), 0L);
                if (dayTargetRows <= 0) {
                    continue;
                }

                dayPlans.put(entry.getKey(), new DaySamplingPlan(dayTotalRows, dayTargetRows));
                plannedRows += dayTargetRows;
            }

            return new SamplingPlan(totalRows, plannedRows, dayPlans);
        }

        long getPlannedRows() {
            return plannedRows;
        }

        int getCoveredDateCount() {
            return dayPlans.size();
        }

        long getTargetRowsForDate(String dateKey) {
            DaySamplingPlan dayPlan = dayPlans.get(dateKey);
            return dayPlan == null ? 0L : dayPlan.targetRows;
        }

        boolean shouldImport(String dateKey) {
            DaySamplingPlan dayPlan = dayPlans.get(dateKey);
            return dayPlan != null && dayPlan.shouldImport();
        }

        private static Map<String, Long> allocateDailyTargets(Map<String, Long> dateRowCounts, long targetRows) {
            if (dateRowCounts == null || dateRowCounts.isEmpty() || targetRows <= 0) {
                return Collections.emptyMap();
            }

            List<String> sortedDates = new ArrayList<>(dateRowCounts.keySet());
            Collections.sort(sortedDates);

            Map<String, Long> quotas = new LinkedHashMap<>();
            for (String date : sortedDates) {
                quotas.put(date, 0L);
            }

            if (targetRows >= sortedDates.size()) {
                for (String date : sortedDates) {
                    quotas.put(date, 1L);
                }
            } else {
                for (int i = 0; i < targetRows; i++) {
                    int index = (int) (((2L * i + 1) * sortedDates.size()) / (2L * targetRows));
                    String date = sortedDates.get(Math.min(index, sortedDates.size() - 1));
                    quotas.put(date, 1L);
                }
                return quotas;
            }

            long guaranteedRows = quotas.values().stream().mapToLong(Long::longValue).sum();
            long remainingRows = targetRows - guaranteedRows;
            if (remainingRows <= 0) {
                return quotas;
            }

            long totalAvailableRows = 0;
            for (Map.Entry<String, Long> entry : dateRowCounts.entrySet()) {
                totalAvailableRows += Math.max(0L, entry.getValue() - quotas.getOrDefault(entry.getKey(), 0L));
            }
            if (totalAvailableRows <= 0) {
                return quotas;
            }

            List<QuotaRemainder> remainders = new ArrayList<>();
            long allocatedRows = 0;
            for (String date : sortedDates) {
                long availableRows = Math.max(0L, dateRowCounts.get(date) - quotas.getOrDefault(date, 0L));
                if (availableRows <= 0) {
                    continue;
                }

                long scaledRows = availableRows * remainingRows;
                long baseRows = scaledRows / totalAvailableRows;
                long remainder = scaledRows % totalAvailableRows;

                quotas.put(date, quotas.get(date) + baseRows);
                allocatedRows += baseRows;
                remainders.add(new QuotaRemainder(date, remainder, availableRows));
            }

            long leftRows = remainingRows - allocatedRows;
            remainders.sort((left, right) -> {
                int remainderCompare = Long.compare(right.remainder, left.remainder);
                if (remainderCompare != 0) {
                    return remainderCompare;
                }
                int capacityCompare = Long.compare(right.availableRows, left.availableRows);
                if (capacityCompare != 0) {
                    return capacityCompare;
                }
                return left.date.compareTo(right.date);
            });

            for (int i = 0; i < remainders.size() && leftRows > 0; i++) {
                QuotaRemainder remainder = remainders.get(i);
                quotas.put(remainder.date, quotas.get(remainder.date) + 1);
                leftRows--;
            }

            return quotas;
        }
    }

    static class DaySamplingPlan {
        final long totalRows;
        final long targetRows;
        private long seenRows;

        DaySamplingPlan(long totalRows, long targetRows) {
            this.totalRows = totalRows;
            this.targetRows = targetRows;
        }

        boolean shouldImport() {
            long previousBucket = seenRows * targetRows / totalRows;
            seenRows++;
            long currentBucket = seenRows * targetRows / totalRows;
            return currentBucket > previousBucket;
        }
    }

    static class QuotaRemainder {
        final String date;
        final long remainder;
        final long availableRows;

        QuotaRemainder(String date, long remainder, long availableRows) {
            this.date = date;
            this.remainder = remainder;
            this.availableRows = availableRows;
        }
    }
}
