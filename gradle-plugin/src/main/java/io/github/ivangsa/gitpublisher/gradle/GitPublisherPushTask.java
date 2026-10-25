/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Guarded push (D-033, D-086). It is a finalizer of every participant so that it is part of the graph whenever a
 * participant is, but it pushes only if the service recorded success for every participant: Gradle runs finalizers
 * after a failure, so the guard, not the finalizer, decides.
 */
@DisableCachingByDefault(because = "Talks to a Git remote")
public abstract class GitPublisherPushTask extends DefaultTask {

    @Internal
    public abstract Property<GitPublisherService> getService();

    /** Paths of the {@code publish*PublicationToGitPublisherRepository} tasks in this invocation's task graph. */
    @Input
    public abstract ListProperty<String> getParticipants();

    /** {@code g:a:v} of the participating publications. */
    @Input
    public abstract ListProperty<String> getCoordinates();

    @TaskAction
    public void push() {
        GitPublisherService service = getService().get();
        try {
            service.publishIfComplete(getParticipants().get(), getCoordinates().get());
        } finally {
            service.release();
        }
    }
}
