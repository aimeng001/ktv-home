package com.homektv.musicsource;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class ProviderCallGuard {
    private static final long CDN_MEDIA_INTERVAL_MS = 350L;
    private static final int CDN_MEDIA_MAX_CONCURRENCY = 2;
    private static final long FALLBACK_INTERVAL_MS = 1_200L;

    private final Map<MusicProvider, Integer> activeCalls = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Long> nextRequestAt = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Integer> activeMediaCalls = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Long> nextMediaAt = new EnumMap<>(MusicProvider.class);
    private final Object fallbackLock = new Object();
    private long nextFallbackAt = 0L;
    private final Supplier<MusicSourceConfig> configSupplier;
    private final ProviderRequestRateLimiter persistentLimiter;

    @Autowired
    public ProviderCallGuard(MusicSourceConfigService configService, ProviderRequestRateLimiter persistentLimiter) {
        this(configService::getConfig, persistentLimiter);
    }

    public ProviderCallGuard(MusicSourceConfigService configService) {
        this(configService::getConfig, null);
    }

    ProviderCallGuard() {
        this(MusicSourceConfig::defaults, null);
    }

    ProviderCallGuard(Supplier<MusicSourceConfig> configSupplier) {
        this(configSupplier, null);
    }

    ProviderCallGuard(Supplier<MusicSourceConfig> configSupplier, ProviderRequestRateLimiter persistentLimiter) {
        this.configSupplier = configSupplier;
        this.persistentLimiter = persistentLimiter;
        for (MusicProvider provider : MusicProvider.values()) {
            activeCalls.put(provider, 0);
            nextRequestAt.put(provider, 0L);
            activeMediaCalls.put(provider, 0);
            nextMediaAt.put(provider, 0L);
        }
    }

    public <T> T call(MusicProvider provider, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquire(provider);
            acquired = true;
            long configuredInterval = configSupplier.get().requestIntervalMs();
            long minPersistent = persistentLimiter != null ? persistentLimiter.minIntervalMs() : 0L;
            throttle(provider, Math.max(configuredInterval, minPersistent));
            if (persistentLimiter != null) persistentLimiter.beforeRequest(provider);
            T result;
            try {
                result = action.get();
            } catch (ProviderRateLimitedException | ProviderRateStateUnavailableException ex) {
                throw ex;
            } catch (ProviderHttpException ex) {
                if (persistentLimiter != null) {
                    persistentLimiter.recordFailure(provider, ex.statusCode(), ex.retryAfter());
                }
                throw ex;
            } catch (MusicSourceException ex) {
                if (persistentLimiter != null) persistentLimiter.recordFailure(provider, null, null);
                throw ex;
            }
            if (persistentLimiter != null) persistentLimiter.recordSuccess(provider);
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "请求已取消", ex);
        } finally {
            if (acquired) release(provider);
        }
    }

    /**
     * Lightweight static CDN image channel isolated from API search quota and
     * search interval so downloading artwork right after search never deadlocks.
     */
    public <T> T callMedia(MusicProvider provider, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquireMedia(provider);
            acquired = true;
            throttleMedia(provider, CDN_MEDIA_INTERVAL_MS);
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "图片下载请求已取消", ex);
        } finally {
            if (acquired) releaseMedia(provider);
        }
    }

    /** Serialized lightweight throttle for public fallback / web image sources. */
    public <T> T callFallback(Supplier<T> action) {
        synchronized (fallbackLock) {
            try {
                long now = System.currentTimeMillis();
                long wait = Math.max(0L, nextFallbackAt - now);
                if (wait > 0L) Thread.sleep(wait);
                nextFallbackAt = System.currentTimeMillis() + FALLBACK_INTERVAL_MS;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MusicSourceException(MusicProvider.QQ, "兜底刮削请求已取消", ex);
            }
            return action.get();
        }
    }

    private void acquireMedia(MusicProvider provider) throws InterruptedException {
        synchronized (activeMediaCalls) {
            while (activeMediaCalls.get(provider) >= CDN_MEDIA_MAX_CONCURRENCY) activeMediaCalls.wait();
            activeMediaCalls.put(provider, activeMediaCalls.get(provider) + 1);
        }
    }

    private void releaseMedia(MusicProvider provider) {
        synchronized (activeMediaCalls) {
            activeMediaCalls.put(provider, activeMediaCalls.get(provider) - 1);
            activeMediaCalls.notifyAll();
        }
    }

    private void throttleMedia(MusicProvider provider, long intervalMs) throws InterruptedException {
        long wait;
        synchronized (nextMediaAt) {
            long now = System.currentTimeMillis();
            wait = Math.max(0L, nextMediaAt.get(provider) - now);
            nextMediaAt.put(provider, Math.max(now, nextMediaAt.get(provider)) + intervalMs);
        }
        if (wait > 0L) Thread.sleep(wait);
    }

    private void acquire(MusicProvider provider) throws InterruptedException {
        synchronized (activeCalls) {
            // The persisted gate is shared by metadata and avatar traffic.
            // Keep one in-flight request per provider even if the legacy
            // metadata setting allows a larger local worker pool.
            while (activeCalls.get(provider) >= 1) activeCalls.wait();
            activeCalls.put(provider, activeCalls.get(provider) + 1);
        }
    }

    private void release(MusicProvider provider) {
        synchronized (activeCalls) {
            activeCalls.put(provider, activeCalls.get(provider) - 1);
            activeCalls.notifyAll();
        }
    }

    private void throttle(MusicProvider provider, long intervalMs) throws InterruptedException {
        long wait;
        synchronized (nextRequestAt) {
            long now = System.currentTimeMillis();
            wait = Math.max(0, nextRequestAt.get(provider) - now);
            nextRequestAt.put(provider, Math.max(now, nextRequestAt.get(provider)) + intervalMs);
        }
        if (wait > 0) Thread.sleep(wait);
    }
}
