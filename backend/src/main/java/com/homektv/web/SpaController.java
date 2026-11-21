package com.homektv.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 手机 H5 单页应用（Vue Router history 模式，base=/m/）托管。
 * Hosts the mobile H5 single-page application (Vue Router history mode, base=/m/).
 *
 * 二维码内容为 http://<NAS_IP>:8080/m?room=default（无尾斜杠），
 * The QR code URL is http://<NAS_IP>:8080/m?room=default (no trailing slash),
 * 且 history 模式下深链接（/m/home、/m/queue 等）刷新会命中服务端，
 * and under history mode, deep links (/m/home, /m/queue, etc.) hit the server on refresh,
 * 需统一转发到 /m/index.html 由前端路由接管。
 * so they must be uniformly forwarded to /m/index.html for the frontend router to handle.
 *
 * 静态资源 /m/assets/** 由 Spring 默认静态资源处理器（classpath:/static/）优先命中，
 * Static resources /m/assets/** are served by Spring's default static resource handler
 * (classpath:/static/) first,
 * 不会走到这里；这里只兜底 SPA 页面路径。
 * so they never reach this controller; this controller only handles SPA page paths as a fallback.
 */
@Controller
public class SpaController {

    /**
     * 服务根路径直接进入大屏点歌主界面。
     * Root path redirects to the KTV kiosk home.
     * @return redirect path to /m/
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/m/";
    }

    /**
     * 二维码入口 /m（无尾斜杠）。
     * QR code entry point /m (no trailing slash).
     * @return forward path to /m/index.html
     */
    @GetMapping("/m")
    public String entry() {
        return "forward:/m/index.html";
    }

    /**
     * SPA 深链接兜底。末段用 {p:[^\\.]+} 约束「不含点号」，天然排除静态资源
     * SPA deep-link fallback. The trailing segment uses {p:[^\\.]+} to constrain "no dots",
     * （/m/assets/index-xxx.js、*.css、*.png 等末段都带扩展名的请求），
     * naturally excluding requests whose trailing segments carry extensions
     * (/m/assets/index-xxx.js, *.css, *.png, etc.),
     * 交给 Spring 默认静态处理器命中真实文件。
     * letting Spring's default static handler serve the actual files.
     *   /m/          → 首页 (home)
     *   /m/home      → 单段路由 (single-segment route)
     *   /m/artist/周杰伦、/m/admin/songs → 两段路由 (two-segment route)
     *   /m/admin/ktv-library/metadata-scrape → 三段路由 (three-segment route)
     * @return forward path to /m/index.html
     */
    @GetMapping({"/m/", "/m/{p:[^\\.]+}", "/m/{p:[^\\.]+}/{s:[^\\.]+}",
            "/m/{p:[^\\.]+}/{s:[^\\.]+}/{t:[^\\.]+}"})
    public String forward() {
        return "forward:/m/index.html";
    }
}
