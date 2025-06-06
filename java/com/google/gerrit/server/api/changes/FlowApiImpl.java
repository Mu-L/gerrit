// Copyright (C) 2025 The Android Open Source Project
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

package com.google.gerrit.server.api.changes;

import static com.google.gerrit.server.api.ApiUtil.asRestApiException;

import com.google.gerrit.extensions.api.changes.FlowApi;
import com.google.gerrit.extensions.common.FlowInfo;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.restapi.flow.DeleteFlow;
import com.google.gerrit.server.restapi.flow.FlowResource;
import com.google.gerrit.server.restapi.flow.GetFlow;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

public class FlowApiImpl implements FlowApi {
  interface Factory {
    FlowApiImpl create(FlowResource flowResource);
  }

  private final FlowResource flowResource;
  private final GetFlow getFlow;
  private final DeleteFlow deleteFlow;

  @Inject
  FlowApiImpl(GetFlow getFlow, DeleteFlow deleteFlow, @Assisted FlowResource flowResource) {
    this.getFlow = getFlow;
    this.deleteFlow = deleteFlow;
    this.flowResource = flowResource;
  }

  @Override
  public FlowInfo get() throws RestApiException {
    try {
      return getFlow.apply(flowResource).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get flow", e);
    }
  }

  @Override
  public void delete() throws RestApiException {
    try {
      deleteFlow.apply(flowResource, new Input());
    } catch (Exception e) {
      throw asRestApiException("Cannot delete flow", e);
    }
  }
}
