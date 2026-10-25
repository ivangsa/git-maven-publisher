/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.util.Arrays;
import java.util.Objects;

/** How the publisher authenticates to the Git remote: not at all, or with an HTTPS token (D-015). */
public sealed interface Credentials {

    /** Default user name for token authentication (GitHub accepts any non-empty name with a token). */
    String DEFAULT_USERNAME = "x-access-token";

    static Credentials none() {
        return None.INSTANCE;
    }

    static Token token(char[] token) {
        return new Token(DEFAULT_USERNAME, token);
    }

    /** Anonymous access, useful for {@code file://} remotes. */
    record None() implements Credentials {
        static final None INSTANCE = new None();
    }

    /**
     * HTTPS token. The token is copied on the way in and out, never printed by {@link #toString()}, and can be wiped
     * with {@link #clear()}.
     */
    final class Token implements Credentials {
        private final String username;
        private final char[] token;

        public Token(String username, char[] token) {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(token, "token");
            if (username.isBlank()) {
                throw new IllegalArgumentException("token username must not be blank");
            }
            if (token.length == 0) {
                throw new IllegalArgumentException("token must not be empty");
            }
            this.username = username;
            this.token = token.clone();
        }

        public String username() {
            return username;
        }

        /** A copy of the token characters. */
        public char[] token() {
            return token.clone();
        }

        /** Overwrites the token held by this object. */
        public void clear() {
            Arrays.fill(token, '\0');
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Token other && username.equals(other.username) && Arrays.equals(token, other.token);
        }

        @Override
        public int hashCode() {
            return 31 * username.hashCode() + Arrays.hashCode(token);
        }

        @Override
        public String toString() {
            return "Token[username=" + username + ", token=***]";
        }
    }
}
