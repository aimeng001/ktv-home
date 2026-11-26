package com.homektv.musicsource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class ProviderCallGuard {
    private static final long CDN_MEDIA_INTERVAL_MS = 350L;
    private static final int CDN_MEDIA_MAX_CONCURRENCY = 2;
    private static final long FALLBACK_INTERVAL_MS = 1_000L;

    private final Map<MusicProvider, Integer> activeCalls = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Long> nextRequestAt = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Integer> activeMediaCalls = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Long> nextMediaAt = new EnumMap<>(MusicProvider.class);
    private final Map<AvatarFallbackProvider, Long> nextFallbackAtByProvider = new EnumMap<>(AvatarFallbackProvider.class);
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
        for (AvatarFallbackProvider fallback : AvatarFallbackProvider.values()) {
            nextFallbackAtByProvider.put(fallback, 0L);
        }
    }

    public long nextAvailableAtMillis(MusicProvider provider) {
        long now = System.currentTimeMillis();
        boolean busy;
        synchronized (activeCalls) {
            busy = activeCalls.getOrDefault(provider, 0) > 0;
        }
        long next;
        synchronized (nextRequestAt) {
            next = Math.max(now, nextRequestAt.getOrDefault(provider, 0L));
        }
        return busy ? next + 2_500L : next;
    }

    public <T> T call(MusicProvider provider, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquire(provider);
            acquired = true;
            if (persistentLimiter != null) persistentLimiter.ensureNotBlocked(provider);
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

    /** Per-provider throttle for public fallback / web image sources without holding locks across HTTP I/O. */
    public <T> T callFallback(AvatarFallbackProvider provider, Supplier<T> action) {
        if (provider == null) {
            return callFallback(action);
        }
        long wait;
        synchronized (fallbackLock) {
            long now = System.currentTimeMillis();
            long next = nextFallbackAtByProvider.getOrDefault(provider, 0L);
            wait = Math.max(0L, next - now);
            nextFallbackAtByProvider.put(provider, Math.max(now, next) + FALLBACK_INTERVAL_MS);
        }
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MusicSourceException(MusicProvider.QQ, "兜底刮削请求已取消", ex);
            }
        }
        return action.get();
    }

    /** Legacy shared fallback image throttle used for direct fallback image downloads. */
    public <T> T callFallback(Supplier<T> action) {
        long wait;
        synchronized (fallbackLock) {
            long now = System.currentTimeMillis();
            wait = Math.max(0L, nextFallbackAt - now);
            nextFallbackAt = Math.max(now, nextFallbackAt) + FALLBACK_INTERVAL_MS;
        }
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MusicSourceException(MusicProvider.QQ, "兜底刮削请求已取消", ex);
            }
        }
        return action.get();
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
            // Conservative anti-ban policy: keep 1 in-flight API request per provider.
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
