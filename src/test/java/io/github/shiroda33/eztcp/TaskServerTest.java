package io.github.shiroda33.eztcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TaskServerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong clock = new AtomicLong();
    private TaskServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = new TaskServer(0, new FixedWindowRateLimiter(5, clock::get));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void probesABatchAndReturnsReachableAndRefusedResultsInInputOrder() throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
             SocketChannel notListening = SocketChannel.open()) {
            notListening.bind(new InetSocketAddress("127.0.0.1", 0));
            int closedPort = ((InetSocketAddress) notListening.getLocalAddress()).getPort();
            String body = """
                    {"targets":[
                      {"host":"127.0.0.1","port":%d},
                      {"host":"127.0.0.1","port":%d}
                    ]}
                    """.formatted(listening.getLocalPort(), closedPort);

            HttpResponse<String> submitted = post(body);
            assertEquals(202, submitted.statusCode());
            String id = json.readTree(submitted.body()).get("id").asText();
            String location = submitted.headers().firstValue("Location").orElseThrow();
            assertEquals("/tasks/" + id, location);

            JsonNode task = awaitCompleted(location);
            assertEquals(id, task.get("id").asText());
            assertEquals(2, task.get("total").asInt());
            assertEquals(2, task.get("completed").asInt());
            assertEquals(2, task.get("results").size());
            JsonNode reachable = task.get("results").get(0);
            assertEquals(listening.getLocalPort(), reachable.get("port").asInt());
            assertTrue(reachable.get("reachable").asBoolean());
            assertTrue(reachable.get("error").isNull());
            assertTrue(reachable.get("durationMs").asLong() >= 0);
            JsonNode refused = task.get("results").get(1);
            assertEquals(closedPort, refused.get("port").asInt());
            assertFalse(refused.get("reachable").asBoolean());
            assertFalse(refused.get("error").asText().isBlank());
        }
    }

    @Test
    void rateLimitsSubmissionsButAllowsResultQueriesAndResetsNextSecond() throws Exception {
        String body = """
                {"targets":[{"host":"127.0.0.1","port":%d}]}
                """.formatted(server.port());
        String location = null;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = post(body);
            assertEquals(202, response.statusCode());
            location = response.headers().firstValue("Location").orElseThrow();
        }
        HttpResponse<String> rejected = post(body);
        assertEquals(429, rejected.statusCode());
        assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
        assertEquals(200, get(location).statusCode());
        clock.set(1_000);
        assertEquals(202, post(body).statusCode());
    }

    @Test
    void rejectsMalformedJsonAndInvalidTargets() throws Exception {
        for (String body : new String[]{"{", "{}", "{\"targets\":[]}",
                "{\"targets\":[{\"host\":\"\",\"port\":80}]}",
                "{\"targets\":[{\"host\":\"localhost\",\"port\":65536}]}"}) {
            HttpResponse<String> response = post(body);
            assertEquals(400, response.statusCode(), body);
            assertTrue(json.readTree(response.body()).has("error"));
        }
    }

    @Test
    void reportsUnknownTasksAndUnsupportedRoutesAndMethods() throws Exception {
        assertEquals(404, get("/tasks/missing").statusCode());
        assertEquals(404, get("/missing").statusCode());
        HttpResponse<String> response = get("/tasks");
        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
    }

    private JsonNode awaitCompleted(String path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode());
            JsonNode task = json.readTree(response.body());
            if (task.get("status").asText().equals("COMPLETED")) {
                return task;
            }
            assertEquals("RUNNING", task.get("status").asText());
            Thread.sleep(20);
        }
        return fail("Task did not complete within 8 seconds");
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/tasks"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
