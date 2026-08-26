package com.homektv.web;

import com.homektv.repo.SongRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public, non-administrative library summary used by the TV standby screen. */
@RestController
@RequestMapping("/api/library")
public class LibraryStatusController {
    private final SongRepository songs;

    public LibraryStatusController(SongRepository songs) {
        this.songs = songs;
    }

    @GetMapping("/status")
    public Map<String, Long> status() {
        return Map.of("totalSongs", songs.count());
    }
}
