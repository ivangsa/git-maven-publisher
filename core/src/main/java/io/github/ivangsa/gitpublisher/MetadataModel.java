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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reader and writer for {@code maven-metadata.xml} at group (plugins), artifact (versions) and version (snapshot)
 * level (design section 5, D-005). It exists for retention and {@link IntegrityCheck}; the native deploy writes the
 * metadata.
 *
 * <p>The model sits on an element tree, so elements it does not know are preserved in place on write. Only the
 * mutators below change the tree; everything else is written as read.
 */
final class MetadataModel {

    /** Which kind of metadata file this is. */
    enum Level {
        /** Lists {@code <plugins>}; has no artifact coordinates. */
        GROUP,
        /** Has {@code <artifactId>} and no {@code <version>}: lists versions. */
        ARTIFACT,
        /** Has {@code <version>}: describes the snapshot builds of one version. */
        VERSION
    }

    record Snapshot(String timestamp, int buildNumber, boolean localCopy) {}

    record SnapshotVersion(String classifier, String extension, String value, String updated) {}

    record Plugin(String name, String prefix, String artifactId) {}

    private final XmlNode root;

    private MetadataModel(XmlNode root) {
        this.root = root;
    }

    static MetadataModel read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    static MetadataModel read(byte[] bytes) throws IOException {
        return read(new ByteArrayInputStream(bytes));
    }

    static MetadataModel read(InputStream in) throws IOException {
        XmlNode root = XmlNode.read(in);
        if (!root.name().equals("metadata")) {
            throw new IOException("root element is <" + root.name() + ">, expected <metadata>");
        }
        rejectDuplicates(root, "metadata", SINGLE_FIELDS_OF_METADATA);
        for (XmlNode v : root.children("versioning")) {
            rejectDuplicates(v, "versioning", SINGLE_FIELDS_OF_VERSIONING);
            for (XmlNode snapshot : v.children("snapshot")) {
                rejectDuplicates(snapshot, "snapshot", SINGLE_FIELDS_OF_SNAPSHOT);
            }
            for (XmlNode container : v.children("snapshotVersions")) {
                for (XmlNode sv : container.children("snapshotVersion")) {
                    rejectDuplicates(sv, "snapshotVersion", SINGLE_FIELDS_OF_SNAPSHOT_VERSION);
                }
            }
        }
        for (XmlNode container : root.children("plugins")) {
            for (XmlNode plugin : container.children("plugin")) {
                rejectDuplicates(plugin, "plugin", SINGLE_FIELDS_OF_PLUGIN);
            }
        }
        return new MetadataModel(root);
    }

    // Fields that Maven's metadata reader accepts at most once per element; it rejects a repeated tag.
    private static final List<String> SINGLE_FIELDS_OF_METADATA =
            List.of("groupId", "artifactId", "version", "versioning", "plugins");
    private static final List<String> SINGLE_FIELDS_OF_VERSIONING =
            List.of("latest", "release", "snapshot", "versions", "lastUpdated", "snapshotVersions");
    private static final List<String> SINGLE_FIELDS_OF_SNAPSHOT = List.of("timestamp", "buildNumber", "localCopy");
    private static final List<String> SINGLE_FIELDS_OF_SNAPSHOT_VERSION =
            List.of("classifier", "extension", "value", "updated");
    private static final List<String> SINGLE_FIELDS_OF_PLUGIN = List.of("name", "prefix", "artifactId");

    private static void rejectDuplicates(XmlNode node, String element, List<String> singleFields)
            throws IOException {
        for (String field : singleFields) {
            if (node.children(field).size() > 1) {
                throw new IOException("duplicated <" + field + "> in <" + element + ">");
            }
        }
    }

    void write(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            root.write(out);
        }
    }

    byte[] toBytes() {
        return root.toBytes();
    }

    @Override
    public String toString() {
        return root.toXml();
    }

    // ---- coordinates ----

    Optional<String> groupId() {
        return root.childText("groupId");
    }

    Optional<String> artifactId() {
        return root.childText("artifactId");
    }

    Optional<String> version() {
        return root.childText("version");
    }

    Level level() {
        if (version().isPresent()) {
            return Level.VERSION;
        }
        if (artifactId().isPresent()) {
            return Level.ARTIFACT;
        }
        return Level.GROUP;
    }

    // ---- group level ----

    List<Plugin> plugins() {
        return root.child("plugins").stream()
                .flatMap(p -> p.children("plugin").stream())
                .map(p -> new Plugin(
                        p.childText("name").orElse(null),
                        p.childText("prefix").orElse(null),
                        p.childText("artifactId").orElse(null)))
                .toList();
    }

    // ---- versioning ----

    Optional<String> latest() {
        return versioningText("latest");
    }

    Optional<String> release() {
        return versioningText("release");
    }

    Optional<String> lastUpdated() {
        return versioningText("lastUpdated");
    }

    /** Versions of an artifact-level file, in document order. */
    List<String> versions() {
        return versioning().flatMap(v -> v.child("versions")).stream()
                .flatMap(v -> v.children("version").stream())
                .map(XmlNode::text)
                .toList();
    }

    Optional<Snapshot> snapshot() {
        return versioning().flatMap(v -> v.child("snapshot")).map(s -> new Snapshot(
                s.childText("timestamp").orElse(null),
                s.childText("buildNumber").map(MetadataModel::parseInt).orElse(0),
                s.childText("localCopy").map(Boolean::parseBoolean).orElse(false)));
    }

    /** Snapshot versions of a version-level file, in document order. */
    List<SnapshotVersion> snapshotVersions() {
        return versioning().flatMap(v -> v.child("snapshotVersions")).stream()
                .flatMap(v -> v.children("snapshotVersion").stream())
                .map(MetadataModel::toSnapshotVersion)
                .toList();
    }

    // ---- mutations (retention) ----

    /** Removes the {@code <snapshotVersion>} entries that match; returns how many were removed. */
    int removeSnapshotVersionsIf(Predicate<SnapshotVersion> matches) {
        Optional<XmlNode> container = versioning().flatMap(v -> v.child("snapshotVersions"));
        if (container.isEmpty()) {
            return 0;
        }
        List<XmlNode> nodes = container.get().children();
        int before = nodes.size();
        nodes.removeIf(n -> n.name().equals("snapshotVersion") && matches.test(toSnapshotVersion(n)));
        return before - nodes.size();
    }

    /** Removes versions from an artifact-level file; the caller fixes {@code latest} and {@code release}. */
    int removeVersions(java.util.Collection<String> toRemove) {
        Optional<XmlNode> container = versioning().flatMap(v -> v.child("versions"));
        if (container.isEmpty()) {
            return 0;
        }
        List<XmlNode> nodes = container.get().children();
        int before = nodes.size();
        nodes.removeIf(n -> n.name().equals("version") && toRemove.contains(n.text()));
        return before - nodes.size();
    }

    void setLatest(String value) {
        setVersioningText("latest", value);
    }

    void setRelease(String value) {
        setVersioningText("release", value);
    }

    void setLastUpdated(String value) {
        setVersioningText("lastUpdated", value);
    }

    // ---- internals ----

    private Optional<XmlNode> versioning() {
        return root.child("versioning");
    }

    private Optional<String> versioningText(String name) {
        return versioning().flatMap(v -> v.childText(name));
    }

    private void setVersioningText(String name, String value) {
        XmlNode versioning = versioning().orElseGet(() -> {
            XmlNode created = new XmlNode("versioning");
            root.children().add(created);
            return created;
        });
        if (value == null) {
            versioning.removeChildren(name);
        } else {
            versioning.setChildText(name, value);
        }
    }

    private static SnapshotVersion toSnapshotVersion(XmlNode n) {
        return new SnapshotVersion(
                n.childText("classifier").orElse(""),
                n.childText("extension").orElse(null),
                n.childText("value").orElse(null),
                n.childText("updated").orElse(null));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
