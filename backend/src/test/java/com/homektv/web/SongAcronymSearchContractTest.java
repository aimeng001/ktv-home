package com.homektv.web;

import com.homektv.library.AssetWriter;
import com.homektv.library.SongSearchService;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import com.homektv.repo.SongSearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SongAcronymSearchContractTest {

    @Test
    void acronymQueryUsesTheExistingPinyinSearchPath() throws Exception {
        AtomicReference<String> queriedKeyword = new AtomicReference<>();
        SongSearchRepository repository = (SongSearchRepository) Proxy.newProxyInstance(
                SongSearchRepository.class.getClassLoader(),
                new Class<?>[]{SongSearchRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("search") && args != null && args.length == 5) {
                        queriedKeyword.set((String) args[0]);
                        return List.of();
                    }
                    if (method.getName().equals("searchShort")) return List.of();
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
        SongController controller = new SongController(
                new SongSearchService(repository),
                (SongRepository) null,
                (SongFileRepository) null,
                (AssetWriter) null
        );
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/songs").param("acronym", "HKTK"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        assertThat(queriedKeyword.get()).isEqualTo("HKTK");
    }
}
