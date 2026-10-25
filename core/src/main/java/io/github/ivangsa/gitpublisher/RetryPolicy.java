/*
 * Copyright 2026 Ivan Garcia Sainz-Aja
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
