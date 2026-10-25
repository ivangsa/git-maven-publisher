# Open questions

Each entry: question · blocks · recommendation. Answered questions move to `docs/decisions.md`.

## Q7 Nightly end-to-end repository
- **Status:** deferred by the maintainer (2026-10-25); revisit before Phase 3.
- **Blocks:** T51.
- **Question:** Which real repo can the nightly job publish to (it gets reset), and which secret?
- **Recommendation:** A throwaway public repo `ivangsa/git-maven-publisher-e2e` (resolved through `raw.githubusercontent.com`) and its own fine-grained PAT stored as `E2E_TOKEN` in this repo.
