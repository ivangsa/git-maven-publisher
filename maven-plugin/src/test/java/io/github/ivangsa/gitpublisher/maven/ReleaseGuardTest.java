/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;

class ReleaseGuardTest {

    @Test
    void snapshotVersionsPass() {
        assertThatCode(() -> ReleaseGuard.check("g:a", "1.0.0-SNAPSHOT", false)).doesNotThrowAnyException();
        assertThatCode(() -> ReleaseGuard.check("g:a", "2.1-beta-1-SNAPSHOT", false)).doesNotThrowAnyException();
    }

    @Test
    void releaseVersionsFailFastNamingTheProjectAndTheSwitch() {
        assertThatThrownBy(() -> ReleaseGuard.check("org.acme:lib", "1.0.0", false))
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("org.acme:lib")
                .hasMessageContaining("1.0.0")
                .hasMessageContaining("git-publisher.allowReleases");
    }

    @Test
    void aSnapshotLookalikeIsStillARelease() {
        assertThatThrownBy(() -> ReleaseGuard.check("g:a", "1.0-snapshot", false)).isInstanceOf(MojoFailureException.class);
        assertThatThrownBy(() -> ReleaseGuard.check("g:a", "1.0-SNAPSHOT-1", false)).isInstanceOf(MojoFailureException.class);
    }

    @Test
    void allowReleasesLetsReleasesThrough() {
        assertThatCode(() -> ReleaseGuard.check("g:a", "1.0.0", true)).doesNotThrowAnyException();
    }
}
