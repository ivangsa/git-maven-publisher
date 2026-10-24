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
package io.github.ivangsa.gitpublisher.fixtures;

import java.net.URI;
import java.nio.file.Path;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * Embedded Jetty serving a directory as static files on a free local port: what GitHub Pages does with the
 * checked-out branch. A missing path answers 404.
 */
public final class PagesServer implements AutoCloseable {

    private final Server server;
    private final ServerConnector connector;

    public PagesServer(Path directory) {
        this.server = new Server();
        this.connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);

        ResourceHandler handler = new ResourceHandler();
        handler.setBaseResource(ResourceFactory.of(handler).newResource(directory.toAbsolutePath()));
        handler.setDirAllowed(false);
        handler.setWelcomeFiles(java.util.List.of());
        server.setHandler(handler);
    }

    public PagesServer start() {
        try {
            server.start();
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("cannot start PagesServer", e);
        }
    }

    /** Base URL with a trailing slash, for example {@code http://127.0.0.1:41234/}. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/");
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("cannot stop PagesServer", e);
        }
    }
}
