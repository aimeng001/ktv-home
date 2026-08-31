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
    private final ArtistAvatarJobService avatarJobs;
    private final ArtistCreditReconciliationService creditReconciliation;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean creditsReconciled = new AtomicBoolean(false);

    public ArtistProfileBootstrap(ArtistProfileService profiles, ArtistAvatarJobService avatarJobs,
                                  ArtistCreditReconciliationService creditReconciliation) {
        this.profiles = profiles;
        this.avatarJobs = avatarJobs;
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

    /** Covers manual metadata edits that do not start a library scan. */
    @Scheduled(initialDelay = 20_000L, fixedDelay = 60_000L)
    public void scheduledRefresh() {
        refreshNow();
    }

    private void refreshNow() {
        if (!refreshRunning.compareAndSet(false, true)) return;
        try {
            reconcileCreditsOnce();
            int count = profiles.backfillFromSongs();
            avatarJobs.enqueuePendingProfiles();
            log.info("artist profile backfill completed: {} source names", count);
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
