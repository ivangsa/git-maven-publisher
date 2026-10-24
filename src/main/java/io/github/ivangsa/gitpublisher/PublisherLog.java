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

/** Where the core reports progress. Adapters bridge it to the Maven Log or the Gradle Logger. Never receives credentials. */
public interface PublisherLog {

    void info(String message);

    void warn(String message);

    void debug(String message);

    /** A log that drops everything. */
    static PublisherLog silent() {
        return new PublisherLog() {
            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {}

            @Override
            public void debug(String message) {}
        };
    }
}
