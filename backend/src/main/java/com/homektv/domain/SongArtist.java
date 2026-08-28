package com.homektv.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** One independently searchable artist credit belonging to a Song. */
@Entity
@Table(name = "song_artists",
        uniqueConstraints = @UniqueConstraint(name = "uk_song_artists_song_key",
                columnNames = {"song_id", "artist_key"}),
        indexes = {
                @Index(name = "idx_song_artists_song_order", columnList = "song_id, artist_order"),
                @Index(name = "idx_song_artists_key", columnList = "artist_key")
        })
public class SongArtist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "song_id", nullable = false)
    private Long songId;

    @Column(name = "artist_name", nullable = false)
    private String artistName;

    @Column(name = "artist_key", nullable = false)
    private String artistKey;

    @Column(name = "artist_py", nullable = false)
    private String artistPy = "";

    @Column(name = "artist_init", nullable = false)
    private String artistInit = "";

    @Column(name = "artist_order", nullable = false)
    private int artistOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSongId() { return songId; }
    public void setSongId(Long songId) { this.songId = songId; }
    public String getArtistName() { return artistName; }
    public void setArtistName(String artistName) { this.artistName = artistName; }
    public String getArtistKey() { return artistKey; }
    public void setArtistKey(String artistKey) { this.artistKey = artistKey; }
    public String getArtistPy() { return artistPy; }
    public void setArtistPy(String artistPy) { this.artistPy = artistPy == null ? "" : artistPy; }
    public String getArtistInit() { return artistInit; }
    public void setArtistInit(String artistInit) { this.artistInit = artistInit == null ? "" : artistInit; }
    public int getArtistOrder() { return artistOrder; }
    public void setArtistOrder(int artistOrder) { this.artistOrder = artistOrder; }
}
