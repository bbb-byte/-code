package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.Product;
import com.ecommerce.analysis.entity.UserBehavior;
import com.ecommerce.analysis.mapper.ProductMapper;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.service.DataImportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
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

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    private final AtomicBoolean importing = new AtomicBoolean(false);
    private final AtomicLong processedRows = new AtomicLong(0);
    private final AtomicLong totalRows = new AtomicLong(0);
    private volatile boolean stopFlag = false;

    @Override
    @Async
    public void importCsvData(String filePath, int batchSize, long maxRows) {
        if (importing.get()) {
            log.warn("已有导入任务正在执行");
            return;
        }

        importing.set(true);
        stopFlag = false;
        processedRows.set(0);
        totalRows.set(maxRows > 0 ? maxRows : Long.MAX_VALUE);

        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false);
                BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            UserBehaviorMapper behaviorMapper = sqlSession.getMapper(UserBehaviorMapper.class);
            ProductMapper productMapper = sqlSession.getMapper(ProductMapper.class);
            Map<Long, ProductSnapshot> syncedProducts = new HashMap<>();

            long importedCount = 0;
            long startTime = System.currentTimeMillis();
            String line = readFirstDataLine(reader);

            if (line == null) {
                log.warn("导入文件为空: {}", filePath);
                return;
            }

            if (!isSupportedArchiveLine(line)) {
                log.error("不支持的数据格式，仅支持 archive 电商行为数据集: {}", filePath);
                return;
            }

            if (isHeaderLine(line)) {
                line = reader.readLine();
            }

            log.info("开始导入 archive 数据文件: {}", filePath);

            while (line != null && !stopFlag) {
                if (!line.trim().isEmpty()) {
                    if (maxRows > 0 && importedCount >= maxRows) {
                        break;
                    }

                    try {
                        ParsedRow parsedRow = parseCsvLine(line);
                        if (parsedRow != null && parsedRow.behavior != null) {
                            behaviorMapper.insert(parsedRow.behavior);
                            importedCount++;
                            processedRows.set(importedCount);

                            syncProductMetadata(productMapper, syncedProducts, parsedRow.product);

                            if (importedCount % batchSize == 0) {
                                sqlSession.flushStatements();
                                sqlSession.commit();
                                if (importedCount % 100000 == 0) {
                                    log.info("已导入 {} 条记录...", importedCount);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析第 {} 条记录失败: {}", importedCount + 1, e.getMessage());
                    }
                }

                line = reader.readLine();
            }

            sqlSession.flushStatements();
            sqlSession.commit();

            long duration = System.currentTimeMillis() - startTime;
            log.info("数据导入完成，共导入 {} 条记录，耗时 {} 秒", importedCount, duration / 1000);
        } catch (Exception e) {
            log.error("数据导入失败: {}", e.getMessage(), e);
        } finally {
            importing.set(false);
        }
    }

    @Override
    public double getImportProgress() {
        if (totalRows.get() == 0) {
            return 0;
        }
        return (double) processedRows.get() / totalRows.get() * 100;
    }

    @Override
    public boolean isImporting() {
        return importing.get();
    }

    @Override
    public void stopImport() {
        stopFlag = true;
        log.info("正在停止导入...");
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

    ParsedRow parseCsvLine(String line) {
        return parseArchiveCsvLine(line);
    }

    ParsedRow parseArchiveCsvLine(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length < 9) {
            return null;
        }

        try {
            String behaviorType = normalizeBehaviorType(parts[1].trim());
            if (behaviorType == null) {
                return null;
            }

            Long itemId = Long.parseLong(parts[2].trim());
            Long categoryId = Long.parseLong(parts[3].trim());
            Long userId = Long.parseLong(parts[7].trim());
            String session = emptyToNull(parts[8]);

            LocalDateTime utcTime = LocalDateTime.parse(parts[0].trim().replace(" UTC", ""), ARCHIVE_TIME_FORMATTER);
            long timestamp = utcTime.toEpochSecond(ZoneOffset.UTC);
            LocalDateTime eventTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), SYSTEM_ZONE);

            UserBehavior behavior = buildBaseBehavior(userId, itemId, categoryId, behaviorType, timestamp);
            behavior.setBehaviorDateTime(eventTime);
            behavior.setUnitPrice(parseDecimal(parts, 6));
            behavior.setQty(1);
            behavior.setEventId(generateMd5(userId + "_" + itemId + "_" + behaviorType + "_" + timestamp + "_" + session));

            Product product = buildProduct(itemId, categoryId, parts[4], parts[5], behavior.getUnitPrice());
            return new ParsedRow(behavior, product);
        } catch (Exception e) {
            return null;
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
        throw new IllegalArgumentException("unsupported_dataset_format");
    }

    private boolean isHeaderLine(String line) {
        String normalized = line == null ? "" : line.trim().toLowerCase();
        return normalized.startsWith("event_time,event_type,product_id");
    }

    private boolean isSupportedArchiveLine(String line) {
        String normalized = line == null ? "" : line.trim().toLowerCase();
        return normalized.startsWith("event_time,event_type,product_id") || isArchiveDataLine(normalized);
    }

    private boolean isArchiveDataLine(String normalized) {
        String[] parts = normalized.split(",", -1);
        return parts.length >= 9
                && parts[0].matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} utc")
                && normalizeBehaviorType(parts[1]) != null;
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

    private String buildProductDisplayName(Long itemId, String brand) {
        return brand == null ? "商品#" + itemId : brand + " #" + itemId;
    }

    private BigDecimal parseDecimal(String[] parts, int index) {
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

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    enum DatasetFormat {
        ARCHIVE
    }

    static class ParsedRow {
        final UserBehavior behavior;
        final Product product;

        ParsedRow(UserBehavior behavior, Product product) {
            this.behavior = behavior;
            this.product = product;
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
}
