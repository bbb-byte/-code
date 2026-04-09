package com.ecommerce.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 公网映射评分分页预览结果
 */
@Data
public class PublicMappingScorePreviewVO {

    private int page;

    private int pageSize;

    private long total;

    private List<PublicMappingScoreRowVO> rows = new ArrayList<>();
}
