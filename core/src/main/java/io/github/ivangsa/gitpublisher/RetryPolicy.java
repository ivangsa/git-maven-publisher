/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry budget for lease failures and transport errors (design section 7). The delay before attempt {@code n + 1} is
 * {@code random(0, min(maxDelay, baseDelay * 2^(n-1)))}.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be smaller than baseDelay");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(5, Duration.ofMillis(500), Duration.ofSeconds(8));
    }
}
