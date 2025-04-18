// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.index;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

/** Listener to handle upgrading index schema versions at startup. */
public class OnlineUpgrader implements LifecycleListener {
  public static class OnlineUpgraderModule extends LifecycleModule {
    @Override
    protected void configure() {
      listener().to(OnlineUpgrader.class);
    }
  }

  private final VersionManager versionManager;
  private final Config cfg;

  @Inject
  OnlineUpgrader(VersionManager versionManager, @GerritServerConfig Config cfg) {
    this.versionManager = versionManager;
    this.cfg = cfg;
  }

  @Override
  public void start() {
    versionManager.startOnlineUpgrade(cfg);
  }

  @Override
  public void stop() {
    // Do nothing; reindexing threadpools are shut down in another listener, and indexes are closed
    // on demand by IndexCollection.
  }
}
