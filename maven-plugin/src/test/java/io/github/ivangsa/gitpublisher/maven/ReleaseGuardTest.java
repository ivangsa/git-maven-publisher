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
