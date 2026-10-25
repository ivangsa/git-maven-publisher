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
