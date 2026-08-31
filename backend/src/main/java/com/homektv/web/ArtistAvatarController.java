package com.homektv.web;

import com.homektv.library.AssetWriter;
import com.homektv.library.ArtistProfileService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Serves only application-owned cached artist avatars. */
@RestController
@RequestMapping("/api/artists")
public class ArtistAvatarController {
    private final ArtistProfileService profiles;
    private final AssetWriter assets;

    public ArtistAvatarController(ArtistProfileService profiles, AssetWriter assets) {
        this.profiles = profiles;
        this.assets = assets;
    }

    @GetMapping("/avatar")
    public ResponseEntity<Resource> avatar(@RequestParam String key) {
        ArtistProfileService.Profile profile = profiles.find(key).orElse(null);
        if (profile == null || profile.avatarPath() == null || profile.avatarPath().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path file = assets.readableCachePath(profile.avatarPath()).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = mediaType(file);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(file));
    }

    private static MediaType mediaType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            if (detected != null) return MediaType.parseMediaType(detected);
        } catch (Exception ignored) {}
        return MediaType.IMAGE_JPEG;
    }
}
