// Copyright (C) 2016 The Android Open Source Project
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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.common.AccountInfo;
import java.util.List;

/** Result object representing the outcome of a request to add/remove a reviewer. */
public class ReviewerResult {
  /** The identifier of an account or group that was to be added/removed as a reviewer. */
  public String input;

  /** If non-null, a string describing why the reviewer could not be added/removed. */
  @Nullable public String error;

  /**
   * Non-null and true if the reviewer cannot be added without explicit confirmation. This may be
   * the case for groups of a certain size. For removals, it's always false.
   */
  @Nullable public Boolean confirm;

  /**
   * List of individual reviewers added to the change. The size of this list may be greater than one
   * (e.g. when a group is added). Null if no reviewers were added.
   */
  @Nullable public List<ReviewerInfo> reviewers;

  /**
   * List of new accounts CCed on the change. The size of this list may be greater than one (e.g.
   * when a group is CCed). Null if no accounts were CCed.
   */
  @Nullable public List<AccountInfo> ccs;

  /** An account removed from the change. Null if no accounts were removed. */
  @Nullable public AccountInfo removed;

  /**
   * Constructs a partially initialized result for the given reviewer.
   *
   * @param input String identifier of an account or group, from user request
   */
  public ReviewerResult(String input) {
    this.input = input;
  }

  /**
   * Constructs an error result for the given account.
   *
   * @param reviewer String identifier of an account or group
   * @param error Error message
   */
  public ReviewerResult(String reviewer, String error) {
    this(reviewer);
    this.error = error;
  }

  /**
   * Constructs a needs-confirmation result for the given account.
   *
   * @param confirm Whether confirmation is needed.
   */
  public ReviewerResult(String reviewer, boolean confirm) {
    this(reviewer);
    this.confirm = confirm;
  }
}
