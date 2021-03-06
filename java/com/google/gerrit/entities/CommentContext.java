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

package com.google.gerrit.entities;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;

/** An entity class representing all context lines of a comment. */
@AutoValue
public abstract class CommentContext {
  private static final CommentContext EMPTY = new AutoValue_CommentContext(ImmutableMap.of(), "");

  public static CommentContext create(ImmutableMap<Integer, String> lines, String contentType) {
    return new AutoValue_CommentContext(lines, contentType);
  }

  /** Map of {line number, line text} of the context lines of a comment */
  public abstract ImmutableMap<Integer, String> lines();

  /**
   * Content type of the source file. Useful for syntax highlighting.
   *
   * @return text/x-gerrit-commit-message if the file is a commit message.
   *     <p>text/x-gerrit-merge-list if the file is a merge list.
   *     <p>The content/mime type, e.g. text/x-c++src otherwise.
   */
  public abstract String contentType();

  public static CommentContext empty() {
    return EMPTY;
  }
}
