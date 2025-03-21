// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.server.change;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.gerrit.entities.RefNames.REFS_CHANGES;
import static com.google.gerrit.server.ChangeUtil.PS_ID_ORDER;
import static com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.CHANGE_MODIFICATION;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.SubmissionId;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.FixInput;
import com.google.gerrit.extensions.common.ProblemInfo;
import com.google.gerrit.extensions.common.ProblemInfo.Status;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.account.Accounts;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.PatchSetState;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.RepoContext;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Checks changes for various kinds of inconsistency and corruption.
 *
 * <p>A single instance may be reused for checking multiple changes, but not concurrently.
 */
public class ConsistencyChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @AutoValue
  public abstract static class Result {
    private static Result create(ChangeNotes notes, List<ProblemInfo> problems) {
      return new AutoValue_ConsistencyChecker_Result(
          notes.getChangeId(), notes.getChange(), ImmutableList.copyOf(problems));
    }

    public abstract Change.Id id();

    @Nullable
    public abstract Change change();

    public abstract ImmutableList<ProblemInfo> problems();
  }

  private final ChangeNotes.Factory notesFactory;
  private final Accounts accounts;
  private final PluginItemContext<AccountPatchReviewStore> accountPatchReviewStore;
  private final GitRepositoryManager repoManager;
  private final PatchSetInfoFactory patchSetInfoFactory;
  private final PatchSetInserter.Factory patchSetInserterFactory;
  private final PatchSetUtil psUtil;
  private final Provider<CurrentUser> user;
  private final Provider<PersonIdent> serverIdent;
  private final RetryHelper retryHelper;
  private final ChangeUtil changeUtil;

  private BatchUpdate.Factory updateFactory;
  private FixInput fix;
  private ChangeNotes notes;
  private Repository repo;
  private RevWalk rw;
  private ObjectInserter oi;

  private RevCommit tip;
  private SetMultimap<ObjectId, PatchSet> patchSetsBySha;
  private PatchSet currPs;
  private RevCommit currPsCommit;

  private List<ProblemInfo> problems;

  @Inject
  ConsistencyChecker(
      @GerritPersonIdent Provider<PersonIdent> serverIdent,
      ChangeNotes.Factory notesFactory,
      Accounts accounts,
      PluginItemContext<AccountPatchReviewStore> accountPatchReviewStore,
      GitRepositoryManager repoManager,
      PatchSetInfoFactory patchSetInfoFactory,
      PatchSetInserter.Factory patchSetInserterFactory,
      PatchSetUtil psUtil,
      Provider<CurrentUser> user,
      RetryHelper retryHelper,
      ChangeUtil changeUtil) {
    this.accounts = accounts;
    this.accountPatchReviewStore = accountPatchReviewStore;
    this.notesFactory = notesFactory;
    this.patchSetInfoFactory = patchSetInfoFactory;
    this.patchSetInserterFactory = patchSetInserterFactory;
    this.psUtil = psUtil;
    this.repoManager = repoManager;
    this.retryHelper = retryHelper;
    this.serverIdent = serverIdent;
    this.user = user;
    this.changeUtil = changeUtil;
    reset();
  }

  private void reset() {
    updateFactory = null;
    notes = null;
    repo = null;
    rw = null;
    problems = new ArrayList<>();
  }

  private Change change() {
    return notes.getChange();
  }

  public Result check(ChangeNotes notes, @Nullable FixInput f) {
    requireNonNull(notes);
    try {
      try (RefUpdateContext ctx = RefUpdateContext.open(CHANGE_MODIFICATION)) {
        return retryHelper
            .changeUpdate(
                "checkChangeConsistency",
                buf -> {
                  try {
                    reset();
                    this.updateFactory = buf;
                    this.notes = notes;
                    fix = f;
                    checkImpl();
                    return result();
                  } finally {
                    if (rw != null) {
                      rw.getObjectReader().close();
                      rw.close();
                      oi.close();
                    }
                    if (repo != null) {
                      repo.close();
                    }
                  }
                })
            .call();
      }
    } catch (RestApiException e) {
      return logAndReturnOneProblem(e, notes, "Error checking change: " + e.getMessage());
    } catch (UpdateException e) {
      return logAndReturnOneProblem(e, notes, "Error checking change");
    }
  }

  private Result logAndReturnOneProblem(Exception e, ChangeNotes notes, String problem) {
    logger.atWarning().withCause(e).log("Error checking change %s", notes.getChangeId());
    return Result.create(notes, ImmutableList.of(problem(problem)));
  }

  private void checkImpl() {
    checkOwner();
    checkCurrentPatchSetEntity();

    // All checks that require the repo.
    if (!openRepo()) {
      return;
    }
    if (!checkPatchSets()) {
      return;
    }
    checkMerged();
  }

  private void checkOwner() {
    try {
      if (!accounts.get(change().getOwner()).isPresent()) {
        problem("Missing change owner: " + change().getOwner());
      }
    } catch (IOException | ConfigInvalidException e) {
      ProblemInfo problem = problem("Failed to look up owner");
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
    }
  }

  private void checkCurrentPatchSetEntity() {
    try {
      currPs = psUtil.current(notes);
      if (currPs == null) {
        problem(
            String.format("Current patch set %d not found", change().currentPatchSetId().get()));
      }
    } catch (StorageException e) {
      ProblemInfo problem = problem("Failed to look up current patch set");
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
    }
  }

  private boolean openRepo() {
    Project.NameKey project = change().getDest().project();
    try {
      repo = repoManager.openRepository(project);
      oi = repo.newObjectInserter();
      rw = new RevWalk(oi.newReader());
      return true;
    } catch (RepositoryNotFoundException e) {
      ProblemInfo problem = problem("Destination repository not found: " + project);
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
      return false;
    } catch (IOException e) {
      ProblemInfo problem = problem("Failed to open repository: " + project);
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
      return false;
    }
  }

  private boolean checkPatchSets() {
    List<PatchSet> all;
    try {
      // Iterate in descending order.
      all = PS_ID_ORDER.sortedCopy(psUtil.byChange(notes));
    } catch (StorageException e) {
      ProblemInfo problem = problem("Failed to look up patch sets");
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
      return false;
    }
    patchSetsBySha = MultimapBuilder.hashKeys(all.size()).treeSetValues(PS_ID_ORDER).build();

    Map<String, Ref> refs;
    try {
      refs =
          repo.getRefDatabase()
              .exactRef(all.stream().map(ps -> ps.id().toRefName()).toArray(String[]::new));
    } catch (IOException e) {
      ProblemInfo problem = problem("Error reading refs");
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
      refs = Collections.emptyMap();
    }

    List<DeletePatchSetFromDbOp> deletePatchSetOps = new ArrayList<>();
    for (PatchSet ps : all) {
      // Check revision format.
      int psNum = ps.id().get();
      String refName = ps.id().toRefName();
      ObjectId objId = ps.commitId();
      patchSetsBySha.put(objId, ps);

      // Check ref existence.
      ProblemInfo refProblem = null;
      Ref ref = refs.get(refName);
      if (ref == null) {
        refProblem = problem("Ref missing: " + refName);
      } else if (!objId.equals(ref.getObjectId())) {
        String actual = ref.getObjectId() != null ? ref.getObjectId().name() : "null";
        refProblem =
            problem(
                String.format(
                    "Expected %s to point to %s, found %s", ref.getName(), objId.name(), actual));
      }

      // Check object existence.
      RevCommit psCommit = parseCommit(objId, String.format("patch set %d", psNum));
      if (psCommit == null) {
        if (fix != null && fix.deletePatchSetIfCommitMissing) {
          deletePatchSetOps.add(new DeletePatchSetFromDbOp(lastProblem(), ps.id()));
        }
        continue;
      } else if (refProblem != null && fix != null) {
        fixPatchSetRef(refProblem, ps);
      }
      if (ps.id().equals(change().currentPatchSetId())) {
        currPsCommit = psCommit;
      }
    }

    // Delete any bad patch sets found above, in a single update.
    deletePatchSets(deletePatchSetOps);

    // Check for duplicates.
    for (Map.Entry<ObjectId, Collection<PatchSet>> e : patchSetsBySha.asMap().entrySet()) {
      if (e.getValue().size() > 1) {
        problem(
            String.format(
                "Multiple patch sets pointing to %s: %s",
                e.getKey().name(), Collections2.transform(e.getValue(), PatchSet::number)));
      }
    }

    return currPs != null && currPsCommit != null;
  }

  private void checkMerged() {
    String refName = change().getDest().branch();
    Ref dest;
    try {
      dest = repo.getRefDatabase().exactRef(refName);
    } catch (IOException e) {
      problem("Failed to look up destination ref: " + refName);
      return;
    }
    if (dest == null) {
      problem("Destination ref not found (may be new branch): " + refName);
      return;
    }
    tip = parseCommit(dest.getObjectId(), "destination ref " + refName);
    if (tip == null) {
      return;
    }

    if (fix != null && fix.expectMergedAs != null) {
      checkExpectMergedAs();
    } else {
      boolean merged;
      try {
        merged = rw.isMergedInto(currPsCommit, tip);
      } catch (IOException e) {
        problem("Error checking whether patch set " + currPs.id().get() + " is merged");
        return;
      }
      checkMergedBitMatchesStatus(currPs.id(), currPsCommit, merged);
    }
  }

  private ProblemInfo wrongChangeStatus(PatchSet.Id psId, RevCommit commit) {
    String refName = change().getDest().branch();
    return problem(
        formatProblemMessage(
            "Patch set %d (%s) is merged into destination ref %s (%s), but change"
                + " status is %s",
            psId.get(), commit.name(), refName, tip.name()));
  }

  private void checkMergedBitMatchesStatus(PatchSet.Id psId, RevCommit commit, boolean merged) {
    String refName = change().getDest().branch();
    if (merged && !change().isMerged()) {
      ProblemInfo p = wrongChangeStatus(psId, commit);
      if (fix != null) {
        fixMerged(p);
      }
    } else if (!merged && change().isMerged()) {
      problem(
          formatProblemMessage(
              "Patch set %d (%s) is not merged into"
                  + " destination ref %s (%s), but change status is %s",
              currPs.id().get(), commit.name(), refName, tip.name()));
    }
  }

  private String formatProblemMessage(
      String message, int psId, String commitName, String refName, String tipName) {
    return String.format(
        message,
        psId,
        commitName,
        refName,
        tipName,
        ChangeUtil.status(change()).toUpperCase(Locale.US));
  }

  private void checkExpectMergedAs() {
    if (!ObjectId.isId(fix.expectMergedAs)) {
      problem("Invalid revision on expected merged commit: " + fix.expectMergedAs);
      return;
    }
    ObjectId objId = ObjectId.fromString(fix.expectMergedAs);
    RevCommit commit = parseCommit(objId, "expected merged commit");
    if (commit == null) {
      return;
    }

    try {
      if (!rw.isMergedInto(commit, tip)) {
        problem(
            String.format(
                "Expected merged commit %s is not merged into destination ref %s (%s)",
                commit.name(), change().getDest().branch(), tip.name()));
        return;
      }

      List<PatchSet.Id> thisCommitPsIds = new ArrayList<>();
      for (Ref ref : repo.getRefDatabase().getRefsByPrefix(REFS_CHANGES)) {
        if (!ref.getObjectId().equals(commit)) {
          continue;
        }
        PatchSet.Id psId = PatchSet.Id.fromRef(ref.getName());
        if (psId == null) {
          continue;
        }
        try {
          Change c = notesFactory.createChecked(change().getProject(), psId.changeId()).getChange();
          if (!c.getDest().equals(change().getDest())) {
            continue;
          }
        } catch (StorageException e) {
          logger.atWarning().withCause(e).log(
              "Error in consistency check of change %s", notes.getChangeId());
          // Include this patch set; should cause an error below, which is good.
        }
        thisCommitPsIds.add(psId);
      }
      switch (thisCommitPsIds.size()) {
        case 0 -> {
          // No patch set for this commit; insert one.
          rw.parseBody(commit);
          String changeId = Iterables.getFirst(changeUtil.getChangeIdsFromFooter(commit), null);
          // Missing Change-Id footer is ok, but mismatched is not.
          if (changeId != null && !changeId.equals(change().getKey().get())) {
            problem(
                String.format(
                    "Expected merged commit %s has Change-Id: %s, but expected %s",
                    commit.name(), changeId, change().getKey().get()));
            return;
          }
          insertMergedPatchSet(commit, null, false);
        }
        case 1 -> {
          // Existing patch set ref pointing to this commit.
          PatchSet.Id id = thisCommitPsIds.get(0);
          if (id.equals(change().currentPatchSetId())) {
            // If it's the current patch set, we can just fix the status.
            fixMerged(wrongChangeStatus(id, commit));
          } else if (id.get() > change().currentPatchSetId().get()) {
            // If it's newer than the current patch set, reuse this patch set
            // ID when inserting a new merged patch set.
            insertMergedPatchSet(commit, id, true);
          } else {
            // If it's older than the current patch set, just delete the old
            // ref, and use a new ID when inserting a new merged patch set.
            insertMergedPatchSet(commit, id, false);
          }
        }
        default ->
            problem(
                String.format(
                    "Multiple patch sets for expected merged commit %s: %s",
                    commit.name(),
                    thisCommitPsIds.stream()
                        .sorted(comparing(PatchSet.Id::get))
                        .collect(toImmutableList())));
      }
    } catch (IOException e) {
      ProblemInfo problem =
          problem("Error looking up expected merged commit " + fix.expectMergedAs);
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s: %s", notes.getChangeId(), problem);
    }
  }

  private void insertMergedPatchSet(
      final RevCommit commit, @Nullable PatchSet.Id psIdToDelete, boolean reuseOldPsId) {
    ProblemInfo notFound = problem("No patch set found for merged commit " + commit.name());
    if (!user.get().isIdentifiedUser()) {
      notFound.status = Status.FIX_FAILED;
      notFound.outcome = "Must be called by an identified user to insert new patch set";
      return;
    }
    ProblemInfo insertPatchSetProblem;
    ProblemInfo deleteOldPatchSetProblem;

    if (psIdToDelete == null) {
      insertPatchSetProblem =
          problem(
              String.format(
                  "Expected merged commit %s has no associated patch set", commit.name()));
      deleteOldPatchSetProblem = null;
    } else {
      String msg =
          String.format(
              "Expected merge commit %s corresponds to patch set %s,"
                  + " not the current patch set %s",
              commit.name(), psIdToDelete.get(), change().currentPatchSetId().get());
      // Maybe an identical problem, but different fix.
      deleteOldPatchSetProblem = reuseOldPsId ? null : problem(msg);
      insertPatchSetProblem = problem(msg);
    }

    List<ProblemInfo> currProblems = new ArrayList<>(3);
    currProblems.add(notFound);
    if (deleteOldPatchSetProblem != null) {
      currProblems.add(deleteOldPatchSetProblem);
    }
    currProblems.add(insertPatchSetProblem);

    try {
      PatchSet.Id psId =
          (psIdToDelete != null && reuseOldPsId)
              ? psIdToDelete
              : ChangeUtil.nextPatchSetId(repo, change().currentPatchSetId());
      PatchSetInserter inserter = patchSetInserterFactory.create(notes, psId, commit);
      try (BatchUpdate bu = newBatchUpdate()) {
        bu.setRepository(repo, rw, oi);

        if (psIdToDelete != null) {
          // Delete the given patch set ref. If reuseOldPsId is true,
          // PatchSetInserter will reinsert the same ref, making it a no-op.
          bu.addOp(
              notes.getChangeId(),
              new BatchUpdateOp() {
                @Override
                public void updateRepo(RepoContext ctx) throws IOException {
                  ctx.addRefUpdate(commit, ObjectId.zeroId(), psIdToDelete.toRefName());
                }
              });
          if (!reuseOldPsId) {
            bu.addOp(
                notes.getChangeId(),
                new DeletePatchSetFromDbOp(requireNonNull(deleteOldPatchSetProblem), psIdToDelete));
          }
        }

        bu.setNotify(NotifyResolver.Result.none());
        bu.addOp(
            notes.getChangeId(),
            inserter
                .disableValidation()
                .setFireRevisionCreated(false)
                .setAllowClosed(true)
                .setMessage("Patch set for merged commit inserted by consistency checker"));
        bu.addOp(notes.getChangeId(), new FixMergedOp(notFound));
        bu.execute();
      }
      notes = notesFactory.createChecked(inserter.getChange());
      insertPatchSetProblem.status = Status.FIXED;
      insertPatchSetProblem.outcome = "Inserted as patch set " + psId.get();
    } catch (StorageException | IOException | UpdateException | RestApiException e) {
      logger.atWarning().withCause(e).log(
          "Error in consistency check of change %s", notes.getChangeId());
      for (ProblemInfo pi : currProblems) {
        pi.status = Status.FIX_FAILED;
        pi.outcome = "Error inserting merged patch set";
      }
      return;
    }
  }

  private static class FixMergedOp implements BatchUpdateOp {
    private final ProblemInfo p;

    private FixMergedOp(ProblemInfo p) {
      this.p = p;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) {
      ctx.getChange().setStatus(Change.Status.MERGED);
      ctx.getUpdate(ctx.getChange().currentPatchSetId())
          .fixStatusToMerged(new SubmissionId(ctx.getChange()));
      p.status = Status.FIXED;
      p.outcome = "Marked change as merged";
      return true;
    }
  }

  private void fixMerged(ProblemInfo p) {
    try (BatchUpdate bu = newBatchUpdate()) {
      bu.setRepository(repo, rw, oi);
      bu.addOp(notes.getChangeId(), new FixMergedOp(p));
      bu.execute();
    } catch (UpdateException | RestApiException e) {
      logger.atWarning().withCause(e).log("Error marking %s as merged", notes.getChangeId());
      p.status = Status.FIX_FAILED;
      p.outcome = "Error updating status to merged";
    }
  }

  private BatchUpdate newBatchUpdate() {
    return updateFactory.create(change().getProject(), user.get(), TimeUtil.now());
  }

  private void fixPatchSetRef(ProblemInfo p, PatchSet ps) {
    try {
      RefUpdate ru = repo.updateRef(ps.id().toRefName());
      ru.setForceUpdate(true);
      ru.setNewObjectId(ps.commitId());
      ru.setRefLogIdent(newRefLogIdent());
      ru.setRefLogMessage("Repair patch set ref", true);
      RefUpdate.Result result = ru.update();
      switch (result) {
        case NEW:
        case FORCED:
        case FAST_FORWARD:
        case NO_CHANGE:
          p.status = Status.FIXED;
          p.outcome = "Repaired patch set ref";
          return;
        case IO_FAILURE:
        case LOCK_FAILURE:
        case NOT_ATTEMPTED:
        case REJECTED:
        case REJECTED_CURRENT_BRANCH:
        case RENAMED:
        case REJECTED_MISSING_OBJECT:
        case REJECTED_OTHER_REASON:
        default:
          p.status = Status.FIX_FAILED;
          p.outcome = "Failed to update patch set ref: " + result;
          return;
      }
    } catch (IOException e) {
      String msg = "Error fixing patch set ref";
      logger.atWarning().withCause(e).log("%s %s", msg, ps.id().toRefName());
      p.status = Status.FIX_FAILED;
      p.outcome = msg;
    }
  }

  private void deletePatchSets(List<DeletePatchSetFromDbOp> ops) {
    try (BatchUpdate bu = newBatchUpdate()) {
      bu.setRepository(repo, rw, oi);
      for (DeletePatchSetFromDbOp op : ops) {
        checkArgument(op.psId.changeId().equals(notes.getChangeId()));
        bu.addOp(notes.getChangeId(), op);
      }
      bu.addOp(notes.getChangeId(), new UpdateCurrentPatchSetOp(ops));
      bu.execute();
    } catch (NoPatchSetsWouldRemainException e) {
      for (DeletePatchSetFromDbOp op : ops) {
        op.p.status = Status.FIX_FAILED;
        op.p.outcome = e.getMessage();
      }
    } catch (UpdateException | RestApiException e) {
      String msg = "Error deleting patch set";
      logger.atWarning().withCause(e).log("%s of change %s", msg, ops.get(0).psId.changeId());
      for (DeletePatchSetFromDbOp op : ops) {
        // Overwrite existing statuses that were set before the transaction was
        // rolled back.
        op.p.status = Status.FIX_FAILED;
        op.p.outcome = msg;
      }
    }
  }

  private class DeletePatchSetFromDbOp implements BatchUpdateOp {
    private final ProblemInfo p;
    private final PatchSet.Id psId;

    private DeletePatchSetFromDbOp(ProblemInfo p, PatchSet.Id psId) {
      this.p = p;
      this.psId = psId;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws PatchSetInfoNotAvailableException {
      // Delete dangling key references.
      accountPatchReviewStore.run(s -> s.clearReviewed(psId));

      // For NoteDb setting the state to deleted is sufficient to filter everything out.
      ctx.getUpdate(psId).setPatchSetState(PatchSetState.DELETED);

      p.status = Status.FIXED;
      p.outcome = "Deleted patch set";
      return true;
    }
  }

  private static class NoPatchSetsWouldRemainException extends RestApiException {
    private static final long serialVersionUID = 1L;

    private NoPatchSetsWouldRemainException() {
      super("Cannot delete patch set; no patch sets would remain");
    }
  }

  private class UpdateCurrentPatchSetOp implements BatchUpdateOp {
    private final Set<PatchSet.Id> toDelete;

    private UpdateCurrentPatchSetOp(List<DeletePatchSetFromDbOp> deleteOps) {
      toDelete = new HashSet<>();
      for (DeletePatchSetFromDbOp op : deleteOps) {
        toDelete.add(op.psId);
      }
    }

    @Override
    public boolean updateChange(ChangeContext ctx)
        throws PatchSetInfoNotAvailableException, NoPatchSetsWouldRemainException {
      if (!toDelete.contains(ctx.getChange().currentPatchSetId())) {
        return false;
      }
      TreeSet<PatchSet.Id> all = new TreeSet<>(comparing(PatchSet.Id::get));
      // Doesn't make any assumptions about the order in which deletes happen
      // and whether they are seen by this op; we are already given the full set
      // of patch sets that will eventually be deleted in this update.
      for (PatchSet ps : psUtil.byChange(ctx.getNotes())) {
        if (!toDelete.contains(ps.id())) {
          all.add(ps.id());
        }
      }
      if (all.isEmpty()) {
        throw new NoPatchSetsWouldRemainException();
      }
      ctx.getChange().setCurrentPatchSet(patchSetInfoFactory.get(ctx.getNotes(), all.last()));
      return true;
    }
  }

  private PersonIdent newRefLogIdent() {
    CurrentUser u = user.get();
    if (u.isIdentifiedUser()) {
      return u.asIdentifiedUser().newRefLogIdent();
    }
    return serverIdent.get();
  }

  @Nullable
  private RevCommit parseCommit(ObjectId objId, String desc) {
    try {
      return rw.parseCommit(objId);
    } catch (MissingObjectException e) {
      problem(String.format("Object missing: %s: %s", desc, objId.name()));
    } catch (IncorrectObjectTypeException e) {
      problem(String.format("Not a commit: %s: %s", desc, objId.name()));
    } catch (IOException e) {
      problem(String.format("Failed to look up: %s: %s", desc, objId.name()));
    }
    return null;
  }

  @CanIgnoreReturnValue
  private ProblemInfo problem(String msg) {
    ProblemInfo p = new ProblemInfo();
    p.message = requireNonNull(msg);
    problems.add(p);
    return p;
  }

  private ProblemInfo lastProblem() {
    return problems.get(problems.size() - 1);
  }

  private Result result() {
    return Result.create(notes, problems);
  }
}
