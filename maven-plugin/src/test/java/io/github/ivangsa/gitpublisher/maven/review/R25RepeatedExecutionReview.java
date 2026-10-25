/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.maven.it.ItSupport;
import java.io.File;

/** Design section 2 guarantees success of every participating execution before push, including repeat bindings. */
public final class R25RepeatedExecutionReview {
    private R25RepeatedExecutionReview() {}

    public static void verify(File basedir) throws Exception {
        assertThat(ItSupport.log(basedir)).contains("unknown checksum algorithm 'INVALID'");
        ItSupport.assertNothingPushed(basedir);
    }
}
