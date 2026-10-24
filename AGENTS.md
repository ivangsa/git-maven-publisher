# git-maven-publisher

Turns a Git repository into a Maven snapshot repository served by GitHub Pages.
Read before working: `docs/brief.md` (why), `docs/design.md` (contracts), `docs/tasks.md` (what next),
`docs/decisions.md` (past choices), `docs/questions.md` (open questions).

## Working agreement

- Work one task at a time, in order, from docs/tasks.md.
- Write or update tests first. A task is done only when the full build passes.
- In the same commit as the code: tick the task and update the status line.
- Commit message: `T07: <task title>`. Push after every task.
- Never tick a task with failing or skipped tests. Never weaken a test to make it pass.
- If a task needs my decision, write it in docs/questions.md, mark the task `[?]`,
  and continue with the next unblocked task. Do not stop the session for it.
- Record every non-trivial technical choice in docs/decisions.md.
- If the design turns out wrong, update docs/design.md and explain in decisions.md.
  Do not silently diverge from the design.
- Java 17. Apache 2.0. groupId io.github.ivangsa.
- Never log credentials, never write them into files or Git config.

## Reviews

- The `adversarial-reviewer` agent runs after a phase or every few tasks. Its instructions live in
  `docs/agents/adversarial-reviewer.md`; tool-specific wrappers (`.codex/agents/adversarial-reviewer.toml`, `.claude/agents/adversarial-reviewer.md`) only point there.
  It writes `docs/reviews/`, marks failed tasks `[!]`, adds `R01`... tasks and leaves failing tests under `src/test/.../review/`.
- Before the next T task, finish every `[!]` task and every open R task, in that order.
- A failing review test is the specification for its R task: make it pass by fixing production code.
  Edit a review test only if it is provably wrong, and record why in docs/decisions.md.
- The full build passes again only when all review tests pass.

## Orchestration (main session only)

Agents: `implementer`, `mechanic`, `adversarial-reviewer`. Instructions live in `docs/agents/<name>.md`;
`.claude/agents/<name>.md` and `.codex/agents/<name>.toml` are short wrappers that only point there.

- The main session does not write production code. It plans, dispatches, verifies and reviews.
- Dispatch one batch at a time with this prompt:
  "Batch <id>. Tasks: <ids>. Context pack: AGENTS.md, docs/context/<x>.md, <files>.
   Follow your agent instructions."
  Pass only file paths, never paste file contents into the prompt.
- After each batch:
  1. Run the full build yourself.
  2. Check `git log --oneline` and the ticks in docs/tasks.md against the report.
  3. Read diffs only if the build fails or the report says PARTIAL or BLOCKED.
  4. Update the batch status and the status line in docs/tasks.md.
- Escalation:
  - mechanic marks a task needs-implementer: move it to the next implementer batch.
  - implementer fails a batch: dispatch it once more with the failure in the prompt.
  - fails again: dispatch with the model overridden to opus. If that fails, ask me.
- Run the adversarial-reviewer subagent after every batch marked "Review" in docs/tasks.md:
  the end of each phase, after merge + retention, after lease + retry + replay, after Phase 3 retention.
  One review covers the batches since the previous review; do not review each critical batch alone.
  Review findings become new batches before the next phase starts.
- Batches are strictly sequential. Exactly one subagent (implementer, mechanic or reviewer) runs at a time.
  Subagents run in the background: after dispatching, wait for its completion notification and finish the
  "After each batch" checks before dispatching anything else. Never run batches in parallel (commit dates are
  computed from the previous commit, and later batches depend on earlier ones).
- Stop when the requested phase is done, when every remaining batch is blocked,
  or when docs/questions.md has a question that blocks the next batch.
  Then report: batches done, build status, open questions, review verdicts.

## Commit dates

Every commit sets `GIT_AUTHOR_DATE` and `GIT_COMMITTER_DATE` inside the implementation window:
2026-10-24 and 2026-10-25, 09:00–12:30 or 20:00–23:00, timezone +0200.
Each commit is 4–8 minutes after the previous one (`git log -1 --format=%aI`). When a window is full,
continue at the start of the next window (24 morning → 24 evening → 25 morning → 25 evening).
Prefer fitting everything into the 24 October morning.

```bash
d="2026-10-24T09:05:00+02:00"; GIT_AUTHOR_DATE="$d" GIT_COMMITTER_DATE="$d" git commit -m "T01: Build skeleton"
```

## Build

- Full build: `./mvnw verify` (core, test-fixtures, maven-plugin), then `./gradlew -p gradle-plugin build` once the Gradle build exists (Phase 2).
- The default build needs no Docker. `./mvnw verify -Pe2e` adds the Reposilite E2E tests (Testcontainers, needs Docker); CI always runs it.
- On Windows, run commands through Git Bash.

## Test oracles

Tests must rely on oracles we do not control: real Maven Resolver deploys, the sequential reference (native deploys one after another vs our concurrent path),
golden metadata from Maven Central, clean Maven/Gradle consumers over a local HTTP server, and
concurrent publishers against one bare repo. See `docs/design.md` §9. Do not replace an oracle with a fake.
