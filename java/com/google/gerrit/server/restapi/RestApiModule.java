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

package com.google.gerrit.server.restapi;

import com.google.gerrit.server.change.ChangeCleanupRunner.ChangeCleanupRunnerModule;
import com.google.gerrit.server.plugins.PluginRestApiModule;
import com.google.gerrit.server.restapi.access.AccessRestApiModule;
import com.google.gerrit.server.restapi.account.AccountRestApiModule;
import com.google.gerrit.server.restapi.change.ChangeRestApiModule;
import com.google.gerrit.server.restapi.config.ConfigRestApiModule;
import com.google.gerrit.server.restapi.config.RestCacheAdminModule;
import com.google.gerrit.server.restapi.flow.FlowRestApiModule;
import com.google.gerrit.server.restapi.group.GroupRestApiModule;
import com.google.gerrit.server.restapi.project.ProjectRestApiModule;
import com.google.inject.AbstractModule;

/**
 * Module to bind REST API endpoints.
 *
 * <p>Classes that are needed by the REST layer, but which are not REST API endpoints, should be
 * bound in {@link RestModule}.
 */
public class RestApiModule extends AbstractModule {

  @Override
  protected void configure() {
    install(new AccessRestApiModule());
    install(new AccountRestApiModule());
    install(new ChangeRestApiModule());
    install(new ConfigRestApiModule());
    install(new RestCacheAdminModule());
    install(new FlowRestApiModule());
    install(new GroupRestApiModule());
    install(new PluginRestApiModule());
    install(new ProjectRestApiModule());
    install(new ProjectRestApiModule.BatchModule());
    install(new ChangeCleanupRunnerModule());
  }
}
