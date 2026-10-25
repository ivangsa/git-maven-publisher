/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import java.util.ArrayList;
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
     * The identity of one execution: the project id, qualified by the execution id when the project binds the goal in
     * more than one execution (every one of them is a participant that must succeed before the push, D-034).
     */
    static String id(MavenProject project, String executionId, String pluginKey) {
        return bindingCount(project, pluginKey) > 1 ? id(project) + "@" + executionId : id(project);
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
            int count = bindingCount(project, pluginKey);
            if (count == 1) {
                binding.add(id(project));
            } else if (count > 1) {
                for (String execution : bindingExecutions(project, pluginKey)) {
                    binding.add(id(project) + "@" + execution);
                }
            }
        }
        return binding.isEmpty() ? all : binding;
    }

    private static int bindingCount(MavenProject project, String pluginKey) {
        return bindingExecutions(project, pluginKey).size();
    }

    private static List<String> bindingExecutions(MavenProject project, String pluginKey) {
        List<String> executions = new ArrayList<>();
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!pluginKey.equals(plugin.getGroupId() + ":" + plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!"none".equals(execution.getPhase()) && execution.getGoals().contains(GOAL)) {
                    executions.add(execution.getId());
                }
            }
        }
        return executions;
    }
}
