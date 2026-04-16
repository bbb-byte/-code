package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.common.Constants;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 统一封装分析域与画像域缓存的读写和清理逻辑。
 * 对同一个缓存 key 使用本地锁，避免高并发未命中时同时穿透到数据库。
 */
@Service
public class AnalysisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 进程内的按 key 锁，用于抑制缓存击穿。
     */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    /**
     * 优先按原始对象类型读取缓存。
     * 适用于 RedisTemplate 能直接反序列化为目标对象的场景。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Supplier<T> loader) {
        Object cached = readRaw(cacheKey);
        if (cached != null) {
            return (T) cached;
        }

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                return (T) cachedInsideLock;
            }

            T value = loader.get();
            writeCache(cacheKey, value, ttlMinutes);
            return value;
        }
    }

    /**
     * 读取单对象缓存。
     * 如果缓存中的实际类型与目标类型不完全一致，则通过 ObjectMapper 做一次转换兜底。
     */
    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Class<T> targetType, Supplier<T> loader) {
        Object cached = readRaw(cacheKey);
        if (cached != null) {
            T converted = convertValue(cached, targetType);
            if (converted != null) {
                return converted;
            }
            evictExact(cacheKey);
        }

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                T converted = convertValue(cachedInsideLock, targetType);
                if (converted != null) {
                    return converted;
                }
                evictExact(cacheKey);
            }

            T value = loader.get();
            writeCache(cacheKey, value, ttlMinutes);
            return value;
        }
    }

    /**
     * 读取列表缓存，并显式指定元素类型，避免泛型擦除带来的反序列化问题。
     */
    public <T> List<T> getOrLoadList(String cacheKey, long ttlMinutes, Class<T> elementType, Supplier<List<T>> loader) {
        Object cached = readRaw(cacheKey);
        if (cached != null) {
            List<T> converted = convertList(cached, elementType);
            if (converted != null) {
                return converted;
            }
            evictExact(cacheKey);
        }

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                List<T> converted = convertList(cachedInsideLock, elementType);
                if (converted != null) {
                    return converted;
                }
                evictExact(cacheKey);
            }

            List<T> value = loader.get();
            writeCache(cacheKey, value, ttlMinutes);
            return value;
        }
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
     * 同时清理分析域和画像域缓存。
     */
    public void evictAllAnalyticsCaches() {
        evictAnalysisCaches();
        evictProfileCaches();
    }

    /**
     * 原样读取 Redis 中的缓存值。
     * Redis 不可用时返回 null，由调用方回退到数据库加载。
     */
    private Object readRaw(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 以 best-effort 方式写入缓存。
     * 缓存写失败不影响主业务流程返回。
     */
    private void writeCache(String cacheKey, Object value, long ttlMinutes) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Best-effort cache write.
        }
    }

    /**
     * 将缓存对象转换为目标类型。
     */
    private <T> T convertValue(Object cached, Class<T> targetType) {
        try {
            if (targetType.isInstance(cached)) {
                return targetType.cast(cached);
            }
            return objectMapper.convertValue(cached, targetType);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将缓存对象转换为带元素类型信息的列表。
     */
    private <T> List<T> convertList(Object cached, Class<T> elementType) {
        try {
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return objectMapper.convertValue(cached, listType);
        } catch (Exception ignored) {
            return null;
        }
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
            // Best-effort cache eviction.
        }
    }

    /**
     * 删除单个缓存键，常用于缓存反序列化失败后的修复。
     */
    private void evictExact(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception ignored) {
            // Best-effort exact cache eviction.
        }
    }
}
