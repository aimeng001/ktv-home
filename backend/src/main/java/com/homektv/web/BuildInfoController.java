package com.homektv.web;

import com.homektv.system.BuildInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, secret-free identity endpoint used by deployment smoke tests. */
@RestController
@RequestMapping("/api/build-info")
public class BuildInfoController {
    private final BuildInfoService service;

    public BuildInfoController(BuildInfoService service) {
        this.service = service;
    }

    @GetMapping
    public BuildInfoService.BuildInfo info() {
        return service.get();
    }
}
