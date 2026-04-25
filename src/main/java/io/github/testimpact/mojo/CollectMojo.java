package io.github.testimpact.mojo;

import io.github.testimpact.common.PluginPaths;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Activates coverage collection by attaching the agent JAR to Surefire's argLine.
 * Bound to {@code process-test-classes} per spec §5.2.
 *
 * Effect on subsequent {@code surefire:test}:
 *   {@code -javaagent:<plugin.jar> -Dtestimpact.dump=<dump.bin>}
 * is prepended to {@code argLine}, instructing the forked test JVM to instrument
 * production classes and emit a per-test class-touch dump.
 */
@Mojo(name = "collect",
        defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = true)
public class CollectMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true)
    private java.util.Map<String, Artifact> pluginArtifacts;

    /** Optional include packages (comma-separated, e.g. {@code com.acme,io.acme}). */
    @Parameter(property = "testimpact.includes")
    private String includes;

    /** Optional exclude packages (comma-separated). */
    @Parameter(property = "testimpact.excludes")
    private String excludes;

    /** Extra JVM args passed to the forked Surefire JVM alongside the agent. */
    @Parameter(property = "testimpact.agentJvmArgs", defaultValue = "")
    private String agentJvmArgs;

    /** Skip collection (e.g. when the resolver determined we are in selection mode and not refreshing). */
    @Parameter(property = "testimpact.skipCollect", defaultValue = "false")
    private boolean skipCollect;

    @Override
    public void execute() throws MojoExecutionException {
        if (skipCollect) {
            getLog().info("test-impact:collect skipped (selection mode, no refresh required)");
            return;
        }
        File agentJar = locateAgentJar();
        if (agentJar == null) {
            getLog().warn("test-impact: could not locate plugin agent JAR — collection skipped, full suite will run");
            return;
        }

        Path dump = PluginPaths.dump(project.getBuild().getDirectory());
        try {
            Files.createDirectories(dump.getParent());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create dump directory", e);
        }

        StringBuilder argLine = new StringBuilder();
        argLine.append("-javaagent:").append(quote(agentJar.getAbsolutePath()));
        argLine.append(" -Dtestimpact.dump=").append(quote(dump.toString()));
        if (includes != null && !includes.isEmpty()) {
            argLine.append(" -Dtestimpact.includes=").append(quote(includes));
        }
        if (excludes != null && !excludes.isEmpty()) {
            argLine.append(" -Dtestimpact.excludes=").append(quote(excludes));
        }
        if (agentJvmArgs != null && !agentJvmArgs.isEmpty()) {
            argLine.append(' ').append(agentJvmArgs);
        }

        String existing = project.getProperties().getProperty("argLine");
        String combined = existing == null || existing.isEmpty()
                ? argLine.toString()
                : argLine + " " + existing;
        project.getProperties().setProperty("argLine", combined);

        getLog().info("test-impact:collect attached agent: " + agentJar.getName());
        getLog().debug("test-impact argLine: " + combined);
    }

    private File locateAgentJar() {
        // Prefer the shaded "agent" classifier JAR if available; otherwise fall back to the plugin JAR.
        if (pluginArtifacts != null) {
            for (Artifact a : pluginArtifacts.values()) {
                if (a == null || a.getFile() == null) continue;
                if ("agent".equals(a.getClassifier())) return a.getFile();
            }
        }
        // Walk the classloader to find our own jar via its protection domain.
        try {
            java.net.URL src = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (src != null) {
                File f = new File(src.toURI());
                if (f.isFile()) return f;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String quote(String s) {
        return s.indexOf(' ') < 0 ? s : "\"" + s + "\"";
    }

    static Set<String> noop() { return java.util.Collections.emptySet(); }
}
