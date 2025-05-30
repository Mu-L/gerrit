// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.query.change;

import static com.google.gerrit.server.project.ProjectCache.illegalState;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AttentionSetUpdate;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.ChangeMessage;
import com.google.gerrit.entities.Comment;
import com.google.gerrit.entities.HumanComment;
import com.google.gerrit.entities.LabelTypes;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.entities.SubmitRecord;
import com.google.gerrit.entities.SubmitRequirement;
import com.google.gerrit.entities.SubmitRequirementResult;
import com.google.gerrit.entities.SubmitRequirementResult.Status;
import com.google.gerrit.entities.SubmitTypeRecord;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.index.RefState;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.DraftCommentsReader;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.ReviewerByEmailSet;
import com.google.gerrit.server.ReviewerSet;
import com.google.gerrit.server.ReviewerStatusUpdate;
import com.google.gerrit.server.StarredChangesReader;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.change.CommentThread;
import com.google.gerrit.server.change.CommentThreads;
import com.google.gerrit.server.change.MergeabilityCache;
import com.google.gerrit.server.change.PureRevert;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SkipCurrentRulesEvaluationOnClosedChanges;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeUtilFactory;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.patch.DiffSummary;
import com.google.gerrit.server.patch.DiffSummaryKey;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListKey;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SubmitRequirementsAdapter;
import com.google.gerrit.server.project.SubmitRequirementsEvaluator;
import com.google.gerrit.server.project.SubmitRequirementsUtil;
import com.google.gerrit.server.project.SubmitRuleEvaluator;
import com.google.gerrit.server.project.SubmitRuleOptions;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * ChangeData provides lazily loaded interface to change metadata loaded from NoteDb. It can be
 * constructed by loading from NoteDb, or calling setters. The latter happens when ChangeData is
 * retrieved through the change index. This happens for Applications that are performance sensitive
 * (eg. dashboard loads, git protocol negotiation) but can tolerate staleness. In that case, setting
 * lazyLoad=false disables loading from NoteDb, so we don't accidentally enable a slow path.
 */
public class ChangeData {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public enum StorageConstraint {
    /**
     * This instance was loaded from the change index. Backfilling missing data from NoteDb is not
     * allowed.
     */
    INDEX_ONLY,
    /**
     * This instance was loaded from the change index. Backfilling missing data from NoteDb is
     * allowed.
     */
    INDEX_PRIMARY_NOTEDB_SECONDARY,
    /** This instance was loaded from NoteDb. */
    NOTEDB_ONLY
  }

  public static List<Change> asChanges(List<ChangeData> changeDatas) {
    List<Change> result = new ArrayList<>(changeDatas.size());
    for (ChangeData cd : changeDatas) {
      result.add(cd.change());
    }
    return result;
  }

  public static Map<Change.Id, ChangeData> asMap(List<ChangeData> changes) {
    return changes.stream().collect(toMap(ChangeData::getId, Function.identity()));
  }

  public static void ensureChangeLoaded(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      @SuppressWarnings("unused")
      var unused = cd.change();
    }
  }

  public static void ensureAllPatchSetsLoaded(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      @SuppressWarnings("unused")
      var unused = cd.patchSets();
    }
  }

  public static void ensureCurrentPatchSetLoaded(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      @SuppressWarnings("unused")
      var unused = cd.currentPatchSet();
    }
  }

  public static void ensureCurrentApprovalsLoaded(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      @SuppressWarnings("unused")
      var unused = cd.currentApprovals();
    }
  }

  public static void ensureMessagesLoaded(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      @SuppressWarnings("unused")
      var unused = cd.messages();
    }
  }

  public static void ensureReviewedByLoadedForOpenChanges(Iterable<ChangeData> changes) {
    List<ChangeData> pending = new ArrayList<>();
    for (ChangeData cd : changes) {
      if (cd.reviewedBy == null && cd.change().isNew()) {
        pending.add(cd);
      }
    }

    if (!pending.isEmpty()) {
      ensureAllPatchSetsLoaded(pending);
      ensureMessagesLoaded(pending);
      for (ChangeData cd : pending) {
        @SuppressWarnings("unused")
        var unused = cd.reviewedBy();
      }
    }
  }

  public static class Factory {
    private final AssistedFactory assistedFactory;

    @Inject
    Factory(AssistedFactory assistedFactory) {
      this.assistedFactory = assistedFactory;
    }

    public ChangeData create(Project.NameKey project, Change.Id id) {
      return assistedFactory.create(project, id, null, null, null);
    }

    public ChangeData create(Project.NameKey project, Change.Id id, ObjectId metaRevision) {
      ChangeData cd = assistedFactory.create(project, id, null, null, null);
      cd.setMetaRevision(metaRevision);
      return cd;
    }

    public ChangeData createNonPrivate(BranchNameKey branch, Change.Id id, ObjectId metaRevision) {
      ChangeData cd = create(branch.project(), id, metaRevision);
      cd.branch = branch.branch();
      cd.isPrivate = false;
      return cd;
    }

    public ChangeData create(Change change) {
      return create(change, null);
    }

    public ChangeData create(Change change, Change.Id virtualId) {
      return assistedFactory.create(
          change.getProject(),
          change.getId(),
          !Objects.equals(virtualId, change.getId()) ? virtualId : null,
          change,
          null);
    }

    public ChangeData create(ChangeNotes notes) {
      return assistedFactory.create(
          notes.getChange().getProject(), notes.getChangeId(), null, notes.getChange(), notes);
    }
  }

  public interface AssistedFactory {
    ChangeData create(
        Project.NameKey project,
        @Assisted("changeId") Change.Id id,
        @Assisted("virtualId") @Nullable Change.Id virtualId,
        @Nullable Change change,
        @Nullable ChangeNotes notes);
  }

  /**
   * Create an instance for testing only.
   *
   * <p>Attempting to lazy load data will fail with NPEs. Callers may consider manually setting
   * fields that can be set.
   *
   * @param project project name
   * @param id change ID
   * @param currentPatchSetId current patchset number
   * @param commitId commit SHA1 of the current patchset
   * @return instance for testing.
   */
  public static ChangeData createForTest(
      Project.NameKey project, Change.Id id, int currentPatchSetId, ObjectId commitId) {
    return createForTest(project, id, currentPatchSetId, commitId, null, null);
  }

  /**
   * Create an instance for testing only.
   *
   * <p>Attempting to lazy load data will fail with NPEs. Callers may consider manually setting
   * fields that can be set.
   *
   * @param project project name
   * @param id change ID
   * @param currentPatchSetId current patchset number
   * @param commitId commit SHA1 of the current patchset
   * @param virtualIdAlgo algorithm for virtualising the Change number
   * @param changeNotes notes associated with the Change
   * @return instance for testing.
   */
  public static ChangeData createForTest(
      Project.NameKey project,
      Change.Id id,
      int currentPatchSetId,
      ObjectId commitId,
      ChangeNumberVirtualIdAlgorithm virtualIdAlgo,
      @Nullable ChangeNotes changeNotes) {
    ChangeData cd =
        new ChangeData(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            virtualIdAlgo,
            false,
            project,
            id,
            null,
            null,
            changeNotes);
    cd.currentPatchSet =
        PatchSet.builder()
            .id(PatchSet.id(id, currentPatchSetId))
            .commitId(commitId)
            .uploader(Account.id(1000))
            .realUploader(Account.id(1000))
            .createdOn(TimeUtil.now())
            .build();
    return cd;
  }

  // Injected fields.
  private @Nullable final StarredChangesReader starredChangesReader;
  private final AllUsersName allUsersName;
  private final ApprovalsUtil approvalsUtil;
  private final ChangeMessagesUtil cmUtil;
  private final ChangeNotes.Factory notesFactory;
  private final CommentsUtil commentsUtil;

  private final DraftCommentsReader draftCommentsReader;
  private final GitRepositoryManager repoManager;
  private final MergeUtilFactory mergeUtilFactory;
  private final MergeabilityCache mergeabilityCache;
  private final PatchListCache patchListCache;
  private final PatchSetUtil psUtil;
  private final ProjectCache projectCache;
  private final TrackingFooters trackingFooters;
  private final PureRevert pureRevert;
  private final boolean propagateSubmitRequirementErrors;

  private final SubmitRequirementsEvaluator submitRequirementsEvaluator;
  private final SubmitRequirementsUtil submitRequirementsUtil;
  private final SubmitRuleEvaluator.Factory submitRuleEvaluatorFactory;
  private final boolean skipCurrentRulesEvaluationOnClosedChanges;

  // Required assisted injected fields.
  private final Project.NameKey project;
  private final Change.Id legacyId;

  // Lazily populated fields, including optional assisted injected fields.

  private final Map<SubmitRuleOptions, List<SubmitRecord>> submitRecords =
      Maps.newLinkedHashMapWithExpectedSize(1);

  private Map<SubmitRequirement, SubmitRequirementResult> submitRequirements;

  private StorageConstraint storageConstraint = StorageConstraint.NOTEDB_ONLY;
  private Change change;
  private ChangeNotes notes;
  private String commitMessage;
  private List<FooterLine> commitFooters;
  private PatchSet currentPatchSet;
  private Collection<PatchSet> patchSets;
  private ListMultimap<PatchSet.Id, PatchSetApproval> allApprovals;

  private ListMultimap<PatchSet.Id, PatchSetApproval> allApprovalsWithCopied;
  private List<PatchSetApproval> currentApprovals;
  private List<String> currentFiles;
  private Optional<DiffSummary> diffSummary;
  private List<HumanComment> publishedComments;
  private CurrentUser visibleTo;
  private List<ChangeMessage> messages;
  private Optional<ChangedLines> changedLines;
  private SubmitTypeRecord submitTypeRecord;
  private String branch;
  private Boolean isPrivate;
  private Boolean mergeable;
  private ObjectId metaRevision;
  private Set<String> hashtags;
  private ImmutableMap<String, String> customKeyedValues;

  /**
   * Map from {@link com.google.gerrit.entities.Account.Id} to the tip of the edit ref for this
   * change and a given user.
   */
  private Table<Account.Id, PatchSet.Id, Ref> editRefsByUser;

  private Set<Account.Id> reviewedBy;

  /**
   * Map from {@link com.google.gerrit.entities.Account.Id} to the tip of the draft comments ref for
   * this change and the user.
   */
  private Set<Account.Id> usersWithDrafts;

  private ImmutableList<Account.Id> stars;
  private Account.Id starredBy;
  private ImmutableList<Account.Id> starAccounts;
  private ReviewerSet reviewers;
  private ReviewerByEmailSet reviewersByEmail;
  private ReviewerSet pendingReviewers;
  private ReviewerByEmailSet pendingReviewersByEmail;
  private List<ReviewerStatusUpdate> reviewerUpdates;
  private PersonIdent author;
  private PersonIdent committer;
  private ImmutableSet<AttentionSetUpdate> attentionSet;
  private Integer parentCount;
  private Integer unresolvedCommentCount;
  private Integer totalCommentCount;
  private LabelTypes labelTypes;
  private Optional<Instant> mergedOn;
  private ImmutableSetMultimap<Project.NameKey, RefState> refStates;
  private ImmutableList<byte[]> refStatePatterns;
  private String changeServerId;
  private final ChangeNumberVirtualIdAlgorithm virtualIdFunc;
  private Boolean failedParsingFromIndex = false;
  private Change.Id virtualId;

  @Inject
  private ChangeData(
      @Nullable StarredChangesReader starredChangesReader,
      ApprovalsUtil approvalsUtil,
      AllUsersName allUsersName,
      ChangeMessagesUtil cmUtil,
      ChangeNotes.Factory notesFactory,
      CommentsUtil commentsUtil,
      DraftCommentsReader draftCommentsReader,
      GitRepositoryManager repoManager,
      MergeUtilFactory mergeUtilFactory,
      MergeabilityCache mergeabilityCache,
      PatchListCache patchListCache,
      PatchSetUtil psUtil,
      ProjectCache projectCache,
      TrackingFooters trackingFooters,
      PureRevert pureRevert,
      @GerritServerConfig Config serverConfig,
      SubmitRequirementsEvaluator submitRequirementsEvaluator,
      SubmitRequirementsUtil submitRequirementsUtil,
      SubmitRuleEvaluator.Factory submitRuleEvaluatorFactory,
      ChangeNumberVirtualIdAlgorithm virtualIdFunc,
      @SkipCurrentRulesEvaluationOnClosedChanges Boolean skipCurrentRulesEvaluationOnClosedChange,
      @Assisted Project.NameKey project,
      @Assisted("changeId") Change.Id id,
      @Assisted("virtualId") @Nullable Change.Id virtualId,
      @Assisted @Nullable Change change,
      @Assisted @Nullable ChangeNotes notes) {
    this.approvalsUtil = approvalsUtil;
    this.allUsersName = allUsersName;
    this.cmUtil = cmUtil;
    this.notesFactory = notesFactory;
    this.commentsUtil = commentsUtil;
    this.draftCommentsReader = draftCommentsReader;
    this.repoManager = repoManager;
    this.mergeUtilFactory = mergeUtilFactory;
    this.mergeabilityCache = mergeabilityCache;
    this.patchListCache = patchListCache;
    this.psUtil = psUtil;
    this.projectCache = projectCache;
    this.starredChangesReader = starredChangesReader;
    this.trackingFooters = trackingFooters;
    this.pureRevert = pureRevert;
    this.propagateSubmitRequirementErrors =
        serverConfig != null
            ? serverConfig.getBoolean("change", "propagateSubmitRequirementErrors", false)
            : false;
    this.submitRequirementsEvaluator = submitRequirementsEvaluator;
    this.submitRequirementsUtil = submitRequirementsUtil;
    this.submitRuleEvaluatorFactory = submitRuleEvaluatorFactory;
    this.skipCurrentRulesEvaluationOnClosedChanges = skipCurrentRulesEvaluationOnClosedChange;

    this.project = project;
    this.legacyId = id;

    this.change = change;
    this.notes = notes;

    this.virtualIdFunc = virtualIdFunc;
    this.virtualId = virtualId;
  }

  /**
   * If false, omit fields that require database/repo IO.
   *
   * <p>This is used to enforce that the dashboard is rendered from the index only. If {@code
   * lazyLoad} is on, the {@code ChangeData} object will load from the database ("lazily") when a
   * field accessor is called.
   */
  @CanIgnoreReturnValue
  public ChangeData setStorageConstraint(StorageConstraint storageConstraint) {
    this.storageConstraint = storageConstraint;
    return this;
  }

  public StorageConstraint getStorageConstraint() {
    return storageConstraint;
  }

  /** Returns {@code true} if we allow reading data from NoteDb. */
  public boolean lazyload() {
    return storageConstraint.ordinal()
        >= StorageConstraint.INDEX_PRIMARY_NOTEDB_SECONDARY.ordinal();
  }

  public AllUsersName getAllUsersNameForIndexing() {
    return allUsersName;
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  public void setFailedParsingFromIndex(Boolean val) {
    this.failedParsingFromIndex = val;
  }

  public boolean hasFailedParsingFromIndex() {
    return failedParsingFromIndex;
  }

  @VisibleForTesting
  public void setCurrentFilePaths(List<String> filePaths) {
    PatchSet ps = currentPatchSet();
    if (ps != null) {
      currentFiles = ImmutableList.copyOf(filePaths);
    }
  }

  public List<String> currentFilePaths() {
    if (currentFiles == null) {
      if (!lazyload()) {
        return Collections.emptyList();
      }
      Optional<DiffSummary> p = getDiffSummary();
      currentFiles = p.map(DiffSummary::getPaths).orElse(Collections.emptyList());
    }
    return currentFiles;
  }

  private Optional<DiffSummary> getDiffSummary() {
    if (diffSummary == null) {
      if (!lazyload()) {
        return Optional.empty();
      }

      Change c = change();
      PatchSet ps = currentPatchSet();
      if (c == null || ps == null || !loadCommitData()) {
        return Optional.empty();
      }

      PatchListKey pk = PatchListKey.againstBase(ps.commitId(), parentCount);
      DiffSummaryKey key = DiffSummaryKey.fromPatchListKey(pk);
      try {
        diffSummary = Optional.of(patchListCache.getDiffSummary(key, c.getProject()));
      } catch (PatchListNotAvailableException e) {
        diffSummary = Optional.empty();
      }
    }
    return diffSummary;
  }

  private Optional<ChangedLines> computeChangedLines() {
    Optional<DiffSummary> ds = getDiffSummary();
    if (ds.isPresent()) {
      return Optional.of(ds.get().getChangedLines());
    }
    return Optional.empty();
  }

  public Optional<ChangedLines> changedLines() {
    if (changedLines == null) {
      if (!lazyload()) {
        return Optional.empty();
      }
      changedLines = computeChangedLines();
    }
    return changedLines;
  }

  public void setChangedLines(int insertions, int deletions) {
    changedLines = Optional.of(new ChangedLines(insertions, deletions));
  }

  public void setLinesInserted(int insertions) {
    changedLines =
        Optional.of(
            new ChangedLines(
                insertions,
                changedLines != null && changedLines.isPresent()
                    ? changedLines.get().deletions
                    : -1));
  }

  public void setLinesDeleted(int deletions) {
    changedLines =
        Optional.of(
            new ChangedLines(
                changedLines != null && changedLines.isPresent()
                    ? changedLines.get().insertions
                    : -1,
                deletions));
  }

  public void setNoChangedLines() {
    changedLines = Optional.empty();
  }

  public Change.Id getId() {
    return legacyId;
  }

  public static void ensureChangeServerId(Iterable<ChangeData> changes) {
    ChangeData first = Iterables.getFirst(changes, null);
    if (first == null) {
      return;
    }

    for (ChangeData cd : changes) {
      var unused = cd.changeServerId();
    }
  }

  @Nullable
  public String changeServerId() {
    if (changeServerId == null) {
      if (!lazyload()) {
        return null;
      }
      changeServerId = notes().getServerId();
    }
    return changeServerId;
  }

  public Change.Id virtualId() {
    if (virtualId == null) {
      virtualId = virtualIdFunc.apply(this::changeServerId, legacyId);
    }
    return virtualId;
  }

  @VisibleForTesting
  public void setVirtualId(int virtualId) {
    this.virtualId = Change.id(virtualId);
  }

  public Project.NameKey project() {
    return project;
  }

  public BranchNameKey branchOrThrow() {
    if (change == null) {
      if (branch != null) {
        return BranchNameKey.create(project, branch);
      }
      throwIfNotLazyLoad("branch");

      @SuppressWarnings("unused")
      var unused = change();
    }
    return change.getDest();
  }

  public boolean isPrivateOrThrow() {
    if (change == null) {
      if (isPrivate != null) {
        return isPrivate;
      }
      throwIfNotLazyLoad("isPrivate");

      @SuppressWarnings("unused")
      var unused = change();
    }
    return change.isPrivate();
  }

  @CanIgnoreReturnValue
  public ChangeData setMetaRevision(ObjectId metaRevision) {
    this.metaRevision = metaRevision;
    return this;
  }

  public Optional<ObjectId> metaRevision() {
    if (notes == null) {
      if (metaRevision != null) {
        return Optional.of(metaRevision);
      }
      if (refStates != null) {
        ImmutableSet<RefState> refs = refStates.get(project);
        if (refs != null) {
          String metaRef = RefNames.changeMetaRef(getId());
          for (RefState r : refs) {
            if (r.ref().equals(metaRef)) {
              return Optional.of(r.id());
            }
          }
        }
      }
      if (!lazyload()) {
        return Optional.empty();
      }

      @SuppressWarnings("unused")
      var unused = notes();
    }
    metaRevision = notes.getRevision();
    return Optional.of(metaRevision);
  }

  public ObjectId metaRevisionOrThrow() {
    return metaRevision()
        .orElseThrow(() -> new IllegalStateException("'metaRevision' field not populated"));
  }

  boolean fastIsVisibleTo(CurrentUser user) {
    return visibleTo == user;
  }

  void cacheVisibleTo(CurrentUser user) {
    visibleTo = user;
  }

  @Nullable
  public Change change() {
    if (change == null && lazyload()) {
      loadChange();
    }
    return change;
  }

  public void setChange(Change c) {
    change = c;
  }

  @CanIgnoreReturnValue
  public Change reloadChange() {
    metaRevision = null;
    return loadChange();
  }

  @CanIgnoreReturnValue
  private Change loadChange() {
    try {
      notes = notesFactory.createChecked(project, legacyId, metaRevision);
    } catch (NoSuchChangeException e) {
      throw new StorageException("Unable to load change " + legacyId, e);
    }
    change = notes.getChange();
    changeServerId = notes.getServerId();
    metaRevision = null;
    setPatchSets(null);
    return change;
  }

  public LabelTypes getLabelTypes() {
    if (labelTypes == null) {
      ProjectState state = projectCache.get(project()).orElseThrow(illegalState(project()));
      labelTypes = state.getLabelTypes(change().getDest());
    }
    return labelTypes;
  }

  public ChangeNotes notes() {
    if (notes == null) {
      if (!lazyload()) {
        throw new StorageException("ChangeNotes not available, lazyLoad = false");
      }
      notes = notesFactory.create(project(), legacyId, metaRevision);
      change = notes.getChange();
      setPatchSets(null);
    }
    return notes;
  }

  @Nullable
  public PatchSet currentPatchSet() {
    if (currentPatchSet == null) {
      Change c = change();
      if (c == null) {
        return null;
      }
      for (PatchSet p : patchSets()) {
        if (p.id().equals(c.currentPatchSetId())) {
          currentPatchSet = p;
          return p;
        }
      }
    }
    return currentPatchSet;
  }

  public List<PatchSetApproval> currentApprovals() {
    if (currentApprovals == null) {
      if (!lazyload()) {
        return Collections.emptyList();
      }
      Change c = change();
      if (c == null) {
        currentApprovals = Collections.emptyList();
      } else {
        try {
          currentApprovals =
              ImmutableList.copyOf(approvalsUtil.byPatchSet(notes(), c.currentPatchSetId()));
        } catch (StorageException e) {
          if (e.getCause() instanceof NoSuchChangeException) {
            currentApprovals = Collections.emptyList();
          } else {
            throw e;
          }
        }
      }
    }
    return currentApprovals;
  }

  public void setCurrentApprovals(List<PatchSetApproval> approvals) {
    currentApprovals = approvals;
  }

  @Nullable
  public String commitMessage() {
    if (commitMessage == null) {
      if (!loadCommitData()) {
        return null;
      }
    }
    return commitMessage;
  }

  /** Returns the list of commit footers (which may be empty). */
  public List<FooterLine> commitFooters() {
    if (commitFooters == null) {
      if (!loadCommitData()) {
        return ImmutableList.of();
      }
    }
    return commitFooters;
  }

  public ListMultimap<String, String> trackingFooters() {
    return trackingFooters.extract(commitFooters());
  }

  @Nullable
  public PersonIdent getAuthor() {
    if (author == null) {
      if (!loadCommitData()) {
        return null;
      }
    }
    return author;
  }

  @Nullable
  public PersonIdent getCommitter() {
    if (committer == null) {
      if (!loadCommitData()) {
        return null;
      }
    }
    return committer;
  }

  private boolean loadCommitData() {
    PatchSet ps = currentPatchSet();
    if (ps == null) {
      return false;
    }
    try (Repository repo = repoManager.openRepository(project());
        RevWalk walk = new RevWalk(repo)) {
      RevCommit c = walk.parseCommit(ps.commitId());
      commitMessage = c.getFullMessage();
      commitFooters = c.getFooterLines();
      author = c.getAuthorIdent();
      committer = c.getCommitterIdent();
      parentCount = c.getParentCount();
    } catch (IOException e) {
      throw new StorageException(
          String.format(
              "Loading commit %s for ps %d of change %d failed.",
              ps.commitId(), ps.id().get(), ps.id().changeId().get()),
          e);
    }
    return true;
  }

  /** Returns the most recent update (i.e. status) per user. */
  public ImmutableSet<AttentionSetUpdate> attentionSet() {
    if (attentionSet == null) {
      if (!lazyload()) {
        return ImmutableSet.of();
      }
      attentionSet = notes().getAttentionSet();
    }
    return attentionSet;
  }

  /**
   * Returns the {@link Optional} value of time when the change was merged.
   *
   * <p>The value can be set from index field, see {@link ChangeData#setMergedOn} or loaded from the
   * database (available in {@link ChangeNotes})
   *
   * @return {@link Optional} value of time when the change was merged.
   * @throws StorageException if {@code lazyLoad} is off, {@link ChangeNotes} can not be loaded
   *     because we do not expect to call the database.
   */
  public Optional<Instant> getMergedOn() throws StorageException {
    if (mergedOn == null) {
      // The value was not loaded yet, try to get from the database.
      mergedOn = notes().getMergedOn();
    }
    return mergedOn;
  }

  /** Sets the value e.g. when loading from index. */
  public void setMergedOn(@Nullable Instant mergedOn) {
    this.mergedOn = Optional.ofNullable(mergedOn);
  }

  /**
   * Sets the specified attention set. If two or more entries refer to the same user, throws an
   * {@link IllegalStateException}.
   */
  public void setAttentionSet(ImmutableSet<AttentionSetUpdate> attentionSet) {
    if (attentionSet.stream().map(AttentionSetUpdate::account).distinct().count()
        != attentionSet.size()) {
      throw new IllegalStateException(
          String.format(
              "Stored attention set for change %d contains duplicate update",
              change.getId().get()));
    }
    this.attentionSet = attentionSet;
  }

  /** Returns patches for the change, in patch set ID order. */
  public Collection<PatchSet> patchSets() {
    if (patchSets == null) {
      patchSets = psUtil.byChange(notes());
    }
    return patchSets;
  }

  public void setPatchSets(Collection<PatchSet> patchSets) {
    this.currentPatchSet = null;
    this.patchSets = patchSets;
  }

  /** Returns patch with the given ID, or null if it does not exist. */
  @Nullable
  public PatchSet patchSet(PatchSet.Id psId) {
    if (currentPatchSet != null && currentPatchSet.id().equals(psId)) {
      return currentPatchSet;
    }
    for (PatchSet ps : patchSets()) {
      if (ps.id().equals(psId)) {
        return ps;
      }
    }
    return null;
  }

  /**
   * Returns all patch set approvals for the change, keyed by ID, ordered by timestamp within each
   * patch set.
   */
  public ListMultimap<PatchSet.Id, PatchSetApproval> approvals() {
    if (allApprovals == null) {
      if (!lazyload()) {
        return ImmutableListMultimap.of();
      }
      allApprovals = approvalsUtil.byChangeExcludingCopiedApprovals(notes());
    }
    return allApprovals;
  }

  public ListMultimap<PatchSet.Id, PatchSetApproval> conditionallyLoadApprovalsWithCopied() {
    if (allApprovalsWithCopied == null) {
      if (!lazyload()) {
        return ImmutableListMultimap.of();
      }
      allApprovalsWithCopied = approvalsUtil.byChangeIncludingCopiedApprovals(notes());
    }
    return allApprovalsWithCopied;
  }

  /* @return legacy submit ('SUBM') approval label */
  // TODO(mariasavtchouk): Deprecate legacy submit label,
  // see com.google.gerrit.entities.LabelId.LEGACY_SUBMIT_NAME
  public Optional<PatchSetApproval> getSubmitApproval() {
    return currentApprovals().stream().filter(PatchSetApproval::isLegacySubmit).findFirst();
  }

  public ReviewerSet reviewers() {
    if (reviewers == null) {
      throwIfNotLazyLoad("reviewers");
      reviewers = approvalsUtil.getReviewers(notes());
    }
    return reviewers;
  }

  public void setReviewers(ReviewerSet reviewers) {
    this.reviewers = reviewers;
  }

  public ReviewerByEmailSet reviewersByEmail() {
    if (reviewersByEmail == null) {
      if (!lazyload()) {
        return ReviewerByEmailSet.empty();
      }
      reviewersByEmail = notes().getReviewersByEmail();
    }
    return reviewersByEmail;
  }

  public void setReviewersByEmail(ReviewerByEmailSet reviewersByEmail) {
    this.reviewersByEmail = reviewersByEmail;
  }

  public ReviewerByEmailSet getReviewersByEmail() {
    return reviewersByEmail;
  }

  public void setPendingReviewers(ReviewerSet pendingReviewers) {
    this.pendingReviewers = pendingReviewers;
  }

  public ReviewerSet getPendingReviewers() {
    return this.pendingReviewers;
  }

  public ReviewerSet pendingReviewers() {
    if (pendingReviewers == null) {
      if (!lazyload()) {
        return ReviewerSet.empty();
      }
      pendingReviewers = notes().getPendingReviewers();
    }
    return pendingReviewers;
  }

  public void setPendingReviewersByEmail(ReviewerByEmailSet pendingReviewersByEmail) {
    this.pendingReviewersByEmail = pendingReviewersByEmail;
  }

  public ReviewerByEmailSet getPendingReviewersByEmail() {
    return pendingReviewersByEmail;
  }

  public ReviewerByEmailSet pendingReviewersByEmail() {
    if (pendingReviewersByEmail == null) {
      if (!lazyload()) {
        return ReviewerByEmailSet.empty();
      }
      pendingReviewersByEmail = notes().getPendingReviewersByEmail();
    }
    return pendingReviewersByEmail;
  }

  public List<ReviewerStatusUpdate> reviewerUpdates() {
    if (reviewerUpdates == null) {
      if (!lazyload()) {
        return Collections.emptyList();
      }
      reviewerUpdates = approvalsUtil.getReviewerUpdates(notes());
    }
    return reviewerUpdates;
  }

  public void setReviewerUpdates(List<ReviewerStatusUpdate> reviewerUpdates) {
    this.reviewerUpdates = reviewerUpdates;
  }

  public List<ReviewerStatusUpdate> getReviewerUpdates() {
    return reviewerUpdates;
  }

  public Collection<HumanComment> publishedComments() {
    if (publishedComments == null) {
      if (!lazyload()) {
        return Collections.emptyList();
      }
      publishedComments = commentsUtil.publishedHumanCommentsByChange(notes());
    }
    return publishedComments;
  }

  @Nullable
  public Integer unresolvedCommentCount() {
    if (unresolvedCommentCount == null) {
      if (!lazyload()) {
        return null;
      }

      List<Comment> comments = publishedComments().stream().collect(toList());

      ImmutableSet<CommentThread<Comment>> commentThreads =
          CommentThreads.forComments(comments).getThreads();
      unresolvedCommentCount =
          (int) commentThreads.stream().filter(CommentThread::unresolved).count();
    }

    return unresolvedCommentCount;
  }

  public void setUnresolvedCommentCount(Integer count) {
    this.unresolvedCommentCount = count;
  }

  @Nullable
  public Integer totalCommentCount() {
    if (totalCommentCount == null) {
      if (!lazyload()) {
        return null;
      }

      // Fail on overflow.
      totalCommentCount = Ints.checkedCast((long) publishedComments().size());
    }
    return totalCommentCount;
  }

  public void setTotalCommentCount(Integer count) {
    this.totalCommentCount = count;
  }

  public List<ChangeMessage> messages() {
    if (messages == null) {
      if (!lazyload()) {
        return Collections.emptyList();
      }
      messages = cmUtil.byChange(notes());
    }
    return messages;
  }

  /**
   * Similar to {@link #submitRequirements()}, except that it also converts submit records resulting
   * from the evaluation of legacy submit rules to submit requirements.
   */
  public Map<SubmitRequirement, SubmitRequirementResult> submitRequirementsIncludingLegacy() {
    Map<SubmitRequirement, SubmitRequirementResult> projectConfigReqs = submitRequirements();
    ImmutableMap<SubmitRequirement, SubmitRequirementResult> legacyReqs =
        SubmitRequirementsAdapter.getLegacyRequirements(this);
    return submitRequirementsUtil.mergeLegacyAndNonLegacyRequirements(
        projectConfigReqs, legacyReqs, this);
  }

  /**
   * Get all evaluated submit requirements for this change, including those from parent projects.
   * For closed changes, submit requirements are read from the change notes. For active changes,
   * submit requirements are evaluated online.
   *
   * <p>For changes loaded from the index, the value will be set from index field {@link
   * com.google.gerrit.server.index.change.ChangeField#STORED_SUBMIT_REQUIREMENTS_FIELD}.
   */
  public Map<SubmitRequirement, SubmitRequirementResult> submitRequirements() {
    if (submitRequirements == null) {
      if (!lazyload()) {
        return Collections.emptyMap();
      }
      Change c = change();
      if (c == null || !c.isClosed()) {
        // Open changes: Evaluate submit requirements online.
        submitRequirements = submitRequirementsEvaluator.evaluateAllRequirements(this);
        if (propagateSubmitRequirementErrors) {
          for (SubmitRequirementResult result : submitRequirements.values()) {
            if (result.status() == Status.ERROR) {
              throw new IllegalStateException(result.errorMessage().orElse("(no message)"));
            }
          }
        }

        logger.atFine().log(
            "Submit requirements evaluated for open change: %s", submitRequirements);
        return submitRequirements;
      }
      // Closed changes: Load submit requirement results from NoteDb.
      submitRequirements =
          notes().getSubmitRequirementsResult().stream()
              .filter(r -> !r.isLegacy())
              .collect(Collectors.toMap(r -> r.submitRequirement(), Function.identity()));
      logger.atFine().log(
          "Submit requirements loaded from NoteDb for closed change: %s", submitRequirements);
    }
    return submitRequirements;
  }

  public void setSubmitRequirements(
      Map<SubmitRequirement, SubmitRequirementResult> submitRequirements) {
    this.submitRequirements = submitRequirements;
  }

  public List<SubmitRecord> submitRecords(SubmitRuleOptions options) {
    // If the change is not submitted yet, 'strict' and 'lenient' both have the same result. If the
    // change is submitted, SubmitRecord requested with 'strict' will contain just a single entry
    // that with status=CLOSED. The latter is cheap to evaluate as we don't have to run any actual
    // evaluation.
    List<SubmitRecord> records = submitRecords.get(options);
    if (records == null) {
      if (storageConstraint != StorageConstraint.NOTEDB_ONLY) {
        // Submit requirements are expensive. We allow loading them only if this change did not
        // originate from the change index and we can invest the extra time.
        logger.atWarning().log(
            "Tried to load SubmitRecords for change fetched from index %s: %d",
            project(), getId().get());
        return Collections.emptyList();
      }
      if (skipCurrentRulesEvaluationOnClosedChanges && change().isClosed()) {
        return notes().getSubmitRecords();
      }
      records = submitRuleEvaluatorFactory.create(options).evaluate(this);
      submitRecords.put(options, records);
      if (!change().isClosed() && submitRecords.size() == 1) {
        // Cache the SubmitRecord with allowClosed = !allowClosed as the SubmitRecord are the same.
        submitRecords.put(
            options.toBuilder()
                .recomputeOnClosedChanges(!options.recomputeOnClosedChanges())
                .build(),
            records);
      }
    }
    return records;
  }

  public void setSubmitRecords(SubmitRuleOptions options, List<SubmitRecord> records) {
    submitRecords.put(options, records);
  }

  public SubmitTypeRecord submitTypeRecord() {
    if (submitTypeRecord == null) {
      submitTypeRecord =
          submitRuleEvaluatorFactory.create(SubmitRuleOptions.defaults()).getSubmitType(this);
    }
    return submitTypeRecord;
  }

  public void setMergeable(Boolean mergeable) {
    this.mergeable = mergeable;
  }

  @Nullable
  public Boolean isMergeable() {
    if (mergeable == null) {
      Change c = change();
      if (c == null) {
        return null;
      }
      if (c.isMerged()) {
        mergeable = true;
      } else if (c.isAbandoned()) {
        return null;
      } else {
        if (!lazyload()) {
          return null;
        }
        PatchSet ps = currentPatchSet();
        if (ps == null) {
          return null;
        }

        try (Repository repo = repoManager.openRepository(project())) {
          Ref ref = repo.getRefDatabase().exactRef(c.getDest().branch());
          SubmitTypeRecord str = submitTypeRecord();
          if (!str.isOk()) {
            // If submit type rules are broken, it's definitely not mergeable.
            // No need to log, as SubmitRuleEvaluator already did it for us.
            return false;
          }
          String mergeStrategy =
              mergeUtilFactory
                  .create(projectCache.get(project()).orElseThrow(illegalState(project())))
                  .mergeStrategyName();
          mergeable =
              mergeabilityCache.get(ps.commitId(), ref, str.type, mergeStrategy, c.getDest(), repo);
        } catch (IOException e) {
          throw new StorageException(e);
        }
      }
    }
    return mergeable;
  }

  @Nullable
  public Boolean isMerge() {
    if (parentCount == null) {
      if (!loadCommitData()) {
        return null;
      }
    }
    return parentCount > 1;
  }

  public Set<Account.Id> editsByUser() {
    return editRefs().rowKeySet();
  }

  public Table<Account.Id, PatchSet.Id, Ref> editRefs() {
    if (editRefsByUser == null) {
      if (!lazyload()) {
        return HashBasedTable.create();
      }
      Change c = change();
      if (c == null) {
        return HashBasedTable.create();
      }
      editRefsByUser = HashBasedTable.create();
      Change.Id id = requireNonNull(change.getId());
      try (Repository repo = repoManager.openRepository(project())) {
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_USERS)) {
          if (!RefNames.isRefsEdit(ref.getName())) {
            continue;
          }
          PatchSet.Id ps = PatchSet.Id.fromEditRef(ref.getName());
          if (id.equals(ps.changeId())) {
            Account.Id accountId = Account.Id.fromRef(ref.getName());
            if (accountId != null) {
              editRefsByUser.put(accountId, ps, ref);
            }
          }
        }
      } catch (IOException e) {
        throw new StorageException(e);
      }
    }
    return editRefsByUser;
  }

  public Set<Account.Id> draftsByUser() {
    if (usersWithDrafts == null) {
      if (!lazyload()) {
        return Collections.emptySet();
      }
      Change c = change();
      if (c == null) {
        return Collections.emptySet();
      }
      usersWithDrafts = draftCommentsReader.getUsersWithDrafts(notes());
    }
    return usersWithDrafts;
  }

  public boolean isReviewedBy(Account.Id accountId) {
    return reviewedBy().contains(accountId);
  }

  public Set<Account.Id> reviewedBy() {
    if (reviewedBy == null) {
      if (!lazyload()) {
        return Collections.emptySet();
      }
      Change c = change();
      if (c == null) {
        return Collections.emptySet();
      }
      List<ReviewedByEvent> events = new ArrayList<>();
      for (ChangeMessage msg : messages()) {
        if (msg.getAuthor() != null) {
          events.add(ReviewedByEvent.create(msg));
        }
      }
      events = Lists.reverse(events);
      reviewedBy = new LinkedHashSet<>();
      Account.Id owner = c.getOwner();
      for (ReviewedByEvent event : events) {
        if (owner.equals(event.author())) {
          break;
        }
        reviewedBy.add(event.author());
      }
    }
    return reviewedBy;
  }

  public void setReviewedBy(Set<Account.Id> reviewedBy) {
    this.reviewedBy = reviewedBy;
  }

  public Set<String> hashtags() {
    if (hashtags == null) {
      if (!lazyload()) {
        return Collections.emptySet();
      }
      hashtags = notes().getHashtags();
    }
    return hashtags;
  }

  public void setHashtags(Set<String> hashtags) {
    this.hashtags = hashtags;
  }

  public Map<String, String> customKeyedValues() {
    if (customKeyedValues == null) {
      if (!lazyload()) {
        return Collections.emptyMap();
      }
      customKeyedValues = notes().getCustomKeyedValues();
    }
    return customKeyedValues;
  }

  public void setCustomKeyedValues(Map<String, String> customKeyedValues) {
    this.customKeyedValues = ImmutableMap.copyOf(customKeyedValues);
  }

  public ImmutableList<Account.Id> stars() {
    if (stars == null) {
      if (!lazyload()) {
        return ImmutableList.of();
      }
      return starAccounts();
    }
    return stars;
  }

  public void setStars(List<Account.Id> accountIds) {
    this.stars = ImmutableList.copyOf(accountIds);
  }

  private ImmutableList<Account.Id> starAccounts() {
    if (starAccounts == null) {
      if (!lazyload()) {
        return ImmutableList.of();
      }
      starAccounts = requireNonNull(starredChangesReader).byChange(virtualId());
    }
    return starAccounts;
  }

  public boolean isStarred(Account.Id accountId) {
    if (starredBy != null) {
      if (!starredBy.equals(accountId)) {
        starredBy = null;
      }
    }
    if (starredBy == null) {
      if (stars != null && stars.contains(accountId)) {
        starredBy = accountId;
      } else {
        if (!lazyload()) {
          return false;
        }
        if (starredChangesReader.isStarred(accountId, legacyId)) {
          starredBy = accountId;
        }
      }
    }
    return starredBy != null;
  }

  /**
   * Returns {@code null} if {@code revertOf} is {@code null}; true if the change is a pure revert;
   * false otherwise.
   */
  @Nullable
  public Boolean isPureRevert() {
    if (change().getRevertOf() == null) {
      return null;
    }
    try {
      return pureRevert.get(notes(), Optional.empty());
    } catch (IOException | BadRequestException | ResourceConflictException e) {
      throw new StorageException("could not compute pure revert", e);
    }
  }

  @Override
  public String toString() {
    MoreObjects.ToStringHelper h = MoreObjects.toStringHelper(this);
    if (change != null) {
      h.addValue(change);
    } else {
      h.addValue(legacyId);
    }
    return h.toString();
  }

  public static class ChangedLines {
    public final int insertions;
    public final int deletions;

    public ChangedLines(int insertions, int deletions) {
      this.insertions = insertions;
      this.deletions = deletions;
    }
  }

  public SetMultimap<Project.NameKey, RefState> getRefStates() {
    if (refStates == null) {
      if (!lazyload()) {
        return ImmutableSetMultimap.of();
      }

      ImmutableSetMultimap.Builder<Project.NameKey, RefState> result =
          ImmutableSetMultimap.builder();
      for (Table.Cell<Account.Id, PatchSet.Id, Ref> edit : editRefs().cellSet()) {
        result.put(
            project,
            RefState.create(
                RefNames.refsEdit(
                    edit.getRowKey(), edit.getColumnKey().changeId(), edit.getColumnKey()),
                edit.getValue().getObjectId()));
      }

      // TODO: instantiating the notes is too much. We don't want to parse NoteDb, we just want the
      // refs.
      result.put(project, RefState.create(notes().getRefName(), notes().getMetaId()));

      refStates = result.build();
    }

    return refStates;
  }

  public void setRefStates(ImmutableSetMultimap<Project.NameKey, RefState> refStates) {
    this.refStates = refStates;
    if (usersWithDrafts == null) {
      // Recover draft state as well.
      // ChangeData exposes #draftsByUser which just provides a Set of Account.Ids of users who
      // have drafts comments on this change. Recovering this list from RefStates makes it
      // available even on ChangeData instances retrieved from the index.
      usersWithDrafts = new HashSet<>();
      if (refStates.containsKey(allUsersName)) {
        refStates.get(allUsersName).stream()
            .filter(r -> RefNames.isRefsDraftsComments(r.ref()))
            .forEach(r -> usersWithDrafts.add(Account.Id.fromRef(r.ref())));
      }
    }
  }

  public ImmutableList<byte[]> getRefStatePatterns() {
    return refStatePatterns;
  }

  public void setRefStatePatterns(Iterable<byte[]> refStatePatterns) {
    this.refStatePatterns = ImmutableList.copyOf(refStatePatterns);
  }

  private void throwIfNotLazyLoad(String field) {
    if (!lazyload()) {
      // We are not allowed to load values from NoteDb. 'field' was not populated, however,
      // we need this value for permission checks.
      throw new IllegalStateException("'" + field + "' field not populated");
    }
  }

  @AutoValue
  abstract static class ReviewedByEvent {
    private static ReviewedByEvent create(ChangeMessage msg) {
      return new AutoValue_ChangeData_ReviewedByEvent(msg.getAuthor(), msg.getWrittenOn());
    }

    public abstract Account.Id author();

    public abstract Instant ts();
  }
}
