/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import java.util.List;

/**
 * T37: every lifecycle scenario of T36 runs twice with {@code --configuration-cache}. The first run stores the entry
 * and the second reuses it (asserted by the base class), and each run must satisfy the same guarantees.
 */
class ConfigurationCacheLifecycleTest extends AbstractLifecycleTest {

    @Override
    List<String> baseArguments() {
        return List.of("--configuration-cache");
    }

    @Override
    int runs() {
        return 2;
    }
}
