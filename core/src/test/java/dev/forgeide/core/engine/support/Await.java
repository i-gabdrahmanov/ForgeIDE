package dev.forgeide.core.engine.support;

import java.util.function.BooleanSupplier;

/** Spin-wait helper for asserting on state that changes asynchronously off the test thread. */
public final class Await {

    private Await() {
    }

    public static void until(BooleanSupplier condition) {
        until(condition, 2_000);
    }

    public static void until(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
