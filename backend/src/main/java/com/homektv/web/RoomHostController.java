package com.homektv.web;

import com.homektv.queue.RoomHostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;

import java.util.Map;

/**
 * 房间主持人控制器，提供房间主持人身份的状态查询、认领和释放接口。
 *
 * Room host controller that provides endpoints for querying, claiming, and
 * releasing the room host role.
 */
@RestController
@RequestMapping("/api/room/host")
public class RoomHostController {
    private final RoomHostService service;
    private final WsBroadcaster broadcaster;

    /**
     * 唯一的构造函数，必须标注 {@code @Autowired}。
     *
     * <p>本类曾经额外提供一个单参便利构造函数，导致出现两个都未标注 {@code @Autowired}
     * 的候选构造函数。Spring 无法在它们之间做出选择、又找不到无参构造函数，
     * 在刷新上下文时抛 {@code BeanInstantiationException: No default constructor found}，
     * 使整个服务端无法启动。该便利构造函数没有任何生产用途（仅测试便利），已删除。
     *
     * @param service 房间主持人服务
     * @param broadcaster WebSocket 广播器
     */
    @Autowired
    public RoomHostController(RoomHostService service, WsBroadcaster broadcaster) {
        this.service = service;
        this.broadcaster = broadcaster;
    }

    /**
     * 查询房间主持人状态。
     *
     * Query the room host status.
     * @param clientToken 客户端令牌，可选
     * @return 包含主持人状态信息的 Map
     */
    @GetMapping
    public Map<String, Object> status(@RequestParam(required = false) String clientToken) {
        return service.status(clientToken);
    }

    /**
     * 认领房间主持人身份。
     *
     * Claim the room host role.
     * @param request 包含 clientToken 的请求体
     * @return 操作结果的 Map
     */
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody HostRequest request) {
        Map<String, Object> result = service.claim(request.clientToken());
        broadcastHostChange(result);
        return result;
    }

    /**
     * 释放房间主持人身份。
     *
     * Release the room host role.
     * @param request 包含 clientToken 的请求体
     * @return 操作结果的 Map
     */
    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody HostRequest request) {
        Map<String, Object> result = service.release(request.clientToken());
        broadcastHostChange(result);
        return result;
    }

    private void broadcastHostChange(Map<String, Object> status) {
        if (broadcaster != null && status != null) {
            Map<String, Object> broadcastPayload = new java.util.LinkedHashMap<>(status);
            broadcastPayload.remove("isHost");
            broadcaster.broadcast(WsEvent.of(WsEvent.ROOM_HOST_CHANGED, broadcastPayload));
        }
    }

    /**
     * 主持人请求体，封装客户端令牌。
     *
     * Host request body that wraps the client token.
     */
    public record HostRequest(String clientToken) {}
}
