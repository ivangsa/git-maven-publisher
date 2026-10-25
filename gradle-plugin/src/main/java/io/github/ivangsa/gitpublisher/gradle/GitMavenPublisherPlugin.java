/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.Describable;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
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

    private static final Logger LOGGER = Logging.getLogger(GitMavenPublisherPlugin.class);
    private static final String RECORDED_KEY = "io.github.ivangsa.gitpublisher.recordedParticipants";
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
        });

        project.getGradle().getTaskGraph().whenReady(graph -> {
            List<PublishToMavenRepository> participants = graph.getAllTasks().stream()
                    .filter(PublishToMavenRepository.class::isInstance)
                    .map(PublishToMavenRepository.class::cast)
                    .filter(GitMavenPublisherPlugin::isParticipant)
                    .collect(Collectors.toList());
            checkSettingsAgree(project, extension);
            // One record per participant, however many projects applied the plugin (each runs this callback). It is added
            // after every action known now, under a name unique to this build, and remembers how many actions the task
            // then has. When it runs it records only if Gradle itself runs it, unwrapped, as the last action of the task
            // with that count; otherwise the participant stays unrecorded and the push is refused (R26, R32, R33, D-104).
            Set<String> recorded = recordedParticipants(project);
            for (PublishToMavenRepository task : participants) {
                if (recorded.add(task.getPath())) {
                    int expected = task.getActions().size() + 1;
                    RecordSuccess record = new RecordSuccess(
                            service, task.getPath(), expected, RecordSuccess.name(task.getPath()));
                    task.doLast(record.getDisplayName(), record);
                }
            }
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
                String.valueOf(extension.getReleaseRepository().getOrNull()),
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
                        + " branch, nojekyll, username, keepSnapshots, keepVersions, releaseRepository, allowReleases, maxAttempts"
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
            parameters.getReleaseRepository().set(extension.getReleaseRepository());
            parameters.getMaxAttempts().set(extension.getMaxAttempts());
            parameters.getDryRun().set(extension.getDryRun());
            parameters.getWorkDirectory().set(workDirectory(project));
            parameters.getSourceDirectory().set(project.getRootProject().getProjectDir());
        });
    }

    /**
     * Records that a participant completed, fail closed (D-104). A task that failed or was skipped never reaches it. When
     * it runs it records only if all of these hold, otherwise it records nothing and the push is refused (D-098):
     *
     * <ul>
     *   <li>the task has exactly the number of actions it had when this record was added as its last action;
     *   <li>the last element of {@link Task#getActions()} carries this record's name (a {@link Describable} display name
     *       unique to this build), and no other element does;
     *   <li>every caller on the stack above {@link #execute} is Gradle or the JDK: no build script, plugin or wrapper
     *       code called it, so Gradle's executor runs it directly as a task action, and the only action that carries it
     *       is the last one.
     * </ul>
     *
     * Together: nothing of the task can run after the record, so the task completed when it records.
     */
    static final class RecordSuccess implements Action<Task>, Describable {
        private static final String NAME_PREFIX = "git-maven-publisher success record of ";
        private static final List<String> TRUSTED_CALLERS = List.of("org.gradle.", "java.", "jdk.", "sun.");

        private final Provider<GitPublisherService> service;
        private final String taskPath;
        private final int expectedActions;
        private final String name;

        RecordSuccess(Provider<GitPublisherService> service, String taskPath, int expectedActions, String name) {
            this.service = service;
            this.taskPath = taskPath;
            this.expectedActions = expectedActions;
            this.name = name;
        }

        /** Unique to this build: a later callback cannot have known it when its own action was created. */
        static String name(String taskPath) {
            return NAME_PREFIX + taskPath + " " + UUID.randomUUID();
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public void execute(Task task) {
            String problem = problem(task.getActions());
            if (problem != null) {
                LOGGER.warn("git-maven-publisher: " + taskPath + " is not recorded as successful because " + problem
                        + ". Its outcome is not known, so nothing will be pushed."
                        + " Do not add, remove, replace or wrap actions of publication tasks after the task graph is ready.");
                return;
            }
            service.get().recordSuccess(taskPath);
        }

        private String problem(List<? extends Action<? super Task>> actions) {
            int actual = actions.size();
            if (actual != expectedActions) {
                return "it has " + actual + " actions instead of " + expectedActions
                        + " (an action was added or removed after the publication record)";
            }
            int carriers = 0;
            for (Action<? super Task> action : actions) {
                if (carriesThisRecord(action)) {
                    carriers++;
                }
            }
            if (carriers != 1 || !carriesThisRecord(actions.get(actual - 1))) {
                return "the publication record is not its last action (the action list was changed after the task graph"
                        + " was ready)";
            }
            String caller = untrustedCaller();
            if (caller != null) {
                return "the publication record was called by " + caller + ", not by Gradle (it was wrapped or called"
                        + " from another action)";
            }
            return null;
        }

        private boolean carriesThisRecord(Action<? super Task> action) {
            if (!(action instanceof Describable)) {
                return false;
            }
            String displayName = ((Describable) action).getDisplayName();
            return name.equals(displayName) || ("Execute " + name).equals(displayName);
        }

        /** The first stack frame above {@link #execute} whose class is not Gradle's or the JDK's, or null. */
        private static String untrustedCaller() {
            String self = RecordSuccess.class.getName();
            return StackWalker.getInstance().walk(frames -> frames
                    .dropWhile(frame -> !(frame.getClassName().equals(self) && frame.getMethodName().equals("execute")))
                    .dropWhile(frame -> frame.getClassName().equals(self))
                    .map(StackWalker.StackFrame::getClassName)
                    .filter(className -> TRUSTED_CALLERS.stream().noneMatch(className::startsWith))
                    .findFirst()
                    .orElse(null));
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> recordedParticipants(Project project) {
        ExtraPropertiesExtension extra = project.getGradle().getExtensions().getExtraProperties();
        synchronized (GitMavenPublisherPlugin.class) {
            if (!extra.has(RECORDED_KEY)) {
                extra.set(RECORDED_KEY, ConcurrentHashMap.newKeySet());
            }
            return (Set<String>) extra.get(RECORDED_KEY);
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
