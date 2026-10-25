/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
    void everyBindingExecutionOfOneProjectIsAParticipant() {
        Plugin plugin = ours(null, "deploy");
        PluginExecution second = new PluginExecution();
        second.setId("second");
        second.setGoals(List.of("deploy"));
        plugin.addExecution(second);
        MavenProject repeated = project("r", plugin);

        assertThat(ParticipantFinder.find(List.of(repeated), G + ":" + A))
                .containsExactlyInAnyOrder("acme:r:1.0-SNAPSHOT@publish", "acme:r:1.0-SNAPSHOT@second");
        assertThat(ParticipantFinder.id(repeated, "second", G + ":" + A)).isEqualTo("acme:r:1.0-SNAPSHOT@second");
        assertThat(ParticipantFinder.id(project("a", ours(null, "deploy")), "publish", G + ":" + A))
                .isEqualTo("acme:a:1.0-SNAPSHOT");
    }

    @Test
    void theIdentityIsGroupArtifactVersion() {
        assertThat(ParticipantFinder.id(project("a"))).isEqualTo("acme:a:1.0-SNAPSHOT");
    }
}
