package com.homektv.web;

import com.homektv.library.CategoryBrowseService;
import com.homektv.web.dto.SongDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分类浏览控制器，提供按艺术家、语言、标签等维度浏览歌曲的 REST API。
 *
 * Category browse controller, providing REST APIs to browse songs by artist, language, tag, and other dimensions.
 */
@RestController
@RequestMapping("/api/browse")
public class CategoryBrowseController {
    private final CategoryBrowseService service;

    public CategoryBrowseController(CategoryBrowseService service) {
        this.service = service;
    }

    @GetMapping("/artists")
    public List<Map<String, Object>> artists() { return service.artists(); }

    @GetMapping("/artists/page")
    public CategoryBrowseService.ArtistPage artistPage(
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String initial,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return service.artistsPage(gender, initial, page, size);
    }

    @GetMapping("/artists/initials")
    public List<String> artistInitials(@RequestParam(required = false) String gender) {
        return service.artistInitials(gender);
    }

    @GetMapping("/languages")
    public List<Map<String, Object>> languages() { return service.languages(); }

    @GetMapping("/tags")
    public List<Map<String, Object>> tags() { return service.tags(); }

    /**
     * 按艺术家、语言、标签和演唱形式等条件筛选歌曲列表。
     *
     * Retrieves a filtered list of songs by artist, language, tag, and vocal form.
     *
     * @param artist   艺术家名称（可选）。Artist name (optional).
     * @param language 语言（可选）。Language (optional).
     * @param tag      标签（可选）。Tag (optional).
     * @param vocalForm 演唱形式（可选）。Vocal form (optional).
     * @param sort     排序方式，默认 "hot"。Sort order, defaults to "hot".
     * @param limit    返回数量上限，默认 100。Maximum number of results, defaults to 100.
     * @return 符合条件的歌曲列表。List of matching songs.
     */
    @GetMapping("/songs")
    public List<SongDto> songs(@RequestParam(required = false) String artist,
                               @RequestParam(required = false) String artistGender,
                               @RequestParam(required = false) String language,
                               @RequestParam(required = false) String tag,
                               @RequestParam(required = false) String vocalForm,
                               @RequestParam(defaultValue = "hot") String sort,
                               @RequestParam(defaultValue = "100") int limit) {
        return service.songs(artist, artistGender, language, tag, vocalForm, sort, limit);
    }

    @GetMapping("/songs/page")
    public CategoryBrowseService.SongPage songPage(@RequestParam(required = false) String artist,
                                                   @RequestParam(required = false) String artistKey,
                                                   @RequestParam(required = false) String artistGender,
                                                   @RequestParam(required = false) String language,
                                                   @RequestParam(required = false) String tag,
                                                   @RequestParam(required = false) String vocalForm,
                                                   @RequestParam(defaultValue = "hot") String sort,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return service.songsPage(artist, artistKey, artistGender, language, tag, vocalForm, sort, page, size);
    }
}
