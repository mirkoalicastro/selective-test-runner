# maven-test-impact-plugin

Run only the tests that matter — bytecode-level test impact analysis for Maven, per the
design spec dated 2026-04-25.

## Status

This is a working **Phase 1–3** implementation: the unit-test foundation, unit-test
selection, and the safety/CI fallback rules from the spec. Phases 4+ (multi-module
reactor sharing, ECG/IEI integration test selection, OTel correlation, Spring Boot
auto-config, JUnit 5 client extension) are not implemented.

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

## Monorepo / multi-module status

**Today this plugin does not safely support monorepos with multiple Maven modules
or services.** It is correct for single-module projects (even when they live in a
shared git tree alongside other services). The known issues for multi-module
builds, in order of severity:

1. **Cross-module coverage is lost.** Each module writes its own
   `target/.test-impact/coverage.db`. A test in module B that covers a class in
   module A is recorded in B's map, but A's `select` reads A's map and never sees
   the link. Spec §9 requires a single shared map at the reactor root.
2. **Change detection is repo-wide, not module-aware.** `ChangeDetector` runs
   `git diff` from the repo root via JGit, so every module sees every changed file
   in the repo — including changes in sibling modules it does not depend on. This
   over-selects tests (safe per spec §7) but defeats most of the speedup in a
   monorepo.
3. **Parallel reactor builds (`mvn -T`) will corrupt the map.** The atomic-rename
   write in `CoverageMapStore` prevents half-written files, but two modules
   finishing `report` concurrently will silently drop one module's update.

### What works today in a monorepo

- Single-module services, even when they coexist in a shared git tree.
- Multi-module builds where every test lives in the same module as the production
  code it covers (rare — integration/e2e tests usually violate this).
- Sequential reactor builds (`mvn` without `-T`), accepting per-module maps and
  the over-selection from issue 2.

### Work required for proper monorepo support (next iteration)

1. **Shared map at reactor root.** Inject `MavenSession`, default the map and dump
   paths to `session.getTopLevelProject().getBuild().getDirectory()/.test-impact/`.
   Update `PluginPaths` accordingly. Small change.
2. **Concurrency-safe writes.** Either a `FileChannel.lock()` around
   read-modify-write in `CoverageMapStore`, or — preferred — a per-module staging
   file plus a single aggregator step bound to the top-level project's `verify`
   that merges all stagings into the shared map. The aggregator approach avoids
   lock contention entirely and is cleaner under `-T`.
3. **Reactor-graph-aware change filtering.** Use
   `MavenSession.getProjectDependencyGraph()` to filter changed classes to those
   reachable from the current module via the reactor dependency graph. Without
   this, the plugin remains *safe* (over-selection only) but loses most of the
   monorepo speedup.
4. **Cross-module class-ref disambiguation.** The forward index uses JVM-internal
   class names which are already global, so the data model needs no change — but
   the `ClassResolver` should walk every reactor module's `target/classes` (not
   just the current module's) so inner-class expansion works for changes in
   sibling modules.

Estimated effort: ~2–3 days, dominated by concurrency edge-case testing under
`mvn -T` rather than by the wiring itself.
