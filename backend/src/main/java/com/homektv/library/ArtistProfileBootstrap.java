package com.homektv.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs the one-time legacy profile backfill off the application startup path. */
@Component
public class ArtistProfileBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ArtistProfileBootstrap.class);
    private final ArtistProfileService profiles;
    private final LocalAvatarResolver localAvatarResolver;
    private final ArtistCreditReconciliationService creditReconciliation;
    private final ArtistAvatarJobService avatarJobs;
    private final Executor executor;
    private final ArtistDirectoryProjectionService directoryProjection;
    private final ArtistGenderDictionaryService genderDictionary;
    private final ArtistGenderMatcher genderMatcher;
    private final AtomicBoolean refreshRequested = new AtomicBoolean(false);
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean creditsReconciled = new AtomicBoolean(false);

    public ArtistProfileBootstrap(ArtistProfileService profiles, LocalAvatarResolver localAvatarResolver,
                                  ArtistCreditReconciliationService creditReconciliation,
                                  ArtistAvatarJobService avatarJobs,
                                  @org.springframework.beans.factory.annotation.Qualifier("artistProfileExecutor")
                                  Executor executor) {
        this(profiles, localAvatarResolver, creditReconciliation, avatarJobs, executor, null, null, null);
    }

    public ArtistProfileBootstrap(ArtistProfileService profiles, LocalAvatarResolver localAvatarResolver,
                                  ArtistCreditReconciliationService creditReconciliation,
                                  ArtistAvatarJobService avatarJobs,
                                  @org.springframework.beans.factory.annotation.Qualifier("artistProfileExecutor")
                                  Executor executor,
                                  ArtistDirectoryProjectionService directoryProjection) {
        this(profiles, localAvatarResolver, creditReconciliation, avatarJobs, executor,
                directoryProjection, null, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ArtistProfileBootstrap(ArtistProfileService profiles, LocalAvatarResolver localAvatarResolver,
                                  ArtistCreditReconciliationService creditReconciliation,
                                  ArtistAvatarJobService avatarJobs,
                                  @org.springframework.beans.factory.annotation.Qualifier("artistProfileExecutor")
                                  Executor executor,
                                  ArtistDirectoryProjectionService directoryProjection,
                                  ArtistGenderDictionaryService genderDictionary,
                                  ArtistGenderMatcher genderMatcher) {
        this.profiles = profiles;
        this.localAvatarResolver = localAvatarResolver;
        this.creditReconciliation = creditReconciliation;
        this.avatarJobs = avatarJobs;
        this.executor = executor;
        this.directoryProjection = directoryProjection;
        this.genderDictionary = genderDictionary;
        this.genderMatcher = genderMatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        requestRefresh();
    }

    /** Reconciles profiles after a scan without delaying the scan response. */
    public void refreshAfterScan() {
        requestRefresh();
    }

    private void requestRefresh() {
        refreshRequested.set(true);
        submitIfPossible();
    }

    private void submitIfPossible() {
        if (!refreshRequested.get() || !refreshRunning.compareAndSet(false, true)) return;
        try {
            AtomicBoolean completed = new AtomicBoolean(true);
            executor.execute(() -> {
                try {
                    if (refreshRequested.getAndSet(false)) completed.set(refreshNow());
                } finally {
                    refreshRunning.set(false);
                    if (completed.get() && refreshRequested.get()) submitIfPossible();
                }
            });
        } catch (RejectedExecutionException failure) {
            refreshRunning.set(false);
            log.debug("artist profile refresh queued for retry: {}", failure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.artist-profile.refresh-retry-ms:60000}")
    void retryRejectedRefresh() {
        submitIfPossible();
    }

    private boolean refreshNow() {
        boolean creditsCompleted = reconcileCreditsOnce();
        try {
            int count = profiles.backfillFromSongs();
            int classified = 0;
            if (genderDictionary != null && genderMatcher != null) {
                genderDictionary.seedBuiltin();
                classified = genderMatcher.applyDictionary();
            }
            int matched = localAvatarResolver.resolveAllCandidates();
            avatarJobs.enqueuePendingProfilesAfterCommit();
            if (directoryProjection != null) directoryProjection.refresh();
            log.info("artist profile backfill completed: {} source names, {} database classifications, {} local avatars resolved",
                    count, classified, matched);
            if (!creditsCompleted) refreshRequested.set(true);
            return creditsCompleted;
        } catch (RuntimeException failure) {
            refreshRequested.set(true);
            log.warn("artist profile backfill did not complete: {}", failure.getMessage());
            return false;
        }
    }

    private boolean reconcileCreditsOnce() {
        if (creditsReconciled.get()) return true;
        try {
            int processed = creditReconciliation.reconcileValidSongs();
            creditsReconciled.set(true);
            log.info("artist credit reconciliation completed: {} valid songs", processed);
            return true;
        } catch (RuntimeException failure) {
            // Keep the flag false so the next post-scan/scheduled refresh can
            // retry after a transient database or migration failure.
            log.warn("artist credit reconciliation did not complete: {}", failure.getMessage());
            return false;
        }
    }
}
