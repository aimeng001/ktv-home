package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.domain.Wish;
import com.homektv.queue.UserService;
import com.homektv.repo.WishRepository;
import com.homektv.security.AdminAuthInterceptor;
import com.homektv.security.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WishControllerSecurityTest {

    @Test
    void wishListUsesTheAdminRouteAndRequiresAuthentication() throws Exception {
        WishRepository wishes = (WishRepository) Proxy.newProxyInstance(
                WishRepository.class.getClassLoader(),
                new Class<?>[]{WishRepository.class},
                (proxy, method, args) -> List.of(new Wish()));

        UserService userService = new UserService(null);
        WishController controller = new WishController(wishes, userService);
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

        mvc.perform(delete("/api/admin/wishes/1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/wishes/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteWish_deletesSuccessfully() {
        AtomicBoolean deleted = new AtomicBoolean();
        WishRepository wishes = (WishRepository) Proxy.newProxyInstance(
                WishRepository.class.getClassLoader(),
                new Class<?>[]{WishRepository.class},
                (proxy, method, args) -> {
                    if ("existsById".equals(method.getName())) return true;
                    if ("deleteById".equals(method.getName())) {
                        deleted.set(true);
                        return null;
                    }
                    return null;
                });

        WishController controller = new WishController(wishes, null);
        Map<String, Object> res = controller.delete(1L);

        assertThat(deleted).isTrue();
        assertThat(res).containsEntry("status", "ok");
    }

    @Test
    void deleteWish_notFoundThrowsApiException() {
        WishRepository wishes = (WishRepository) Proxy.newProxyInstance(
                WishRepository.class.getClassLoader(),
                new Class<?>[]{WishRepository.class},
                (proxy, method, args) -> "existsById".equals(method.getName()) ? false : null);

        WishController controller = new WishController(wishes, null);

        assertThatThrownBy(() -> controller.delete(999L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("心愿记录不存在");
    }

    @Test
    void exportCsv_returnsUtf8CsvWithHeaders() {
        Wish w = new Wish();
        w.setId(5L);
        w.setKeyword("晴天, 杰伦");
        w.setCreatedBy(123L);
        w.setCreatedAt(OffsetDateTime.of(2026, 9, 2, 12, 0, 0, 0, ZoneOffset.UTC));

        WishRepository wishes = (WishRepository) Proxy.newProxyInstance(
                WishRepository.class.getClassLoader(),
                new Class<?>[]{WishRepository.class},
                (proxy, method, args) -> "findAllByOrderByCreatedAtDesc".equals(method.getName()) ? List.of(w) : null);

        WishController controller = new WishController(wishes, null);
        ResponseEntity<byte[]> response = controller.exportCsv();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("ktv-wishes.csv");
        String content = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("ID,关键词,创建者ID,创建时间");
        assertThat(content).contains("5,\"晴天, 杰伦\",123,2026-09-02T12:00Z");
    }

    @Test
    void rejectsKeywordExceedingMaxLength() {
        WishRepository wishes = (WishRepository) Proxy.newProxyInstance(
                WishRepository.class.getClassLoader(),
                new Class<?>[]{WishRepository.class},
                (proxy, method, args) -> null);
        UserService userService = new UserService(null);
        WishController controller = new WishController(wishes, userService);

        Map<String, String> body = Map.of(
                "keyword", "A".repeat(101),
                "client_token", "user-token"
        );

        assertThatThrownBy(() -> controller.add(body))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能超过 100 个字符");
    }
}
