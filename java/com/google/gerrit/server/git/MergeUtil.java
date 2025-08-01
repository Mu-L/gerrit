// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.server.git;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.joining;

import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.auto.value.AutoOneOf;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.FooterConstants;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BooleanProjectConfig;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.exceptions.InvalidMergeStrategyException;
import com.google.gerrit.exceptions.MergeWithConflictsNotSupportedException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.common.NoMergeBaseReason;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.CodeReviewCommit.CodeReviewRevWalk;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.submit.ChangeAlreadyMergedException;
import com.google.gerrit.server.submit.CommitMergeStatus;
import com.google.gerrit.server.submit.MergeIdenticalTreeException;
import com.google.gerrit.server.submit.MergeSorter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.errors.NoMergeBaseException.MergeBaseFailureReason;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeFormatter;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.merge.ThreeWayMergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevFlag;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.TemporaryBuffer;

/**
 * Utility methods used during the merge process.
 *
 * <p><strong>Note:</strong> Unless otherwise specified, the methods in this class <strong>do
 * not</strong> flush {@link ObjectInserter}s. Callers that want to read back objects before
 * flushing should use {@link ObjectInserter#newReader()}. This is already the default behavior of
 * {@code BatchUpdate}.
 */
@AutoFactory
public class MergeUtil {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String R_HEADS_MASTER = Constants.R_HEADS + Constants.MASTER;

  public static boolean useRecursiveMerge(Config cfg) {
    return cfg.getBoolean("core", null, "useRecursiveMerge", true);
  }

  public static boolean useGitattributesForMerge(Config cfg) {
    return cfg.getBoolean("core", null, "useGitattributesForMerge", false);
  }

  public static ThreeWayMergeStrategy getMergeStrategy(Config cfg) {
    return useRecursiveMerge(cfg) ? MergeStrategy.RECURSIVE : MergeStrategy.RESOLVE;
  }

  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final ApprovalsUtil approvalsUtil;
  private final ProjectState project;
  private final boolean useContentMerge;
  private final boolean useRecursiveMerge;
  private final boolean useGitattributesForMerge;
  private final PluggableCommitMessageGenerator commitMessageGenerator;
  private final ChangeUtil changeUtil;

  MergeUtil(
      @Provided @GerritServerConfig Config serverConfig,
      @Provided IdentifiedUser.GenericFactory identifiedUserFactory,
      @Provided DynamicItem<UrlFormatter> urlFormatter,
      @Provided ApprovalsUtil approvalsUtil,
      @Provided PluggableCommitMessageGenerator commitMessageGenerator,
      @Provided ChangeUtil changeUtil,
      ProjectState project) {
    this(
        serverConfig,
        identifiedUserFactory,
        urlFormatter,
        approvalsUtil,
        commitMessageGenerator,
        changeUtil,
        project,
        project.is(BooleanProjectConfig.USE_CONTENT_MERGE));
  }

  MergeUtil(
      @Provided @GerritServerConfig Config serverConfig,
      @Provided IdentifiedUser.GenericFactory identifiedUserFactory,
      @Provided DynamicItem<UrlFormatter> urlFormatter,
      @Provided ApprovalsUtil approvalsUtil,
      @Provided PluggableCommitMessageGenerator commitMessageGenerator,
      @Provided ChangeUtil changeUtil,
      ProjectState project,
      boolean useContentMerge) {
    this.identifiedUserFactory = identifiedUserFactory;
    this.urlFormatter = urlFormatter;
    this.approvalsUtil = approvalsUtil;
    this.commitMessageGenerator = commitMessageGenerator;
    this.changeUtil = changeUtil;
    this.project = project;
    this.useContentMerge = useContentMerge;
    this.useRecursiveMerge = useRecursiveMerge(serverConfig);
    this.useGitattributesForMerge = useGitattributesForMerge(serverConfig);
  }

  public CodeReviewCommit getFirstFastForward(
      CodeReviewCommit mergeTip, RevWalk rw, List<CodeReviewCommit> toMerge) {
    for (Iterator<CodeReviewCommit> i = toMerge.iterator(); i.hasNext(); ) {
      try {
        final CodeReviewCommit n = i.next();
        if (mergeTip == null || rw.isMergedInto(mergeTip, n)) {
          i.remove();
          return n;
        }
      } catch (IOException e) {
        throw new StorageException("Cannot fast-forward test during merge", e);
      }
    }
    return mergeTip;
  }

  public List<CodeReviewCommit> reduceToMinimalMerge(
      MergeSorter mergeSorter, Collection<CodeReviewCommit> toSort) {
    List<CodeReviewCommit> result = new ArrayList<>();
    try {
      result.addAll(mergeSorter.sort(toSort));
    } catch (IOException | StorageException e) {
      throw new StorageException("Branch head sorting failed", e);
    }
    result.sort(CodeReviewCommit.ORDER);
    return result;
  }

  public CodeReviewCommit createCherryPickFromCommit(
      ObjectInserter inserter,
      Config repoConfig,
      RevCommit mergeTip,
      RevCommit originalCommit,
      PersonIdent cherryPickCommitterIdent,
      String commitMsg,
      CodeReviewRevWalk rw,
      int parentIndex,
      boolean ignoreIdenticalTree,
      boolean allowConflicts,
      boolean diff3Format,
      AttributesNodeProvider attributesNodeProvider)
      throws IOException,
          MergeIdenticalTreeException,
          MergeConflictException,
          MethodNotAllowedException,
          InvalidMergeStrategyException {

    ThreeWayMerger m = newThreeWayMerger(inserter, repoConfig, attributesNodeProvider);
    RevCommit baseCommit = originalCommit.getParent(parentIndex);
    m.setBase(baseCommit);

    DirCache dc = DirCache.newInCore();
    if (allowConflicts && m instanceof ResolveMerger) {
      // The DirCache must be set on ResolveMerger before calling
      // ResolveMerger#merge(AnyObjectId...) otherwise the entries in DirCache don't get populated.
      ((ResolveMerger) m).setDirCache(dc);
    }

    ObjectId tree;
    ImmutableSet<String> filesWithGitConflicts;
    if (m.merge(mergeTip, originalCommit)) {
      filesWithGitConflicts = null;
      tree = m.getResultTreeId();
      logger.atFine().log(
          "CherryPick treeId=%s (no conflicts, inserter: %s)", tree.name(), m.getObjectInserter());
      if (tree.equals(mergeTip.getTree()) && !ignoreIdenticalTree) {
        throw new MergeIdenticalTreeException("identical tree");
      }
    } else {
      if (!allowConflicts) {
        throw new MergeConflictException(
            String.format(
                "merge conflict while merging commits %s and %s",
                mergeTip.toObjectId(), originalCommit.toObjectId()));
      }

      if (!useContentMerge) {
        // If content merge is disabled we don't have a ResolveMerger and hence cannot merge with
        // conflict markers.
        throw new MethodNotAllowedException(
            "Cherry-pick with allow conflicts requires that content merge is enabled.");
      }

      // For merging with conflict markers we need a ResolveMerger, double-check that we have one.
      checkState(m instanceof ResolveMerger, "allow conflicts is not supported");

      if (m.getResultTreeId() != null) {
        // Merging with conflicts below uses the same DirCache instance that has been used by the
        // Merger to attempt the merge without conflicts.
        //
        // The Merger uses the DirCache to do the updates, and in particular to write the result
        // tree. DirCache caches a single DirCacheTree instance that is used to write the result
        // tree, but it writes the result tree only if there were no conflicts.
        //
        // Merging with conflicts uses the same DirCache instance to write the tree with conflicts
        // that has been used by the Merger. This means if the Merger unexpectedly wrote a result
        // tree although there had been conflicts, then merging with conflicts uses the same
        // DirCacheTree instance to write the tree with conflicts. However DirCacheTree#writeTree
        // writes a tree only once and then that tree is cached. Further invocations of
        // DirCacheTree#writeTree have no effect and return the previously created tree. This means
        // merging with conflicts can only successfully create the tree with conflicts if the Merger
        // didn't write a result tree yet. Hence this is checked here and we log a warning if the
        // result tree was already written.
        logger.atWarning().log(
            "result tree has already been written: %s (merge: %s, conflicts: %s, failed: %s)",
            m,
            m.getResultTreeId().name(),
            ((ResolveMerger) m).getUnmergedPaths(),
            ((ResolveMerger) m).getFailingPaths());
      }

      Map<String, MergeResult<? extends Sequence>> mergeResults =
          ((ResolveMerger) m).getMergeResults();

      filesWithGitConflicts =
          mergeResults.entrySet().stream()
              .filter(e -> e.getValue().containsConflicts())
              .map(Map.Entry::getKey)
              .collect(toImmutableSet());

      tree =
          mergeWithConflicts(
              rw,
              inserter,
              dc,
              "BASE",
              MergeBase.create(baseCommit),
              "HEAD",
              mergeTip,
              "CHANGE",
              originalCommit,
              mergeResults,
              diff3Format);
      logger.atFine().log(
          "AutoMerge treeId=%s (with conflicts, inserter: %s)", tree.name(), inserter);
    }

    CommitBuilder cherryPickCommit = new CommitBuilder();
    cherryPickCommit.setTreeId(tree);
    cherryPickCommit.setParentId(mergeTip);
    cherryPickCommit.setAuthor(originalCommit.getAuthorIdent());
    cherryPickCommit.setCommitter(cherryPickCommitterIdent);
    cherryPickCommit.setMessage(commitMsg);
    matchAuthorToCommitterDate(project, cherryPickCommit);
    CodeReviewCommit commit = rw.parseCommit(inserter.insert(cherryPickCommit));
    commit.setConflicts(
        baseCommit, mergeTip, originalCommit, mergeStrategyName(), filesWithGitConflicts);
    logger.atFine().log("CherryPick commitId=%s", commit.name());
    return commit;
  }

  @SuppressWarnings("resource") // TemporaryBuffer requires calling close before reading.
  public static ObjectId mergeWithConflicts(
      RevWalk rw,
      ObjectInserter ins,
      DirCache dc,
      String baseName,
      MergeBase base,
      String oursName,
      RevCommit ours,
      String theirsName,
      RevCommit theirs,
      Map<String, MergeResult<? extends Sequence>> mergeResults,
      boolean diff3Format)
      throws IOException {
    int nameLength = Math.max(Math.max(oursName.length(), theirsName.length()), baseName.length());

    String baseDescription =
        switch (base.getKind()) {
          case BASE_COMMIT -> {
            rw.parseBody(base.baseCommit());
            String baseMsg = base.baseCommit().getShortMessage();
            yield String.format(
                "%s %s",
                base.baseCommit().getName(), baseMsg.substring(0, Math.min(baseMsg.length(), 60)));
          }
          case NO_BASE_REASON -> base.noBaseReason().getDescription();
        };
    String baseNameFormatted =
        String.format("%-" + nameLength + "s (%s)", baseName, baseDescription);

    rw.parseBody(ours);
    rw.parseBody(theirs);
    String oursMsg = ours.getShortMessage();
    String theirsMsg = theirs.getShortMessage();

    String oursNameFormatted =
        String.format(
            "%-" + nameLength + "s (%s %s)",
            oursName,
            ours.getName(),
            oursMsg.substring(0, Math.min(oursMsg.length(), 60)));
    String theirsNameFormatted =
        String.format(
            "%-" + nameLength + "s (%s %s)",
            theirsName,
            theirs.getName(),
            theirsMsg.substring(0, Math.min(theirsMsg.length(), 60)));

    MergeFormatter fmt = new MergeFormatter();
    Map<String, ObjectId> resolved = new HashMap<>();
    for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResults.entrySet()) {
      MergeResult<? extends Sequence> p = entry.getValue();
      TemporaryBuffer buf = null;
      try {
        // TODO(dborowitz): Respect inCoreLimit here.
        buf = new TemporaryBuffer.LocalFile(null, 10 * 1024 * 1024);
        if (diff3Format) {
          fmt.formatMergeDiff3(
              buf, p, baseNameFormatted, oursNameFormatted, theirsNameFormatted, UTF_8);
        } else {
          fmt.formatMerge(buf, p, baseNameFormatted, oursNameFormatted, theirsNameFormatted, UTF_8);
        }
        buf.close(); // Flush file and close for writes, but leave available for reading.

        try (InputStream in = buf.openInputStream()) {
          resolved.put(entry.getKey(), ins.insert(Constants.OBJ_BLOB, buf.length(), in));
        }
      } finally {
        if (buf != null) {
          buf.destroy();
        }
      }
    }

    DirCacheBuilder builder = dc.builder();
    int cnt = dc.getEntryCount();
    for (int i = 0; i < cnt; ) {
      DirCacheEntry entry = dc.getEntry(i);
      if (entry.getStage() == 0) {
        builder.add(entry);
        i++;
        continue;
      }

      int next = dc.nextEntry(i);
      String path = entry.getPathString();
      DirCacheEntry res = new DirCacheEntry(path);
      if (resolved.containsKey(path)) {
        // For a file with content merge conflict that we produced a result
        // above on, collapse the file down to a single stage 0 with just
        // the blob content, and a randomly selected mode (the lowest stage,
        // which should be the merge base, or ours).
        res.setFileMode(entry.getFileMode());
        res.setObjectId(resolved.get(path));

      } else if (next == i + 1) {
        // If there is exactly one stage present, shouldn't be a conflict...
        res.setFileMode(entry.getFileMode());
        res.setObjectId(entry.getObjectId());

      } else if (next == i + 2) {
        // Two stages suggests a delete/modify conflict. Pick the higher
        // stage as the automatic result.
        entry = dc.getEntry(i + 1);
        res.setFileMode(entry.getFileMode());
        res.setObjectId(entry.getObjectId());

      } else {
        // 3 stage conflict, no resolve above
        // Punt on the 3-stage conflict and show the base, for now.
        res.setFileMode(entry.getFileMode());
        res.setObjectId(entry.getObjectId());
      }
      builder.add(res);
      i = next;
    }
    builder.finish();
    return dc.writeTree(ins);
  }

  public static CodeReviewCommit createMergeCommit(
      ObjectInserter inserter,
      Config repoConfig,
      RevCommit mergeTip,
      RevCommit originalCommit,
      String mergeStrategy,
      boolean allowConflicts,
      PersonIdent authorIdent,
      PersonIdent committerIdent,
      String commitMsg,
      CodeReviewRevWalk rw,
      boolean diff3Format)
      throws IOException,
          MergeIdenticalTreeException,
          MergeConflictException,
          InvalidMergeStrategyException {

    if (!MergeStrategy.THEIRS.getName().equals(mergeStrategy)
        && rw.isMergedInto(originalCommit, mergeTip)) {
      throw new ChangeAlreadyMergedException(
          "'" + originalCommit.getName() + "' has already been merged");
    }

    Merger m = newMerger(inserter, repoConfig, mergeStrategy);

    DirCache dc = DirCache.newInCore();
    if (allowConflicts && m instanceof ResolveMerger) {
      // The DirCache must be set on ResolveMerger before calling
      // ResolveMerger#merge(AnyObjectId...) otherwise the entries in DirCache don't get populated.
      ((ResolveMerger) m).setDirCache(dc);
    }

    ObjectId tree;
    ImmutableSet<String> filesWithGitConflicts;
    MergeBase mergeBase;
    if (m.merge(false, mergeTip, originalCommit)) {
      mergeBase = MergeBase.create(rw, mergeStrategy, m.getBaseCommitId());
      filesWithGitConflicts = null;
      tree = m.getResultTreeId();
    } else {
      List<String> conflicts = ImmutableList.of();
      Map<String, ResolveMerger.MergeFailureReason> failed = ImmutableMap.of();
      if (m instanceof ResolveMerger) {
        conflicts = ((ResolveMerger) m).getUnmergedPaths();
        failed = ((ResolveMerger) m).getFailingPaths();
      }

      if (m.getResultTreeId() != null) {
        // Merging with conflicts below uses the same DirCache instance that has been used by the
        // Merger to attempt the merge without conflicts.
        //
        // The Merger uses the DirCache to do the updates, and in particular to write the result
        // tree. DirCache caches a single DirCacheTree instance that is used to write the result
        // tree, but it writes the result tree only if there were no conflicts.
        //
        // Merging with conflicts uses the same DirCache instance to write the tree with conflicts
        // that has been used by the Merger. This means if the Merger unexpectedly wrote a result
        // tree although there had been conflicts, then merging with conflicts uses the same
        // DirCacheTree instance to write the tree with conflicts. However DirCacheTree#writeTree
        // writes a tree only once and then that tree is cached. Further invocations of
        // DirCacheTree#writeTree have no effect and return the previously created tree. This means
        // merging with conflicts can only successfully create the tree with conflicts if the Merger
        // didn't write a result tree yet. Hence this is checked here and we log a warning if the
        // result tree was already written.
        logger.atWarning().log(
            "result tree has already been written: %s (merge: %s, conflicts: %s, failed: %s)",
            m, m.getResultTreeId().name(), conflicts, failed);
      }

      if (!allowConflicts) {
        throw new MergeConflictException(createConflictMessage(conflicts));
      }

      // For merging with conflict markers we need a ResolveMerger, double-check that we have one.
      if (!(m instanceof ResolveMerger)) {
        throw new MergeWithConflictsNotSupportedException(MergeStrategy.get(mergeStrategy));
      }
      Map<String, MergeResult<? extends Sequence>> mergeResults =
          ((ResolveMerger) m).getMergeResults();

      filesWithGitConflicts =
          mergeResults.entrySet().stream()
              .filter(e -> e.getValue().containsConflicts())
              .map(Map.Entry::getKey)
              .collect(toImmutableSet());

      mergeBase = MergeBase.create(rw, mergeStrategy, m.getBaseCommitId());
      tree =
          mergeWithConflicts(
              rw,
              inserter,
              dc,
              "BASE",
              mergeBase,
              "TARGET BRANCH",
              mergeTip,
              "SOURCE BRANCH",
              originalCommit,
              mergeResults,
              diff3Format);
    }

    CommitBuilder mergeCommit = new CommitBuilder();
    mergeCommit.setTreeId(tree);
    mergeCommit.setParentIds(mergeTip, originalCommit);
    mergeCommit.setAuthor(authorIdent);
    mergeCommit.setCommitter(committerIdent);
    mergeCommit.setMessage(commitMsg);
    CodeReviewCommit commit = rw.parseCommit(inserter.insert(mergeCommit));

    switch (mergeBase.getKind()) {
      case BASE_COMMIT ->
          commit.setConflicts(
              mergeBase.baseCommit(),
              mergeTip,
              originalCommit,
              mergeStrategy,
              filesWithGitConflicts);
      case NO_BASE_REASON ->
          commit.setConflictsBaseNotAvailable(
              mergeTip,
              originalCommit,
              mergeStrategy,
              mergeBase.noBaseReason(),
              filesWithGitConflicts);
    }
    return commit;
  }

  public static String createConflictMessage(List<String> filesWithConflicts) {
    StringBuilder sb = new StringBuilder("merge conflict(s)");

    // If the simple-two-way-in-core merge strategy was used, we don't know which files had
    // conflicts and filesWithConflicts is empty.
    if (!filesWithConflicts.isEmpty()) {
      StringJoiner joiner = new StringJoiner("\n* ", ":\n* ", "\n");
      filesWithConflicts.forEach(joiner::add);
      sb.append(joiner.toString());
    }

    return sb.toString();
  }

  /**
   * Adds footers to existing commit message based on the state of the change.
   *
   * <p>This adds the following footers if they are missing:
   *
   * <ul>
   *   <li>Reviewed-on: <i>url</i>
   *   <li>Reviewed-by | Tested-by | <i>Other-Label-Name</i>: <i>reviewer</i>
   *   <li>Change-Id
   * </ul>
   *
   * @return new message
   */
  private String createDetailedCommitMessage(RevCommit n, ChangeNotes notes, PatchSet.Id psId) {
    Change c = notes.getChange();
    final List<FooterLine> footers = n.getFooterLines();
    final StringBuilder msgbuf = new StringBuilder();
    msgbuf.append(n.getFullMessage());

    if (msgbuf.length() == 0) {
      // WTF, an empty commit message?
      msgbuf.append("<no commit message provided>");
    }
    if (msgbuf.charAt(msgbuf.length() - 1) != '\n') {
      // Missing a trailing LF? Correct it (perhaps the editor was broken).
      msgbuf.append('\n');
    }
    if (footers.isEmpty()) {
      // Doesn't end in a "Signed-off-by: ..." style line? Add another line
      // break to start a new paragraph for the reviewed-by tag lines.
      //
      msgbuf.append('\n');
    }

    if (changeUtil.getChangeIdsFromFooter(n).isEmpty()) {
      msgbuf.append(FooterConstants.CHANGE_ID.getName());
      msgbuf.append(": ");
      msgbuf.append(c.getKey().get());
      msgbuf.append('\n');
    }

    Optional<String> url = urlFormatter.get().getChangeViewUrl(c.getProject(), c.getId());
    if (url.isPresent()) {
      if (!contains(footers, FooterConstants.REVIEWED_ON, url.get())) {
        msgbuf
            .append(FooterConstants.REVIEWED_ON.getName())
            .append(": ")
            .append(url.get())
            .append('\n');
      }
    }
    PatchSetApproval submitAudit = null;

    for (PatchSetApproval a : safeGetApprovals(notes, psId)) {
      if (a.value() <= 0) {
        // Negative votes aren't counted.
        continue;
      }

      if (a.isLegacySubmit()) {
        // Submit is treated specially, below (becomes committer)
        //
        if (submitAudit == null || a.granted().compareTo(submitAudit.granted()) > 0) {
          submitAudit = a;
        }
        continue;
      }

      final Account acc = identifiedUserFactory.create(a.accountId()).getAccount();
      final StringBuilder identbuf = new StringBuilder();
      if (acc.fullName() != null && acc.fullName().length() > 0) {
        if (identbuf.length() > 0) {
          identbuf.append(' ');
        }
        identbuf.append(acc.fullName());
      }
      if (acc.preferredEmail() != null && acc.preferredEmail().length() > 0) {
        if (isSignedOffBy(footers, acc.preferredEmail())) {
          continue;
        }
        if (identbuf.length() > 0) {
          identbuf.append(' ');
        }
        identbuf.append('<');
        identbuf.append(acc.preferredEmail());
        identbuf.append('>');
      }
      if (identbuf.length() == 0) {
        // Nothing reasonable to describe them by? Ignore them.
        continue;
      }

      final String tag;
      if (isCodeReview(a.labelId())) {
        tag = "Reviewed-by";
      } else if (isVerified(a.labelId())) {
        tag = "Tested-by";
      } else {
        final Optional<LabelType> lt = project.getLabelTypes().byLabel(a.labelId());
        if (!lt.isPresent()) {
          continue;
        }
        tag = lt.get().getName();
      }

      if (!contains(footers, new FooterKey(tag), identbuf.toString())) {
        msgbuf.append(tag);
        msgbuf.append(": ");
        msgbuf.append(identbuf);
        msgbuf.append('\n');
      }
    }
    return msgbuf.toString();
  }

  public String createCommitMessageOnSubmit(CodeReviewCommit n, RevCommit mergeTip) {
    return createCommitMessageOnSubmit(n, mergeTip, n.notes(), n.getPatchsetId());
  }

  /**
   * Creates a commit message for a change, which can be customized by plugins.
   *
   * <p>By default, adds footers to existing commit message based on the state of the change.
   * Plugins implementing {@link ChangeMessageModifier} can modify the resulting commit message
   * arbitrarily.
   *
   * @return new message
   */
  public String createCommitMessageOnSubmit(
      RevCommit n, @Nullable RevCommit mergeTip, ChangeNotes notes, PatchSet.Id id) {
    return commitMessageGenerator.generate(
        n, mergeTip, notes.getChange().getDest(), createDetailedCommitMessage(n, notes, id));
  }

  private static boolean isCodeReview(LabelId id) {
    return LabelId.CODE_REVIEW.equalsIgnoreCase(id.get());
  }

  private static boolean isVerified(LabelId id) {
    return LabelId.VERIFIED.equalsIgnoreCase(id.get());
  }

  private Iterable<PatchSetApproval> safeGetApprovals(ChangeNotes notes, PatchSet.Id psId) {
    try {
      return approvalsUtil.byPatchSet(notes, psId);
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log("Can't read approval records for %s", psId);
      return Collections.emptyList();
    }
  }

  private static boolean contains(List<FooterLine> footers, FooterKey key, String val) {
    for (FooterLine line : footers) {
      if (line.matches(key) && val.equals(line.getValue())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSignedOffBy(List<FooterLine> footers, String email) {
    for (FooterLine line : footers) {
      if (line.matches(FooterKey.SIGNED_OFF_BY) && email.equals(line.getEmailAddress())) {
        return true;
      }
    }
    return false;
  }

  public boolean canMerge(
      MergeSorter mergeSorter,
      Repository repo,
      CodeReviewCommit mergeTip,
      CodeReviewCommit toMerge) {
    if (hasMissingDependencies(mergeSorter, toMerge)) {
      logger.atFine().log("%s cannot be merged due to missing dependencies", toMerge.name());
      return false;
    }

    return canMerge(mergeTip, repo, toMerge);
  }

  private boolean canMerge(CodeReviewCommit mergeTip, Repository repo, CodeReviewCommit toMerge) {
    try (ObjectInserter ins = new InMemoryInserter(repo)) {
      return newThreeWayMerger(ins, repo).merge(mergeTip, toMerge);
    } catch (LargeObjectException e) {
      logger.atWarning().log("%s cannot be merged due to LargeObjectException", toMerge.name());
      return false;
    } catch (NoMergeBaseException e) {
      logger.atFine().log(
          "%s cannot be merged because no merge base could be found", toMerge.name());
      return false;
    } catch (IOException e) {
      throw new StorageException("Cannot merge " + toMerge.name(), e);
    }
  }

  public boolean canFastForward(
      MergeSorter mergeSorter,
      CodeReviewCommit mergeTip,
      CodeReviewRevWalk rw,
      CodeReviewCommit toMerge) {
    if (hasMissingDependencies(mergeSorter, toMerge)) {
      return false;
    }

    return canFastForward(mergeTip, rw, toMerge);
  }

  private boolean canFastForward(
      CodeReviewCommit mergeTip, CodeReviewRevWalk rw, CodeReviewCommit toMerge) {
    try {
      return mergeTip == null
          || rw.isMergedInto(mergeTip, toMerge)
          || rw.isMergedInto(toMerge, mergeTip);
    } catch (IOException e) {
      throw new StorageException("Cannot fast-forward test during merge", e);
    }
  }

  public boolean canFastForwardOrMerge(
      MergeSorter mergeSorter,
      CodeReviewCommit mergeTip,
      CodeReviewRevWalk rw,
      Repository repo,
      CodeReviewCommit toMerge) {
    if (hasMissingDependencies(mergeSorter, toMerge)) {
      return false;
    }

    return canFastForward(mergeTip, rw, toMerge) || canMerge(mergeTip, repo, toMerge);
  }

  public boolean canCherryPick(
      MergeSorter mergeSorter,
      Repository repo,
      CodeReviewCommit mergeTip,
      CodeReviewRevWalk rw,
      CodeReviewCommit toMerge) {
    if (mergeTip == null) {
      // The branch is unborn. Fast-forward is possible.
      //
      return true;
    }

    if (toMerge.getParentCount() == 0) {
      // Refuse to merge a root commit into an existing branch,
      // we cannot obtain a delta for the cherry-pick to apply.
      //
      return false;
    }

    if (toMerge.getParentCount() == 1) {
      // If there is only one parent, a cherry-pick can be done by
      // taking the delta relative to that one parent and redoing
      // that on the current merge tip.
      //
      try (ObjectInserter ins = new InMemoryInserter(repo)) {
        ThreeWayMerger m = newThreeWayMerger(ins, repo);
        m.setBase(toMerge.getParent(0));
        return m.merge(mergeTip, toMerge);
      } catch (IOException e) {
        throw new StorageException(
            String.format(
                "Cannot merge commit %s with mergetip %s", toMerge.name(), mergeTip.name()),
            e);
      }
    }

    // There are multiple parents, so this is a merge commit. We
    // don't want to cherry-pick the merge as clients can't easily
    // rebase their history with that merge present and replaced
    // by an equivalent merge with a different first parent. So
    // instead behave as though MERGE_IF_NECESSARY was configured.
    //
    return canFastForwardOrMerge(mergeSorter, mergeTip, rw, repo, toMerge);
  }

  public boolean hasMissingDependencies(MergeSorter mergeSorter, CodeReviewCommit toMerge) {
    try {
      return !mergeSorter.sort(Collections.singleton(toMerge)).contains(toMerge);
    } catch (IOException | StorageException e) {
      throw new StorageException("Branch head sorting failed", e);
    }
  }

  public CodeReviewCommit mergeOneCommit(
      PersonIdent author,
      PersonIdent committer,
      CodeReviewRevWalk rw,
      ObjectInserter inserter,
      Config repoConfig,
      BranchNameKey destBranch,
      CodeReviewCommit mergeTip,
      CodeReviewCommit n,
      AttributesNodeProvider attributesNodeProvider)
      throws InvalidMergeStrategyException {
    ThreeWayMerger m = newThreeWayMerger(inserter, repoConfig, attributesNodeProvider);
    try {
      if (m.merge(mergeTip, n)) {
        return writeMergeCommit(
            author, committer, rw, inserter, destBranch, mergeTip, m.getResultTreeId(), n);
      }
      failed(rw, mergeTip, n, CommitMergeStatus.PATH_CONFLICT);
    } catch (NoMergeBaseException e) {
      try {
        failed(rw, mergeTip, n, getCommitMergeStatus(e.getReason()));
      } catch (IOException e2) {
        throw new StorageException("Cannot merge " + n.name(), e2);
      }
    } catch (IOException e) {
      throw new StorageException("Cannot merge " + n.name(), e);
    }
    return mergeTip;
  }

  private static CommitMergeStatus getCommitMergeStatus(MergeBaseFailureReason reason) {
    switch (reason) {
      case MULTIPLE_MERGE_BASES_NOT_SUPPORTED:
      case TOO_MANY_MERGE_BASES:
      default:
        return CommitMergeStatus.MANUAL_RECURSIVE_MERGE;
      case CONFLICTS_DURING_MERGE_BASE_CALCULATION:
        return CommitMergeStatus.PATH_CONFLICT;
    }
  }

  /**
   * Marks all commits that are reachable from the given commit {@code n} as failed by setting the
   * provided {@code failure} status code on them.
   *
   * <p>If the same commits are retrieved from the same {@link CodeReviewRevWalk} instance later the
   * status code that we set here can be read there.
   */
  private static void failed(
      CodeReviewRevWalk rw,
      CodeReviewCommit mergeTip,
      CodeReviewCommit n,
      CommitMergeStatus failure)
      throws MissingObjectException, IncorrectObjectTypeException, IOException {
    rw.reset();
    rw.markStart(n);
    rw.markUninteresting(mergeTip);
    CodeReviewCommit failed;
    while ((failed = rw.next()) != null) {
      failed.setStatusCode(failure);
    }
  }

  public CodeReviewCommit writeMergeCommit(
      PersonIdent author,
      PersonIdent committer,
      CodeReviewRevWalk rw,
      ObjectInserter inserter,
      BranchNameKey destBranch,
      CodeReviewCommit mergeTip,
      ObjectId treeId,
      CodeReviewCommit n)
      throws IOException, MissingObjectException, IncorrectObjectTypeException {
    final List<CodeReviewCommit> merged = new ArrayList<>();
    rw.reset();
    rw.markStart(n);
    rw.markUninteresting(mergeTip);
    CodeReviewCommit crc;
    while ((crc = rw.next()) != null) {
      if (crc.getPatchsetId() != null) {
        merged.add(crc);
      }
    }

    StringBuilder msgbuf = new StringBuilder().append(summarize(rw, merged));
    if (!R_HEADS_MASTER.equals(destBranch.branch())) {
      msgbuf.append(" into ");
      msgbuf.append(destBranch.shortName());
    }

    if (merged.size() > 1) {
      msgbuf.append("\n\n* changes:\n");
      for (CodeReviewCommit c : merged) {
        rw.parseBody(c);
        msgbuf.append("  ");
        msgbuf.append(c.getShortMessage());
        msgbuf.append("\n");
      }
    }

    final CommitBuilder mergeCommit = new CommitBuilder();
    mergeCommit.setTreeId(treeId);
    mergeCommit.setParentIds(mergeTip, n);
    mergeCommit.setAuthor(author);
    mergeCommit.setCommitter(committer);
    mergeCommit.setMessage(msgbuf.toString());

    CodeReviewCommit mergeResult = rw.parseCommit(inserter.insert(mergeCommit));
    mergeResult.setNotes(n.getNotes());
    return mergeResult;
  }

  private String summarize(RevWalk rw, List<CodeReviewCommit> merged) throws IOException {
    if (merged.size() == 1) {
      CodeReviewCommit c = merged.get(0);
      rw.parseBody(c);
      return String.format("Merge \"%s\"", c.getShortMessage());
    }

    ImmutableSortedSet<String> topics =
        merged.stream()
            .map(c -> c.change().getTopic())
            .filter(t -> !Strings.isNullOrEmpty(t))
            .map(t -> "\"" + t + "\"")
            .collect(toImmutableSortedSet(naturalOrder()));

    if (!topics.isEmpty()) {
      return String.format(
          "Merge changes from topic%s %s",
          topics.size() > 1 ? "s" : "", topics.stream().collect(joining(", ")));
    }
    return merged.stream()
        .limit(5)
        .map(c -> c.change().getKey().abbreviate())
        .collect(joining(",", "Merge changes ", merged.size() > 5 ? ", ..." : ""));
  }

  public ThreeWayMerger newThreeWayMerger(ObjectInserter inserter, Repository repo)
      throws InvalidMergeStrategyException {
    return newThreeWayMerger(inserter, repo.getConfig(), repo.createAttributesNodeProvider());
  }

  public ThreeWayMerger newThreeWayMerger(
      ObjectInserter inserter, Config repoConfig, AttributesNodeProvider attributesNodeProvider)
      throws InvalidMergeStrategyException {
    return newThreeWayMerger(
        inserter,
        repoConfig,
        attributesNodeProvider,
        mergeStrategyName(),
        useGitattributesForMerge);
  }

  public String mergeStrategyName() {
    return mergeStrategyName(useContentMerge, useRecursiveMerge);
  }

  public static String mergeStrategyName(boolean useContentMerge, boolean useRecursiveMerge) {
    String mergeStrategy;

    if (useContentMerge) {
      // Settings for this project allow us to try and automatically resolve
      // conflicts within files if needed. Use either the old resolve merger or
      // new recursive merger, and instruct to operate in core.
      if (useRecursiveMerge) {
        mergeStrategy = MergeStrategy.RECURSIVE.getName();
      } else {
        mergeStrategy = MergeStrategy.RESOLVE.getName();
      }
    } else {
      // No auto conflict resolving allowed. If any of the
      // affected files was modified, merge will fail.
      mergeStrategy = MergeStrategy.SIMPLE_TWO_WAY_IN_CORE.getName();
    }

    logger.atFine().log(
        "mergeStrategy = %s (useContentMerge = %s, useRecursiveMerge = %s)",
        mergeStrategy, useContentMerge, useRecursiveMerge);
    return mergeStrategy;
  }

  public static ThreeWayMerger newThreeWayMerger(
      ObjectInserter inserter,
      Config repoConfig,
      AttributesNodeProvider attributesNodeProvider,
      String strategyName,
      boolean useGitattributesForMerge)
      throws InvalidMergeStrategyException {
    Merger m = newMerger(inserter, repoConfig, strategyName);
    checkArgument(
        m instanceof ThreeWayMerger,
        "merge strategy %s does not support three-way merging",
        strategyName);
    if (m instanceof ResolveMerger && useGitattributesForMerge) {
      ((ResolveMerger) m).setAttributesNodeProvider(attributesNodeProvider);
    }
    return (ThreeWayMerger) m;
  }

  public static Merger newMerger(ObjectInserter inserter, Config repoConfig, String strategyName)
      throws InvalidMergeStrategyException {
    MergeStrategy strategy = MergeStrategy.get(strategyName);
    if (strategy == null) {
      throw new InvalidMergeStrategyException(strategyName);
    }
    return strategy.newMerger(
        new ObjectInserter.Filter() {
          @Override
          protected ObjectInserter delegate() {
            return inserter;
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}

          @Override
          public String toString() {
            return String.format(
                "%s (wrapped inserter: %s)", super.toString(), inserter.toString());
          }
        },
        repoConfig);
  }

  public void markCleanMerges(
      RevWalk rw, RevFlag canMergeFlag, CodeReviewCommit mergeTip, Set<RevCommit> alreadyAccepted) {
    if (mergeTip == null) {
      // If mergeTip is null here, branchTip was null, indicating a new branch
      // at the start of the merge process. We also elected to merge nothing,
      // probably due to missing dependencies. Nothing was cleanly merged.
      //
      return;
    }

    try {
      rw.resetRetain(canMergeFlag);
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.REVERSE, true);
      rw.markStart(mergeTip);
      for (RevCommit c : alreadyAccepted) {
        // If branch was not created by this submit.
        if (!Objects.equals(c, mergeTip)) {
          rw.markUninteresting(c);
        }
      }

      CodeReviewCommit c;
      while ((c = (CodeReviewCommit) rw.next()) != null) {
        if (c.getPatchsetId() != null && c.getStatusCode() == null) {
          c.setStatusCode(CommitMergeStatus.CLEAN_MERGE);
        }
      }
    } catch (IOException e) {
      throw new StorageException("Cannot mark clean merges", e);
    }
  }

  public Set<Change.Id> findUnmergedChanges(
      Set<Change.Id> expected,
      CodeReviewRevWalk rw,
      RevFlag canMergeFlag,
      CodeReviewCommit oldTip,
      CodeReviewCommit mergeTip,
      Iterable<Change.Id> alreadyMerged) {
    if (mergeTip == null) {
      return expected;
    }

    try {
      Set<Change.Id> found = Sets.newHashSetWithExpectedSize(expected.size());
      Iterables.addAll(found, alreadyMerged);
      rw.resetRetain(canMergeFlag);
      rw.sort(RevSort.TOPO);
      rw.markStart(mergeTip);
      if (oldTip != null) {
        rw.markUninteresting(oldTip);
      }

      CodeReviewCommit c;
      while ((c = rw.next()) != null) {
        if (c.getPatchsetId() == null) {
          continue;
        }
        Change.Id id = c.getPatchsetId().changeId();
        if (!expected.contains(id)) {
          continue;
        }
        found.add(id);
        if (found.size() == expected.size()) {
          return Collections.emptySet();
        }
      }
      return Sets.difference(expected, found);
    } catch (IOException e) {
      throw new StorageException("Cannot check if changes were merged", e);
    }
  }

  @Nullable
  public static CodeReviewCommit findAnyMergedInto(
      CodeReviewRevWalk rw, Iterable<CodeReviewCommit> commits, CodeReviewCommit tip)
      throws IOException {
    for (CodeReviewCommit c : commits) {
      // TODO(dborowitz): Seems like this could get expensive for many patch
      // sets. Is there a more efficient implementation?
      if (rw.isMergedInto(c, tip)) {
        return c;
      }
    }
    return null;
  }

  public static RevCommit resolveCommit(Repository repo, RevWalk rw, String str)
      throws BadRequestException, ResourceNotFoundException, IOException {
    try {
      ObjectId commitId = repo.resolve(str);
      if (commitId == null) {
        throw new BadRequestException("Cannot resolve '" + str + "' to a commit");
      }
      return rw.parseCommit(commitId);
    } catch (AmbiguousObjectException | IncorrectObjectTypeException | RevisionSyntaxException e) {
      throw new BadRequestException(e.getMessage());
    } catch (MissingObjectException e) {
      throw new ResourceNotFoundException(e.getMessage());
    }
  }

  private static void matchAuthorToCommitterDate(ProjectState project, CommitBuilder commit) {
    if (project.is(BooleanProjectConfig.MATCH_AUTHOR_TO_COMMITTER_DATE)) {
      commit.setAuthor(
          new PersonIdent(
              commit.getAuthor(),
              commit.getCommitter().getWhenAsInstant(),
              commit.getCommitter().getZoneId()));
    }
  }

  @AutoOneOf(MergeBase.Kind.class)
  public abstract static class MergeBase {
    public enum Kind {
      BASE_COMMIT,
      NO_BASE_REASON
    }

    public abstract Kind getKind();

    public abstract RevCommit baseCommit();

    public abstract NoMergeBaseReason noBaseReason();

    public static MergeBase create(
        RevWalk rw, String mergeStrategy, @Nullable ObjectId baseCommitId) throws IOException {
      if (baseCommitId != null) {
        try {
          RevCommit baseCommit = rw.parseCommit(baseCommitId);
          return AutoOneOf_MergeUtil_MergeBase.baseCommit(baseCommit);
        } catch (MissingObjectException e) {
          // RecursiveMerger performs a content merge, if necessary across multiple bases, using
          // recursion to compute a usable base and trying to parse this computed base fails with a
          // MissingObjectException.
          return AutoOneOf_MergeUtil_MergeBase.noBaseReason(NoMergeBaseReason.COMPUTED_BASE);
        }
      }

      if ("ours".equals(mergeStrategy) || "theirs".equals(mergeStrategy)) {
        return AutoOneOf_MergeUtil_MergeBase.noBaseReason(
            NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
      }

      // baseCommitId is null if the merged commits do not have a common predecessor
      // (e.g. if 2 initial commits or 2 commits with unrelated histories are merged)
      return AutoOneOf_MergeUtil_MergeBase.noBaseReason(NoMergeBaseReason.NO_COMMON_ANCESTOR);
    }

    public static MergeBase create(RevCommit baseCommit) {
      return AutoOneOf_MergeUtil_MergeBase.baseCommit(baseCommit);
    }
  }
}
