package com.homektv.web;
import com.homektv.common.ApiException;

import com.homektv.config.AppProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/release")
public class ReleaseController {
    private static final String ARM32 = "armeabi-v7a";
    private static final String ARM64 = "arm64-v8a";

    private final AppProperties properties;
    private final ResourceLoader resources;

    public ReleaseController(AppProperties properties, ResourceLoader resources) {
        this.properties = properties;
        this.resources = resources;
    }

    @GetMapping
    public ReleaseInfo info() {
        AppProperties.Release release = properties.getRelease();
        AppProperties.Announcement announcement = release.getAnnouncement();
        String noticeId = announcement.getId() == null || announcement.getId().isBlank()
                ? release.getVersion() : announcement.getId().trim();
        return new ReleaseInfo(
                release.getVersion(), release.getVersionCode(),
                new AnnouncementInfo(announcement.isEnabled(), noticeId,
                        announcement.getTitle(), announcement.getMessage()),
                new TvPackages(packageInfo(ARM32), packageInfo(ARM64)));
    }

    @GetMapping("/tv/apk/{abi}")
    public ResponseEntity<Resource> downloadTvApk(@PathVariable String abi) {
        PackageInfo info = packageInfo(abi);
        if (!info.available()) throw new ApiException("TV_APK_NOT_AVAILABLE", "当前镜像未包含该架构的 Android TV 安装包");
        Resource resource = resources.getResource(pathFor(abi));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(info.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .contentLength(contentLength(resource))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private PackageInfo packageInfo(String abi) {
        String path = pathFor(abi);
        Resource resource = resources.getResource(path);
        boolean available = resource.exists() && resource.isReadable();
        String fileName = "home-ktv-tv-" + properties.getRelease().getVersion() + "-" + abi + ".apk";
        return new PackageInfo(abi, available, "/api/release/tv/apk/" + abi, fileName,
                available ? contentLength(resource) : 0);
    }

    private String pathFor(String abi) {
        return switch (abi) {
            case ARM32 -> properties.getRelease().getArmeabiV7aApk();
            case ARM64 -> properties.getRelease().getArm64V8aApk();
            default -> throw new ApiException("TV_ABI_NOT_SUPPORTED", "不支持的 Android TV 架构：" + abi);
        };
    }

    private long contentLength(Resource resource) {
        try {
            return resource.contentLength();
        } catch (Exception exception) {
            throw new ApiException("TV_APK_READ_FAILED", "无法读取 Android TV 安装包");
        }
    }

    public record ReleaseInfo(String version, long versionCode, AnnouncementInfo announcement, TvPackages tv) {}
    public record AnnouncementInfo(boolean enabled, String id, String title, String message) {}
    public record TvPackages(PackageInfo armeabiV7a, PackageInfo arm64V8a) {}
    public record PackageInfo(String abi, boolean available, String url, String fileName, long size) {}
}
