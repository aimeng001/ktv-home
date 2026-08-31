package com.homektv.musicsource;

import java.util.List;

/** Untrusted, provider-sourced artist metadata kept separate from track metadata. */
public record ExternalArtist(
        MusicProvider provider,
        String externalId,
        String displayName,
        List<String> aliases,
        String avatarUrl
) {}
