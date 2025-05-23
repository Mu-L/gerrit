// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.extensions.api.changes;

import static com.google.gerrit.extensions.client.ReviewerState.CC;
import static com.google.gerrit.extensions.client.ReviewerState.REVIEWER;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.restapi.DefaultInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Input passed to {@code POST /changes/[id]/revisions/[id]/review}. */
public class ReviewInput {
  @DefaultInput public String message;

  public String tag;

  public Map<String, Short> labels;
  public Map<String, List<CommentInput>> comments;

  /**
   * How to process draft comments already in the database that were not also described in this
   * input request.
   *
   * <p>If not set, the default is {@link DraftHandling#KEEP}. If {@link #onBehalfOf} is set, then
   * no other value besides {@code KEEP} is allowed.
   */
  public DraftHandling drafts;

  /** A list of draft IDs that should be published. */
  public List<String> draftIdsToPublish;

  /** Who to send email notifications to after review is stored. */
  public NotifyHandling notify;

  public Map<RecipientType, NotifyInfo> notifyDetails;

  /** If true check to make sure that the comments being posted aren't already present. */
  public boolean omitDuplicateComments;

  /**
   * Account ID, name, email address or username of another user. The review will be posted/updated
   * on behalf of this named user instead of the caller. Caller must have the labelAs-$NAME
   * permission granted for each label that appears in {@link #labels}. This is in addition to the
   * named user also needing to have permission to use the labels.
   */
  public String onBehalfOf;

  /** Reviewers that should be added to this change or removed from it. */
  public List<ReviewerInput> reviewers;

  /**
   * If true mark the change as work in progress. It is an error for both {@link #workInProgress}
   * and {@link #ready} to be true.
   */
  public boolean workInProgress;

  /**
   * If true mark the change as ready for review. It is an error for both {@link #workInProgress}
   * and {@link #ready} to be true.
   */
  public boolean ready;

  /** Users that should be added to the attention set of this change. */
  public List<AttentionSetInput> addToAttentionSet;

  /** Users that should be removed from the attention set of this change. */
  public List<AttentionSetInput> removeFromAttentionSet;

  /**
   * Users in the attention set will only be added and removed based on {@link #addToAttentionSet}
   * and {@link #removeFromAttentionSet}. Normally, they are also added and removed when some events
   * occur. E.g, adding/removing reviewers, marking a change ready for review or work in progress,
   * and replying on changes.
   */
  public boolean ignoreAutomaticAttentionSetRules;

  @Nullable public List<ListChangesOption> responseFormatOptions;

  public enum DraftHandling {
    /** Leave pending drafts alone. */
    KEEP,

    /** Publish pending drafts on this revision only. */
    PUBLISH,

    /** Publish pending drafts on all revisions. */
    PUBLISH_ALL_REVISIONS
  }

  public static class CommentInput extends Comment {
    public Boolean unresolved;
  }

  @CanIgnoreReturnValue
  public ReviewInput message(String msg) {
    message = msg != null && !msg.isEmpty() ? msg : null;
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput patchSetLevelComment(String message) {
    Objects.requireNonNull(message);
    CommentInput comment = new CommentInput();
    comment.message = message;
    // TODO(davido): Because of cyclic dependency, we cannot use here Patch.PATCHSET_LEVEL constant
    comments = Collections.singletonMap("/PATCHSET_LEVEL", Collections.singletonList(comment));
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput label(String name, short value) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException();
    }
    if (labels == null) {
      labels = new LinkedHashMap<>(4);
    }
    labels.put(name, value);
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput label(String name, int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new IllegalArgumentException();
    }
    return label(name, (short) value);
  }

  @CanIgnoreReturnValue
  public ReviewInput label(String name) {
    return label(name, (short) 1);
  }

  @CanIgnoreReturnValue
  public ReviewInput reviewer(String reviewer) {
    return reviewer(reviewer, REVIEWER, /* confirmed= */ false);
  }

  @CanIgnoreReturnValue
  public ReviewInput cc(String cc) {
    return reviewer(cc, CC, /* confirmed= */ false);
  }

  @CanIgnoreReturnValue
  public ReviewInput reviewer(String reviewer, ReviewerState state, boolean confirmed) {
    ReviewerInput input = new ReviewerInput();
    input.reviewer = reviewer;
    input.state = state;
    input.confirmed = confirmed;
    if (reviewers == null) {
      reviewers = new ArrayList<>();
    }
    reviewers.add(input);
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput addUserToAttentionSet(String user, String reason) {
    AttentionSetInput input = new AttentionSetInput();
    input.user = user;
    input.reason = reason;
    if (addToAttentionSet == null) {
      addToAttentionSet = new ArrayList<>();
    }
    addToAttentionSet.add(input);
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput removeUserFromAttentionSet(String user, String reason) {
    AttentionSetInput input = new AttentionSetInput();
    input.user = user;
    input.reason = reason;
    if (removeFromAttentionSet == null) {
      removeFromAttentionSet = new ArrayList<>();
    }
    removeFromAttentionSet.add(input);
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput blockAutomaticAttentionSetRules() {
    ignoreAutomaticAttentionSetRules = true;
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput setWorkInProgress(boolean workInProgress) {
    this.workInProgress = workInProgress;
    ready = !workInProgress;
    return this;
  }

  @CanIgnoreReturnValue
  public ReviewInput setReady(boolean ready) {
    this.ready = ready;
    workInProgress = !ready;
    return this;
  }

  public static ReviewInput recommend() {
    return new ReviewInput().label("Code-Review", 1);
  }

  public static ReviewInput dislike() {
    return new ReviewInput().label("Code-Review", -1);
  }

  public static ReviewInput noScore() {
    return new ReviewInput().label("Code-Review", 0);
  }

  public static ReviewInput approve() {
    return new ReviewInput().label("Code-Review", 2);
  }

  public static ReviewInput reject() {
    return new ReviewInput().label("Code-Review", -2);
  }

  public static ReviewInput create() {
    return new ReviewInput();
  }
}
