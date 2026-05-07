![Selective Test Runner](docs/images/logo.png)

A Maven plugin that tracks which production classes each test touches at the bytecode level, then uses Git to detect what changed and runs only the affected tests. Zero annotations. Zero config changes to your tests. Just add the plugin and watch your feedback loop shrink.

## Why?

Large Maven projects waste minutes (or hours) re-running thousands of tests when only a handful of source files changed. This plugin fixes that:

- **Bytecode-level precision**: instruments every method entry via a Java agent, so it catches dependencies that static analysis misses (reflection, polymorphism, lambdas).
- **Git-aware**: diffs your working tree against the last commit, last tag, or last full run to find changed files.
- **Zero test changes**: works with JUnit 4, JUnit 5, and TestNG out of the box. No annotations, no base classes, no test rewrites.
- **Safe by default**: when in doubt, runs everything. Missing coverage data? Full run. Git error? Full run. The plugin never silently skips tests.
- **Multi-module ready**: supports reactors with shared coverage maps and concurrent-safe writes under `mvn -T`.

## Quick start

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>io.github.mirkoalicastro</groupId>
  <artifactId>test-impact-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
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

**First run:** all tests execute and coverage is recorded. **Every run after:** only tests affected by your changes are selected. That's it.

## How it works

```
                  process-test-classes              test                    verify
                 ┌──────────────────────┐  ┌─────────────────────┐  ┌───────────────────┐
                 │                      │  │                     │  │                   │
  git diff ──>   │  collect: attach     │  │  Surefire runs      │  │  report: merge    │
  changed files  │  Java agent to       │  │  only selected      │  │  coverage dump    │
       │         │  Surefire's argLine  │  │  tests              │  │  into shared map  │
       │         │                      │  │                     │  │                   │
       └──────>  │  select: intersect   │  │  Agent records      │  │  Emit JSON report │
                 │  changes with        │  │  which classes      │  │  + console summary│
                 │  coverage map        │  │  each test touches  │  │                   │
                 └──────────────────────┘  └─────────────────────┘  └───────────────────┘
```

1. **Collect**: attaches a Java agent to Surefire's forked JVM. The agent instruments every method entry in your production and test classes using ASM bytecode rewriting.
2. **Select**: uses JGit to detect changed `.java` files, resolves them to compiled classes (including inner classes), looks up the coverage map to find which tests touch those classes, and sets Surefire's `test` filter.
3. **Report**: merges the per-module coverage dump into the shared coverage map (JSON) and writes a human-readable summary.

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
| `fullRunInterval` | `50` | Force a periodic full run every N incremental builds. Set to `0` to disable. |
| `includes` | *(auto)* | Comma-separated package prefixes to instrument. Auto-detected from your source roots if omitted. |
| `excludes` | *(empty)* | Comma-separated package prefixes to exclude from instrumentation. |
| `failOnEmptySelection` | `false` | If `true`, fail the build when no tests match the changed classes instead of falling back to a full run. |
| `coverageMapPath` | `<reactor-root>/target/.test-impact/coverage.json` | Override the coverage map location. |

## Baseline strategies

Choose the right baseline for your workflow:

| Strategy | Best for | How it works |
|----------|----------|--------------|
| `lastCommit` | **CI / pull requests** | Diffs HEAD against HEAD~1. Each push re-evaluates. |
| `lastTag` | **Release pipelines** | Diffs HEAD against the most recent Git tag by timestamp. |
| `lastFullRun` | **Local development** | Diffs the working tree against the commit hash recorded in the coverage map from the last full test run. |

## Multi-module reactors

The plugin works out of the box with multi-module Maven projects, including parallel builds (`mvn -T`):

- **Shared coverage map** at the reactor root (`target/.test-impact/coverage.json`)
- **Per-module dumps**: each module's Surefire JVM writes its own binary dump
- **Concurrency-safe merges**: `report` uses a JVM monitor + OS-level `FileLock` for safe concurrent writes
- **Dependency-aware filtering**: `select` uses `MavenSession.getProjectDependencyGraph()` to only consider changes in upstream modules

## Safety guarantees

The plugin is designed to **never silently skip tests**. It falls back to a full run when:

- No coverage map exists (first run)
- Coverage map version doesn't match the plugin version
- Coverage map is older than `fullRunInterval` builds
- Git change detection fails
- The change set is empty (ambiguous state)
- No tests intersect with the changed classes

This means you can adopt the plugin incrementally with confidence. The worst case is running all tests, same as without the plugin.

## Supported test frameworks

The agent detects test methods by annotation:

| Framework | Annotations |
|-----------|------------|
| **JUnit 5** | `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate` |
| **JUnit 4** | `@Test` |
| **TestNG** | `@Test` |

No configuration needed: all three are detected automatically.

## Goals reference

| Goal | Phase | Description |
|------|-------|-------------|
| `test-impact:collect` | `process-test-classes` | Attaches the Java agent to Surefire's `argLine` for bytecode instrumentation |
| `test-impact:select` | `process-test-classes` | Detects changed sources and sets Surefire's `test` filter to impacted tests only |
| `test-impact:report` | `verify` | Merges coverage dump into the shared map, generates JSON report and console summary |
| `test-impact:invalidate` | *(manual)* | Clears the coverage map and all per-module state for a clean rebuild |

To reset the coverage map and force a full rebuild:

```bash
mvn test-impact:invalidate
```

## Requirements

- **Java** 11+
- **Maven** 3.9+
- **Git** repository (for change detection)

## Building from source

```bash
git clone https://github.com/mirkoalicastro/test-impact-maven-plugin.git
cd test-impact-maven-plugin
mvn clean verify
```

This produces three artifacts:
- `selective-test-runner-core-1.0.0-SNAPSHOT.jar`: build-tool-agnostic core (agent, change detection, impact resolution, coverage persistence)
- `selective-test-runner-core-1.0.0-SNAPSHOT-agent.jar`: the shaded agent JAR (ASM relocated) used as `-javaagent` in the forked Surefire JVM
- `test-impact-maven-plugin-1.0.0-SNAPSHOT.jar`: the Maven plugin

## Contributing

Contributions are welcome! Here's how to get started:

1. **Fork** the repository and create a feature branch from `main`
2. **Build & test** locally with `mvn clean verify`
3. **Keep changes focused**: one feature or fix per pull request
4. **Add tests** for new functionality
5. **Open a pull request** against `main` with a clear description of what and why

### Project structure

```
selective-test-runner-core/          # Build-tool-agnostic core
  agent/          # Java agent: instrumentation, coverage recording
  change/         # Git change detection, source-to-class resolution
  store/          # Coverage map persistence (JSON)
  resolve/        # Impact analysis, test selection logic
  report/         # JSON + console report generation
  common/         # Shared utilities (paths, dump reader)

test-impact-maven-plugin/            # Maven plugin (thin wrapper over core)
  mojo/           # Maven plugin goals (collect, select, report, invalidate)
  common/         # Maven-specific utilities (reactor scope)
```

### Areas where help is appreciated

- **Integration test selection**: Failsafe support with endpoint-flow modeling
- **Gradle port**: adapt the agent and selection logic for Gradle builds
- **Performance benchmarks**: real-world numbers on large open source projects
- **Documentation**: usage guides, example projects

## License

This project is open source. See the [LICENSE](LICENSE) file for details.
