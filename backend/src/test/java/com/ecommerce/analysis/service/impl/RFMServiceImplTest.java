package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.mapper.UserBehaviorMapper;
import com.ecommerce.analysis.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RFMServiceImplTest {

    @Test
    void shouldUpsertProfilesWhenCalculatingRfm() {
        UserBehaviorMapper userBehaviorMapper = mock(UserBehaviorMapper.class);
        UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);

        when(userBehaviorMapper.getUserBehaviorSummary()).thenReturn(Arrays.asList(
                summary(1001L, 12, 2, 1, 3, new BigDecimal("299.90"),
                        LocalDateTime.of(2024, 10, 1, 10, 0, 0),
                        LocalDateTime.of(2024, 10, 1, 10, 0, 0)),
                summary(1002L, 5, 0, 0, 0, BigDecimal.ZERO,
                        LocalDateTime.of(2024, 10, 2, 12, 0, 0),
                        null)));
        when(userProfileMapper.upsertProfile(any())).thenReturn(1);

        RFMServiceImpl service = new RFMServiceImpl();
        ReflectionTestUtils.setField(service, "userBehaviorMapper", userBehaviorMapper);
        ReflectionTestUtils.setField(service, "userProfileMapper", userProfileMapper);

        service.calculateAllUserRFM();

        verify(userProfileMapper, times(2)).upsertProfile(any());
        verify(userProfileMapper, never()).insert(any());
        verify(userProfileMapper, never()).selectOne(any());
    }

    @Test
    void shouldRejectConcurrentAnalysisRun() throws Exception {
        UserBehaviorMapper userBehaviorMapper = mock(UserBehaviorMapper.class);
        UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(userBehaviorMapper.getUserBehaviorSummary()).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new TimeoutException("test did not release analysis latch");
            }
            return Collections.emptyList();
        });

        RFMServiceImpl service = new RFMServiceImpl();
        ReflectionTestUtils.setField(service, "userBehaviorMapper", userBehaviorMapper);
        ReflectionTestUtils.setField(service, "userProfileMapper", userProfileMapper);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(service::calculateAllUserRFM);

        try {
            if (!started.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("first analysis run did not start in time");
            }

            assertThrows(IllegalStateException.class, service::calculateAllUserRFM);
        } finally {
            release.countDown();
            future.get(3, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private Map<String, Object> summary(Long userId,
            int totalViews,
            int totalCarts,
            int totalFavs,
            int totalBuys,
            BigDecimal totalAmount,
            LocalDateTime lastActiveTime,
            LocalDateTime lastBuyTime) {
        Map<String, Object> map = new HashMap<>();
        map.put("user_id", userId);
        map.put("total_views", totalViews);
        map.put("total_carts", totalCarts);
        map.put("total_favs", totalFavs);
        map.put("total_buys", totalBuys);
        map.put("total_amount", totalAmount);
        map.put("last_active_time", lastActiveTime);
        map.put("last_buy_time", lastBuyTime);
        return map;
    }
}
