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

package com.google.gerrit.pgm.util;

import static com.google.gerrit.server.config.GerritServerConfigModule.getSecureStoreClassName;
import static com.google.inject.Stage.PRODUCTION;

import com.google.gerrit.common.Die;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.dropwizard.DropWizardMetricMaker;
import com.google.gerrit.server.LibModuleLoader;
import com.google.gerrit.server.LibModuleType;
import com.google.gerrit.server.ModuleOverloader;
import com.google.gerrit.server.config.GerritOptions;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.experiments.ConfigExperimentFeatures.ConfigExperimentFeaturesModule;
import com.google.gerrit.server.git.GitRepositoryManagerModule;
import com.google.gerrit.server.git.SystemReaderInstaller;
import com.google.gerrit.server.schema.SchemaModule;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Option;

public abstract class SiteProgram extends AbstractProgram {
  protected List<Module> testDbModules = new ArrayList<>();

  @Option(
      name = "--site-path",
      aliases = {"-d"},
      usage = "Local directory containing site data")
  void setSitePath(String path) {
    sitePath = Path.of(path).normalize();
  }

  private Path sitePath = Path.of(".");

  protected SiteProgram() {}

  protected SiteProgram(Path sitePath) {
    this.sitePath = sitePath.normalize();
  }

  /** Returns the site path specified on the command line. */
  protected Path getSitePath() {
    return sitePath;
  }

  /** Ensures we are running inside of a valid site, otherwise throws a Die. */
  protected void mustHaveValidSite() throws Die {
    if (!Files.exists(sitePath.resolve("etc").resolve("gerrit.config"))) {
      throw die("not a Gerrit site: '" + getSitePath() + "'\nPerhaps you need to run init first?");
    }
  }

  /** Provides database connectivity and site path. */
  protected Injector createDbInjector() {
    return createDbInjector(false, GerritOptions.DEFAULT);
  }

  /** Provides database connectivity and site path. */
  protected Injector createDbInjector(boolean enableMetrics, GerritOptions options) {
    List<Module> modules = new ArrayList<>();

    Module sitePathModule =
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(Path.class).annotatedWith(SitePath.class).toInstance(getSitePath());
            bind(String.class)
                .annotatedWith(SecureStoreClassName.class)
                .toProvider(Providers.of(getConfiguredSecureStoreClass()));
          }
        };
    modules.add(sitePathModule);

    if (enableMetrics) {
      modules.add(new DropWizardMetricMaker.ApiModule());
    } else {
      modules.add(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(MetricMaker.class).to(DisabledMetricMaker.class);
            }
          });
    }

    modules.add(
        new LifecycleModule() {
          @Override
          protected void configure() {
            listener().to(SystemReaderInstaller.class);
          }
        });
    Module configModule = new GerritServerConfigModule();
    modules.add(configModule);
    modules.add(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(GerritRuntime.class).toInstance(getGerritRuntime());
          }
        });
    Module gerritOptionsModule =
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(GerritOptions.class).toInstance(options);
          }
        };
    modules.add(gerritOptionsModule);
    Injector cfgInjector = Guice.createInjector(sitePathModule, configModule, gerritOptionsModule);

    modules.add(cfgInjector.getInstance(SchemaModule.class));
    modules.add(cfgInjector.getInstance(GitRepositoryManagerModule.class));
    // The only implementation of experiments is available in all programs that can use
    // gerrit.config
    modules.add(new ConfigExperimentFeaturesModule());

    try {
      List<Module> extraDbModules =
          LibModuleLoader.loadModules(cfgInjector, LibModuleType.DB_MODULE_TYPE);
      extraDbModules.addAll(testDbModules);
      return Guice.createInjector(PRODUCTION, ModuleOverloader.override(modules, extraDbModules));
    } catch (CreationException ce) {
      Message first = ce.getErrorMessages().iterator().next();
      Throwable why = first.getCause();

      StringBuilder buf = new StringBuilder();
      if (why != null) {
        buf.append(why.getMessage());
        why = why.getCause();
      } else {
        buf.append(first.getMessage());
      }
      while (why != null) {
        buf.append("\n  caused by ");
        buf.append(why.toString());
        why = why.getCause();
      }
      throw die(buf.toString(), new RuntimeException("DbInjector failed", ce));
    }
  }

  /** Returns the current runtime used by this Gerrit program. */
  protected GerritRuntime getGerritRuntime() {
    return GerritRuntime.BATCH;
  }

  protected final String getConfiguredSecureStoreClass() {
    return getSecureStoreClassName(sitePath);
  }
}
