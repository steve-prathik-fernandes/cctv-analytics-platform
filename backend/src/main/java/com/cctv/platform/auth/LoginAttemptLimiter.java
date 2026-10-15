package com.cctv.platform.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits repeated failed logins per email address (NFR-S9). After {@code maxFailedLogins}
 * failures inside the window, further attempts for that email are refused until the oldest
 * failure leaves the window.
 *
 * <p>State is in memory, so it resets on restart and is not shared between instances. That is
 * enough for the single-instance deployment in the architecture document.
 */
@Component
public class LoginAttemptLimiter {

    private static final int PRUNE_THRESHOLD = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final AuthProperties props;
    private final Clock clock;

    public LoginAttemptLimiter(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public boolean isBlocked(String email) {
        Deque<Instant> attempts = failures.get(email);
        if (attempts == null) return false;
        synchronized (attempts) {
            dropExpired(attempts);
            return attempts.size() >= props.maxFailedLogins();
        }
    }

    public void recordFailure(String email) {
        if (failures.size() > PRUNE_THRESHOLD) prune();
        Deque<Instant> attempts = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (attempts) {
            dropExpired(attempts);
            attempts.addLast(clock.instant());
        }
    }

    public void reset(String email) {
        failures.remove(email);
    }

    private void dropExpired(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(props.failedLoginWindow());
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }

    /** Keeps memory bounded when many different emails are tried. */
    private void prune() {
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                dropExpired(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }
}
