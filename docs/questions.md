# Open questions

Each entry: question · blocks · recommendation. Answered questions move to `docs/decisions.md`.

## Q7 Nightly end-to-end repository
- **Status:** deferred by the maintainer (2026-10-25); revisit before Phase 3.
- **Blocks:** T51.
- **Question:** Which real repo can the nightly job publish to (it gets reset), and which secret?
- **Recommendation:** A throwaway public repo `ivangsa/git-maven-publisher-e2e` (resolved through `raw.githubusercontent.com`) and its own fine-grained PAT stored as `E2E_TOKEN` in this repo.

## Q12 Plugin Portal validation needs the maintainer's publishing key (T45)
- **Status:** open (2026-10-25). Raised by B18.
- **Blocks:** T45's "Done when" (`publishPlugins --validate-only` passes). Does not block other tasks.
- **Question:** `publishPlugins --validate-only` checks the configuration on the portal side, and that needs `GRADLE_PUBLISH_KEY`/`GRADLE_PUBLISH_SECRET`. The agents may not read or create secrets, so the check cannot run here. Local evidence: `validatePlugins` passes; on a release-version copy of the build (version `0.1.0`, scratch directory only), `publishPlugins --validate-only` gets past the SNAPSHOT check, the marker and the descriptor, and stops at "Missing publishing keys".
- **Recommendation:** The maintainer runs `./gradlew -p gradle-plugin publishPlugins --validate-only` once with his own key (environment variables only, never written to a file), at the first release commit, or accepts `validatePlugins` plus the release job's `publishPlugins` as the evidence. Then T45 is ticked.
