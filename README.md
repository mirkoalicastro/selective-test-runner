# maven-test-impact-plugin

Run only the tests that matter — bytecode-level test impact analysis for Maven, per the
design spec dated 2026-04-25.

## Status

This is a working **Phase 1–3** implementation plus multi-module reactor support:
the unit-test foundation, unit-test selection, the safety/CI fallback rules from
the spec, and a shared coverage map at the reactor root with concurrency-safe
writes under `mvn -T`. Phases 4+ (ECG/IEI integration test selection, OTel
correlation, Spring Boot auto-config, JUnit 5 client extension) are not
implemented.

## What's here

| Component | File |
| --- | --- |
| Java agent (premain) | `agent/CoverageAgent.java` |
| Bytecode transformer (ASM) | `agent/CoverageTransformer.java` |
| Per-thread test recorder | `agent/CoverageRecorder.java` |
| Coverage map (in-memory + MessagePack persistence) | `store/CoverageMap.java`, `store/CoverageMapStore.java` |
| Git change detection (JGit) | `change/ChangeDetector.java`, `change/Baseline.java` |
| Source → class-ref resolution (incl. inner classes) | `change/ClassResolver.java` |
| Impact resolver (with safety fallbacks) | `resolve/ImpactResolver.java`, `resolve/Selection.java` |
| `test-impact:collect` mojo | `mojo/CollectMojo.java` |
| `test-impact:select` mojo | `mojo/SelectMojo.java` |
| `test-impact:report` mojo | `mojo/ReportMojo.java` |
| `test-impact:invalidate` mojo | `mojo/InvalidateMojo.java` |
| JSON + console reporting | `report/ImpactReport.java` |

## Build

```bash
mvn package
```

This produces two artifacts:
- `maven-test-impact-plugin-1.0.0-SNAPSHOT.jar` — the Maven plugin
- `maven-test-impact-plugin-1.0.0-SNAPSHOT-agent.jar` — the shaded agent JAR with ASM
  relocated, used as `-javaagent` in the forked Surefire JVM

## Use

```xml
<plugin>
  <groupId>io.github.testimpact</groupId>
  <artifactId>maven-test-impact-plugin</artifactId>
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
  <configuration>
    <baseline>lastCommit</baseline>
    <fullRunInterval>50</fullRunInterval>
  </configuration>
</plugin>
```

## Lifecycle binding

```
process-test-classes  → test-impact:collect    (attaches javaagent to argLine)
test                  → test-impact:select     (sets -Dtest=... for surefire)
                      → maven-surefire-plugin:test
verify                → test-impact:report     (merges dump into coverage.db, emits JSON)
```

## Safety guarantees (spec §7)

The resolver returns `FULL_RUN` for any of: missing map, version mismatch, age past
`fullRunInterval`, empty change set, empty intersection, or git failure. The plugin
never silently skips tests.

## Notes on scope

- IT selection (Failsafe, ECG, IEI) is deliberately deferred — it's a separate model
  (endpoint-flow rather than coverage) and is roughly the size of this whole codebase
  again.
- The agent identifies test methods by annotation (`@Test`, `@ParameterizedTest`,
  `@RepeatedTest`, `@TestFactory`, `@TestTemplate`, JUnit 4 `@Test`, TestNG `@Test`).
  Pure programmatic test discovery (e.g. JUnit 5 dynamic tests via `TestFactory`
  returning `DynamicTest`) gets one `beginTest`/`endTest` per factory call, not per
  generated test — coarse but safe.

## Monorepo / multi-module support

Multi-module reactors are supported, including under `mvn -T`. The model:

| Artifact | Location | Why |
| --- | --- | --- |
| Coverage map (`coverage.db`) | reactor root `target/.test-impact/` | one shared forward index across the whole reactor |
| Agent dump (`dump.bin`) | per-module `target/.test-impact/` | each module's test JVM writes its own |
| Selection record (`selection.json`) | per-module | per-module `select` decision |
| JSON report (`test-impact-report.json`) | per-module | per-module summary |
| Build counter (`builds-since-full.txt`) | per-module | each module independently triggers periodic full runs |

What changes per phase:

- `select` reads the shared map, filters changed sources to those owned by modules
  reachable upstream from the current module via
  `MavenSession.getProjectDependencyGraph()`, then filters the resolved tests to
  those whose `.class` lives in the current module's test output dir.
- `report` reads its module's dump and merges it into the shared map via
  `CoverageMapStore.mergeAndSave`, which serialises read-modify-write under a JVM
  monitor + OS-level `FileLock` on a sibling `.lock` file — safe under `mvn -T`.
- `invalidate` clears the shared map plus every reactor module's per-module state.

### Caveats

- **Source-to-module mapping is by basedir prefix.** Files outside any module's
  basedir (e.g. top-level config) are conservatively treated as relevant to every
  module. Same for files under a parent POM at the repo root: the parent's
  empty-prefix basedir acts as a catch-all.
- **Dynamically-typed reactor projects** (e.g. modules added at runtime via
  extensions) may not appear in `session.getProjects()` at the time `select`
  runs; their changes won't be filtered.
- **The agent does not aggregate per-test data across modules.** A single test ID
  must run in a single module's surefire to be recorded coherently — this is the
  normal case for unit tests (test class lives in the module that owns it).
