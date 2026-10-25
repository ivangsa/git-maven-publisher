/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

/** Where the core reports progress. Adapters bridge it to the Maven Log or the Gradle Logger. Never receives credentials. */
public interface PublisherLog {

    void info(String message);

    void warn(String message);

    void debug(String message);

    /** A log that drops everything. */
    static PublisherLog silent() {
        return new PublisherLog() {
            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {}

            @Override
            public void debug(String message) {}
        };
    }
}
