package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.common.Constants;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 统一封装分析域缓存的读写与清理逻辑。
 */
@Service
public class AnalysisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 优先按原始对象类型读取缓存；适合 RedisTemplate 能直接反序列化的场景。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Supplier<T> loader) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return (T) cached;
            }
        } catch (Exception ignored) {
            // Redis 不可用时直接回退到数据库读取，避免缓存故障放大成接口故障。
        }

        T value = loader.get();
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // 缓存写失败不影响主流程返回。
        }
        return value;
    }

    /**
     * 读取单对象缓存；如果反序列化类型不完全一致，则通过 ObjectMapper 做一次转换兜底。
     */
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
            // 缓存写失败不影响主流程返回。
        }
        return value;
    }

    /**
     * 读取列表缓存，并显式声明列表元素类型，避免泛型擦除导致的反序列化问题。
     */
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
            // 缓存写失败不影响主流程返回。
        }
        return value;
    }

    /**
     * 清理分析域缓存。
     */
    public void evictAnalysisCaches() {
        evictByPrefix(Constants.REDIS_ANALYSIS_PREFIX);
    }

    /**
     * 清理画像域缓存。
     */
    public void evictProfileCaches() {
        evictByPrefix(Constants.REDIS_PROFILE_PREFIX);
    }

    /**
     * 同时清理分析和画像相关缓存。
     */
    public void evictAllAnalyticsCaches() {
        evictAnalysisCaches();
        evictProfileCaches();
    }

    /**
     * 按前缀批量删除缓存键。
     */
    private void evictByPrefix(String prefix) {
        try {
            Set<String> keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
            // 缓存淘汰是最佳努力策略，不应反向影响业务流程。
        }
    }

    /**
     * 仅删除一个确定的缓存键，常用于类型转换失败后的修复。
     */
    private void evictExact(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception ignored) {
            // 最佳努力删除即可。
        }
    }
}
