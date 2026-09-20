package com.homektv.web;

import com.homektv.playback.PlaybackVariantService;
import com.homektv.web.dto.PlaybackDescriptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Unified playback resolution endpoint for every client. */
@RestController
@RequestMapping("/api/playback")
public class PlaybackController {

    private final PlaybackVariantService variants;

    public PlaybackController(PlaybackVariantService variants) {
        this.variants = variants;
    }

    @GetMapping("/resolve/{fileId}")
    public PlaybackDescriptor resolve(@PathVariable Long fileId,
                                      @RequestParam(defaultValue = "false") boolean forceTranscode,
                                      @RequestParam(defaultValue = "false") boolean retryFailed) {
        return variants.resolve(fileId, forceTranscode, retryFailed);
    }
}
