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

package com.google.gerrit.entities;

import static com.google.gerrit.entities.RefNames.REFS_CHANGES;

import com.google.auto.value.AutoValue;
import com.google.common.primitives.Ints;
import com.google.gerrit.common.ConvertibleToProto;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * A change proposed to be merged into a branch.
 *
 * <p>The data graph rooted below a Change can be quite complex:
 *
 * <pre>
 *   {@link Change}
 *     |
 *     +- {@link ChangeMessage}: &quot;cover letter&quot; or general comment.
 *     |
 *     +- {@link PatchSet}: a single variant of this change.
 *          |
 *          +- {@link PatchSetApproval}: a +/- vote on the change's current state.
 *          |
 *          +- {@link HumanComment}: comment about a specific line
 * </pre>
 *
 * <p>
 *
 * <h5>PatchSets</h5>
 *
 * <p>Every change has at least one PatchSet. A change starts out with one PatchSet, the initial
 * proposal put forth by the change owner. This {@link Account} is usually also listed as the author
 * and committer in the PatchSetInfo.
 *
 * <p>Each PatchSet contains zero or more Patch records, detailing the file paths impacted by the
 * change (otherwise known as, the file paths the author added/deleted/modified). Sometimes a merge
 * commit can contain zero patches, if the merge has no conflicts, or has no impact other than to
 * cut off a line of development.
 *
 * <p>Each Comment is a draft or a published comment about a single line of the associated file.
 * These are the inline comment entities created by users as they perform a review.
 *
 * <p>When additional PatchSets appear under a change, these PatchSets reference <i>replacement</i>
 * commits; alternative commits that could be made to the project instead of the original commit
 * referenced by the first PatchSet.
 *
 * <p>A change has at most one current PatchSet. The current PatchSet is updated when a new
 * replacement PatchSet is uploaded. When a change is submitted, the current patch set is what is
 * merged into the destination branch.
 *
 * <p>
 *
 * <h5>ChangeMessage</h5>
 *
 * <p>The ChangeMessage entity is a general free-form comment about the whole change, rather than
 * Comment's file and line specific context. The ChangeMessage appears at the start of any email
 * generated by Gerrit, and is shown on the change overview page, rather than in a file-specific
 * context. Users often use this entity to describe general remarks about the overall concept
 * proposed by the change.
 *
 * <p>
 *
 * <h5>PatchSetApproval</h5>
 *
 * <p>PatchSetApproval entities exist to fill in the <i>cells</i> of the approvals table in the web
 * UI. That is, a single PatchSetApproval record's key is the tuple {@code
 * (PatchSet,Account,ApprovalCategory)}. Each PatchSetApproval carries with it a small score value,
 * typically within the range -2..+2.
 *
 * <p>If an Account has created only PatchSetApprovals with a score value of 0, the Change shows in
 * their dashboard, and they are said to be CC'd (carbon copied) on the Change, but are not a direct
 * reviewer. This often happens when an account was specified at upload time with the {@code --cc}
 * command line flag, or have published comments, but left the approval scores at 0 ("No Score").
 *
 * <p>If an Account has one or more PatchSetApprovals with a score != 0, the Change shows in their
 * dashboard, and they are said to be an active reviewer. Such individuals are highlighted when
 * notice of a replacement patch set is sent, or when notice of the change submission occurs.
 */
public final class Change {

  public static Id id(int id) {
    return new AutoValue_Change_Id(id);
  }

  /** The numeric change ID */
  @AutoValue
  @ConvertibleToProto
  public abstract static class Id {
    /**
     * Parse a Change.Id out of a string representation.
     *
     * @param str the string to parse
     * @return Optional containing the Change.Id, or {@code Optional.empty()} if str does not
     *     represent a valid Change.Id.
     */
    public static Optional<Id> tryParse(String str) {
      Integer id = Ints.tryParse(str);
      return id != null ? Optional.of(Change.id(id)) : Optional.empty();
    }

    @Nullable
    public static Id fromRef(String ref) {
      if (RefNames.isRefsEdit(ref)) {
        return fromEditRefPart(ref);
      }
      int cs = startIndex(ref);
      if (cs < 0) {
        return null;
      }
      int ce = nextNonDigit(ref, cs);
      if (ref.substring(ce).equals(RefNames.META_SUFFIX)
          || ref.substring(ce).equals(RefNames.ROBOT_COMMENTS_SUFFIX)
          || PatchSet.Id.fromRef(ref, ce) >= 0) {
        return Change.id(Integer.parseInt(ref.substring(cs, ce)));
      }
      return null;
    }

    @Nullable
    public static Id fromAllUsersRef(String ref) {
      if (ref == null) {
        return null;
      }
      String prefix;
      if (ref.startsWith(RefNames.REFS_STARRED_CHANGES)) {
        prefix = RefNames.REFS_STARRED_CHANGES;
      } else if (ref.startsWith(RefNames.REFS_DRAFT_COMMENTS)) {
        prefix = RefNames.REFS_DRAFT_COMMENTS;
      } else {
        return null;
      }
      int cs = startIndex(ref, prefix);
      if (cs < 0) {
        return null;
      }
      int ce = nextNonDigit(ref, cs);
      if (ce < ref.length() && ref.charAt(ce) == '/' && isNumeric(ref, ce + 1)) {
        return Change.id(Integer.parseInt(ref.substring(cs, ce)));
      }
      return null;
    }

    private static boolean isNumeric(String s, int off) {
      if (off >= s.length()) {
        return false;
      }
      for (int i = off; i < s.length(); i++) {
        if (!Character.isDigit(s.charAt(i))) {
          return false;
        }
      }
      return true;
    }

    @Nullable
    public static Id fromEditRefPart(String ref) {
      int startChangeId = ref.indexOf(RefNames.EDIT_PREFIX) + RefNames.EDIT_PREFIX.length();
      int endChangeId = nextNonDigit(ref, startChangeId);
      String id = ref.substring(startChangeId, endChangeId);
      if (id != null && !id.isEmpty()) {
        return Change.id(Integer.parseInt(id));
      }
      return null;
    }

    @Nullable
    public static Id fromRefPart(String ref) {
      Integer id = RefNames.parseShardedRefPart(ref);
      return id != null ? Change.id(id) : null;
    }

    static int startIndex(String ref) {
      return startIndex(ref, REFS_CHANGES);
    }

    static int startIndex(String ref, String expectedPrefix) {
      if (ref == null || !ref.startsWith(expectedPrefix)) {
        return -1;
      }

      // Last 2 digits.
      int ls = expectedPrefix.length();
      int le = nextNonDigit(ref, ls);
      if (le - ls != 2 || le >= ref.length() || ref.charAt(le) != '/') {
        return -1;
      }

      // Change ID.
      int cs = le + 1;
      if (cs >= ref.length() || ref.charAt(cs) == '0') {
        return -1;
      }
      int ce = nextNonDigit(ref, cs);
      if (ce >= ref.length() || ref.charAt(ce) != '/') {
        return -1;
      }
      switch (ce - cs) {
        case 0 -> {
          return -1;
        }
        case 1 -> {
          if (ref.charAt(ls) != '0' || ref.charAt(ls + 1) != ref.charAt(cs)) {
            return -1;
          }
        }
        default -> {
          if (ref.charAt(ls) != ref.charAt(ce - 2) || ref.charAt(ls + 1) != ref.charAt(ce - 1)) {
            return -1;
          }
        }
      }
      return cs;
    }

    static int nextNonDigit(String s, int i) {
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
        i++;
      }
      return i;
    }

    abstract int id();

    public int get() {
      return id();
    }

    public String toRefPrefix() {
      return refPrefixBuilder().toString();
    }

    StringBuilder refPrefixBuilder() {
      StringBuilder r = new StringBuilder(32).append(REFS_CHANGES);
      int m = get() % 100;
      if (m < 10) {
        r.append('0');
      }
      return r.append(m).append('/').append(get()).append('/');
    }

    @Override
    public final String toString() {
      return Integer.toString(get());
    }
  }

  public static Key key(String key) {
    return new AutoValue_Change_Key(key);
  }

  /**
   * Globally unique identification of this change. This generally takes the form of a string
   * "Ixxxxxx...", and is stored in the Change-Id footer of a commit.
   */
  @AutoValue
  @ConvertibleToProto
  public abstract static class Key {
    // TODO(dborowitz): This hardly seems worth it: why would someone pass a URL-encoded change key?
    // Ideally the standard key() factory method would enforce the format and throw IAE.
    public static Key parse(String str) {
      return Change.key(KeyUtil.decode(str));
    }

    @SerializedName("id")
    abstract String key();

    public String get() {
      return key();
    }

    /** Construct a key that is after all keys prefixed by this key. */
    public Key max() {
      final StringBuilder revEnd = new StringBuilder(get().length() + 1);
      revEnd.append(get());
      revEnd.append('\u9fa5');
      return Change.key(revEnd.toString());
    }

    /** Obtain a shorter version of this key string, using a leading prefix. */
    public String abbreviate() {
      final String s = get();
      return s.substring(0, Math.min(s.length(), 9));
    }

    @Override
    public final String toString() {
      return get();
    }

    public static TypeAdapter<Key> typeAdapter(Gson gson) {
      return new AutoValue_Change_Key.GsonTypeAdapter(gson);
    }
  }

  /** Minimum database status constant for an open change. */
  private static final char MIN_OPEN = 'a';

  /** Database constant for {@link Status#NEW}. */
  public static final char STATUS_NEW = 'n';

  /** Maximum database status constant for an open change. */
  private static final char MAX_OPEN = 'z';

  /** Database constant for {@link Status#MERGED}. */
  public static final char STATUS_MERGED = 'M';

  /** ID number of the first patch set in a change. */
  public static final int INITIAL_PATCH_SET_ID = 1;

  /** Change-Id pattern. */
  public static final String CHANGE_ID_PATTERN = "^[iI][0-9a-f]{4,}.*$";

  /**
   * Current state within the basic workflow of the change.
   *
   * <p>Within the database, lower case codes ('a'..'z') indicate a change that is still open, and
   * that can be modified/refined further, while upper case codes ('A'..'Z') indicate a change that
   * is closed and cannot be further modified.
   */
  public enum Status {
    /**
     * Change is open and pending review, or review is in progress.
     *
     * <p>This is the default state assigned to a change when it is first created in the database. A
     * change stays in the NEW state throughout its review cycle, until the change is submitted or
     * abandoned.
     *
     * <p>Changes in the NEW state can be moved to:
     *
     * <ul>
     *   <li>{@link #MERGED} - when the Submit Patch Set action is used;
     *   <li>{@link #ABANDONED} - when the Abandon action is used.
     * </ul>
     */
    NEW(STATUS_NEW, ChangeStatus.NEW),

    /**
     * Change is closed, and submitted to its destination branch.
     *
     * <p>Once a change has been merged, it cannot be further modified by adding a replacement patch
     */
    MERGED(STATUS_MERGED, ChangeStatus.MERGED),

    /**
     * Change is closed, but was not submitted to its destination branch.
     *
     * <p>Once a change has been abandoned, it cannot be further modified by adding a replacement
     * patch set, and it cannot be merged. Draft comments however may be published, permitting
     * reviewers to send constructive feedback.
     */
    ABANDONED('A', ChangeStatus.ABANDONED);

    static {
      boolean ok = true;
      if (Status.values().length != ChangeStatus.values().length) {
        ok = false;
      }
      for (Status s : Status.values()) {
        ok &= s.name().equals(s.changeStatus.name());
      }
      if (!ok) {
        throw new IllegalStateException(
            "Mismatched status mapping: "
                + Arrays.asList(Status.values())
                + " != "
                + Arrays.asList(ChangeStatus.values()));
      }
    }

    private final char code;
    private final boolean closed;
    private final ChangeStatus changeStatus;

    Status(char c, ChangeStatus cs) {
      code = c;
      closed = !(MIN_OPEN <= c && c <= MAX_OPEN);
      changeStatus = cs;
    }

    public char getCode() {
      return code;
    }

    public boolean isOpen() {
      return !closed;
    }

    public boolean isClosed() {
      return closed;
    }

    public ChangeStatus asChangeStatus() {
      return changeStatus;
    }

    @Nullable
    public static Status forCode(char c) {
      for (Status s : Status.values()) {
        if (s.code == c) {
          return s;
        }
      }

      return null;
    }

    @Nullable
    public static Status forChangeStatus(ChangeStatus cs) {
      for (Status s : Status.values()) {
        if (s.changeStatus == cs) {
          return s;
        }
      }
      return null;
    }
  }

  /** Locally assigned unique identifier of the change */
  private Id changeId;

  /** ServerId of the Gerrit instance that has created the change */
  @Nullable private String serverId;

  /** Globally assigned unique identifier of the change */
  private Key changeKey;

  /** When this change was first introduced into the database. */
  private Instant createdOn;

  /**
   * When was a meaningful modification last made to this record's data
   *
   * <p>Note, this update timestamp includes its children.
   */
  private Instant lastUpdatedOn;

  private Account.Id owner;

  /** The branch (and project) this change merges into. */
  private BranchNameKey dest;

  /** Current state code; see {@link Status}. */
  private char status;

  /** The current patch set. */
  private int currentPatchSetId;

  /** Subject from the current patch set. */
  private String subject;

  /** Topic name assigned by the user, if any. */
  @Nullable private String topic;

  /**
   * First line of first patch set's commit message.
   *
   * <p>Unlike {@link #subject}, this string does not change if future patch sets change the first
   * line.
   */
  @Nullable private String originalSubject;

  /**
   * Unique id for the changes submitted together assigned during merging. Only set if the status is
   * MERGED.
   */
  @Nullable private String submissionId;

  /** Whether the change is private. */
  private boolean isPrivate;

  /** Whether the change is work in progress. */
  private boolean workInProgress;

  /** Whether the change has started review. */
  private boolean reviewStarted;

  /** References a change that this change reverts. */
  @Nullable private Id revertOf;

  /** References the source change and patchset that this change was cherry-picked from. */
  @Nullable private PatchSet.Id cherryPickOf;

  Change() {}

  public Change(
      Change.Key newKey, Change.Id newId, Account.Id ownedBy, BranchNameKey forBranch, Instant ts) {
    changeKey = newKey;
    changeId = newId;
    createdOn = ts;
    lastUpdatedOn = createdOn;
    owner = ownedBy;
    dest = forBranch;
    setStatus(Status.NEW);
  }

  public Change(Change other) {
    changeId = other.changeId;
    changeKey = other.changeKey;
    createdOn = other.createdOn;
    lastUpdatedOn = other.lastUpdatedOn;
    owner = other.owner;
    dest = other.dest;
    status = other.status;
    currentPatchSetId = other.currentPatchSetId;
    subject = other.subject;
    originalSubject = other.originalSubject;
    submissionId = other.submissionId;
    topic = other.topic;
    isPrivate = other.isPrivate;
    workInProgress = other.workInProgress;
    reviewStarted = other.reviewStarted;
    revertOf = other.revertOf;
    cherryPickOf = other.cherryPickOf;
  }

  /** 32 bit integer identity for a change. */
  public Change.Id getId() {
    return changeId;
  }

  /**
   * Set the serverId of the Gerrit instance that created the change. It can be set to null for
   * testing purposes in the protobuf converter tests.
   */
  public void setServerId(@Nullable String serverId) {
    this.serverId = serverId;
  }

  /**
   * ServerId of the Gerrit instance that created the change. It could be null when the change is
   * not fetched from NoteDb but obtained through protobuf deserialisation.
   */
  @Nullable
  public String getServerId() {
    return serverId;
  }

  /** 32 bit integer identity for a change. */
  public int getChangeId() {
    return changeId.get();
  }

  /** The Change-Id tag out of the initial commit, or a natural key. */
  public Change.Key getKey() {
    return changeKey;
  }

  public void setKey(Change.Key k) {
    changeKey = k;
  }

  public Instant getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(Instant ts) {
    createdOn = ts;
  }

  public Instant getLastUpdatedOn() {
    return lastUpdatedOn;
  }

  public void setLastUpdatedOn(Instant now) {
    lastUpdatedOn = now;
  }

  public Account.Id getOwner() {
    return owner;
  }

  public void setOwner(Account.Id owner) {
    this.owner = owner;
  }

  public BranchNameKey getDest() {
    return dest;
  }

  public void setDest(BranchNameKey dest) {
    this.dest = dest;
  }

  public Project.NameKey getProject() {
    return dest.project();
  }

  public String getSubject() {
    return subject;
  }

  public String getOriginalSubject() {
    return originalSubject != null ? originalSubject : subject;
  }

  @Nullable
  public String getOriginalSubjectOrNull() {
    return originalSubject;
  }

  /** Get the id of the most current {@link PatchSet} in this change. */
  @Nullable
  public PatchSet.Id currentPatchSetId() {
    if (currentPatchSetId > 0) {
      return PatchSet.id(changeId, currentPatchSetId);
    }
    return null;
  }

  public void setCurrentPatchSet(PatchSetInfo ps) {
    if (originalSubject == null && subject != null) {
      // Change was created before schema upgrade. Use the last subject
      // associated with this change, as the most recent discussion will
      // be under that thread in an email client such as GMail.
      originalSubject = subject;
    }

    currentPatchSetId = ps.getKey().get();
    subject = ps.getSubject();

    if (originalSubject == null) {
      // Newly created changes remember the first commit's subject.
      originalSubject = subject;
    }
  }

  public void setCurrentPatchSet(PatchSet.Id psId, String subject, String originalSubject) {
    if (!psId.changeId().equals(changeId)) {
      throw new IllegalArgumentException("patch set ID " + psId + " is not for change " + changeId);
    }
    currentPatchSetId = psId.get();
    this.subject = subject;
    this.originalSubject = originalSubject;
  }

  public void clearCurrentPatchSet() {
    currentPatchSetId = 0;
    subject = null;
    originalSubject = null;
  }

  @Nullable
  public String getSubmissionId() {
    return submissionId;
  }

  public void setSubmissionId(String id) {
    this.submissionId = id;
  }

  public Status getStatus() {
    return Status.forCode(status);
  }

  public void setStatus(Status newStatus) {
    status = newStatus.getCode();
  }

  public boolean isNew() {
    return getStatus().equals(Status.NEW);
  }

  public boolean isMerged() {
    return getStatus().equals(Status.MERGED);
  }

  public boolean isAbandoned() {
    return getStatus().equals(Status.ABANDONED);
  }

  public boolean isClosed() {
    return isAbandoned() || isMerged();
  }

  @Nullable
  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public boolean isPrivate() {
    return isPrivate;
  }

  public void setPrivate(boolean isPrivate) {
    this.isPrivate = isPrivate;
  }

  public boolean isWorkInProgress() {
    return workInProgress;
  }

  public void setWorkInProgress(boolean workInProgress) {
    this.workInProgress = workInProgress;
  }

  public boolean hasReviewStarted() {
    return reviewStarted;
  }

  public void setReviewStarted(boolean reviewStarted) {
    this.reviewStarted = reviewStarted;
  }

  public void setRevertOf(Id revertOf) {
    this.revertOf = revertOf;
  }

  @Nullable
  public Id getRevertOf() {
    return this.revertOf;
  }

  @Nullable
  public PatchSet.Id getCherryPickOf() {
    return cherryPickOf;
  }

  public void setCherryPickOf(@Nullable PatchSet.Id cherryPickOf) {
    this.cherryPickOf = cherryPickOf;
  }

  @Override
  public String toString() {
    return new StringBuilder(getClass().getSimpleName())
        .append('{')
        .append(changeId)
        .append(" (")
        .append(changeKey)
        .append("), ")
        .append("dest=")
        .append(dest)
        .append(", ")
        .append("status=")
        .append(status)
        .append('}')
        .toString();
  }
}
