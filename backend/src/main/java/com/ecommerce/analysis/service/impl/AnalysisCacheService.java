package com.ecommerce.analysis.service.impl;

import com.ecommerce.analysis.common.Constants;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 统一封装分析域与画像域缓存的读写和清理逻辑。
 * 对同一个缓存 key 使用本地锁，避免高并发未命中时同时穿透到数据库。
 */
@Service
@Slf4j
public class AnalysisCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.cache.redis-failure-cooldown-seconds:300}")
    private long redisFailureCooldownSeconds;

    /**
     * 进程内的按 key 锁，用于抑制缓存击穿。
     */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final AtomicLong redisBypassUntilEpochMillis = new AtomicLong(0L);

    /**
     * 优先按原始对象类型读取缓存。
     * 适用于 RedisTemplate 能直接反序列化为目标对象的场景。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String cacheKey, long ttlMinutes, Supplier<T> loader) {
        Object cached = readRaw(cacheKey);
        if (cached != null) {
            log.debug("Redis cache hit: key={}", cacheKey);
            return (T) cached;
        }
        log.debug("Redis cache miss: key={}", cacheKey);

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                log.debug("Redis cache hit after lock: key={}", cacheKey);
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
                log.debug("Redis cache hit: key={}, targetType={}", cacheKey, targetType.getSimpleName());
                return converted;
            }
            evictExact(cacheKey);
        }
        log.debug("Redis cache miss: key={}, targetType={}", cacheKey, targetType.getSimpleName());

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                T converted = convertValue(cachedInsideLock, targetType);
                if (converted != null) {
                    log.debug("Redis cache hit after lock: key={}, targetType={}", cacheKey, targetType.getSimpleName());
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
                log.debug("Redis cache hit: key={}, elementType={}", cacheKey, elementType.getSimpleName());
                return converted;
            }
            evictExact(cacheKey);
        }
        log.debug("Redis cache miss: key={}, elementType={}", cacheKey, elementType.getSimpleName());

        Object lock = keyLocks.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            Object cachedInsideLock = readRaw(cacheKey);
            if (cachedInsideLock != null) {
                List<T> converted = convertList(cachedInsideLock, elementType);
                if (converted != null) {
                    log.debug("Redis cache hit after lock: key={}, elementType={}", cacheKey, elementType.getSimpleName());
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
        if (shouldBypassRedis()) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Redis cache read failed for key={}. Falling back to database query.", cacheKey, e);
            return null;
        }
    }

    /**
     * 以 best-effort 方式写入缓存。
     * 缓存写失败不影响主业务流程返回。
     */
    private void writeCache(String cacheKey, Object value, long ttlMinutes) {
        if (shouldBypassRedis()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttlMinutes, TimeUnit.MINUTES);
            log.debug("Redis cache write success: key={}, ttlMinutes={}", cacheKey, ttlMinutes);
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Redis cache write failed for key={}, ttlMinutes={}.", cacheKey, ttlMinutes, e);
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
        if (shouldBypassRedis()) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Redis cache evicted by prefix: prefix={}, count={}", prefix, keys.size());
            }
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Redis cache eviction by prefix failed: prefix={}", prefix, e);
        }
    }

    /**
     * 删除单个缓存键，常用于缓存反序列化失败后的修复。
     */
    private void evictExact(String cacheKey) {
        if (shouldBypassRedis()) {
            return;
        }
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Redis cache evicted: key={}", cacheKey);
        } catch (Exception e) {
            markRedisUnavailable();
            log.warn("Redis cache eviction failed: key={}", cacheKey, e);
        }
    }

    private boolean shouldBypassRedis() {
        long bypassUntil = redisBypassUntilEpochMillis.get();
        return bypassUntil > System.currentTimeMillis();
    }

    private void markRedisUnavailable() {
        long bypassUntil = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1L, redisFailureCooldownSeconds));
        redisBypassUntilEpochMillis.set(bypassUntil);
    }
}
