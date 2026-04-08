package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.entity.UserBehavior;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据导入服务实现类（基于JDBC批处理优化）
 */
@Slf4j
@Service
public class DataImportServiceImpl implements DataImportService {

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // 导入状态
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

        long importedCount = 0;

        // 使用 ExecutorType.BATCH 开启批处理模式，关闭自动提交
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            UserBehaviorMapper batchMapper = sqlSession.getMapper(UserBehaviorMapper.class);

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                String line;
                long lineNumber = 0;

                log.info("开始导入数据文件: {}", filePath);
                long startTime = System.currentTimeMillis();

                while ((line = reader.readLine()) != null && !stopFlag) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    if (maxRows > 0 && lineNumber >= maxRows) {
                        break;
                    }

                    try {
                        UserBehavior behavior = parseCsvLine(line);
                        if (behavior != null) {
                            batchMapper.insert(behavior);
                            lineNumber++;
                            importedCount++;
                            processedRows.set(lineNumber);

                            // 每累积 batchSize 条执行一次 flushStatements，集中提交
                            if (lineNumber % batchSize == 0) {
                                sqlSession.flushStatements();
                                sqlSession.commit();
                                if (lineNumber % 100000 == 0) {
                                    log.info("已导入 {} 条记录...", lineNumber);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析行 {} 失败: {}", lineNumber, e.getMessage());
                    }
                }

                // 提交剩余未满 batchSize 的数据
                sqlSession.flushStatements();
                sqlSession.commit();

                long duration = System.currentTimeMillis() - startTime;
                log.info("数据导入完成！共导入 {} 条记录，耗时 {} 秒", importedCount, duration / 1000);

            } catch (Exception e) {
                sqlSession.rollback();
                log.error("数据导入失败: {}", e.getMessage(), e);
            }
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

    /**
     * 解析CSV行数据
     * 新格式: user_id,item_id,category_id,behavior_type,timestamp,unit_price,qty
     * 兼容格式: user_id,item_id,category_id,behavior_type,timestamp,price
     * 旧格式: user_id,item_id,category_id,behavior_type,timestamp
     */
    private UserBehavior parseCsvLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 5) {
            return null;
        }

        try {
            UserBehavior behavior = new UserBehavior();

            Long userId = Long.parseLong(parts[0].trim());
            Long itemId = Long.parseLong(parts[1].trim());
            Long categoryId = Long.parseLong(parts[2].trim());
            String behaviorType = parts[3].trim();
            long timestamp = Long.parseLong(parts[4].trim());

            behavior.setUserId(userId);
            behavior.setItemId(itemId);
            behavior.setCategoryId(categoryId);
            behavior.setBehaviorType(behaviorType);
            behavior.setBehaviorTime(timestamp);

            // 生成事件唯一ID (用于去重)
            String rawKey = userId + "_" + itemId + "_" + behaviorType + "_" + timestamp;
            behavior.setEventId(generateMd5(rawKey));

            // 转换时间戳为日期时间
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.of("Asia/Shanghai"));
            behavior.setBehaviorDateTime(dateTime);

            // 解析价格字段（新格式支持，旧格式默认为0）
            if (parts.length >= 6 && !parts[5].trim().isEmpty()) {
                try {
                    behavior.setUnitPrice(new java.math.BigDecimal(parts[5].trim()));
                } catch (NumberFormatException e) {
                    behavior.setUnitPrice(java.math.BigDecimal.ZERO);
                }
            } else {
                behavior.setUnitPrice(java.math.BigDecimal.ZERO);
            }

            // 解析数量字段（默认1）
            if (parts.length >= 7 && !parts[6].trim().isEmpty()) {
                try {
                    behavior.setQty(Integer.parseInt(parts[6].trim()));
                } catch (NumberFormatException e) {
                    behavior.setQty(1);
                }
            } else {
                behavior.setQty(1);
            }

            return behavior;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 生成MD5哈希值
     */
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
            return input.hashCode() + "";
        }
    }
}
