package com.homektv.web;

import com.homektv.playback.PlaybackResolutionService;
import com.homektv.web.dto.PlaybackDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** HTTP-level contract for the unified playback resolution endpoint. */
class PlaybackControllerContractTest {

    @Test
    void resolveReturnsTheDescriptorForTheRequestedFile() throws Exception {
        PlaybackResolutionService service = mock(PlaybackResolutionService.class);
        when(service.resolve(7L, false)).thenReturn(PlaybackDescriptor.idle());
        MockMvc mvc = standaloneSetup(new PlaybackController(service)).build();

        mvc.perform(get("/api/playback/resolve/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));

        verify(service).resolve(7L, false);
    }

    /**
     * 已安装的 Android TV 老版本仍会带 retryFailed 查询参数。
     * 该参数从未下传到服务层，删掉形参后必须继续返回 200，不能让老客户端 400。
     */
    @Test
    void legacyRetryFailedQueryParamIsStillAccepted() throws Exception {
        PlaybackResolutionService service = mock(PlaybackResolutionService.class);
        when(service.resolve(7L, false)).thenReturn(PlaybackDescriptor.idle());
        MockMvc mvc = standaloneSetup(new PlaybackController(service)).build();

        mvc.perform(get("/api/playback/resolve/7").param("retryFailed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"));

        verify(service).resolve(7L, false);
    }
}
