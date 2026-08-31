package com.homektv.musicsource;

import java.time.Duration;
import java.util.List;

/** Provider boundary for artist identities and artist-specific artwork. */
public interface ArtistMetadataProvider {
    MusicProvider provider();

    List<ExternalArtist> search(String artistName, int limit, Duration timeout);
}
