// Copyright (C) 2008 The Android Open Source Project
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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.common.NoMergeBaseReason;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.submit.CommitMergeStatus;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** Extended commit entity with code review specific metadata. */
public class CodeReviewCommit extends RevCommit implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * Default ordering when merging multiple topologically-equivalent commits.
   *
   * <p>Operates only on these commits and does not take ancestry into account.
   *
   * <p>Use this in preference to the default order, which comes from {@link AnyObjectId} and only
   * orders on SHA-1.
   */
  public static final Ordering<CodeReviewCommit> ORDER =
      Ordering.natural()
          .onResultOf(
              (CodeReviewCommit c) ->
                  c.getPatchsetId() != null ? c.getPatchsetId().changeId().get() : null)
          .nullsFirst();

  public static CodeReviewRevWalk newRevWalk(Repository repo) {
    return new CodeReviewRevWalk(repo);
  }

  public static CodeReviewRevWalk newRevWalk(ObjectReader reader) {
    return new CodeReviewRevWalk(reader);
  }

  public static class CodeReviewRevWalk extends RevWalk {
    private CodeReviewRevWalk(Repository repo) {
      super(repo);
    }

    private CodeReviewRevWalk(ObjectReader reader) {
      super(reader);
    }

    @Override
    protected CodeReviewCommit createCommit(AnyObjectId id) {
      return new CodeReviewCommit(id);
    }

    @Override
    public CodeReviewCommit next()
        throws MissingObjectException, IncorrectObjectTypeException, IOException {
      return (CodeReviewCommit) super.next();
    }

    @Override
    public void markStart(RevCommit c)
        throws MissingObjectException, IncorrectObjectTypeException, IOException {
      checkArgument(c instanceof CodeReviewCommit);
      super.markStart(c);
    }

    @Override
    public void markUninteresting(RevCommit c)
        throws MissingObjectException, IncorrectObjectTypeException, IOException {
      checkArgument(c instanceof CodeReviewCommit);
      super.markUninteresting(c);
    }

    @Override
    public CodeReviewCommit lookupCommit(AnyObjectId id) {
      return (CodeReviewCommit) super.lookupCommit(id);
    }

    @Override
    public CodeReviewCommit parseCommit(AnyObjectId id)
        throws MissingObjectException, IncorrectObjectTypeException, IOException {
      return (CodeReviewCommit) super.parseCommit(id);
    }
  }

  /**
   * Unique key of the PatchSet entity from the code review system.
   *
   * <p>This value is only available on commits that have a PatchSet represented in the code review
   * system.
   */
  private PatchSet.Id patchsetId;

  private ChangeNotes notes;

  /**
   * The result status for this commit.
   *
   * <p>Only valid if {@link #patchsetId} is not null.
   */
  private CommitMergeStatus statusCode;

  /**
   * Message for the status that is returned to the calling user if the status indicates a problem
   * that prevents submit.
   */
  private transient Optional<String> statusMessage = Optional.empty();

  /**
   * Information about conflicts in this commit.
   *
   * <p>Only set for patch sets that are created by Gerrit as a result of performing a Git merge.
   *
   * <p>If this field is not set it's unknown whether this patch set contains any file with
   * conflicts.
   */
  @Nullable private PatchSet.Conflicts conflicts;

  /** List of files in this commit that contain Git conflict markers. */
  private ImmutableSet<String> filesWithGitConflicts;

  public CodeReviewCommit(AnyObjectId id) {
    super(id);
  }

  public ChangeNotes notes() {
    return notes;
  }

  public CommitMergeStatus getStatusCode() {
    return statusCode;
  }

  public void setStatusCode(CommitMergeStatus statusCode) {
    this.statusCode = statusCode;
  }

  public Optional<String> getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(@Nullable String statusMessage) {
    this.statusMessage = Optional.ofNullable(statusMessage);
  }

  public void setNoConflictsForNonMergeCommit() {
    this.conflicts =
        PatchSet.Conflicts.create(
            /* base= */ Optional.empty(),
            /* ours= */ Optional.empty(),
            /* theirs= */ Optional.empty(),
            /* mergeStrategy= */ Optional.empty(),
            /* noBaseReason= */ Optional.of(NoMergeBaseReason.NO_MERGE_PERFORMED),
            /* containsConflicts= */ false);
  }

  public void setConflicts(
      ObjectId base,
      ObjectId ours,
      ObjectId theirs,
      String mergeStrategy,
      @Nullable Set<String> filesWithGitConflicts) {
    if (filesWithGitConflicts != null && !filesWithGitConflicts.isEmpty()) {
      this.conflicts =
          PatchSet.Conflicts.create(
              Optional.of(base),
              Optional.of(ours),
              Optional.of(theirs),
              Optional.of(mergeStrategy),
              /* noBaseReason= */ Optional.empty(),
              /* containsConflicts= */ true);
      this.filesWithGitConflicts = ImmutableSet.copyOf(filesWithGitConflicts);
    } else {
      this.conflicts =
          PatchSet.Conflicts.create(
              Optional.of(base),
              Optional.of(ours),
              Optional.of(theirs),
              Optional.of(mergeStrategy),
              /* noBaseReason= */ Optional.empty(),
              /* containsConflicts= */ false);
    }
  }

  public void setConflictsBaseNotAvailable(
      ObjectId ours,
      ObjectId theirs,
      String mergeStrategy,
      NoMergeBaseReason noMergeBaseReason,
      @Nullable Set<String> filesWithGitConflicts) {
    if (filesWithGitConflicts != null && !filesWithGitConflicts.isEmpty()) {
      this.conflicts =
          PatchSet.Conflicts.create(
              Optional.empty(),
              Optional.of(ours),
              Optional.of(theirs),
              Optional.of(mergeStrategy),
              Optional.of(noMergeBaseReason),
              /* containsConflicts= */ true);
      this.filesWithGitConflicts = ImmutableSet.copyOf(filesWithGitConflicts);
    } else {
      this.conflicts =
          PatchSet.Conflicts.create(
              Optional.empty(),
              Optional.of(ours),
              Optional.of(theirs),
              Optional.of(mergeStrategy),
              Optional.of(noMergeBaseReason),
              /* containsConflicts= */ false);
    }
  }

  public Optional<PatchSet.Conflicts> getConflicts() {
    return Optional.ofNullable(conflicts);
  }

  public ImmutableSet<String> getFilesWithGitConflicts() {
    return filesWithGitConflicts != null ? filesWithGitConflicts : ImmutableSet.of();
  }

  public PatchSet.Id getPatchsetId() {
    return patchsetId;
  }

  public void setPatchsetId(PatchSet.Id patchsetId) {
    this.patchsetId = patchsetId;
  }

  public void copyFrom(CodeReviewCommit src) {
    notes = src.notes;
    patchsetId = src.patchsetId;
    statusCode = src.statusCode;
  }

  public Change change() {
    return getNotes().getChange();
  }

  public ChangeNotes getNotes() {
    return notes;
  }

  public void setNotes(ChangeNotes notes) {
    this.notes = notes;
  }

  /** Custom serialization due to {@link #statusMessage} not being Serializable by default. */
  private void writeObject(ObjectOutputStream oos) throws IOException {
    oos.defaultWriteObject();
    if (this.statusMessage.isPresent()) {
      oos.writeUTF(this.statusMessage.get());
    }
  }

  /** Custom deserialization due to {@link #statusMessage} not being Serializable by default. */
  private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
    ois.defaultReadObject();
    String statusMessage = null;
    if (ois.available() > 0) {
      statusMessage = ois.readUTF();
    }
    this.statusMessage = Optional.ofNullable(statusMessage);
  }
}
