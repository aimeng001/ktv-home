package com.homektv.repo;

import com.homektv.domain.SongArtist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SongArtistRepository extends JpaRepository<SongArtist, Long> {
    List<SongArtist> findBySongIdOrderByArtistOrder(Long songId);
    long deleteBySongId(Long songId);
}
