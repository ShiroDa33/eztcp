package io.github.shiroda33.eztcp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowRateLimiterTest {
    @Test
    void rejectsOverflowAndResetsInNextSecond() {
        AtomicLong clock = new AtomicLong(1_000);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, clock::get);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        clock.set(1_999);
        assertFalse(limiter.tryAcquire());
        clock.set(2_000);
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void sharesTheLimitAcrossConcurrentCallers() throws InterruptedException {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(5, () -> 0);
        AtomicInteger accepted = new AtomicInteger();
        var callers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 100; i++) {
                callers.submit(() -> {
                    if (limiter.tryAcquire()) {
                        accepted.incrementAndGet();
                    }
                });
            }
            callers.shutdown();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(5, accepted.get());
        } finally {
            callers.shutdownNow();
        }
    }
}
