![Selective Test Runner](docs/images/logo.png)

A Maven plugin that tracks which production classes each test touches at the bytecode level, then uses Git to detect what changed and runs only the affected tests. No annotations, no config changes to your tests.

## Why?

Large Maven projects spend a lot of time re-running tests when only a few source files changed. This plugin addresses that:

- Instruments every method entry via a Java agent, catching dependencies that static analysis misses (reflection, polymorphism, lambdas)
- Diffs your working tree against the last commit, last tag, or last full run to find changed files
- Works with JUnit 4, JUnit 5, and TestNG without requiring annotations, base classes, or test rewrites
- Falls back to a full run when something is uncertain (missing coverage data, git errors, etc.)
- Supports multi-module reactors with shared coverage maps and concurrent writes under `mvn -T`

## Quick start

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>io.github.mirkoalicastro</groupId>
  <artifactId>test-impact-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions>
    <execution>
      <goals>
        <goal>collect</goal>
        <goal>select</goal>
        <goal>report</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Then run your build as usual:

```bash
mvn verify
```

On the first run all tests execute and coverage is recorded. On subsequent runs only tests affected by your changes are selected.

## How it works

1. **Collect** (`process-test-classes`): attaches a Java agent to Surefire's forked JVM. The agent instruments method entries in your production and test classes using ASM.
2. **Select** (`process-test-classes`): uses JGit to detect changed `.java` files, resolves them to compiled classes (including inner classes), looks up the coverage map to find which tests touch those classes, and sets Surefire's `test` filter.
3. **Report** (`verify`): merges the per-module coverage dump into the shared coverage map (JSON) and prints a summary.

## Configuration

```xml
<configuration>
  <!-- How to detect changes: lastCommit | lastTag | lastFullRun -->
  <baseline>lastCommit</baseline>

  <!-- Force a full run every N builds (0 = never) -->
  <fullRunInterval>50</fullRunInterval>

  <!-- Package prefixes to instrument (dot notation, comma-separated) -->
  <!-- Auto-detected from source roots if omitted -->
  <includes>com.mycompany.myapp</includes>

  <!-- Package prefixes to exclude from instrumentation -->
  <excludes>com.mycompany.myapp.generated</excludes>

  <!-- Fail the build if no tests are selected instead of running all -->
  <failOnEmptySelection>false</failOnEmptySelection>
</configuration>
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `baseline` | `lastCommit` | Git baseline for change detection. `lastCommit` = HEAD vs HEAD~1, `lastTag` = HEAD vs most recent tag, `lastFullRun` = working tree vs the commit recorded in the coverage map. |
| `fullRunInterval` | `50` | Force a full run every N incremental builds. Set to `0` to disable. |
| `includes` | *(auto)* | Comma-separated package prefixes to instrument. Auto-detected from source roots if omitted. |
| `excludes` | *(empty)* | Comma-separated package prefixes to exclude from instrumentation. |
| `failOnEmptySelection` | `false` | If `true`, fail the build when no tests match the changed classes instead of falling back to a full run. |
| `coverageMapPath` | `<reactor-root>/target/.test-impact/coverage.json` | Override the coverage map location. |

## Baseline strategies

| Strategy | Use case | How it works |
|----------|----------|--------------|
| `lastCommit` | CI / pull requests | Diffs HEAD against HEAD~1. Each push re-evaluates. |
| `lastTag` | Release pipelines | Diffs HEAD against the most recent Git tag by timestamp. |
| `lastFullRun` | Local development | Diffs the working tree against the commit hash recorded in the coverage map from the last full test run. |

## Multi-module reactors

The plugin supports multi-module Maven projects, including parallel builds (`mvn -T`):

- Shared coverage map at the reactor root (`target/.test-impact/coverage.json`)
- Each module's Surefire JVM writes its own binary dump
- `report` uses a JVM monitor + OS-level `FileLock` for concurrent writes
- `select` uses `MavenSession.getProjectDependencyGraph()` to only consider changes in upstream modules

## Fallback behaviour

The plugin falls back to a full run when:

- No coverage map exists (first run)
- Coverage map version doesn't match the plugin version
- Coverage map is older than `fullRunInterval` builds
- Git change detection fails
- The change set is empty
- No tests intersect with the changed classes

The worst case is running all tests, same as without the plugin.

## Supported test frameworks

The agent detects test methods by annotation:

| Framework | Annotations |
|-----------|------------|
| JUnit 5 | `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate` |
| JUnit 4 | `@Test` |
| TestNG | `@Test` |

## Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `test-impact:collect` | `process-test-classes` | Attaches the Java agent to Surefire's `argLine` |
| `test-impact:select` | `process-test-classes` | Detects changed sources and sets Surefire's `test` filter |
| `test-impact:report` | `verify` | Merges coverage dump into the shared map and generates a report |
| `test-impact:invalidate` | *(manual)* | Clears the coverage map and all per-module state |

To reset the coverage map and force a full rebuild:

```bash
mvn test-impact:invalidate
```

## Requirements

- Java 11+
- Maven 3.9+
- Git repository

## Building from source

```bash
git clone https://github.com/mirkoalicastro/test-impact-maven-plugin.git
cd test-impact-maven-plugin
mvn clean verify
```

This produces three artifacts:
- `selective-test-runner-core-1.0.0-SNAPSHOT.jar`: the core library (agent, change detection, impact resolution, coverage persistence)
- `selective-test-runner-core-1.0.0-SNAPSHOT-agent.jar`: the shaded agent JAR (ASM relocated)
- `test-impact-maven-plugin-1.0.0-SNAPSHOT.jar`: the Maven plugin

## Releasing

Releases are published to Maven Central automatically via GitHub Actions when a version tag is pushed.

1. Make sure all changes are merged to `main` and the build is green
2. Tag the release:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. The deployment workflow sets the version from the tag (stripping the `v` prefix), signs the artifacts with GPG, and publishes to Maven Central
4. After the release, bump the version on `main` for the next development cycle:
   ```bash
   mvn versions:set -DnextSnapshot=true -DgenerateBackupPoms=false
   git commit -am "Bump version to next SNAPSHOT"
   git push
   ```
   This automatically increments the patch version and appends `-SNAPSHOT` (e.g., `1.0.0-SNAPSHOT` becomes `1.0.1-SNAPSHOT`).

The deployment workflow requires these GitHub secrets:

| Secret | Description |
|--------|-------------|
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |
| `GPG_PRIVATE_KEY` | Armored GPG private key (`gpg --armor --export-secret-keys <keyid>`) |
| `GPG_PASSPHRASE` | Passphrase for the GPG key |

## Contributing

1. Fork the repository and create a feature branch from `main`
2. Build and test locally with `mvn clean verify`
3. One feature or fix per pull request
4. Add tests for new functionality
5. Open a pull request against `main`

### Project structure

```
selective-test-runner-core/
  agent/          Java agent, coverage recording
  change/         Git change detection, source-to-class resolution
  store/          Coverage map persistence (JSON)
  resolve/        Impact analysis, test selection
  report/         Report generation
  common/         Shared utilities

test-impact-maven-plugin/
  mojo/           Maven goals (collect, select, report, invalidate)
  common/         Maven-specific utilities
```

## License

See the [LICENSE](LICENSE) file for details.
