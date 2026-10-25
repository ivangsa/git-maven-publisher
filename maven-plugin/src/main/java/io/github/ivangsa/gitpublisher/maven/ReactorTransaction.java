/*
 * Copyright (c) 2026 ZenWave's Authors and Contributors
 *
 * Licensed under the MIT License; see the LICENSE file in the project root.
 */
package io.github.ivangsa.gitpublisher.maven;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.eclipse.aether.artifact.Artifact;

/**
 * The reactor transaction of design section 2 (D-034): the set of participants of one Maven session and the entries
 * they check in. Completion is a count over the participant set, not reactor order, so it is correct under {@code -T}:
 * the participant whose check-in completes the set receives every entry and runs the one publication. A participant
 * that fails never checks in, so the set never completes and nothing is pushed.
 */
final class ReactorTransaction {

    private static final String SESSION_KEY = ReactorTransaction.class.getName();

    /**
     * What one participant contributes: its project, its execution id when the project binds the goal more than once
     * (empty otherwise) and (unless skipped) its settings.
     */
    record Entry(
            String project,
            String execution,
            boolean skipped,
            PublisherParameters parameters,
            List<Artifact> artifacts) {

        Entry(String project, boolean skipped, PublisherParameters parameters, List<Artifact> artifacts) {
            this(project, "", skipped, parameters, artifacts);
        }

        Entry(String project, boolean skipped, PublisherParameters parameters) {
            this(project, "", skipped, parameters, List.of());
        }

        /** The participant identity: the project, qualified by the execution id for a repeated binding. */
        String key() {
            return execution.isEmpty() ? project : project + "@" + execution;
        }
    }

    private final Set<String> participants;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean completed;

    ReactorTransaction(Set<String> participants) {
        this.participants = Set.copyOf(participants);
    }

    /** The transaction of this session, created on first use (the session data is shared by {@code -T} threads). */
    static ReactorTransaction of(MavenSession session, String pluginKey) {
        return (ReactorTransaction) session.getRepositorySession()
                .getData()
                .computeIfAbsent(
                        SESSION_KEY,
                        () -> new ReactorTransaction(ParticipantFinder.find(session.getProjects(), pluginKey)));
    }

    /**
     * Records the entry. Returns every entry when this check-in completes the set (exactly once per transaction), empty
     * otherwise.
     *
     * @throws MojoFailureException when the project is not a participant or its settings differ from those of a
     *     participant that checked in earlier (before any checkout)
     */
    synchronized Optional<List<Entry>> checkIn(Entry entry) throws MojoFailureException {
        if (!participants.contains(entry.key())) {
            throw new MojoFailureException(entry.key()
                    + " runs git-maven-publisher:deploy but its effective model does not bind the goal, so it is not"
                    + " in the participant set of this build. Bind the goal in every module that should publish.");
        }
        if (completed) {
            return Optional.empty();
        }
        if (!entry.skipped()) {
            for (Entry earlier : entries.values()) {
                if (earlier.skipped() || earlier.key().equals(entry.key())) {
                    continue;
                }
                List<String> differing = entry.parameters().differencesFrom(earlier.parameters());
                if (!differing.isEmpty()) {
                    throw new MojoFailureException("git-publisher settings of " + entry.project() + " differ from those of "
                            + earlier.project() + ": " + String.join(", ", differing)
                            + ". Every participant of the reactor must publish with the same settings (skip excepted);"
                            + " nothing was checked out or pushed.");
                }
                break;
            }
        }
        entries.put(entry.key(), entry);
        if (entries.keySet().containsAll(participants)) {
            completed = true;
            return Optional.of(List.copyOf(entries.values()));
        }
        return Optional.empty();
    }
}
