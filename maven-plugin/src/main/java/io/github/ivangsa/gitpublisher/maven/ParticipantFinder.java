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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

/**
 * Finds the participants of the reactor transaction (design section 2, D-034): the projects of the session whose
 * effective model, active profiles included, binds this plugin's {@code deploy} goal.
 */
final class ParticipantFinder {

    private static final String GOAL = "deploy";

    private ParticipantFinder() {}

    static String id(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
    }

    /**
     * @param pluginKey {@code groupId:artifactId} of this plugin
     * @return the ids of the binding projects; when no project binds the goal (it was started from the command line)
     *     every project of the session, because Maven runs the goal in each of them
     */
    static Set<String> find(List<MavenProject> projects, String pluginKey) {
        Set<String> binding = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (MavenProject project : projects) {
            all.add(id(project));
            if (binds(project, pluginKey)) {
                binding.add(id(project));
            }
        }
        return binding.isEmpty() ? all : binding;
    }

    private static boolean binds(MavenProject project, String pluginKey) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!pluginKey.equals(plugin.getGroupId() + ":" + plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!"none".equals(execution.getPhase()) && execution.getGoals().contains(GOAL)) {
                    return true;
                }
            }
        }
        return false;
    }
}
