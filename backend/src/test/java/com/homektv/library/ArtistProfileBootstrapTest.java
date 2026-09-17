package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

class ArtistProfileBootstrapTest {

    @Test
    void startupBackfillRepairsCreditsBeforeBuildingProfilesAndResolvesLocalAvatars() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        when(credits.reconcileValidSongs()).thenReturn(42);
        when(localAvatarResolver.resolveAllCandidates()).thenReturn(5);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(
                profiles, localAvatarResolver, credits, avatarJobs, Runnable::run);
        bootstrap.backfill();
        bootstrap.refreshAfterScan();

        verify(credits, times(1)).reconcileValidSongs();
        verify(profiles, times(2)).backfillFromSongs();
        verify(localAvatarResolver, times(2)).resolveAllCandidates();
        verify(avatarJobs, times(2)).enqueuePendingProfilesAfterCommit();
    }

    @Test
    void failedCreditRepairIsRetriedByTheNextRefresh() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        when(credits.reconcileValidSongs())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(3);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(
                profiles, localAvatarResolver, credits, avatarJobs, Runnable::run);
        bootstrap.backfill();
        bootstrap.refreshAfterScan();

        verify(credits, times(2)).reconcileValidSongs();
    }

    @Test
    void rejectedRefreshRemainsRequestedUntilTheCompensationAttemptCanRun() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        AtomicBoolean reject = new AtomicBoolean(true);
        Executor executor = command -> {
            if (reject.getAndSet(false)) throw new RejectedExecutionException("queue full");
            command.run();
        };

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(
                profiles, localAvatarResolver, credits, avatarJobs, executor);
        bootstrap.backfill();
        bootstrap.retryRejectedRefresh();

        verify(profiles).backfillFromSongs();
        verify(localAvatarResolver).resolveAllCandidates();
        verify(avatarJobs).enqueuePendingProfilesAfterCommit();
    }

    @Test
    void transientRefreshFailureRemainsRequestedForScheduledCompensation() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        when(profiles.backfillFromSongs())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(1);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(
                profiles, localAvatarResolver, credits, avatarJobs, Runnable::run);
        bootstrap.backfill();
        bootstrap.retryRejectedRefresh();

        verify(profiles, times(2)).backfillFromSongs();
        verify(avatarJobs).enqueuePendingProfilesAfterCommit();
    }
}
