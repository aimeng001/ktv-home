package com.homektv.web;

import com.homektv.domain.Wish;
import com.homektv.queue.UserService;
import com.homektv.repo.WishRepository;
import com.homektv.config.AppProperties;
import com.homektv.security.AdminAuthInterceptor;
import com.homektv.security.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WishControllerSecurityTest {

    @Test
    void wishListUsesTheAdminRouteAndRequiresAuthentication() throws Exception {
        WishRepository wishes = mock(WishRepository.class);
        when(wishes.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(new Wish()));
        WishController controller = new WishController(wishes, mock(UserService.class));
        AppProperties properties = new AppProperties();
        properties.setAdminPassword("admin-secret");
        AdminAuthService auth = new AdminAuthService(properties);
        String token = auth.login("admin-secret");
        MockMvc mvc = standaloneSetup(controller)
                .addInterceptors(new AdminAuthInterceptor(auth))
                .build();

        mvc.perform(get("/api/admin/wishes"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/wishes").header("X-Admin-Token", token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/wishes"))
                .andExpect(status().isMethodNotAllowed());
    }
}
