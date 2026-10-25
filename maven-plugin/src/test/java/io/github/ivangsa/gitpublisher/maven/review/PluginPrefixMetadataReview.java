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
