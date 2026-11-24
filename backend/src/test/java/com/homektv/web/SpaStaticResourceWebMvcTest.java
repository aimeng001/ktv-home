package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SpaController.class)
@Import({MediaStreamExecutorConfiguration.class, TranscodeAsyncWebConfig.class})
class SpaStaticResourceWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void spaIndexHtmlIsServedWithNoCacheHeader() throws Exception {
        mvc.perform(get("/m/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
                .andExpect(content().string(containsString("<div id=\"app\"></div>")));
    }

    @Test
    void spaEntryAndAdminRoutesForwardToIndexHtml() throws Exception {
        mvc.perform(get("/m"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/m/index.html"));

        mvc.perform(get("/m/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/m/index.html"));

        mvc.perform(get("/m/admin"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/m/index.html"));

        mvc.perform(get("/m/admin/ktv-library/metadata-scrape"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/m/index.html"));
    }

    @Test
    void missingHtmlUnderMReturnsNotFound() throws Exception {
        mvc.perform(get("/m/not-exist.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hashedAssetsUnderMAssetsAreServedImmutably() throws Exception {
        Resource[] assets = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/static/m/assets/*.js");
        if (assets.length == 0) {
            return;
        }
        String assetName = assets[0].getFilename();
        mvc.perform(get("/m/assets/" + assetName))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));
    }
}
