/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
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
