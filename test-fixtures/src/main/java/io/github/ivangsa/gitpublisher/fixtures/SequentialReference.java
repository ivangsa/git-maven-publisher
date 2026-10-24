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
package io.github.ivangsa.gitpublisher.fixtures;

import java.nio.file.Path;
import java.util.List;

/**
 * The primary oracle of docs/design.md section 9: a scripted list of publications deployed one after another by the
 * real Maven Resolver into a clean {@code file://} repository. The result is the tree a native, non-concurrent
 * deployment produces; the concurrent path under test must give the same tree after {@link TreeNormalizer}.
 */
public final class SequentialReference {

    /** One publication: a Java project (jar, pom, sources) or explicit parts. */
    public record Step(String groupId, String artifactId, String version, List<ResolverDeployer.Part> parts) {
        public static Step javaProject(String groupId, String artifactId, String version) {
            return new Step(groupId, artifactId, version, List.of());
        }

        public static Step of(String groupId, String artifactId, String version, ResolverDeployer.Part... parts) {
            return new Step(groupId, artifactId, version, List.of(parts));
        }

        /** {@code g:a:v}, as in {@code PublishInfo.coordinates}. */
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    private SequentialReference() {}

    /** Deploys the steps in order into {@code target} (created if needed) and returns it. */
    public static Path replay(Path target, List<Step> script, String... checksumAlgorithms) {
        try (ResolverDeployer deployer = new ResolverDeployer(target, checksumAlgorithms)) {
            for (Step step : script) {
                apply(deployer, step);
            }
        }
        return target;
    }

    /** Applies one step with an existing deployer, for publishers that deploy into their own work tree. */
    public static void apply(ResolverDeployer deployer, Step step) {
        if (step.parts().isEmpty()) {
            deployer.deployJavaProject(step.groupId(), step.artifactId(), step.version());
        } else {
            deployer.deploy(
                    step.groupId(),
                    step.artifactId(),
                    step.version(),
                    step.parts().toArray(new ResolverDeployer.Part[0]));
        }
    }
}
