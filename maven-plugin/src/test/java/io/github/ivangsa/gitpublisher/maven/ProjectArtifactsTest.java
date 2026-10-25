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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectArtifactsTest {

    @TempDir
    Path tmp;

    private MavenProject project(String packaging, String version) throws IOException {
        Model model = new Model();
        model.setGroupId("acme");
        model.setArtifactId("lib");
        model.setVersion(version);
        model.setPackaging(packaging);
        MavenProject project = new MavenProject(model);
        Path pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        project.setFile(pom.toFile());
        return project;
    }

    private org.apache.maven.artifact.Artifact mavenArtifact(
            MavenProject project, String type, String classifier, String file) throws IOException {
        DefaultArtifact artifact = new DefaultArtifact(
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion(),
                "compile",
                type,
                classifier,
                new DefaultArtifactHandler(type));
        if (file != null) {
            artifact.setFile(Files.writeString(tmp.resolve(file), "content of " + file).toFile());
        }
        return artifact;
    }

    @Test
    void aJarProjectDeploysTheMainArtifactAndThePom() throws Exception {
        MavenProject project = project("jar", "1.0-SNAPSHOT");
        project.setArtifact(mavenArtifact(project, "jar", null, "lib.jar"));

        List<Artifact> artifacts = ProjectArtifacts.collect(project);

        assertThat(artifacts)
                .extracting(a -> a.getExtension() + "|" + a.getClassifier() + "|" + a.getBaseVersion())
                .containsExactlyInAnyOrder("jar||1.0-SNAPSHOT", "pom||1.0-SNAPSHOT");
        assertThat(artifacts).allSatisfy(a -> {
            assertThat(a.getGroupId()).isEqualTo("acme");
            assertThat(a.getArtifactId()).isEqualTo("lib");
            assertThat(a.getFile()).isNotNull().exists();
        });
    }

    @Test
    void attachedArtifactsKeepTheirClassifierAndExtensionIncludingSignatures() throws Exception {
        MavenProject project = project("jar", "1.0-SNAPSHOT");
        project.setArtifact(mavenArtifact(project, "jar", null, "lib.jar"));
        project.addAttachedArtifact(mavenArtifact(project, "jar", "sources", "lib-sources.jar"));
        project.addAttachedArtifact(mavenArtifact(project, "jar", "javadoc", "lib-javadoc.jar"));
        project.addAttachedArtifact(mavenArtifact(project, "jar.asc", null, "lib.jar.asc"));
        project.addAttachedArtifact(mavenArtifact(project, "pom.asc", null, "lib.pom.asc"));
        project.addAttachedArtifact(mavenArtifact(project, "jar.asc", "sources", "lib-sources.jar.asc"));

        List<Artifact> artifacts = ProjectArtifacts.collect(project);

        assertThat(artifacts)
                .extracting(a -> a.getExtension() + "|" + a.getClassifier())
                .containsExactlyInAnyOrder(
                        "jar|",
                        "pom|",
                        "jar|sources",
                        "jar|javadoc",
                        "jar.asc|",
                        "pom.asc|",
                        "jar.asc|sources");
    }

    @Test
    void aPomProjectDeploysOnlyThePomEvenThoughItsMainArtifactIsThePomFile() throws Exception {
        MavenProject project = project("pom", "1.0-SNAPSHOT");
        project.setArtifact(mavenArtifact(project, "pom", null, "pom-main.xml"));

        List<Artifact> artifacts = ProjectArtifacts.collect(project);

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).getExtension()).isEqualTo("pom");
        assertThat(artifacts.get(0).getFile().toPath()).isEqualTo(tmp.resolve("pom.xml"));
    }

    @Test
    void aJarProjectWithoutAPackagedFileFailsWithAHint() throws Exception {
        MavenProject project = project("jar", "1.0-SNAPSHOT");
        project.setArtifact(mavenArtifact(project, "jar", null, null));

        assertThatThrownBy(() -> ProjectArtifacts.collect(project))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("acme:lib")
                .hasMessageContaining("package");
    }

    @Test
    void anAttachedArtifactWithoutAFileFailsNamingIt() throws Exception {
        MavenProject project = project("jar", "1.0-SNAPSHOT");
        project.setArtifact(mavenArtifact(project, "jar", null, "lib.jar"));
        project.addAttachedArtifact(mavenArtifact(project, "jar", "sources", null));

        assertThatThrownBy(() -> ProjectArtifacts.collect(project))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("sources");
    }
}
