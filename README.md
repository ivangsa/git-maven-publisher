# git-maven-publisher

Turns a Git repository into a Maven snapshot repository, served as static files by whatever you choose (raw.githubusercontent.com, GitHub Pages, ...).

Status: early development (0.1.0-SNAPSHOT). No release yet. Design and plan live in `docs/`.

## Build

```sh
./mvnw verify
```

Requires JDK 17 or newer. The default build needs no Docker.

The project is one Maven module, `git-maven-publisher-maven-plugin` (packaging `maven-plugin`). The test harness and golden files live under `src/test`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
