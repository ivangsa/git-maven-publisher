/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asks a release repository whether {@code <repo>/<groupPath>/<artifactId>/<base>/<artifactId>-<base>.pom} exists
 * (design section 6, D-106, D-107). One instance serves one publication: it owns one HTTP client (default proxy
 * selector) and remembers every answer, so the attempts of the retry loop ask each question once.
 *
 * <p>"Exists" means: for {@code file:} a regular file; for {@code https} a final answer (redirects are followed) of
 * status 200 whose content type is not HTML. Everything else is doubt and means no: other statuses, an HTML page
 * (login or landing pages), timeouts, errors, and names that cannot be one path segment (R43, R46, D-113; every name is percent-encoded). A failure other than a plain 404 is logged once, without the query.
 */
final class ReleaseCheck {

    private final PublisherLog log;
    private final Map<URI, Boolean> answers = new HashMap<>();
    private HttpClient client;

    ReleaseCheck(PublisherLog log) {
        this.log = log;
    }

    /** The number of distinct questions answered so far. */
    int answered() {
        return answers.size();
    }

    boolean exists(URI repository, String groupPath, String artifactId, String base) {
        if (repository.getRawQuery() != null || repository.getRawFragment() != null) {
            return false; // RetentionPolicy rejects these (D-109); never ask the root in place of the POM
        }
        URI target;
        try {
            if (groupPath == null || groupPath.isEmpty()) {
                return false;
            }
            StringBuilder relative = new StringBuilder();
            for (String part : groupPath.split("/", -1)) {
                relative.append(encode(part)).append('/');
            }
            String file = artifactId + "-" + base + ".pom";
            relative.append(encode(artifactId)).append('/').append(encode(base)).append('/').append(encode(file));
            String root = repository.toASCIIString();
            target = new URI(
                    URI.create(root.endsWith("/") ? root + relative : root + "/" + relative).toASCIIString());
        } catch (IllegalArgumentException | URISyntaxException e) {
            return false; // doubt (R46, D-113): a name that cannot be one path segment
        }
        Boolean known = answers.get(target);
        if (known != null) {
            return known;
        }
        boolean answer = ask(target);
        answers.put(target, answer);
        return answer;
    }

    /**
     * Percent-encodes one name as exactly one path segment (R46, D-113) through the URI constructor, which quotes
     * every character not allowed in a path (including {@code ?}, {@code #} and {@code %}). Empty names, {@code .},
     * {@code ..}, names with {@code /} or {@code \} and control characters are doubt: {@link IllegalArgumentException}.
     */
    private static String encode(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("not a path segment");
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("not a path segment");
            }
        }
        try {
            String raw = new URI(null, null, "/" + segment, null).getRawPath();
            return raw.substring(1);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private boolean ask(URI target) {
        try {
            if ("file".equalsIgnoreCase(target.getScheme())) {
                return Files.isRegularFile(Path.of(target));
            }
            HttpRequest request = HttpRequest.newBuilder(target)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 404) {
                return false;
            }
            if (status != 200) {
                warn(target, "answered HTTP " + status);
                return false;
            }
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
                warn(target, "answered with an HTML page, not a POM");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            warn(target, "failed (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    private HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.getDefault())
                    .build();
        }
        return client;
    }

    private void warn(URI target, String what) {
        String shown = target.getScheme() + "://" + target.getRawAuthority() + target.getRawPath();
        log.warn("Release check " + shown + " " + what + "; the -SNAPSHOT version is kept");
    }
}
