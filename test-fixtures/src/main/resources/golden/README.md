# Golden metadata fixtures

Real files from public Maven repositories, copied byte for byte. Used by `GoldenFixturesTest`
(parse with `maven-repository-metadata`, JSON for `.module`, XML for `.pom`). Paths mirror the repository layout.
Fetched on 2026-10-23 with `curl -L`.

| File (under `golden/`) | Source URL | Why |
| --- | --- | --- |
| `org/apache/commons/commons-lang3/maven-metadata.xml` | https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/maven-metadata.xml | A-level, many versions, Central |
| `org/apache/maven/plugins/maven-metadata.xml` | https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-metadata.xml | G-level (`<plugins>`) |
| `org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml` | https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/maven-metadata.xml | A-level, KMP library (`release` = `0.8.0-0.6.x-compat`, as Central declares it) |
| `org/jetbrains/kotlinx/kotlinx-datetime/0.8.0-0.6.x-compat/kotlinx-datetime-0.8.0-0.6.x-compat.{module,pom}` | https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-datetime/0.8.0-0.6.x-compat/ | KMP root module and POM |
| `org/jetbrains/kotlinx/kotlinx-datetime-{jvm,js,linuxx64}/0.8.0-0.6.x-compat/*.{module,pom}` | https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/ | KMP target artifactIds |
| `org/apache/maven/resolver/maven-resolver-api/2.0.25-SNAPSHOT/maven-metadata.xml` | https://repository.apache.org/content/repositories/snapshots/org/apache/maven/resolver/maven-resolver-api/2.0.25-SNAPSHOT/maven-metadata.xml | V-level of a current `-SNAPSHOT` (latest on that snapshot repo) |
