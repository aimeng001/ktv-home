package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistProfileBootstrapTest {

    @Test
    void startupBackfillRepairsCreditsBeforeBuildingProfilesAndDoesNotRepeatAfterSuccess() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        when(credits.reconcileValidSongs()).thenReturn(42);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(profiles, avatarJobs, credits);
        bootstrap.backfill();
        bootstrap.scheduledRefresh();

        verify(credits, times(1)).reconcileValidSongs();
        verify(profiles, times(2)).backfillFromSongs();
        verify(avatarJobs, times(2)).enqueuePendingProfiles();
    }

    @Test
    void failedCreditRepairIsRetriedByTheNextRefresh() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        ArtistAvatarJobService avatarJobs = mock(ArtistAvatarJobService.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        when(credits.reconcileValidSongs())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(3);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(profiles, avatarJobs, credits);
        bootstrap.backfill();
        bootstrap.scheduledRefresh();

        verify(credits, times(2)).reconcileValidSongs();
    }
}
