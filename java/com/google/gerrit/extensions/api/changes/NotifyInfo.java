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

import com.google.common.base.MoreObjects;
import com.google.gerrit.common.ConvertibleToProto;
import java.util.List;
import java.util.Objects;

/** Detailed information about who should be notified about an update. */
@ConvertibleToProto
public class NotifyInfo {
  public List<String> accounts;

  /**
   * @param accounts may be either just a list of: account IDs, Full names, usernames, or emails.
   *     Also could be a list of those: "Full name <email@example.com>" or "Full name (<ID>)"
   */
  public NotifyInfo(List<String> accounts) {
    this.accounts = accounts;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof NotifyInfo)) {
      return false;
    }
    NotifyInfo other = (NotifyInfo) o;
    return Objects.equals(other.accounts, accounts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accounts);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("accounts", accounts).toString();
  }
}
