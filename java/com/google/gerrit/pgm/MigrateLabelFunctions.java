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

package com.google.gerrit.pgm;

import com.google.gerrit.entities.Project;
import com.google.gerrit.pgm.util.SiteProgram;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.schema.MigrateLabelFunctionsToSubmitRequirement;
import com.google.gerrit.server.schema.MigrateLabelFunctionsToSubmitRequirement.Status;
import com.google.gerrit.server.schema.UpdateUI;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Set;
import org.kohsuke.args4j.Option;

public class MigrateLabelFunctions extends SiteProgram {

  @Option(name = "--project", usage = "Project(s) to migrate")
  private ArrayList<String> projects = new ArrayList<>();

  @Inject GitRepositoryManager gitRepoManager;
  @Inject MigrateLabelFunctionsToSubmitRequirement migrator;

  @Override
  public int run() throws Exception {
    Injector dbInjector = createDbInjector();
    dbInjector.injectMembers(this);

    if (!projects.isEmpty()) {
      migrateProjects(projects.stream().map(Project::nameKey).toList());
    } else {
      migrateProjects(gitRepoManager.list());
    }

    return 0;
  }

  private void migrateProjects(Iterable<Project.NameKey> projects) throws Exception {
    for (Project.NameKey name : projects) {
      Status status = migrator.executeMigration(name, new UpdateUIImpl());
      System.out.printf("%s: %s\n", status, name);
    }
  }

  private static class UpdateUIImpl implements UpdateUI {

    @Override
    public void message(String message) {
      System.out.println(message);
    }

    @Override
    public boolean yesno(boolean defaultValue, String message) {
      return false;
    }

    @Override
    public void waitForUser() {}

    @Override
    public String readString(String defaultValue, Set<String> allowedValues, String message) {
      return "";
    }

    @Override
    public boolean isBatch() {
      return false;
    }
  }
}
