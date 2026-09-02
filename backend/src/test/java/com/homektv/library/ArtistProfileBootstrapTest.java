package com.homektv.library;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistProfileBootstrapTest {

    @Test
    void startupBackfillRepairsCreditsBeforeBuildingProfilesAndResolvesLocalAvatars() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        when(credits.reconcileValidSongs()).thenReturn(42);
        when(localAvatarResolver.resolveAllCandidates()).thenReturn(5);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(profiles, localAvatarResolver, credits);
        bootstrap.backfill();
        bootstrap.refreshAfterScan();

        verify(credits, times(1)).reconcileValidSongs();
        verify(profiles, times(2)).backfillFromSongs();
        verify(localAvatarResolver, times(2)).resolveAllCandidates();
    }

    @Test
    void failedCreditRepairIsRetriedByTheNextRefresh() {
        ArtistProfileService profiles = mock(ArtistProfileService.class);
        LocalAvatarResolver localAvatarResolver = mock(LocalAvatarResolver.class);
        ArtistCreditReconciliationService credits = mock(ArtistCreditReconciliationService.class);
        when(credits.reconcileValidSongs())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(3);

        ArtistProfileBootstrap bootstrap = new ArtistProfileBootstrap(profiles, localAvatarResolver, credits);
        bootstrap.backfill();
        bootstrap.refreshAfterScan();

        verify(credits, times(2)).reconcileValidSongs();
    }
}
