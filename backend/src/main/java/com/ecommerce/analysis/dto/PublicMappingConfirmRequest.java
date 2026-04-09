package com.ecommerce.analysis.dto;

import com.ecommerce.analysis.vo.PublicMappingScoreRowVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 公网映射确认入库请求。
 */
@Data
public class PublicMappingConfirmRequest {

    private List<PublicMappingScoreRowVO> rows = new ArrayList<>();
}
