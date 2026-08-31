package com.homektv.web;

import com.homektv.library.AssetWriter;
import com.homektv.library.SettingService;
import com.homektv.library.StandbyContentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;

/**
 * 待机画面控制器，处理待机内容展示、Logo 上传与图片访问。
 *
 * Standby controller for standby content display, logo upload, and image serving.
 */
@RestController
@RequestMapping("/api")
public class StandbyController {
    private final StandbyContentService service;
    private final SettingService settingService;
    private final AssetWriter assetWriter;

    public StandbyController(StandbyContentService service, SettingService settingService, AssetWriter assetWriter) {
        this.service = service;
        this.settingService = settingService;
        this.assetWriter = assetWriter;
    }

    /**
     * 获取待机画面内容。
     *
     * Get standby content.
     * @return 包含待机画面信息的 Map / map containing standby content info
     */
    @GetMapping("/standby/content")
    public Map<String, Object> content() { return service.content(); }

    /**
     * 上传待机画面 Logo 图片。
     *
     * Upload standby logo image.
     * @param file 上传的图片文件 / uploaded image file
     * @return 更新后的待机画面内容 / updated standby content
     */
    @PostMapping(value = "/admin/standby/logo", consumes = "multipart/form-data")
    public Map<String, Object> uploadLogo(@RequestPart("file") MultipartFile file) {
        service.uploadLogo(file);
        return service.content();
    }

    /**
     * 获取待机画面 Logo 图片。
     *
     * Serve standby logo image.
     * @return Logo 图片资源或 404 / logo image resource or 404
     */
    @GetMapping("/standby/logo")
    public ResponseEntity<Resource> logo() {
        Object value = settingService.getAll().get("standby_logo_path");
        if (value == null) return ResponseEntity.notFound().build();
        return assetWriter.readableStandbyLogo(value.toString())
                .map(file -> {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG :
                            name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") :
                            (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? MediaType.IMAGE_JPEG : null;
                    if (type == null) return ResponseEntity.notFound().<Resource>build();
                    Resource resource = new FileSystemResource(file);
                    return ResponseEntity.ok().contentType(type).body(resource);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
