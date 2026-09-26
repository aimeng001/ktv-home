package com.homektv.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.ControlRequest;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Isolated("Testcontainers JUnit extension does not support parallel test execution")
class PartyLoadIntegrationTest {

    private static final int CLIENT_COUNT = 10;
    private static final Logger log = LoggerFactory.getLogger(PartyLoadIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SongRepository songRepo;
    @Autowired com.homektv.repo.SongFileRepository songFileRepo;
    @Autowired ObjectMapper mapper;

    @Test
    void tenPhonesCanOrderAndUseRemoteControlsConcurrently() throws Exception {
        List<Long> songIds = saveSongs();
        List<ClientConnection> clients = connectPhones();
        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_COUNT);

        try {
            assertThat(clients).allSatisfy(client ->
                    assertThat(awaitEvent(client.events(), WsEvent.SYNC_FULL)).isNotNull());
            assertThat(clients).hasSize(CLIENT_COUNT).allSatisfy(client ->
                    assertThat(client.session().isOpen()).isTrue());

            long orderStarted = System.nanoTime();
            List<CompletableFuture<ResponseEntity<QueueSnapshot>>> orders = new ArrayList<>();
            for (int index = 0; index < CLIENT_COUNT; index++) {
                int clientIndex = index;
                orders.add(CompletableFuture.supplyAsync(() -> postControl(
                        "order", Map.of("song_id", songIds.get(clientIndex)), token(clientIndex)), executor));
            }
            CompletableFuture.allOf(orders.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            Duration orderDuration = Duration.ofNanos(System.nanoTime() - orderStarted);

            assertThat(orders).allSatisfy(order -> assertThat(order.join().getStatusCode().is2xxSuccessful()).isTrue());
            QueueSnapshot queue = rest.getForObject("/api/queue", QueueSnapshot.class);
            assertThat(queue).isNotNull();
            assertThat(queue.playing()).isNotNull();
            assertThat(queue.list()).hasSize(CLIENT_COUNT - 1);
            assertThat(orderDuration).isLessThan(Duration.ofSeconds(5));
            log.info("10-client concurrent ordering completed in {} ms", orderDuration.toMillis());

            for (ClientConnection client : clients) {
                assertThat(awaitEvent(client.events(), WsEvent.QUEUE_UPDATED)).isNotNull();
            }

            long remoteStarted = System.nanoTime();
            List<CompletableFuture<ResponseEntity<QueueSnapshot>>> controls = new ArrayList<>();
            for (int index = 0; index < CLIENT_COUNT; index++) {
                int clientIndex = index;
                controls.add(CompletableFuture.supplyAsync(() -> postControl(
                        "effect", Map.of("effect_id", "cheers"), token(clientIndex)), executor));
            }
            CompletableFuture.allOf(controls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            Duration remoteDuration = Duration.ofNanos(System.nanoTime() - remoteStarted);

            assertThat(controls).allSatisfy(control -> assertThat(control.join().getStatusCode().is2xxSuccessful()).isTrue());
            assertThat(remoteDuration).isLessThan(Duration.ofSeconds(5));
            log.info("10-client concurrent remote controls completed in {} ms", remoteDuration.toMillis());
            for (ClientConnection client : clients) {
                assertThat(awaitEvent(client.events(), WsEvent.EFFECT_PLAY)).isNotNull();
            }
        } finally {
            executor.shutdownNow();
            for (ClientConnection client : clients) {
                client.session().close();
            }
        }
    }

    private List<Long> saveSongs() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < CLIENT_COUNT; index++) {
            Song song = new Song();
            song.setTitle("聚会压测歌曲" + index);
            song.setArtist("测试歌手" + index);
            song.setMediaType("KTV_VIDEO");
            song.setFingerprint("party-load-" + System.nanoTime() + "-" + index);
            Long songId = songRepo.save(song).getId();
            com.homektv.domain.SongFile sf = new com.homektv.domain.SongFile();
            sf.setSongId(songId);
            sf.setFilePath("/music/party-load-" + System.nanoTime() + "-" + index + ".mp4");
            sf.setFormat("mp4");
            sf.setMediaType("KTV_VIDEO");
            sf.setValid(true);
            sf.setProbePending(false);
            sf.setFileMtime(java.time.OffsetDateTime.now());
            songFileRepo.save(sf);
            ids.add(songId);
        }
        return ids;
    }

    private List<ClientConnection> connectPhones() throws Exception {
        List<ClientConnection> clients = new ArrayList<>();
        for (int index = 0; index < CLIENT_COUNT; index++) {
            BlockingQueue<String> events = new LinkedBlockingQueue<>();
            WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                    events.offer(message.getPayload());
                }
            }, new WebSocketHttpHeaders(), URI.create(
                    "ws://localhost:" + port + "/ws?client_type=h5&client_token=" + token(index)))
                    .get(5, TimeUnit.SECONDS);
            clients.add(new ClientConnection(session, events));
        }
        return clients;
    }

    private ResponseEntity<QueueSnapshot> postControl(String action, Map<String, Object> params, String token) {
        return rest.postForEntity("/api/control", new ControlRequest(action, params, token), QueueSnapshot.class);
    }

    private JsonNode awaitEvent(BlockingQueue<String> events, String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String payload = events.poll(500, TimeUnit.MILLISECONDS);
            if (payload == null) continue;
            JsonNode event = mapper.readTree(payload);
            if (type.equals(event.path("type").asText())) return event;
        }
        throw new AssertionError("未收到事件: " + type);
    }

    private String token(int index) {
        return "party-load-client-" + index;
    }

    private record ClientConnection(WebSocketSession session, BlockingQueue<String> events) {}
}
