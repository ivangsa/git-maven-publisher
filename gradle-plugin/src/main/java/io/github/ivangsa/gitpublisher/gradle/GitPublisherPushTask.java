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
