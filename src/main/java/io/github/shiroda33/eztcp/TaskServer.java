package io.github.shiroda33.eztcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TaskServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService httpWorkers = Executors.newFixedThreadPool(4);
    private final ProbeService probes = new ProbeService();
    private final ObjectMapper json = new ObjectMapper();
    private final FixedWindowRateLimiter limiter;

    public TaskServer(int port) throws IOException {
        this(port, new FixedWindowRateLimiter(5, System::currentTimeMillis));
    }

    TaskServer(int port, FixedWindowRateLimiter limiter) throws IOException {
        this.limiter = limiter;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(httpWorkers);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/tasks")) {
                if (!exchange.getRequestMethod().equals("POST")) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                submit(exchange);
            } else if (path.startsWith("/tasks/") && path.length() > "/tasks/".length()) {
                if (!exchange.getRequestMethod().equals("GET")) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                ProbeService.TaskView task = probes.find(path.substring("/tasks/".length()));
                if (task == null) {
                    respond(exchange, 404, Map.of("error", "Task not found"));
                } else {
                    respond(exchange, 200, task);
                }
            } else {
                respond(exchange, 404, Map.of("error", "Not found"));
            }
        }
    }

    private void submit(HttpExchange exchange) throws IOException {
        if (!limiter.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, 429, Map.of("error", "At most 5 task submissions per second"));
            return;
        }

        Submission request;
        try {
            request = json.readValue(exchange.getRequestBody(), Submission.class);
        } catch (JsonProcessingException e) {
            respond(exchange, 400, Map.of("error", "Invalid JSON request"));
            return;
        }
        if (request == null || request.targets() == null || request.targets().isEmpty()) {
            respond(exchange, 400, Map.of("error", "targets must not be empty"));
            return;
        }
        for (ProbeService.Target target : request.targets()) {
            if (target == null || target.host() == null || target.host().isBlank()
                    || target.port() < 1 || target.port() > 65_535) {
                respond(exchange, 400, Map.of("error", "Each target needs a host and a port from 1 to 65535"));
                return;
            }
        }

        String id = probes.submit(request.targets());
        exchange.getResponseHeaders().set("Location", "/tasks/" + id);
        respond(exchange, 202, Map.of("id", id));
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        respond(exchange, 405, Map.of("error", "Method not allowed"));
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        httpWorkers.shutdownNow();
        probes.close();
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getProperty("port", "8080"));
        TaskServer server = new TaskServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("eztcp listening on http://localhost:" + server.port());
    }

    record Submission(List<ProbeService.Target> targets) { }
}
