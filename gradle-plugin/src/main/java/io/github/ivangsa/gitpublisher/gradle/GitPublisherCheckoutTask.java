/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Fetches the branch and materializes the served tree once, before any participant writes to it. */
@DisableCachingByDefault(because = "Talks to a Git remote and prepares shared state")
public abstract class GitPublisherCheckoutTask extends DefaultTask {

    @Internal
    public abstract Property<GitPublisherService> getService();

    @TaskAction
    public void checkout() {
        getService().get().checkout();
    }
}
