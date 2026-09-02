package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the one-time legacy profile backfill off the application startup path. */
@Component
public class ArtistProfileBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ArtistProfileBootstrap.class);
    private final ArtistProfileService profiles;
    private final LocalAvatarResolver localAvatarResolver;
    private final ArtistCreditReconciliationService creditReconciliation;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean creditsReconciled = new AtomicBoolean(false);

    public ArtistProfileBootstrap(ArtistProfileService profiles, LocalAvatarResolver localAvatarResolver,
                                  ArtistCreditReconciliationService creditReconciliation) {
        this.profiles = profiles;
        this.localAvatarResolver = localAvatarResolver;
        this.creditReconciliation = creditReconciliation;
    }

    @Async("artistProfileExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        refreshNow();
    }

    /** Reconciles profiles after a scan without delaying the scan response. */
    @Async("artistProfileExecutor")
    public void refreshAfterScan() {
        refreshNow();
    }

    private void refreshNow() {
        if (!refreshRunning.compareAndSet(false, true)) return;
        try {
            reconcileCreditsOnce();
            int count = profiles.backfillFromSongs();
            int matched = localAvatarResolver.resolveAllCandidates();
            log.info("artist profile backfill completed: {} source names, {} local avatars resolved", count, matched);
        } catch (RuntimeException failure) {
            log.warn("artist profile backfill did not complete: {}", failure.getMessage());
        } finally {
            refreshRunning.set(false);
        }
    }

    private void reconcileCreditsOnce() {
        if (creditsReconciled.get()) return;
        try {
            int processed = creditReconciliation.reconcileValidSongs();
            creditsReconciled.set(true);
            log.info("artist credit reconciliation completed: {} valid songs", processed);
        } catch (RuntimeException failure) {
            // Keep the flag false so the next post-scan/scheduled refresh can
            // retry after a transient database or migration failure.
            log.warn("artist credit reconciliation did not complete: {}", failure.getMessage());
        }
    }
}
