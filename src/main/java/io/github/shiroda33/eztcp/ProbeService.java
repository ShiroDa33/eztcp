package io.github.shiroda33.eztcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class ProbeService implements AutoCloseable {
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final Map<String, List<CompletableFuture<Result>>> tasks = new ConcurrentHashMap<>();

    String submit(List<Target> targets) {
        String id = UUID.randomUUID().toString();
        List<CompletableFuture<Result>> results = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(() -> probe(target), workers))
                .toList();
        tasks.put(id, results);
        return id;
    }

    TaskView find(String id) {
        List<CompletableFuture<Result>> futures = tasks.get(id);
        if (futures == null) {
            return null;
        }
        List<Result> results = futures.stream()
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .toList();
        String status = results.size() == futures.size() ? "COMPLETED" : "RUNNING";
        return new TaskView(id, status, futures.size(), results.size(), results);
    }

    private Result probe(Target target) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), 2_000);
            return new Result(target.host(), target.port(), true, elapsedMillis(start), null);
        } catch (IOException e) {
            return new Result(target.host(), target.port(), false, elapsedMillis(start), e.getMessage());
        }
    }

    private long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    record Target(String host, int port) { }

    record Result(String host, int port, boolean reachable, long durationMs, String error) { }

    record TaskView(String id, String status, int total, int completed, List<Result> results) { }
}
