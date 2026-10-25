/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

/**
 * A publication failed and nothing was pushed (or the failure is reported before any push). The message never
 * contains credentials.
 */
public class PublishException extends Exception {

    private static final long serialVersionUID = 1L;

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
