package com.homektv.web;

import com.homektv.library.LibraryStatusService;
import com.homektv.repo.SongRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Public, non-administrative library summary used by the TV standby screen. */
@RestController
@RequestMapping("/api/library")
public class LibraryStatusController {
    private final SongRepository songs;
    private LibraryStatusService statusService;

    public LibraryStatusController(SongRepository songs) {
        this.songs = songs;
    }

    @Autowired(required = false)
    void setStatusService(LibraryStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        if (statusService == null) return Map.of("totalSongs", songs.count());
        LibraryStatusService.PublicStatus value = statusService.status();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalSongs", value.totalSongs());
        response.put("libraryMode", value.libraryMode());
        response.put("rootState", value.rootState());
        response.put("scanState", value.scanState());
        response.put("phase", value.phase());
        response.put("discoveredFiles", value.discoveredFiles());
        response.put("indexedFiles", value.indexedFiles());
        response.put("indexedSongs", value.indexedSongs());
        response.put("readySongs", value.readySongs());
        response.put("probePendingFiles", value.probePendingFiles());
        response.put("catalogRevision", value.catalogRevision());
        response.put("statusRevision", value.statusRevision());
        response.put("errorCode", value.errorCode());
        response.put("updatedAt", value.updatedAt());
        return response;
    }
}
