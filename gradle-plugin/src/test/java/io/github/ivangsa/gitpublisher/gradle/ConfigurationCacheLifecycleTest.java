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
