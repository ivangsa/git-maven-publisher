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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

/**
 * What a project hands to the native deploy: the main artifact, the POM and every attached artifact (sources, javadoc,
 * classifiers, {@code .asc} signatures), the same set {@code maven-deploy-plugin} deploys.
 */
final class ProjectArtifacts {

    private ProjectArtifacts() {}

    static List<Artifact> collect(MavenProject project) throws MojoExecutionException {
        String id = project.getGroupId() + ":" + project.getArtifactId();
        List<Artifact> artifacts = new ArrayList<>();
        if (!"pom".equals(project.getPackaging())) {
            org.apache.maven.artifact.Artifact main = project.getArtifact();
            File file = main.getFile();
            if (file == null || !file.isFile()) {
                throw new MojoExecutionException(id + " has no packaged main artifact to publish. Run the package"
                        + " phase before this goal (for example mvn deploy), or use packaging pom.");
            }
            artifacts.add(new DefaultArtifact(
                    project.getGroupId(),
                    project.getArtifactId(),
                    main.getClassifier(),
                    extension(main),
                    project.getVersion(),
                    null,
                    file));
        }
        artifacts.add(new DefaultArtifact(
                project.getGroupId(), project.getArtifactId(), null, "pom", project.getVersion(), null, project.getFile()));
        for (org.apache.maven.artifact.Artifact attached : project.getAttachedArtifacts()) {
            File file = attached.getFile();
            if (file == null || !file.isFile()) {
                throw new MojoExecutionException(id + " has an attached artifact without a file: " + attached.getType()
                        + (attached.hasClassifier() ? " (" + attached.getClassifier() + ")" : ""));
            }
            artifacts.add(new DefaultArtifact(
                    project.getGroupId(),
                    project.getArtifactId(),
                    attached.getClassifier(),
                    extension(attached),
                    project.getVersion(),
                    null,
                    file));
        }
        return artifacts;
    }

    private static String extension(org.apache.maven.artifact.Artifact artifact) {
        if (artifact.getArtifactHandler() != null && artifact.getArtifactHandler().getExtension() != null) {
            return artifact.getArtifactHandler().getExtension();
        }
        return artifact.getType();
    }
}
