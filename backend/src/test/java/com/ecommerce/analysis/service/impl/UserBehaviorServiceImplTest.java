package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.mapper.ProductPublicMetricMapper;
import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.mapper.UserProfileMapper;
import com.ecommerce.analysis.vo.HotProductsPublicMetricsPageVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserBehaviorServiceImplTest {

    @Test
    void shouldReturnHotProductsWithPublicMetricsFromDedicatedMapper() {
        UserBehaviorMapper userBehaviorMapper = mock(UserBehaviorMapper.class);
        UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
        ProductPublicMetricMapper productPublicMetricMapper = mock(ProductPublicMetricMapper.class);
        AnalysisCacheService analysisCacheService = mock(AnalysisCacheService.class);

        Map<String, Object> first = new HashMap<>();
        first.put("item_id", 44600062L);
        first.put("buy_count", 12L);
        first.put("positive_rate", new BigDecimal("0.9830"));
        first.put("review_count", 2560L);
        first.put("source_platform", "jd");

        Map<String, Object> second = new HashMap<>();
        second.put("item_id", 99887766L);
        second.put("buy_count", 7L);
        second.put("positive_rate", null);
        second.put("review_count", null);
        second.put("source_platform", null);

        HotProductsPublicMetricsPageVO page = new HotProductsPublicMetricsPageVO();
        page.setPage(1);
        page.setPageSize(5);
        page.setTotal(2);
        page.setRows(Arrays.asList(first, second));

        when(analysisCacheService.getOrLoad(
                org.mockito.ArgumentMatchers.eq("analysis:hot-products:public-metrics:jd:false:1:5"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(HotProductsPublicMetricsPageVO.class),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(page);

        UserBehaviorServiceImpl service = new UserBehaviorServiceImpl();
        ReflectionTestUtils.setField(service, "userBehaviorMapper", userBehaviorMapper);
        ReflectionTestUtils.setField(service, "userProfileMapper", userProfileMapper);
        ReflectionTestUtils.setField(service, "productPublicMetricMapper", productPublicMetricMapper);
        ReflectionTestUtils.setField(service, "analysisCacheService", analysisCacheService);

        HotProductsPublicMetricsPageVO result = service.getHotProductsWithPublicMetrics(1, 5, false);

        assertEquals(2, result.getRows().size());
        assertEquals(2L, result.getTotal());
        assertEquals(44600062L, result.getRows().get(0).get("item_id"));
        assertEquals(new BigDecimal("0.9830"), result.getRows().get(0).get("positive_rate"));
        assertEquals(null, result.getRows().get(1).get("source_platform"));
        verify(analysisCacheService).getOrLoad(
                org.mockito.ArgumentMatchers.eq("analysis:hot-products:public-metrics:jd:false:1:5"),
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(HotProductsPublicMetricsPageVO.class),
                org.mockito.ArgumentMatchers.any());
    }
}
