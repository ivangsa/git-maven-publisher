/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import io.github.ivangsa.gitpublisher.Credentials;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;
import java.util.List;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

/**
 * Token resolution (design section 8, D-035): the {@code settings.xml} server named by {@code serverId} (its password is
 * the token, decrypted by Maven), then the environment variable {@code GIT_PUBLISHER_TOKEN}, then anonymous access.
 * There is no token parameter or property, and nothing here logs or returns a secret in a message.
 */
final class CredentialsResolver {

    static final String TOKEN_ENVIRONMENT_VARIABLE = "GIT_PUBLISHER_TOKEN";

    private CredentialsResolver() {}

    static Credentials resolve(
            String serverId,
            List<Server> servers,
            SettingsDecrypter decrypter,
            Function<String, String> environment,
            Consumer<String> warn)
            throws MojoExecutionException {
        if (serverId != null && !serverId.isBlank()) {
            Server server = find(servers, serverId.trim());
            if (server == null) {
                warn.accept("git-publisher.serverId '" + serverId.trim() + "' is not a server in settings.xml;"
                        + " looking for " + TOKEN_ENVIRONMENT_VARIABLE + " instead");
            } else {
                Server decrypted = decrypt(server, decrypter);
                String password = decrypted.getPassword();
                if (password == null || password.isEmpty()) {
                    warn.accept("The settings.xml server '" + server.getId() + "' has no password;"
                            + " looking for " + TOKEN_ENVIRONMENT_VARIABLE + " instead");
                } else {
                    String username = decrypted.getUsername();
                    return new Credentials.Token(
                            username == null || username.isBlank() ? Credentials.DEFAULT_USERNAME : username,
                            password.toCharArray());
                }
            }
        }
        String fromEnvironment = environment.apply(TOKEN_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return Credentials.token(fromEnvironment.trim().toCharArray());
        }
        return Credentials.none();
    }

    /** The servers are those of the execution request, the same list Maven builds its authentication selector from. */
    private static Server find(List<Server> servers, String id) {
        for (Server candidate : servers) {
            if (id.equals(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private static Server decrypt(Server server, SettingsDecrypter decrypter) throws MojoExecutionException {
        SettingsDecryptionResult result = decrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        for (SettingsProblem problem : result.getProblems()) {
            if (problem.getSeverity() == SettingsProblem.Severity.ERROR
                    || problem.getSeverity() == SettingsProblem.Severity.FATAL) {
                throw new MojoExecutionException("cannot decrypt the password of the settings.xml server '"
                        + server.getId() + "': " + problem.getMessage());
            }
        }
        return result.getServer();
    }
}
