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

import com.google.errorprone.annotations.Immutable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.proto.Entities;
import com.google.gerrit.proto.Entities.Change_Id;
import com.google.protobuf.Parser;

@Immutable
public enum ChangeIdProtoConverter implements SafeProtoConverter<Entities.Change_Id, Change.Id> {
  INSTANCE;

  @Override
  public Entities.Change_Id toProto(Change.Id changeId) {
    return Entities.Change_Id.newBuilder().setId(changeId.get()).build();
  }

  @Override
  public Change.Id fromProto(Entities.Change_Id proto) {
    return Change.id(proto.getId());
  }

  @Override
  public Parser<Entities.Change_Id> getParser() {
    return Entities.Change_Id.parser();
  }

  @Override
  public Class<Change_Id> getProtoClass() {
    return Change_Id.class;
  }

  @Override
  public Class<Change.Id> getEntityClass() {
    return Change.Id.class;
  }
}
