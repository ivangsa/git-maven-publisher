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
