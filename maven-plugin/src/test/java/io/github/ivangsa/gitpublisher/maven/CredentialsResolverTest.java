/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ivangsa.gitpublisher.Credentials;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;

import org.apache.maven.settings.building.DefaultSettingsProblem;
import org.apache.maven.settings.building.SettingsProblem;

import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.junit.jupiter.api.Test;

/** Token resolution order of design section 8: {@code serverId} server, then GIT_PUBLISHER_TOKEN, then anonymous. */
class CredentialsResolverTest {

    private final List<String> warnings = new ArrayList<>();

    private static List<Server> settings(Server... servers) {
        return List.of(servers);
    }

    private static Server server(String id, String username, String password) {
        Server server = new Server();
        server.setId(id);
        server.setUsername(username);
        server.setPassword(password);
        return server;
    }

    /** Stands in for the Maven decrypter: "enc:" prefixed passwords are decrypted by removing it. */
    private static final SettingsDecrypter PREFIX_DECRYPTER = new SettingsDecrypter() {
        @Override
        public SettingsDecryptionResult decrypt(SettingsDecryptionRequest request) {
            FakeResult result = new FakeResult();
            for (Server server : request.getServers()) {
                Server copy = server.clone();
                if (copy.getPassword() != null && copy.getPassword().startsWith("enc:")) {
                    copy.setPassword(copy.getPassword().substring(4));
                }
                result.addServer(copy);
            }
            return result;
        }
    };

    private Credentials resolve(String serverId, List<Server> settings, Map<String, String> env) throws Exception {
        return CredentialsResolver.resolve(serverId, settings, PREFIX_DECRYPTER, env::get, warnings::add);
    }

    private static String tokenOf(Credentials credentials) {
        return new String(((Credentials.Token) credentials).token());
    }

    @Test
    void theServerPasswordIsTheTokenAndTheUserNameDefaults() throws Exception {
        Credentials credentials = resolve("snapshots", settings(server("snapshots", null, "s3cr3t-from-server")), Map.of());

        assertThat(credentials).isInstanceOf(Credentials.Token.class);
        assertThat(((Credentials.Token) credentials).username()).isEqualTo("x-access-token");
        assertThat(tokenOf(credentials)).isEqualTo("s3cr3t-from-server");
    }

    @Test
    void theServerUserNameIsUsedWhenGiven() throws Exception {
        Credentials credentials = resolve("snapshots", settings(server("snapshots", "ci-bot", "t")), Map.of());

        assertThat(((Credentials.Token) credentials).username()).isEqualTo("ci-bot");
    }

    @Test
    void encryptedPasswordsAreDecryptedByMavensDecrypter() throws Exception {
        Credentials credentials = resolve("snapshots", settings(server("snapshots", null, "enc:plain-token")), Map.of());

        assertThat(tokenOf(credentials)).isEqualTo("plain-token");
    }

    @Test
    void theServerWinsOverTheEnvironment() throws Exception {
        Credentials credentials = resolve(
                "snapshots",
                settings(server("snapshots", null, "from-server")),
                Map.of("GIT_PUBLISHER_TOKEN", "from-env"));

        assertThat(tokenOf(credentials)).isEqualTo("from-server");
    }

    @Test
    void withoutAServerIdTheEnvironmentTokenIsUsed() throws Exception {
        Credentials credentials = resolve(null, settings(), Map.of("GIT_PUBLISHER_TOKEN", "from-env"));

        assertThat(((Credentials.Token) credentials).username()).isEqualTo("x-access-token");
        assertThat(tokenOf(credentials)).isEqualTo("from-env");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aServerIdThatIsNotInSettingsFallsBackToTheEnvironmentWithAWarning() throws Exception {
        Credentials credentials = resolve("missing", settings(), Map.of("GIT_PUBLISHER_TOKEN", "from-env"));

        assertThat(tokenOf(credentials)).isEqualTo("from-env");
        assertThat(warnings).singleElement().asString().contains("missing").doesNotContain("from-env");
    }

    @Test
    void aServerWithoutAPasswordFallsBackWithAWarning() throws Exception {
        Credentials credentials = resolve("snapshots", settings(server("snapshots", "bot", null)), Map.of());

        assertThat(credentials).isEqualTo(Credentials.none());
        assertThat(warnings).singleElement().asString().contains("snapshots");
    }

    @Test
    void noTokenAnywhereMeansAnonymousAccess() throws Exception {
        assertThat(resolve(null, settings(), Map.of())).isEqualTo(Credentials.none());
        assertThat(resolve("  ", settings(), Map.of("GIT_PUBLISHER_TOKEN", "  "))).isEqualTo(Credentials.none());
    }

    @Test
    void aPasswordThatCannotBeDecryptedFailsWithoutEchoingIt() {
        SettingsDecrypter failing = request -> {
            FakeResult result = new FakeResult();
            result.addServer(request.getServers().get(0));
result.getProblems().add(new DefaultSettingsProblem(                    "master password missing", SettingsProblem.Severity.ERROR, "settings.xml", -1, -1, null));
            return result;
        };

        assertThatThrownBy(() -> CredentialsResolver.resolve(
                        "snapshots",
                        settings(server("snapshots", null, "{enc-secret-value}")),
                        failing,
                        name -> null,
                        warnings::add))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("snapshots")
                .hasMessageNotContaining("enc-secret-value");
    }

    /** The decrypter's result type is package-private in Maven; this is the minimum a caller reads. */
    private static final class FakeResult implements SettingsDecryptionResult {
        private final List<Server> servers = new ArrayList<>();
        private final List<SettingsProblem> problems = new ArrayList<>();

        void addServer(Server server) {
            servers.add(server);
        }

        @Override
        public Server getServer() {
            return servers.isEmpty() ? null : servers.get(0);
        }

        @Override
        public List<Server> getServers() {
            return servers;
        }

        @Override
        public org.apache.maven.settings.Proxy getProxy() {
            return null;
        }

        @Override
        public List<org.apache.maven.settings.Proxy> getProxies() {
            return List.of();
        }

        @Override
        public List<SettingsProblem> getProblems() {
            return problems;
        }
    }
}
