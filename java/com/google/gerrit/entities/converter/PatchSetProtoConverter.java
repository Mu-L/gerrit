// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.entities.converter;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.proto.Entities;
import com.google.protobuf.Parser;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;

@Immutable
public enum PatchSetProtoConverter implements SafeProtoConverter<Entities.PatchSet, PatchSet> {
  INSTANCE;

  private final ProtoConverter<Entities.PatchSet_Id, PatchSet.Id> patchSetIdConverter =
      PatchSetIdProtoConverter.INSTANCE;
  private final ProtoConverter<Entities.Conflicts, PatchSet.Conflicts> conflictsConverter =
      ConflictsProtoConverter.INSTANCE;
  private final ProtoConverter<Entities.ObjectId, ObjectId> objectIdConverter =
      ObjectIdProtoConverter.INSTANCE;
  private final ProtoConverter<Entities.Account_Id, Account.Id> accountIdConverter =
      AccountIdProtoConverter.INSTANCE;

  @Override
  public Entities.PatchSet toProto(PatchSet patchSet) {
    Entities.PatchSet.Builder builder =
        Entities.PatchSet.newBuilder()
            .setId(patchSetIdConverter.toProto(patchSet.id()))
            .setCommitId(objectIdConverter.toProto(patchSet.commitId()))
            .setUploaderAccountId(accountIdConverter.toProto(patchSet.uploader()))
            .setRealUploaderAccountId(accountIdConverter.toProto(patchSet.realUploader()))
            .setCreatedOn(patchSet.createdOn().toEpochMilli());
    patchSet.branch().ifPresent(builder::setBranch);
    ImmutableList<String> groups = patchSet.groups();
    if (!groups.isEmpty()) {
      builder.setGroups(PatchSet.joinGroups(groups));
    }
    patchSet.pushCertificate().ifPresent(builder::setPushCertificate);
    patchSet.description().ifPresent(builder::setDescription);
    patchSet
        .conflicts()
        .ifPresent(conflicts -> builder.setConflicts(conflictsConverter.toProto(conflicts)));
    return builder.build();
  }

  @Override
  public PatchSet fromProto(Entities.PatchSet proto) {
    PatchSet.Builder builder =
        PatchSet.builder()
            .id(patchSetIdConverter.fromProto(proto.getId()))
            .groups(
                proto.hasGroups() ? PatchSet.splitGroups(proto.getGroups()) : ImmutableList.of());
    if (proto.hasPushCertificate()) {
      builder.pushCertificate(proto.getPushCertificate());
    }
    if (proto.hasDescription()) {
      builder.description(proto.getDescription());
    }
    if (proto.hasBranch()) {
      builder.branch(proto.getBranch());
    }
    if (proto.hasConflicts()) {
      builder.conflicts(Optional.of(conflictsConverter.fromProto(proto.getConflicts())));
    }

    // The following fields used to theoretically be nullable in PatchSet, but in practice no
    // production codepath should have ever serialized an instance that was missing one of these
    // fields.
    //
    // However, since some protos may theoretically be missing these fields, we need to support
    // them. Populate specific sentinel values for each field as documented in the PatchSet javadoc.
    // Callers that encounter one of these sentinels will likely fail, for example by failing to
    // look up the zeroId. They would have also failed back when the fields were nullable, for
    // example with NPE; the current behavior just fails slightly differently.
    Account.Id uploader =
        proto.hasUploaderAccountId()
            ? accountIdConverter.fromProto(proto.getUploaderAccountId())
            : Account.id(0);
    builder
        .commitId(
            proto.hasCommitId()
                ? objectIdConverter.fromProto(proto.getCommitId())
                : ObjectId.zeroId())
        .uploader(uploader)
        .realUploader(
            proto.hasRealUploaderAccountId()
                ? accountIdConverter.fromProto(proto.getRealUploaderAccountId())
                : uploader)
        .createdOn(
            proto.hasCreatedOn() ? Instant.ofEpochMilli(proto.getCreatedOn()) : Instant.EPOCH);

    return builder.build();
  }

  @Override
  public Parser<Entities.PatchSet> getParser() {
    return Entities.PatchSet.parser();
  }

  @Override
  public Class<Entities.PatchSet> getProtoClass() {
    return Entities.PatchSet.class;
  }

  @Override
  public Class<PatchSet> getEntityClass() {
    return PatchSet.class;
  }
}
