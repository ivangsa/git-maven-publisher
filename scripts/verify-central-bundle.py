#!/usr/bin/env python3
# Copyright (c) 2026 ZenWave's Authors and Contributors
#
# Licensed under the MIT License; see the LICENSE file in the project root.
"""Checks a Maven Central bundle produced by `./mvnw -Prelease deploy -Dcentral.skipUpload=true` (T32a, D-072).

Usage (run with -I, see docs/decisions.md D-077):
  python3 -I scripts/verify-central-bundle.py [--gradle-plugin] <bundle.zip> <version> <key-fingerprint> <gnupg-homedir>

With --gradle-plugin the bundle is the one of `./gradlew -p gradle-plugin centralBundle` (T45, D-111): the plugin jar
(.pom, .jar, -sources.jar, -javadoc.jar, .module) and the plugin marker (.pom only; its POM must depend on the plugin jar
of the same version). Without it, the bundle is the Maven one.

Checks, per published artifact (parent POM, core, maven-plugin):
  - the exact file set: .pom, and for jars .jar, -sources.jar, -javadoc.jar; nothing else under io/github/ivangsa;
    no entry mentions test-fixtures (D-072: never in a bundle);
  - every file has a .asc signature that gpg verifies with the given key fingerprint, plus .md5 and .sha1
    checksums matching the content (.sha256/.sha512 too when present);
  - the POM, as uploaded (no parent inheritance resolved), has name, description, url, licenses, developers, scm;
  - jars open, the sources jar has .java files, the javadoc jar has index.html.

Exit status 0 only when every check passes. Prints no key material, only the fingerprint it was given.
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

GROUP_PATH = "io/github/ivangsa"
# (artifactId, packaging) of every artifact the release publishes. test-fixtures is deliberately absent.
ARTIFACTS = [
    ("git-maven-publisher-parent", "pom"),
    ("git-maven-publisher-core", "jar"),
    ("git-maven-publisher-maven-plugin", "jar"),
]
REQUIRED_POM_FIELDS = ["name", "description", "url"]
# The Gradle plugin bundle (T45, D-111): the plugin jar, and the plugin marker whose groupId is the plugin id.
PLUGIN_ARTIFACT = "git-maven-publisher-gradle-plugin"
MARKER_GROUP_PATH = "io/github/ivangsa/git-maven-publisher"
MARKER_ARTIFACT = "io.github.ivangsa.git-maven-publisher.gradle.plugin"


def expected_files(version):
    """Returns {zip entry: kind} for every file the bundle must contain (kind: pom, jar, sources, javadoc)."""
    files = {}
    for artifact_id, packaging in ARTIFACTS:
        base = f"{GROUP_PATH}/{artifact_id}/{version}/{artifact_id}-{version}"
        files[f"{base}.pom"] = "pom"
        if packaging == "jar":
            files[f"{base}.jar"] = "jar"
            files[f"{base}-sources.jar"] = "sources"
            files[f"{base}-javadoc.jar"] = "javadoc"
    return files


def expected_gradle_plugin_files(version):
    """Same, for the Gradle plugin bundle (kind also: module for Gradle Module Metadata, marker for the marker POM; the marker is POM-only)."""
    base = f"{GROUP_PATH}/{PLUGIN_ARTIFACT}/{version}/{PLUGIN_ARTIFACT}-{version}"
    marker = f"{MARKER_GROUP_PATH}/{MARKER_ARTIFACT}/{version}/{MARKER_ARTIFACT}-{version}"
    return {
        f"{base}.pom": "pom",
        f"{base}.jar": "jar",
        f"{base}-sources.jar": "sources",
        f"{base}-javadoc.jar": "javadoc",
        f"{base}.module": "module",
        f"{marker}.pom": "marker",
    }


def check_marker(entry, data, version, failures):
    """The marker POM points to the plugin jar: a dependency on the plugin artifact at the same version."""
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        return  # reported by check_pom
    dependencies = child(root, "dependencies")
    found = False
    for dep in (list(dependencies) if dependencies is not None else []):
        if (text_of(dep, "groupId") == "io.github.ivangsa" and text_of(dep, "artifactId") == PLUGIN_ARTIFACT
                and text_of(dep, "version") == version):
            found = True
    if not found:
        failures.append(f"{entry}: the marker does not depend on io.github.ivangsa:{PLUGIN_ARTIFACT}:{version}")
    if text_of(root, "groupId") != "io.github.ivangsa.git-maven-publisher" or text_of(root, "artifactId") != MARKER_ARTIFACT:
        failures.append(f"{entry}: the marker coordinates are not the plugin id and its .gradle.plugin artifact")


def check_module(entry, data, failures):
    """Gradle Module Metadata is JSON with a formatVersion and variants."""
    try:
        doc = json.loads(data)
    except ValueError as e:
        failures.append(f"{entry}: not valid JSON ({e})")
        return
    if "formatVersion" not in doc or not doc.get("variants"):
        failures.append(f"{entry}: Gradle module metadata without formatVersion or variants")


def strip_ns(tag):
    return tag.split("}", 1)[-1]


def child(elem, name):
    for c in elem:
        if strip_ns(c.tag) == name:
            return c
    return None


def text_of(elem, path):
    """Text of the first element at a slash-separated path of direct children, or ''."""
    cur = elem
    for name in path.split("/"):
        if cur is None:
            return ""
        cur = child(cur, name)
    return (cur.text or "").strip() if cur is not None else ""


def check_pom(entry, data, failures):
    try:
        root = ET.fromstring(data)
    except ET.ParseError as e:
        failures.append(f"{entry}: not well-formed XML ({e})")
        return
    for field in REQUIRED_POM_FIELDS:
        if not text_of(root, field):
            failures.append(f"{entry}: missing <{field}>")
    licenses = child(root, "licenses")
    license_el = child(licenses, "license") if licenses is not None else None
    if license_el is None:
        failures.append(f"{entry}: missing <licenses>/<license>")
    else:
        if not text_of(license_el, "name"):
            failures.append(f"{entry}: license without <name>")
        if not text_of(license_el, "url"):
            failures.append(f"{entry}: license without <url>")
    developers = child(root, "developers")
    dev_el = child(developers, "developer") if developers is not None else None
    if dev_el is None:
        failures.append(f"{entry}: missing <developers>/<developer>")
    elif not text_of(dev_el, "name"):
        failures.append(f"{entry}: developer without <name>")
    scm = child(root, "scm")
    if scm is None:
        failures.append(f"{entry}: missing <scm>")
    elif not text_of(scm, "url") and not text_of(scm, "connection"):
        failures.append(f"{entry}: scm without <url> or <connection>")


def check_jar(entry, kind, data, failures):
    tmp = tempfile.NamedTemporaryFile(suffix=".jar", delete=False)
    try:
        tmp.write(data)
        tmp.close()
        with zipfile.ZipFile(tmp.name) as jar:
            bad = jar.testzip()
            if bad is not None:
                failures.append(f"{entry}: corrupt member {bad}")
            names = jar.namelist()
            if kind == "sources" and not any(n.endswith(".java") for n in names):
                failures.append(f"{entry}: sources jar has no .java file")
            if kind == "javadoc" and "index.html" not in names:
                failures.append(f"{entry}: javadoc jar has no index.html")
            if any("test-fixtures" in n for n in names):
                failures.append(f"{entry}: jar contains test-fixtures classes")
    except zipfile.BadZipFile:
        failures.append(f"{entry}: not a zip file")
    finally:
        os.unlink(tmp.name)


def check_checksums(zf, entry, data, failures):
    algorithms = {"md5": "md5", "sha1": "sha1", "sha256": "sha256", "sha512": "sha512"}
    for suffix, algo in algorithms.items():
        name = f"{entry}.{suffix}"
        required = suffix in ("md5", "sha1")
        if name not in zf.namelist():
            if required:
                failures.append(f"{entry}: missing .{suffix}")
            continue
        expected = zf.read(name).decode("ascii", "replace").strip().split()[0].lower()
        actual = hashlib.new(algo, data).hexdigest()
        if expected != actual:
            failures.append(f"{entry}: .{suffix} does not match the content")


def check_signature(zf, entry, data, homedir, fingerprint, failures, workdir):
    sig_name = f"{entry}.asc"
    if sig_name not in zf.namelist():
        failures.append(f"{entry}: missing .asc signature")
        return
    local = os.path.join(workdir, entry.replace("/", "_"))
    with open(local, "wb") as f:
        f.write(data)
    with open(local + ".asc", "wb") as f:
        f.write(zf.read(sig_name))
    result = subprocess.run(
        ["gpg", "--homedir", homedir, "--batch", "--no-tty", "--status-fd", "1",
         "--verify", local + ".asc", local],
        stdin=subprocess.DEVNULL, capture_output=True, text=True, check=False)
    valid = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
    if result.returncode != 0 or not valid:
        failures.append(f"{entry}: .asc does not verify (gpg exit {result.returncode})")
        return
    fields = valid[0]
    # VALIDSIG <signing fpr> ... <primary fpr> is the last-but-one and last field; accept either to allow subkeys.
    if fingerprint not in (fields[2], fields[-1]):
        failures.append(f"{entry}: signed by a key other than the expected fingerprint")


def main(argv):
    gradle_plugin = "--gradle-plugin" in argv
    argv = [a for a in argv if a != "--gradle-plugin"]
    if len(argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    bundle, version, fingerprint, homedir = argv[1:]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?", version):
        print(f"FAIL: version {version!r} is not a release version", file=sys.stderr)
        return 2
    if not re.fullmatch(r"[0-9A-Fa-f]{40}", fingerprint):
        print("FAIL: fingerprint must be 40 hex digits", file=sys.stderr)
        return 2

    failures = []
    expected = expected_gradle_plugin_files(version) if gradle_plugin else expected_files(version)
    with zipfile.ZipFile(bundle) as zf, tempfile.TemporaryDirectory() as workdir:
        names = zf.namelist()
        leaked = [n for n in names if "test-fixtures" in n]
        for n in leaked:
            failures.append(f"{n}: test-fixtures must not be in a bundle")

        # Every published file, plus its signature and checksums, and nothing else under the group.
        allowed = set()
        for entry in expected:
            allowed.add(entry)
            for suffix in (".asc", ".md5", ".sha1", ".sha256", ".sha512"):
                allowed.add(entry + suffix)
        for n in names:
            if n.endswith("/"):
                continue  # directory entries: `zip -r`, which Central documents, writes them too
            if n.startswith(GROUP_PATH + "/") and n not in allowed:
                failures.append(f"{n}: unexpected file in the bundle")
        for entry, kind in expected.items():
            if entry not in names:
                failures.append(f"{entry}: missing from the bundle")
                continue
            data = zf.read(entry)
            if kind in ("pom", "marker"):
                check_pom(entry, data, failures)
                if kind == "marker":
                    check_marker(entry, data, version, failures)
            elif kind == "module":
                check_module(entry, data, failures)
            else:
                check_jar(entry, kind, data, failures)
            check_checksums(zf, entry, data, failures)
            check_signature(zf, entry, data, homedir, fingerprint, failures, workdir)
            status = "ok" if not any(f.startswith(entry + ":") for f in failures) else "FAILED"
            print(f"{status:6} {entry}")

    if failures:
        print("", file=sys.stderr)
        for f in failures:
            print(f"FAIL: {f}", file=sys.stderr)
        return 1
    print(f"PASS: {len(expected)} artifacts, all signed by {fingerprint}, checksums and POM fields verified")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
