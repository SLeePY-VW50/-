package com.campus.lab.schedule;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ScheduleBatchLock implements AutoCloseable {

    private final String key;
    private final String token;
    private final AtomicBoolean held = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BooleanSupplier ownershipCheck;
    private final Consumer<ScheduleBatchLock> releaser;
    private volatile ScheduledFuture<?> renewal;

    ScheduleBatchLock(
            String key, String token, BooleanSupplier ownershipCheck,
            Consumer<ScheduleBatchLock> releaser) {
        this.key = key;
        this.token = token;
        this.ownershipCheck = ownershipCheck;
        this.releaser = releaser;
    }

    public String key() {
        return key;
    }

    public String token() {
        return token;
    }

    public void ensureHeld() {
        boolean owned;
        try {
            owned = held.get() && Boolean.TRUE.equals(ownershipCheck.getAsBoolean());
        } catch (RuntimeException exception) {
            held.set(false);
            throw new IllegalStateException("BATCH_LOCK_LOST", exception);
        }
        if (!owned) {
            held.set(false);
            throw new IllegalStateException("BATCH_LOCK_LOST");
        }
    }

    void renewal(ScheduledFuture<?> renewal) {
        this.renewal = renewal;
    }

    void markLost() {
        held.set(false);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        held.set(false);
        ScheduledFuture<?> currentRenewal = renewal;
        if (currentRenewal != null) {
            currentRenewal.cancel(false);
        }
        releaser.accept(this);
    }
}
