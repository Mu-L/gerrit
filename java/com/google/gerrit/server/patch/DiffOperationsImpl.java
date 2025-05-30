// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.patch;

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.gerrit.entities.Patch.COMMIT_MSG;
import static com.google.gerrit.entities.Patch.MERGE_LIST;
import static java.util.Comparator.naturalOrder;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.Patch.ChangeType;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.DiffPreferencesInfo;
import com.google.gerrit.extensions.client.DiffPreferencesInfo.Whitespace;
import com.google.gerrit.server.cache.CacheModule;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.patch.diff.ModifiedFilesCache;
import com.google.gerrit.server.patch.diff.ModifiedFilesCacheImpl;
import com.google.gerrit.server.patch.diff.ModifiedFilesCacheKey;
import com.google.gerrit.server.patch.diff.ModifiedFilesLoader;
import com.google.gerrit.server.patch.filediff.FileDiffCache;
import com.google.gerrit.server.patch.filediff.FileDiffCacheImpl;
import com.google.gerrit.server.patch.filediff.FileDiffCacheKey;
import com.google.gerrit.server.patch.filediff.FileDiffOutput;
import com.google.gerrit.server.patch.gitdiff.GitModifiedFilesCacheImpl;
import com.google.gerrit.server.patch.gitdiff.ModifiedFile;
import com.google.gerrit.server.patch.gitfilediff.GitFileDiffCacheImpl;
import com.google.gerrit.server.patch.gitfilediff.GitFileDiffCacheImpl.DiffAlgorithm;
import com.google.gerrit.server.update.RepoView;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Provides different file diff operations. Uses the underlying Git/Gerrit caches to speed up the
 * diff computation.
 */
@Singleton
public class DiffOperationsImpl implements DiffOperations {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting static final int RENAME_SCORE = 60;

  private static final DiffAlgorithm DEFAULT_DIFF_ALGORITHM =
      DiffAlgorithm.HISTOGRAM_WITH_FALLBACK_MYERS;
  private static final Whitespace DEFAULT_WHITESPACE = Whitespace.IGNORE_NONE;

  private final GitRepositoryManager repoManager;
  private final ModifiedFilesCache modifiedFilesCache;
  private final ModifiedFilesCacheImpl modifiedFilesCacheImpl;
  private final ModifiedFilesLoader.Factory modifiedFilesLoaderFactory;
  private final FileDiffCache fileDiffCache;
  private final BaseCommitUtil baseCommitUtil;

  public static Module module() {
    return new CacheModule() {
      @Override
      protected void configure() {
        bind(DiffOperations.class).to(DiffOperationsImpl.class);
        install(GitModifiedFilesCacheImpl.module());
        install(ModifiedFilesCacheImpl.module());
        install(GitFileDiffCacheImpl.module());
        install(FileDiffCacheImpl.module());
      }
    };
  }

  @Inject
  public DiffOperationsImpl(
      GitRepositoryManager repoManager,
      ModifiedFilesCache modifiedFilesCache,
      ModifiedFilesCacheImpl modifiedFilesCacheImpl,
      ModifiedFilesLoader.Factory modifiedFilesLoaderFactory,
      FileDiffCache fileDiffCache,
      BaseCommitUtil baseCommit) {
    this.repoManager = repoManager;
    this.modifiedFilesCache = modifiedFilesCache;
    this.modifiedFilesCacheImpl = modifiedFilesCacheImpl;
    this.modifiedFilesLoaderFactory = modifiedFilesLoaderFactory;
    this.fileDiffCache = fileDiffCache;
    this.baseCommitUtil = baseCommit;
  }

  @Override
  public Map<String, FileDiffOutput> listModifiedFilesAgainstParent(
      Project.NameKey project, ObjectId newCommit, int parent, DiffOptions diffOptions)
      throws DiffNotAvailableException {
    try (Repository repo = repoManager.openRepository(project);
        ObjectInserter ins = repo.newObjectInserter();
        ObjectReader reader = ins.newReader();
        RevWalk revWalk = new RevWalk(reader);
        RepoView repoView = new RepoView(repo, revWalk, ins)) {
      logger.atFine().log(
          "Opened repo %s to list modified files against parent for %s (inserter: %s)",
          project, newCommit.name(), ins);

      DiffParameters diffParams = computeDiffParameters(project, newCommit, parent, repoView, ins);
      return getModifiedFiles(diffParams, diffOptions);
    } catch (IOException e) {
      throw new DiffNotAvailableException(
          "Failed to evaluate the parent/base commit for commit " + newCommit, e);
    }
  }

  @Override
  public Map<String, ModifiedFile> loadModifiedFilesAgainstParentIfNecessary(
      Project.NameKey project,
      ObjectId newCommit,
      int parentNum,
      RepoView repoView,
      ObjectInserter ins,
      boolean enableRenameDetection)
      throws DiffNotAvailableException {
    try {
      DiffParameters diffParams =
          computeDiffParameters(project, newCommit, parentNum, repoView, ins);
      return loadModifiedFilesWithoutCacheIfNecessary(
          project, diffParams, repoView.getRevWalk(), repoView.getConfig(), enableRenameDetection);
    } catch (IOException e) {
      throw new DiffNotAvailableException(
          String.format(
              "Failed to evaluate the parent/base commit for commit '%s' with parentNum=%d",
              newCommit, parentNum),
          e);
    }
  }

  @Override
  public Map<String, FileDiffOutput> listModifiedFiles(
      Project.NameKey project, ObjectId oldCommit, ObjectId newCommit, DiffOptions diffOptions)
      throws DiffNotAvailableException {
    DiffParameters params =
        DiffParameters.builder()
            .project(project)
            .newCommit(newCommit)
            .baseCommit(oldCommit)
            .comparisonType(ComparisonType.againstOtherPatchSet())
            .build();
    return getModifiedFiles(params, diffOptions);
  }

  @Override
  public Map<String, ModifiedFile> loadModifiedFilesIfNecessary(
      Project.NameKey project,
      ObjectId oldCommit,
      ObjectId newCommit,
      RevWalk revWalk,
      Config repoConfig,
      boolean enableRenameDetection)
      throws DiffNotAvailableException {
    DiffParameters params =
        DiffParameters.builder()
            .project(project)
            .newCommit(newCommit)
            .baseCommit(oldCommit)
            .comparisonType(ComparisonType.againstOtherPatchSet())
            .build();
    return loadModifiedFilesWithoutCacheIfNecessary(
        project, params, revWalk, repoConfig, enableRenameDetection);
  }

  @Override
  public FileDiffOutput getModifiedFileAgainstParent(
      Project.NameKey project,
      ObjectId newCommit,
      int parent,
      String fileName,
      @Nullable DiffPreferencesInfo.Whitespace whitespace)
      throws DiffNotAvailableException {
    try (Repository repo = repoManager.openRepository(project);
        ObjectInserter ins = repo.newObjectInserter();
        ObjectReader reader = ins.newReader();
        RevWalk revWalk = new RevWalk(reader);
        RepoView repoView = new RepoView(repo, revWalk, ins)) {
      logger.atFine().log(
          "Opened repo %s to get modified file against parent for %s (inserter: %s)",
          project, newCommit.name(), ins);
      DiffParameters diffParams = computeDiffParameters(project, newCommit, parent, repoView, ins);
      FileDiffCacheKey key =
          createFileDiffCacheKey(
              project,
              diffParams.baseCommit(),
              newCommit,
              fileName,
              DEFAULT_DIFF_ALGORITHM,
              /* useTimeout= */ true,
              whitespace);
      return getModifiedFileForKey(key);
    } catch (IOException e) {
      throw new DiffNotAvailableException(
          "Failed to evaluate the parent/base commit for commit " + newCommit, e);
    }
  }

  @Override
  public FileDiffOutput getModifiedFile(
      Project.NameKey project,
      ObjectId oldCommit,
      ObjectId newCommit,
      String fileName,
      @Nullable DiffPreferencesInfo.Whitespace whitespace)
      throws DiffNotAvailableException {
    FileDiffCacheKey key =
        createFileDiffCacheKey(
            project,
            oldCommit,
            newCommit,
            fileName,
            DEFAULT_DIFF_ALGORITHM,
            /* useTimeout= */ true,
            whitespace);
    return getModifiedFileForKey(key);
  }

  private ImmutableMap<String, FileDiffOutput> getModifiedFiles(
      DiffParameters diffParams, DiffOptions diffOptions) throws DiffNotAvailableException {
    try {
      Project.NameKey project = diffParams.project();
      ObjectId newCommit = diffParams.newCommit();
      ObjectId oldCommit = diffParams.baseCommit();
      ComparisonType cmp = diffParams.comparisonType();

      ImmutableList<ModifiedFile> modifiedFiles =
          modifiedFilesCache.get(createModifiedFilesKey(project, oldCommit, newCommit));

      List<FileDiffCacheKey> fileCacheKeys = new ArrayList<>();
      fileCacheKeys.add(
          createFileDiffCacheKey(
              project,
              oldCommit,
              newCommit,
              COMMIT_MSG,
              DEFAULT_DIFF_ALGORITHM,
              /* useTimeout= */ true,
              /* whitespace= */ null));

      if (cmp.isAgainstAutoMerge() || isMergeAgainstParent(cmp, project, newCommit)) {
        fileCacheKeys.add(
            createFileDiffCacheKey(
                project,
                oldCommit,
                newCommit,
                MERGE_LIST,
                DEFAULT_DIFF_ALGORITHM,
                /* useTimeout= */ true,
                /* whitespace= */ null));
      }

      if (diffParams.skipFiles() == null) {
        modifiedFiles.stream()
            .map(
                entity ->
                    createFileDiffCacheKey(
                        project,
                        oldCommit,
                        newCommit,
                        entity.newPath().isPresent()
                            ? entity.newPath().get()
                            : entity.oldPath().get(),
                        DEFAULT_DIFF_ALGORITHM,
                        /* useTimeout= */ true,
                        /* whitespace= */ null))
            .forEach(fileCacheKeys::add);
      }
      return getModifiedFilesForKeys(fileCacheKeys, diffOptions);
    } catch (IOException e) {
      throw new DiffNotAvailableException(e);
    }
  }

  private FileDiffOutput getModifiedFileForKey(FileDiffCacheKey key)
      throws DiffNotAvailableException {
    ImmutableMap<String, FileDiffOutput> diffList =
        getModifiedFilesForKeys(ImmutableList.of(key), DiffOptions.DEFAULTS);
    return diffList.containsKey(key.newFilePath())
        ? diffList.get(key.newFilePath())
        : FileDiffOutput.empty(key.newFilePath(), key.oldCommit(), key.newCommit());
  }

  /**
   * Lookup the file diffs for the input {@code keys}. For results where the cache reports negative
   * results, e.g. due to timeouts in the cache loader, this method requests the diff again using
   * the fallback algorithm {@link DiffAlgorithm#HISTOGRAM_NO_FALLBACK}.
   */
  private ImmutableMap<String, FileDiffOutput> getModifiedFilesForKeys(
      List<FileDiffCacheKey> keys, DiffOptions diffOptions) throws DiffNotAvailableException {
    ImmutableMap<FileDiffCacheKey, FileDiffOutput> fileDiffs = fileDiffCache.getAll(keys);
    List<FileDiffCacheKey> fallbackKeys = new ArrayList<>();

    ImmutableList.Builder<FileDiffOutput> result = ImmutableList.builder();

    // Use the fallback diff algorithm for negative results
    for (FileDiffCacheKey key : fileDiffs.keySet()) {
      FileDiffOutput diff = fileDiffs.get(key);
      if (diff.isNegative()) {
        FileDiffCacheKey fallbackKey =
            createFileDiffCacheKey(
                key.project(),
                key.oldCommit(),
                key.newCommit(),
                key.newFilePath(),
                // Use the fallback diff algorithm
                DiffAlgorithm.HISTOGRAM_NO_FALLBACK,
                // We don't enforce timeouts with the fallback algorithm. Timeouts were introduced
                // because of a bug in JGit that happens only when the histogram algorithm uses
                // Myers as fallback. See https://issues.gerritcodereview.com/issues/40000618
                /* useTimeout= */ false,
                key.whitespace());
        logger.atFine().log(
            "fallback to computing git file diff for %s with %s as diff algorithm and no timeout",
            key.newFilePath(), DiffAlgorithm.HISTOGRAM_NO_FALLBACK);
        fallbackKeys.add(fallbackKey);
      } else {
        result.add(diff);
      }
    }
    result.addAll(fileDiffCache.getAll(fallbackKeys).values());
    return mapByFilePath(result.build(), diffOptions);
  }

  /**
   * Map a collection of {@link FileDiffOutput} based on their file paths. The result map keys
   * represent the old file path for deleted files, or the new path otherwise.
   */
  private ImmutableMap<String, FileDiffOutput> mapByFilePath(
      ImmutableCollection<FileDiffOutput> fileDiffOutputs, DiffOptions diffOptions) {
    ImmutableMap.Builder<String, FileDiffOutput> diffs = ImmutableMap.builder();

    for (FileDiffOutput fileDiffOutput : fileDiffOutputs) {
      if (fileDiffOutput.isEmpty()
          || (diffOptions.skipFilesWithAllEditsDueToRebase() && allDueToRebase(fileDiffOutput))) {
        continue;
      }
      if (fileDiffOutput.changeType() == ChangeType.DELETED) {
        diffs.put(fileDiffOutput.oldPath().get(), fileDiffOutput);
      } else {
        diffs.put(fileDiffOutput.newPath().get(), fileDiffOutput);
      }
    }
    return diffs.build();
  }

  private static boolean allDueToRebase(FileDiffOutput fileDiffOutput) {
    return fileDiffOutput.allEditsDueToRebase()
        && !(fileDiffOutput.changeType() == ChangeType.RENAMED
            || fileDiffOutput.changeType() == ChangeType.COPIED);
  }

  private boolean isMergeAgainstParent(ComparisonType cmp, Project.NameKey project, ObjectId commit)
      throws IOException {
    return (cmp.isAgainstParent() && baseCommitUtil.getNumParents(project, commit) > 1);
  }

  private static ModifiedFilesCacheKey createModifiedFilesKey(
      Project.NameKey project, ObjectId aCommit, ObjectId bCommit) {
    return ModifiedFilesCacheKey.builder()
        .project(project)
        .aCommit(aCommit)
        .bCommit(bCommit)
        .renameScore(RENAME_SCORE)
        .build();
  }

  private static FileDiffCacheKey createFileDiffCacheKey(
      Project.NameKey project,
      ObjectId aCommit,
      ObjectId bCommit,
      String newPath,
      DiffAlgorithm diffAlgorithm,
      boolean useTimeout,
      @Nullable Whitespace whitespace) {
    whitespace = whitespace == null ? DEFAULT_WHITESPACE : whitespace;
    return FileDiffCacheKey.builder()
        .project(project)
        .oldCommit(aCommit)
        .newCommit(bCommit)
        .newFilePath(newPath)
        .renameScore(RENAME_SCORE)
        .diffAlgorithm(diffAlgorithm)
        .whitespace(whitespace)
        .useTimeout(useTimeout)
        .build();
  }

  /**
   * Retrieves the modified files from the {@link ModifiedFilesCache} if they are already cached. If
   * not, the modified files are loaded directly (using the provided {@link RevWalk}) rather than
   * loading them via the {@link ModifiedFilesCache} (that would open a new {@link RevWalk}
   * instance).
   *
   * <p>The results will be stored in the {@link ModifiedFilesCache} so that calling this method
   * multiple times loads the modified files only once (for the first call, for further calls the
   * cached modified files are returned).
   */
  private ImmutableMap<String, ModifiedFile> loadModifiedFilesWithoutCacheIfNecessary(
      Project.NameKey project,
      DiffParameters diffParams,
      RevWalk revWalk,
      Config repoConfig,
      boolean enableRenameDetection)
      throws DiffNotAvailableException {
    ModifiedFilesCacheKey.Builder cacheKeyBuilder =
        ModifiedFilesCacheKey.builder()
            .project(project)
            .aCommit(diffParams.baseCommit())
            .bCommit(diffParams.newCommit());
    if (enableRenameDetection) {
      cacheKeyBuilder.renameScore(RENAME_SCORE);
    } else {
      cacheKeyBuilder.disableRenameDetection();
    }
    ModifiedFilesCacheKey cacheKey = cacheKeyBuilder.build();

    Optional<ImmutableList<ModifiedFile>> cachedModifiedFiles =
        modifiedFilesCacheImpl.getIfPresent(cacheKey);
    if (cachedModifiedFiles.isPresent()) {
      return toMap(cachedModifiedFiles.get());
    }

    ModifiedFilesLoader modifiedFilesLoader = modifiedFilesLoaderFactory.create();
    if (enableRenameDetection) {
      modifiedFilesLoader.withRenameDetection(RENAME_SCORE);
    }
    ImmutableMap<String, ModifiedFile> modifiedFiles =
        toMap(
            modifiedFilesLoader.load(
                project, repoConfig, revWalk, diffParams.baseCommit(), diffParams.newCommit()));

    // Store the result in the cache.
    modifiedFilesCacheImpl.put(cacheKey, ImmutableList.copyOf(modifiedFiles.values()));
    return modifiedFiles;
  }

  private static ImmutableMap<String, ModifiedFile> toMap(
      ImmutableList<ModifiedFile> modifiedFiles) {
    return modifiedFiles.stream()
        .collect(
            toImmutableSortedMap(
                naturalOrder(), ModifiedFile::getDefaultPath, Function.identity()));
  }

  @AutoValue
  abstract static class DiffParameters {
    abstract Project.NameKey project();

    abstract ObjectId newCommit();

    /**
     * Base commit represents the old commit of the diff. For diffs against the root commit, this
     * should be set to {@link ObjectId#zeroId()}.
     */
    abstract ObjectId baseCommit();

    abstract ComparisonType comparisonType();

    @Nullable
    abstract Integer parent();

    /** Compute the diff for {@value Patch#COMMIT_MSG} and {@link Patch#MERGE_LIST} only. */
    @Nullable
    abstract Boolean skipFiles();

    static Builder builder() {
      return new AutoValue_DiffOperationsImpl_DiffParameters.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder project(Project.NameKey project);

      abstract Builder newCommit(ObjectId newCommit);

      abstract Builder baseCommit(ObjectId baseCommit);

      abstract Builder parent(@Nullable Integer parent);

      abstract Builder skipFiles(@Nullable Boolean skipFiles);

      abstract Builder comparisonType(ComparisonType comparisonType);

      public abstract DiffParameters build();
    }
  }

  /** Compute Diff parameters - the base commit and the comparison type - using the input args. */
  private DiffParameters computeDiffParameters(
      Project.NameKey project,
      ObjectId newCommit,
      int parent,
      RepoView repoView,
      ObjectInserter ins)
      throws IOException {
    DiffParameters.Builder result =
        DiffParameters.builder().project(project).newCommit(newCommit).parent(parent);
    if (parent > 0) {
      RevCommit baseCommit = baseCommitUtil.getBaseCommit(repoView, ins, newCommit, parent);
      if (baseCommit == null) {
        // The specified parent doesn't exist or is not supported, fall back to comparing against
        // the root.
        result.baseCommit(ObjectId.zeroId());
        result.comparisonType(ComparisonType.againstRoot());
        return result.build();
      }

      result.baseCommit(baseCommit);
      result.comparisonType(ComparisonType.againstParent(parent));
      return result.build();
    }
    int numParents = baseCommitUtil.getNumParents(project, newCommit);
    if (numParents == 0) {
      result.baseCommit(ObjectId.zeroId());
      result.comparisonType(ComparisonType.againstRoot());
      return result.build();
    }
    if (numParents == 1) {
      result.baseCommit(baseCommitUtil.getBaseCommit(repoView, ins, newCommit, parent));
      result.comparisonType(ComparisonType.againstParent(1));
      return result.build();
    }
    if (numParents > 2) {
      logger.atFine().log(
          "Diff against auto-merge for merge commits "
              + "with more than two parents is not supported. Commit %s has %d parents."
              + " Falling back to the diff against the first parent.",
          newCommit, numParents);
      result.baseCommit(baseCommitUtil.getBaseCommit(repoView, ins, newCommit, 1).getId());
      result.comparisonType(ComparisonType.againstParent(1));
      result.skipFiles(true);
    } else {
      result.baseCommit(
          baseCommitUtil.getBaseCommit(repoView, ins, newCommit, /* parentNum= */ null));
      result.comparisonType(ComparisonType.againstAutoMerge());
    }
    return result.build();
  }
}
