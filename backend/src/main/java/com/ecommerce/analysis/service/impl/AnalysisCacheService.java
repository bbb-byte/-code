package com.ecommerce.analysis.service.impl;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecommerce.analysis.common.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class AnalysisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Supplier<T> loader) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return (T) cached;
            }
        } catch (Exception ignored) {
            // Fall back to database reads when Redis is unavailable or deserialization fails.
        }

        T value = loader.get();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Cache write failures should not affect the main response path.
        }
        return value;
    }

    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Class<T> targetType, Supplier<T> loader) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                if (targetType.isInstance(cached)) {
                    return targetType.cast(cached);
                }
                return objectMapper.convertValue(cached, targetType);
            }
        } catch (Exception ignored) {
            evictExact(cacheKey);
        }

        T value = loader.get();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Cache write failures should not affect the main response path.
        }
        return value;
    }

    public <T> List<T> getOrLoadList(String cacheKey, long ttlMinutes, Class<T> elementType, Supplier<List<T>> loader) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
                return objectMapper.convertValue(cached, listType);
            }
        } catch (Exception ignored) {
            evictExact(cacheKey);
        }

        List<T> value = loader.get();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Cache write failures should not affect the main response path.
        }
        return value;
    }

    public void evictAnalysisCaches() {
        evictByPrefix(Constants.REDIS_ANALYSIS_PREFIX);
    }

    public void evictProfileCaches() {
        evictByPrefix(Constants.REDIS_PROFILE_PREFIX);
    }

    public void evictAllAnalyticsCaches() {
        evictAnalysisCaches();
        evictProfileCaches();
    }

    private void evictByPrefix(String prefix) {
        try {
            Set<String> keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
            // Cache eviction should be best-effort only.
        }
    }

    private void evictExact(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception ignored) {
            // Best-effort eviction only.
        }
    }
}
