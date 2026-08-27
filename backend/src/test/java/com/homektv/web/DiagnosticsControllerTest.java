package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.diagnostics.MountInfoParser;
import com.homektv.diagnostics.NasMountInspector;
import com.homektv.diagnostics.DiagnosticBundleService;
import com.homektv.security.AdminAuthInterceptor;
import com.homektv.security.AdminAuthService;
import com.homektv.ws.ActivePlayerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagnosticsControllerTest {

    @Test
    void diagnosticsRequiresExistingAdminAuthenticationAndReturnsOnlyAllowlistedFields() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setAdminPassword("admin-secret");
        properties.setLibraryMode(com.homektv.library.LibraryMode.EXTERNAL_READ_ONLY);
        properties.getRelease().setVersion("1.0.0");
        AdminAuthService auth = new AdminAuthService(properties);
        String token = auth.login("admin-secret");
        NasMountInspector mount = mock(NasMountInspector.class);
        when(mount.inspectConfiguredLibrary()).thenReturn(
                new MountInfoParser.Inspection(MountInfoParser.Status.READ_ONLY, "/source-music", "cifs"));
        ActivePlayerRegistry players = new ActivePlayerRegistry();
        DiagnosticBundleService bundle = mock(DiagnosticBundleService.class);
        when(bundle.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new byte[]{0x50, 0x4b});
        DiagnosticsController controller = new DiagnosticsController(properties, mount, players, bundle);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AdminAuthInterceptor(auth))
                .build();

        mvc.perform(get("/api/admin/diagnostics"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/diagnostics").header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.libraryMode").value("EXTERNAL_READ_ONLY"))
                .andExpect(jsonPath("$.nasMountStatus").value("READ_ONLY"))
                .andExpect(jsonPath("$.connectedPlayers").value(0))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/source-music"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("cifs"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("admin-secret"))));

        mvc.perform(post("/api/admin/diagnostics/bundle"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/diagnostics/bundle").header("X-Admin-Token", token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(content().bytes(new byte[]{0x50, 0x4b}));
    }
}
