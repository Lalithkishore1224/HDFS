package com.cloudstorage.security;

import com.cloudstorage.account.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after {@value #MAX_FAILURES} wrong passwords in a row for one username,
 * further attempts are refused for {@link #LOCK_TIME}. In memory only, which is enough for one server.
 */
@Component
class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(1);

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    void checkAllowed(String username) {
        Attempts current = attempts.get(username);
        Instant now = clock.instant();
        if (current != null && current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
            throw new TooManyAttemptsException(Duration.between(now, current.lockedUntil()).toSeconds() + 1);
        }
    }

    void failed(String username) {
        Instant now = clock.instant();
        attempts.merge(username, new Attempts(1, null), (old, ignored) -> {
            boolean expiredLock = old.lockedUntil() != null && !now.isBefore(old.lockedUntil());
            int failures = (expiredLock ? 0 : old.failures()) + 1;
            return new Attempts(failures, failures >= MAX_FAILURES ? now.plus(LOCK_TIME) : null);
        });
    }

    void succeeded(String username) {
        attempts.remove(username);
    }
}
