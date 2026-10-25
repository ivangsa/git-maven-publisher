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
package io.github.ivangsa.gitpublisher.maven.it;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.EnumSet;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * A real Git HTTPS server for the plugin ITs: JGit's {@link GitServlet} over TLS (a self-signed certificate for
 * 127.0.0.1 made by the JDK's {@code keytool}) behind a filter that accepts basic authentication with exactly one
 * token and answers 401 to everything else, anonymous requests included. {@link #truststore()} trusts the certificate
 * on top of the JDK's own roots, for the {@code javax.net.ssl.trustStore} of the Maven that runs the IT.
 */
public final class ItServer implements AutoCloseable {

    static final String STORE_PASSWORD = "changeit";

    private final Server server;
    private final Repository repository;
    private final URI url;
    private final Path truststore;

    private ItServer(Server server, Repository repository, URI url, Path truststore) {
        this.server = server;
        this.repository = repository;
        this.url = url;
        this.truststore = truststore;
    }

    /** @param tlsDirectory empty directory for the key store and the trust store */
    public static ItServer start(Path bareDirectory, Path tlsDirectory, String acceptedToken) throws Exception {
        Files.createDirectories(tlsDirectory);
        Path keystore = tlsDirectory.resolve("server.p12");
        Process keytool = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "it", "-keyalg", "RSA", "-keysize", "2048",
                        "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1", "-validity", "2",
                        "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", STORE_PASSWORD)
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        Path truststore = tlsDirectory.resolve("truststore.p12");
        writeTruststore(keystore, truststore);

        Repository repository = new FileRepositoryBuilder().setGitDir(bareDirectory.toFile()).setBare().build();
        StoredConfig config = repository.getConfig();
        config.setBoolean("http", null, "receivepack", true);
        config.save();

        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((request, name) -> {
            repository.incrementOpen();
            return repository;
        });
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(keystore.toString());
        ssl.setKeyStorePassword(STORE_PASSWORD);
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, ssl);
        connector.setHost("127.0.0.1");
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        context.addFilter(new FilterHolder(new BasicAuth(acceptedToken)), "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(servlet), "/*");
        server.setHandler(context);
        server.start();
        URI url = URI.create("https://127.0.0.1:" + connector.getLocalPort() + "/remote.git");
        return new ItServer(server, repository, url, truststore);
    }

    /** The JDK's trusted roots plus the certificate of this server. */
    private static void writeTruststore(Path keystore, Path truststore) throws Exception {
        KeyStore server = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            server.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        int index = 0;
        for (TrustManager manager : defaults.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (Certificate root : x509.getAcceptedIssuers()) {
                    trust.setCertificateEntry("root-" + index++, root);
                }
            }
        }
        trust.setCertificateEntry("it-server", server.getCertificate("it"));
        try (OutputStream out = Files.newOutputStream(truststore)) {
            trust.store(out, STORE_PASSWORD.toCharArray());
        }
    }

    /** {@code https://127.0.0.1:<port>/remote.git} */
    public URI url() {
        return url;
    }

    public Path truststore() {
        return truststore;
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            repository.close();
        }
    }

    /** Accepts exactly one token (any user name); answers 401 with a challenge otherwise. */
    private static final class BasicAuth implements Filter {
        private final String token;

        BasicAuth(String token) {
            this.token = token;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String header = req.getHeader("Authorization");
            if (header != null && header.startsWith("Basic ")) {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
                if (decoded.substring(decoded.indexOf(':') + 1).equals(token)) {
                    chain.doFilter(request, response);
                    return;
                }
            }
            resp.setHeader("WWW-Authenticate", "Basic realm=\"git\"");
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
