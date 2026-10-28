package io.github.shiroda33.eztcp;

import java.util.function.LongSupplier;

final class FixedWindowRateLimiter {
    private final int limit;
    private final LongSupplier clock;
    private long window;
    private int used;

    FixedWindowRateLimiter(int limit, LongSupplier clock) {
        this.limit = limit;
        this.clock = clock;
        this.window = clock.getAsLong() / 1_000;
    }

    synchronized boolean tryAcquire() {
        long currentWindow = clock.getAsLong() / 1_000;
        if (currentWindow != window) {
            window = currentWindow;
            used = 0;
        }
        if (used == limit) {
            return false;
        }
        used++;
        return true;
    }
}
