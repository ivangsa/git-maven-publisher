/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ivangsa.gitpublisher.maven.it.ItSupport;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.shared.verifier.Verifier;

/** Invoker review regression: the same real Maven plugin build deployed natively and through Git. */
public final class PluginPrefixMetadataReview {
    private PluginPrefixMetadataReview() {}

    public static void verify(File basedir) throws Exception {
        Path root = basedir.toPath().toAbsolutePath();
        Path reference = root.resolveSibling("review-plugin-metadata-native");
        Path local = root.getParent().getParent().resolve("local-repo");
        Path settings = root.resolve("native-settings.xml");
        Files.writeString(settings, "<settings><localRepository>" + local + "</localRepository></settings>");
        Verifier verifier = new Verifier(root.toString(), settings.toString());
        verifier.setAutoclean(false); // preserve the Git publication whose tree we compare with native deploy
        verifier.setLocalRepo(local.toString());
        verifier.setLogFileName("native.log");
        verifier.setCliOptions(new ArrayList<>(List.of("-B", "-ntp", "-Dgit-publisher.skip=true",
                "-Dmaven.deploy.skip=false", "-DaltDeploymentRepository=native::" + reference.toUri())));
        verifier.executeGoal("deploy");
        verifier.verifyErrorFreeLog();

        Path groupMetadata = reference.resolve("it/reactor/maven-metadata.xml");
        assertThat(groupMetadata).as("native Maven deploy produces plugin prefix metadata").exists();
        assertThat(Files.readString(groupMetadata)).contains("<prefix>review</prefix>",
                "<artifactId>review-maven-plugin</artifactId>");
        try (var bare = ItSupport.bare(basedir)) {
            assertThat(bare.readTree(ItSupport.BRANCH))
                    .as("Git publication must transport the group metadata native Maven generated")
                    .containsKey("it/reactor/maven-metadata.xml");
            assertThat(bare.readTree(ItSupport.BRANCH).get("it/reactor/maven-metadata.xml"))
                    .isEqualTo(Files.readAllBytes(groupMetadata));
        }
    }
}
