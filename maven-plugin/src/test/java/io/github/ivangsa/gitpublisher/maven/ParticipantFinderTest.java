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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class ParticipantFinderTest {

    private static final String G = "io.github.ivangsa";
    private static final String A = "git-maven-publisher-maven-plugin";

    private static MavenProject project(String artifactId, Plugin... plugins) {
        Model model = new Model();
        model.setGroupId("acme");
        model.setArtifactId(artifactId);
        model.setVersion("1.0-SNAPSHOT");
        Build build = new Build();
        for (Plugin plugin : plugins) {
            build.addPlugin(plugin);
        }
        model.setBuild(build);
        return new MavenProject(model);
    }

    private static Plugin ours(String phase, String... goals) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(G);
        plugin.setArtifactId(A);
        PluginExecution execution = new PluginExecution();
        execution.setId("publish");
        execution.setPhase(phase);
        execution.setGoals(List.of(goals));
        plugin.addExecution(execution);
        return plugin;
    }

    private static Plugin other() {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-deploy-plugin");
        PluginExecution execution = new PluginExecution();
        execution.setGoals(List.of("deploy"));
        plugin.addExecution(execution);
        return plugin;
    }

    @Test
    void projectsThatBindTheDeployGoalAreParticipants() {
        MavenProject a = project("a", ours(null, "deploy"));
        MavenProject pom = project("parent");
        MavenProject b = project("b", ours("deploy", "deploy"), other());

        assertThat(ParticipantFinder.find(List.of(pom, a, b), G + ":" + A))
                .containsExactlyInAnyOrder("acme:a:1.0-SNAPSHOT", "acme:b:1.0-SNAPSHOT");
    }

    @Test
    void anotherGoalOrAnotherPluginOrADisabledPhaseDoesNotBind() {
        MavenProject sameOtherGoal = project("x", ours(null, "help"));
        MavenProject disabled = project("y", ours("none", "deploy"));
        MavenProject deployPlugin = project("z", other());
        MavenProject binding = project("w", ours(null, "deploy"));

        assertThat(ParticipantFinder.find(List.of(sameOtherGoal, disabled, deployPlugin, binding), G + ":" + A))
                .containsExactly("acme:w:1.0-SNAPSHOT");
    }

    @Test
    void whenNothingBindsTheGoalEveryProjectOfTheSessionParticipates() {
        MavenProject a = project("a");
        MavenProject b = project("b");

        assertThat(ParticipantFinder.find(List.of(a, b), G + ":" + A))
                .containsExactlyInAnyOrder("acme:a:1.0-SNAPSHOT", "acme:b:1.0-SNAPSHOT");
    }

    @Test
    void theIdentityIsGroupArtifactVersion() {
        assertThat(ParticipantFinder.id(project("a"))).isEqualTo("acme:a:1.0-SNAPSHOT");
    }
}
