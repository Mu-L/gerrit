// Copyright (C) 2009 The Android Open Source Project
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

import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gerrit.common.IoUtil;
import com.google.gerrit.common.PageLinks;
import com.google.gerrit.common.PluginData;
import com.google.gerrit.index.SchemaDefinitions;
import com.google.gerrit.index.project.ProjectSchemaDefinitions;
import com.google.gerrit.pgm.init.BaseInit;
import com.google.gerrit.pgm.init.Browser;
import com.google.gerrit.pgm.init.InitPlugins;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.util.ErrorLogFile;
import com.google.gerrit.server.config.GerritOptions;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.index.GerritIndexStatus;
import com.google.gerrit.server.index.account.AccountSchemaDefinitions;
import com.google.gerrit.server.index.change.ChangeSchemaDefinitions;
import com.google.gerrit.server.index.group.GroupSchemaDefinitions;
import com.google.gerrit.server.ioutil.HostPlatform;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.gerrit.server.util.ReplicaUtil;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.util.Providers;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.kohsuke.args4j.Option;

/** Initialize a new Gerrit installation. */
public class Init extends BaseInit {
  @Option(
      name = "--batch",
      aliases = {"-b"},
      usage = "Batch mode; skip interactive prompting")
  private boolean batchMode;

  @Option(name = "--delete-caches", usage = "Delete all persistent caches without asking")
  private boolean deleteCaches;

  @Option(name = "--no-auto-start", usage = "Don't automatically start daemon after init")
  private boolean noAutoStart;

  @Option(name = "--skip-plugins", usage = "Don't install plugins")
  private boolean skipPlugins;

  @Option(name = "--list-plugins", usage = "List available plugins")
  private boolean listPlugins;

  @Option(name = "--install-plugin", usage = "Install given plugin without asking")
  private List<String> installPlugins;

  @Option(name = "--install-all-plugins", usage = "Install all plugins from war without asking")
  private boolean installAllPlugins;

  @Option(
      name = "--secure-store-lib",
      usage = "Path to jar providing SecureStore implementation class")
  private String secureStoreLib;

  @Option(name = "--dev", usage = "Setup site with default options suitable for developers")
  private boolean dev;

  @Option(name = "--skip-all-downloads", usage = "Don't download libraries")
  private boolean skipAllDownloads;

  @Option(name = "--skip-download", usage = "Don't download given library")
  private List<String> skippedDownloads;

  @Option(name = "--reindex-threads", usage = "Number of threads to use for reindex after init")
  private int reindexThreads = 1;

  @Option(name = "--show-cache-stats", usage = "Show cache statistics at the end")
  private boolean showCacheStats;

  @Inject Browser browser;

  private GerritIndexStatus indexStatus;

  public Init() {
    super(new WarDistribution(), null);
  }

  public Init(Path sitePath) {
    super(sitePath, true, new WarDistribution(), null);
    batchMode = true;
    noAutoStart = true;
  }

  @Override
  protected boolean beforeInit(SiteInit init) throws Exception {
    indexStatus = new GerritIndexStatus(init.site);
    ErrorLogFile.errorOnlyConsole();

    if (!skipPlugins) {
      final List<PluginData> plugins =
          InitPlugins.listPluginsAndRemoveTempFiles(init.site, pluginsDistribution);
      ConsoleUI ui = ConsoleUI.getInstance(false);
      if (installAllPlugins && !nullOrEmpty(installPlugins)) {
        ui.message("Cannot use --install-plugin together with --install-all-plugins.\n");
        return true;
      }
      verifyInstallPluginList(ui, plugins);
      if (listPlugins) {
        if (!plugins.isEmpty()) {
          ui.message("Available plugins:\n");
          for (PluginData plugin : plugins) {
            ui.message(" * %s version %s\n", plugin.name, plugin.version);
          }
        } else {
          ui.message("No plugins found.\n");
        }
        return true;
      }
    }
    return false;
  }

  @Override
  protected void afterInit(SiteRun run) throws Exception {
    ImmutableList<SchemaDefinitions<?>> schemaDefs =
        ImmutableList.of(
            AccountSchemaDefinitions.INSTANCE,
            ChangeSchemaDefinitions.INSTANCE,
            GroupSchemaDefinitions.INSTANCE,
            ProjectSchemaDefinitions.INSTANCE);
    List<Module> modules = new ArrayList<>();
    modules.add(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(Path.class).annotatedWith(SitePath.class).toInstance(getSitePath());
            bind(Browser.class);
            bind(String.class)
                .annotatedWith(SecureStoreClassName.class)
                .toProvider(Providers.of(getConfiguredSecureStoreClass()));
            bind(GerritOptions.class).toInstance(GerritOptions.DEFAULT);
          }
        });
    modules.add(new GerritServerConfigModule());
    Guice.createInjector(modules).injectMembers(this);
    if (reindexThreads != -1 && !ReplicaUtil.isReplica(run.flags.cfg)) {
      List<String> indicesToReindex = new ArrayList<>();
      for (SchemaDefinitions<?> schemaDef : schemaDefs) {
        if (!indexStatus.exists(schemaDef.getName())) {
          indicesToReindex.add(schemaDef.getName());
        }
      }
      reindex(indicesToReindex);
    }
    start(run);
  }

  @Override
  protected List<String> getInstallPlugins() {
    return installPlugins;
  }

  @Override
  protected boolean installAllPlugins() {
    return installAllPlugins;
  }

  @Override
  protected ConsoleUI getConsoleUI() {
    return ConsoleUI.getInstance(batchMode);
  }

  @Override
  protected boolean getAutoStart() {
    return !noAutoStart;
  }

  @Override
  protected boolean getDeleteCaches() {
    return deleteCaches;
  }

  @Override
  protected boolean skipPlugins() {
    return skipPlugins;
  }

  @Override
  protected boolean isDev() {
    return dev;
  }

  @Override
  protected boolean skipAllDownloads() {
    return skipAllDownloads;
  }

  @Override
  protected List<String> getSkippedDownloads() {
    return skippedDownloads != null ? skippedDownloads : Collections.emptyList();
  }

  @Override
  protected String getSecureStoreLib() {
    return secureStoreLib;
  }

  void start(SiteRun run) throws Exception {
    if (reindexThreads != -1 && run.flags.autoStart) {
      if (HostPlatform.isWin32()) {
        System.err.println("Automatic startup not supported on Win32.");
      } else {
        startDaemon(run);
        if (!run.ui.isBatch()) {
          browser.open(PageLinks.ADMIN_PROJECTS);
        }
      }
    }
  }

  void startDaemon(SiteRun run) {
    String[] argv = {run.site.gerrit_sh.toAbsolutePath().toString(), "start"};
    Process proc;
    try {
      System.err.println("Executing " + argv[0] + " " + argv[1]);
      proc = Runtime.getRuntime().exec(argv);
    } catch (IOException e) {
      System.err.println("error: cannot start Gerrit: " + e.getMessage());
      return;
    }

    try {
      proc.getOutputStream().close();
    } catch (IOException e) {
      // Ignored
    }

    IoUtil.copyWithThread(proc.getInputStream(), System.err);
    IoUtil.copyWithThread(proc.getErrorStream(), System.err);

    for (; ; ) {
      try {
        int rc = proc.waitFor();
        if (rc != 0) {
          System.err.println("error: cannot start Gerrit: exit status " + rc);
        }
        break;
      } catch (InterruptedException e) {
        // retry
      }
    }
  }

  private void verifyInstallPluginList(ConsoleUI ui, List<PluginData> plugins) {
    if (nullOrEmpty(installPlugins)) {
      return;
    }
    Set<String> missing = Sets.newHashSet(installPlugins);
    plugins.stream().forEach(p -> missing.remove(p.name));
    if (!missing.isEmpty()) {
      ui.message("Cannot find plugin(s): %s\n", Joiner.on(", ").join(missing));
      listPlugins = true;
    }
  }

  private void reindex(List<String> indices) throws Exception {
    if (indices.isEmpty()) {
      return;
    }
    List<String> reindexArgs =
        Lists.newArrayList(
            "--site-path", getSitePath().toString(), "--threads", Integer.toString(reindexThreads));
    for (String index : indices) {
      reindexArgs.add("--index");
      reindexArgs.add(index);
    }
    if (showCacheStats) {
      reindexArgs.add("--show-cache-stats");
    }

    getConsoleUI()
        .message(String.format("Init complete, reindexing %s with:", String.join(",", indices)));
    getConsoleUI().message(" reindex " + reindexArgs.stream().collect(joining(" ")));
    Reindex reindexPgm = new Reindex();

    @SuppressWarnings("unused")
    var unused = reindexPgm.main(reindexArgs.stream().toArray(String[]::new));
  }

  private static boolean nullOrEmpty(List<?> list) {
    return list == null || list.isEmpty();
  }
}
