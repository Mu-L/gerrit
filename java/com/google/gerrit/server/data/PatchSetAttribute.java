// Copyright (C) 2010 The Android Open Source Project
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

package com.google.gerrit.server.data;

import com.google.gerrit.extensions.client.ChangeKind;
import java.util.List;

public class PatchSetAttribute implements Cloneable {
  public int number;
  public String revision;
  public List<String> parents;
  public String ref;
  public AccountAttribute uploader;
  public Long createdOn;
  public AccountAttribute author;
  public ChangeKind kind;

  public List<ApprovalAttribute> approvals;
  public List<PatchSetCommentAttribute> comments;
  public List<PatchAttribute> files;
  public int sizeInsertions;
  public int sizeDeletions;

  public PatchSetAttribute shallowClone() {
    try {
      return (PatchSetAttribute) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError(e);
    }
  }
}
