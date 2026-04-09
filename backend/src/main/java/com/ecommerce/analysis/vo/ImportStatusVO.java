package com.ecommerce.analysis.vo;

import lombok.Data;

/**
 * 数据导入状态
 */
@Data
public class ImportStatusVO {

    private boolean importing;
    private double progress;
    private long totalRows;
    private long processedRows;
    private long insertedRows;
    private long skippedRows;
    private long inFileDuplicateRows;
    private long dbDuplicateRows;
    private long parseErrorRows;
    private long unsupportedBehaviorRows;
    private long preprocessedRows;
    private long defaultedPriceRows;
    private long defaultedQtyRows;
    private String filePath;
    private String format;
    private String message;
    private Long startedAt;
    private Long finishedAt;
}
