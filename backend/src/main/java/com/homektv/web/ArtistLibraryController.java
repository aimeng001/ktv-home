package com.homektv.web;

import com.homektv.library.ArtistLibraryService;
import com.homektv.library.ArtistProfileService;
import com.homektv.library.AssetWriter;
import com.homektv.library.LocalAvatarResolver;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/artists")
public class ArtistLibraryController {
    private static final Set<String> ALLOWED_IMAGE_EXTS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private final ArtistLibraryService service;
    private final LocalAvatarResolver localAvatarResolver;
    private final AssetWriter assetWriter;
    private final ArtistProfileService profileService;

    public ArtistLibraryController(ArtistLibraryService service,
                                   LocalAvatarResolver localAvatarResolver,
                                   AssetWriter assetWriter,
                                   ArtistProfileService profileService) {
        this.service = service;
        this.localAvatarResolver = localAvatarResolver;
        this.assetWriter = assetWriter;
        this.profileService = profileService;
    }

    @PostMapping("/{artistKey}/avatar")
    public Map<String, Object> uploadAvatar(@PathVariable String artistKey,
                                           @RequestParam("file") MultipartFile file) throws IOException {
        if (artistKey == null || artistKey.isBlank()) {
            throw new ApiException("INVALID_ARTIST_KEY", "歌手 Key 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("INVALID_FILE", "上传头像文件不能为空");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new ApiException("FILE_TOO_LARGE", "头像图片不能超过 10MB");
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "avatar.jpg";
        int dot = originalName.lastIndexOf('.');
        String ext = dot > 0 ? originalName.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
        if (!ALLOWED_IMAGE_EXTS.contains(ext)) {
            throw new ApiException("INVALID_IMAGE_TYPE", "仅支持 JPG、PNG 或 WEBP 格式图片");
        }
        String cleanExt = ext.startsWith(".") ? ext.substring(1) : ext;
        String relPath = assetWriter.writeArtistCover(artistKey, file.getBytes(), cleanExt);
        profileService.setAvatarReady(artistKey, "CUSTOM_UPLOAD", relPath);
        return Map.of("success", true, "avatarUrl", "/api/artists/avatar?key=" + artistKey);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String gender,
                                          @RequestParam(required = false) Boolean reviewed,
                                          @RequestParam(defaultValue = "500") int limit) {
        return service.listForCompatibility(keyword, gender, reviewed, limit);
    }

    @GetMapping("/page")
    public ArtistLibraryService.ArtistPage page(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String gender,
                                                @RequestParam(required = false) Boolean reviewed,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return service.page(keyword, gender, reviewed, page, size);
    }

    @PostMapping("/local-avatars/scan")
    public Map<String, Object> scanLocalAvatars() {
        int matched = localAvatarResolver.resolveAllCandidates();
        return Map.of("success", true, "matched", matched);
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) { return service.analyze(request.artist()); }

    @PostMapping("/analyze-batch")
    public List<Map<String, Object>> analyzeBatch(@RequestBody BatchAnalyzeRequest request) {
        return service.analyzeBatch(request == null ? List.of() : request.artists());
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody ApplyRequest request) { return service.apply(request.artist(), request.gender()); }

    public record AnalyzeRequest(String artist) {}
    public record BatchAnalyzeRequest(List<String> artists) {}
    public record ApplyRequest(String artist, String gender) {}
}
