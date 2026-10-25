/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import java.util.List;

/** T36: the lifecycle scenarios without the configuration cache. */
class LifecycleTest extends AbstractLifecycleTest {

    @Override
    List<String> baseArguments() {
        return List.of("--no-configuration-cache");
    }

    @Override
    int runs() {
        return 1;
    }
}
