package io.github.testimpact.change;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

/**
 * Detects changed source files between the working tree and a configurable baseline, then resolves
 * them to JVM-internal class names (including inner classes via {@code $N}).
 */
public final class ChangeDetector {

  private final File workingDir;

  public ChangeDetector(File workingDir) {
    this.workingDir = workingDir;
  }

  /**
   * @return changed source files (Java sources only), as paths relative to the repo root, or {@code
   *     null} if the change set could not be computed (caller should fall back).
   */
  public Set<String> changedSources(Baseline baseline, String coverageBuildHash) {
    try (Repository repo =
        new FileRepositoryBuilder().findGitDir(workingDir).readEnvironment().build()) {
      if (repo.getDirectory() == null) return null;

      ObjectId from = resolveBaseline(repo, baseline, coverageBuildHash);
      if (from == null) return null;

      try (Git git = new Git(repo);
          RevWalk revWalk = new RevWalk(repo)) {
        RevTree fromTree = revWalk.parseTree(from);
        AbstractTreeIterator oldIter =
            new CanonicalTreeParser(null, repo.newObjectReader(), fromTree.getId());
        AbstractTreeIterator newIter = new FileTreeIterator(repo);

        List<DiffEntry> diffs =
            git.diff()
                .setOldTree(oldIter)
                .setNewTree(newIter)
                .setShowNameAndStatusOnly(true)
                .call();

        Set<String> result = new TreeSet<>();
        for (DiffEntry e : diffs) {
          String path =
              e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
          if (path != null && path.endsWith(".java")) result.add(path);
        }
        return result;
      }
    } catch (IOException | GitAPIException e) {
      return null;
    }
  }

  private ObjectId resolveBaseline(Repository repo, Baseline baseline, String coverageBuildHash)
      throws IOException {
    switch (baseline) {
      case LAST_COMMIT:
        return repo.resolve("HEAD");
      case LAST_TAG:
        return resolveLatestTag(repo);
      case LAST_FULL_RUN:
        if (coverageBuildHash == null || coverageBuildHash.isEmpty()) return null;
        return repo.resolve(coverageBuildHash);
      default:
        return null;
    }
  }

  private ObjectId resolveLatestTag(Repository repo) throws IOException {
    try (Git git = new Git(repo)) {
      List<Ref> tags = git.tagList().call();
      if (tags.isEmpty()) return null;
      // Pick the most recent tag by tagger/commit time.
      Ref best = null;
      long bestTime = Long.MIN_VALUE;
      try (RevWalk rw = new RevWalk(repo)) {
        for (Ref t : tags) {
          long time = rw.parseCommit(t.getObjectId()).getCommitTime();
          if (time > bestTime) {
            bestTime = time;
            best = t;
          }
        }
      }
      return best == null ? null : best.getObjectId();
    } catch (GitAPIException e) {
      return null;
    }
  }

  /** Resolve current HEAD commit SHA, or empty string if unavailable. */
  public String headCommit() {
    try (Repository repo =
        new FileRepositoryBuilder().findGitDir(workingDir).readEnvironment().build()) {
      if (repo.getDirectory() == null) return "";
      ObjectId head = repo.resolve("HEAD");
      return head == null ? "" : head.getName();
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * Repo work-tree root, or {@code null} if not in a git repo. The change set returned by {@link
   * #changedSources} uses paths relative to this dir.
   */
  public File repoRoot() {
    try (Repository repo =
        new FileRepositoryBuilder().findGitDir(workingDir).readEnvironment().build()) {
      if (repo.getDirectory() == null) return null;
      return repo.getWorkTree();
    } catch (IOException e) {
      return null;
    }
  }
}
