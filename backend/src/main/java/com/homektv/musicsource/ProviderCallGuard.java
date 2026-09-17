package com.homektv.musicsource;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class ProviderCallGuard {
    private final Map<MusicProvider, Integer> activeCalls = new EnumMap<>(MusicProvider.class);
    private final Map<MusicProvider, Long> nextRequestAt = new EnumMap<>(MusicProvider.class);
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
        }
    }

    public <T> T call(MusicProvider provider, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquire(provider);
            acquired = true;
            throttle(provider, configSupplier.get().requestIntervalMs());
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

    private void throttle(MusicProvider provider, int intervalMs) throws InterruptedException {
        long wait;
        synchronized (nextRequestAt) {
            long now = System.currentTimeMillis();
            wait = Math.max(0, nextRequestAt.get(provider) - now);
            nextRequestAt.put(provider, Math.max(now, nextRequestAt.get(provider)) + intervalMs);
        }
        if (wait > 0) Thread.sleep(wait);
    }
}
