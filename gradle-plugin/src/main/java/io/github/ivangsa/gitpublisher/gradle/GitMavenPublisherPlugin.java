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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.TaskProvider;

/**
 * Plugin id {@code io.github.ivangsa.git-maven-publisher}: a thin wrapper over {@code maven-publish} (design section 2).
 *
 * <p>Lifecycle (D-033, D-086): every {@code publish*PublicationToGitPublisherRepository} task depends on
 * {@code gitPublisherCheckout} and is finalized by the guarded {@code gitPublisherPush}.
 */
public class GitMavenPublisherPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "gitMavenPublisher";
    public static final String REPOSITORY_NAME = "gitPublisher";
    public static final String TOKEN_ENVIRONMENT_VARIABLE = "GIT_PUBLISHER_TOKEN";
    public static final String TOKEN_PROPERTY = "gitPublisherToken";
    public static final String CHECKOUT_TASK = "gitPublisherCheckout";
    public static final String PUSH_TASK = "gitPublisherPush";

    private static final String SERVICE_NAME = "gitPublisher";
    private static final String SIGNATURE_KEY = "io.github.ivangsa.gitpublisher.settingsSignature";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("maven-publish");
        GitMavenPublisherExtension extension =
                project.getExtensions().create(EXTENSION_NAME, GitMavenPublisherExtension.class);
        extension.getBranch().convention("gh-pages");
        extension.getNojekyll().convention(false);
        // No convention for the token: the environment variable and the Gradle property are resolved by the service when
        // it runs, never at configuration time, so a configuration cache entry holds no credential (D-087).
        extension.getUsername().convention("x-access-token");
        extension.getKeepSnapshots().convention(3);
        extension.getKeepVersions().convention(-1);
        extension.getAllowReleases().convention(false);
        extension.getMaxAttempts().convention(5);
        extension.getDryRun().convention(false);

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        File repositoryDirectory = repositoryDirectory(project);
        publishing.getRepositories().maven(repository -> {
            repository.setName(REPOSITORY_NAME);
            repository.setUrl(repositoryDirectory);
        });

        Provider<GitPublisherService> service = registerService(project, extension);
        TaskProvider<GitPublisherCheckoutTask> checkout =
                project.getTasks().register(CHECKOUT_TASK, GitPublisherCheckoutTask.class, task -> {
                    task.setGroup("publishing");
                    task.setDescription("Fetches the publish branch and prepares the local repository (once per build).");
                    task.getService().set(service);
                    task.usesService(service);
                });
        TaskProvider<GitPublisherPushTask> push =
                project.getTasks().register(PUSH_TASK, GitPublisherPushTask.class, task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Pushes the publication once, only if every participating publication task succeeded.");
                    task.getService().set(service);
                    task.usesService(service);
                    // Ordering is explicit; sharing the service does not order tasks (D-033). mustRunAfter ignores
                    // tasks that are not in the task graph, so listing every candidate is harmless.
                    task.mustRunAfter((Callable<Object>) () -> participantTasks(project));
                });

        project.getTasks().withType(PublishToMavenRepository.class).configureEach(task -> {
            if (!isParticipant(task)) {
                return;
            }
            task.dependsOn(checkout);
            task.finalizedBy(push);
            task.usesService(service);
            task.doLast(new RecordSuccess(service, task.getPath()));
        });

        project.getGradle().getTaskGraph().whenReady(graph -> {
            List<PublishToMavenRepository> participants = graph.getAllTasks().stream()
                    .filter(PublishToMavenRepository.class::isInstance)
                    .map(PublishToMavenRepository.class::cast)
                    .filter(GitMavenPublisherPlugin::isParticipant)
                    .collect(Collectors.toList());
            checkSettingsAgree(project, extension);
            if (!extension.getAllowReleases().get()) {
                for (PublishToMavenRepository task : participants) {
                    MavenPublication publication = task.getPublication();
                    if (!publication.getVersion().endsWith("-SNAPSHOT")) {
                        throw new GradleException("Publication " + coordinates(publication)
                                + " is a release, but this plugin publishes snapshots."
                                + " Set gitMavenPublisher.allowReleases = true to publish releases too.");
                    }
                }
            }
            List<String> paths =
                    participants.stream().map(Task::getPath).sorted().collect(Collectors.toList());
            List<String> coordinates = participants.stream()
                    .map(PublishToMavenRepository::getPublication)
                    .map(GitMavenPublisherPlugin::coordinates)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            push.configure(task -> {
                task.getParticipants().set(paths);
                task.getCoordinates().set(coordinates);
            });
        });
    }

    private static boolean isParticipant(PublishToMavenRepository task) {
        // By name: the repository is assigned after the task is created, so it cannot be read when tasks are configured.
        return task.getName().startsWith("publish")
                && task.getName().endsWith("PublicationToGitPublisherRepository");
    }

    private static String coordinates(MavenPublication publication) {
        return publication.getGroupId() + ":" + publication.getArtifactId() + ":" + publication.getVersion();
    }

    /** Every candidate participant of the build; only those in the task graph take part in ordering. */
    private static List<Task> participantTasks(Project project) {
        List<Task> tasks = new ArrayList<>();
        for (Project candidate : project.getRootProject().getAllprojects()) {
            for (PublishToMavenRepository task : candidate.getTasks().withType(PublishToMavenRepository.class)) {
                if (isParticipant(task)) {
                    tasks.add(task);
                }
            }
        }
        return tasks;
    }

    /**
     * One service serves the whole build and is configured from the first project that applies the plugin. A project
     * that applies it with different settings would be ignored silently, so the build fails before anything runs.
     */
    private static void checkSettingsAgree(Project project, GitMavenPublisherExtension extension) {
        String mine = String.join(
                "|",
                String.valueOf(extension.getRepository().getOrNull()),
                extension.getBranch().get(),
                String.valueOf(extension.getNojekyll().get()),
                extension.getUsername().get(),
                String.valueOf(extension.getKeepSnapshots().get()),
                String.valueOf(extension.getKeepVersions().get()),
                String.valueOf(extension.getAllowReleases().get()),
                String.valueOf(extension.getMaxAttempts().get()),
                String.valueOf(extension.getDryRun().get()));
        AtomicReference<String[]> reference = signatureReference(project);
        String[] first = new String[] {project.getPath(), mine};
        if (!reference.compareAndSet(null, first)) {
            String[] known = reference.get();
            if (!Objects.equals(known[1], mine)) {
                throw new GradleException("gitMavenPublisher settings of " + project.getDisplayName()
                        + " differ from those of project '" + known[0]
                        + "'. One publication serves the whole build, so every project must use the same repository,"
                        + " branch, nojekyll, username, keepSnapshots, keepVersions, allowReleases, maxAttempts"
                        + " and dryRun.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String[]> signatureReference(Project project) {
        ExtraPropertiesExtension extra = project.getGradle().getExtensions().getExtraProperties();
        synchronized (GitMavenPublisherPlugin.class) {
            if (!extra.has(SIGNATURE_KEY)) {
                extra.set(SIGNATURE_KEY, new AtomicReference<String[]>());
            }
            return (AtomicReference<String[]>) extra.get(SIGNATURE_KEY);
        }
    }

    private static Provider<GitPublisherService> registerService(
            Project project, GitMavenPublisherExtension extension) {
        return project.getGradle().getSharedServices().registerIfAbsent(SERVICE_NAME, GitPublisherService.class, spec -> {
            // Writers are limited to one: two publications never write metadata at the same time (design section 2).
            spec.getMaxParallelUsages().set(1);
            GitPublisherService.Parameters parameters = spec.getParameters();
            parameters.getRepository().set(extension.getRepository());
            parameters.getBranch().set(extension.getBranch());
            parameters.getNojekyll().set(extension.getNojekyll());
            parameters.getToken().set(extension.getToken());
            parameters.getUsername().set(extension.getUsername());
            parameters.getKeepSnapshots().set(extension.getKeepSnapshots());
            parameters.getKeepVersions().set(extension.getKeepVersions());
            parameters.getMaxAttempts().set(extension.getMaxAttempts());
            parameters.getDryRun().set(extension.getDryRun());
            parameters.getWorkDirectory().set(workDirectory(project));
            parameters.getSourceDirectory().set(project.getRootProject().getProjectDir());
        });
    }

    /** Records that a participant completed. A task that failed never reaches its last action. */
    static final class RecordSuccess implements Action<Task> {
        private final Provider<GitPublisherService> service;
        private final String taskPath;

        RecordSuccess(Provider<GitPublisherService> service, String taskPath) {
            this.service = service;
            this.taskPath = taskPath;
        }

        @Override
        public void execute(Task task) {
            service.get().recordSuccess(taskPath);
        }
    }

    /** {@code <rootBuildDir>/git-publisher}: contains {@code repo/} (served tree) and {@code git/} (object store). */
    static File workDirectory(Project project) {
        return project.getRootProject()
                .getLayout()
                .getBuildDirectory()
                .dir("git-publisher")
                .get()
                .getAsFile();
    }

    static File repositoryDirectory(Project project) {
        return new File(workDirectory(project), "repo");
    }
}
