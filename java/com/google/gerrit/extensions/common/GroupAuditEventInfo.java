// Copyright (C) 2015 The Android Open Source Project
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

package com.google.gerrit.extensions.common;

import com.google.gerrit.common.Nullable;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public abstract class GroupAuditEventInfo {
  public enum Type {
    ADD_USER,
    REMOVE_USER,
    ADD_GROUP,
    REMOVE_GROUP
  }

  public Type type;
  public AccountInfo user;

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  public Timestamp date;

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  public static UserMemberAuditEventInfo createAddUserEvent(
      AccountInfo user, Timestamp date, AccountInfo member) {
    return new UserMemberAuditEventInfo(Type.ADD_USER, user, date.toInstant(), member);
  }

  public static UserMemberAuditEventInfo createAddUserEvent(
      AccountInfo user, Instant date, AccountInfo member) {
    return new UserMemberAuditEventInfo(Type.ADD_USER, user, date, member);
  }

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  public static UserMemberAuditEventInfo createRemoveUserEvent(
      AccountInfo user, Optional<Timestamp> date, AccountInfo member) {
    return new UserMemberAuditEventInfo(
        Type.REMOVE_USER, user, date.map(Timestamp::toInstant).orElse(null), member);
  }

  public static UserMemberAuditEventInfo createRemoveUserEvent(
      AccountInfo user, @Nullable Instant date, AccountInfo member) {
    return new UserMemberAuditEventInfo(Type.REMOVE_USER, user, date, member);
  }

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  public static GroupMemberAuditEventInfo createAddGroupEvent(
      AccountInfo user, Timestamp date, GroupInfo member) {
    return new GroupMemberAuditEventInfo(Type.ADD_GROUP, user, date.toInstant(), member);
  }

  public static GroupMemberAuditEventInfo createAddGroupEvent(
      AccountInfo user, Instant date, GroupInfo member) {
    return new GroupMemberAuditEventInfo(Type.ADD_GROUP, user, date, member);
  }

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  public static GroupMemberAuditEventInfo createRemoveGroupEvent(
      AccountInfo user, Optional<Timestamp> date, GroupInfo member) {
    return new GroupMemberAuditEventInfo(
        Type.REMOVE_GROUP, user, date.map(Timestamp::toInstant).orElse(null), member);
  }

  public static GroupMemberAuditEventInfo createRemoveGroupEvent(
      AccountInfo user, @Nullable Instant date, GroupInfo member) {
    return new GroupMemberAuditEventInfo(Type.REMOVE_GROUP, user, date, member);
  }

  protected GroupAuditEventInfo(Type type, AccountInfo user, Optional<Timestamp> date) {
    this.type = type;
    this.user = user;
    this.date = date.orElse(null);
  }

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  protected GroupAuditEventInfo(Type type, AccountInfo user, @Nullable Instant date) {
    this.type = type;
    this.user = user;
    this.date = date != null ? Timestamp.from(date) : null;
  }

  public static class UserMemberAuditEventInfo extends GroupAuditEventInfo {
    public AccountInfo member;

    private UserMemberAuditEventInfo(
        Type type, AccountInfo user, @Nullable Instant date, AccountInfo member) {
      super(type, user, date);
      this.member = member;
    }
  }

  public static class GroupMemberAuditEventInfo extends GroupAuditEventInfo {
    public GroupInfo member;

    private GroupMemberAuditEventInfo(
        Type type, AccountInfo user, @Nullable Instant date, GroupInfo member) {
      super(type, user, date);
      this.member = member;
    }
  }
}
