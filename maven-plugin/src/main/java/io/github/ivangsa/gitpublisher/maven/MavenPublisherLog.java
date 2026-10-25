/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import io.github.ivangsa.gitpublisher.PublisherLog;
import org.apache.maven.plugin.logging.Log;

/** Bridges the core's log to the Maven log. */
final class MavenPublisherLog implements PublisherLog {

    private final Log log;

    MavenPublisherLog(Log log) {
        this.log = log;
    }

    @Override
    public void info(String message) {
        log.info(message);
    }

    @Override
    public void warn(String message) {
        log.warn(message);
    }

    @Override
    public void debug(String message) {
        log.debug(message);
    }
}
