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

package com.google.gerrit.httpd.init;

import static com.google.inject.Stage.PRODUCTION;

import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.auth.AuthModule;
import com.google.gerrit.extensions.client.AuthType;
import com.google.gerrit.extensions.client.GitBasicAuthPolicy;
import com.google.gerrit.gpg.GpgModule;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.httpd.GerritAuthModule;
import com.google.gerrit.httpd.GetUserFilter;
import com.google.gerrit.httpd.GitOverHttpModule;
import com.google.gerrit.httpd.H2CacheBasedWebSession;
import com.google.gerrit.httpd.HttpCanonicalWebUrlProvider;
import com.google.gerrit.httpd.HttpdModule;
import com.google.gerrit.httpd.RequestCleanupFilter;
import com.google.gerrit.httpd.RequestContextFilter;
import com.google.gerrit.httpd.RequestMetricsFilter;
import com.google.gerrit.httpd.RequireSslFilter.RequireSslFilterModule;
import com.google.gerrit.httpd.SetThreadNameFilter;
import com.google.gerrit.httpd.WebModule;
import com.google.gerrit.httpd.WebSshGlueModule;
import com.google.gerrit.httpd.auth.oauth.OAuthModule;
import com.google.gerrit.httpd.auth.openid.OpenIdModule;
import com.google.gerrit.httpd.auth.restapi.OAuthRestModule;
import com.google.gerrit.httpd.plugins.HttpPluginModule;
import com.google.gerrit.httpd.raw.StaticModule;
import com.google.gerrit.index.IndexType;
import com.google.gerrit.lifecycle.LifecycleManager;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.lucene.LuceneIndexModule;
import com.google.gerrit.metrics.dropwizard.DropWizardMetricMaker;
import com.google.gerrit.pgm.util.LogFileManager.LogFileManagerModule;
import com.google.gerrit.server.DefaultRefLogIdentityProvider;
import com.google.gerrit.server.LibModuleLoader;
import com.google.gerrit.server.LibModuleType;
import com.google.gerrit.server.ModuleOverloader;
import com.google.gerrit.server.StartupChecks.StartupChecksModule;
import com.google.gerrit.server.account.AccountCacheImpl;
import com.google.gerrit.server.account.AccountDeactivator.AccountDeactivatorModule;
import com.google.gerrit.server.account.AuthTokenModule;
import com.google.gerrit.server.account.CachingAuthTokenModule;
import com.google.gerrit.server.account.InternalAccountDirectory.InternalAccountDirectoryModule;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdCaseSensitivityMigrator;
import com.google.gerrit.server.api.GerritApiModule;
import com.google.gerrit.server.api.PluginApiModule;
import com.google.gerrit.server.api.projects.ProjectQueryBuilderModule;
import com.google.gerrit.server.audit.AuditModule;
import com.google.gerrit.server.cache.h2.H2CacheModule;
import com.google.gerrit.server.cache.mem.DefaultMemoryCacheModule;
import com.google.gerrit.server.change.AttentionSetOwnerAdder.AttentionSetOwnerAdderModule;
import com.google.gerrit.server.change.ChangeCleanupRunner.ChangeCleanupRunnerModule;
import com.google.gerrit.server.change.DraftCommentsCleanupRunner;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.AuthConfigModule;
import com.google.gerrit.server.config.CanonicalWebUrlModule;
import com.google.gerrit.server.config.DefaultUrlFormatter.DefaultUrlFormatterModule;
import com.google.gerrit.server.config.DownloadConfig;
import com.google.gerrit.server.config.GerritGlobalModule;
import com.google.gerrit.server.config.GerritInstanceNameModule;
import com.google.gerrit.server.config.GerritOptions;
import com.google.gerrit.server.config.GerritRuntime;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.GerritServerConfigModule;
import com.google.gerrit.server.config.SitePath;
import com.google.gerrit.server.config.SysExecutorModule;
import com.google.gerrit.server.events.EventBroker.EventBrokerModule;
import com.google.gerrit.server.events.StreamEventsApiListener.StreamEventsApiListenerModule;
import com.google.gerrit.server.git.ChangesByProjectCache;
import com.google.gerrit.server.git.GarbageCollectionModule;
import com.google.gerrit.server.git.GitRepositoryManagerModule;
import com.google.gerrit.server.git.SystemReaderInstaller;
import com.google.gerrit.server.git.WorkQueue.WorkQueueModule;
import com.google.gerrit.server.index.IndexModule;
import com.google.gerrit.server.index.OnlineUpgrader.OnlineUpgraderModule;
import com.google.gerrit.server.index.VersionManager;
import com.google.gerrit.server.index.options.AutoFlush;
import com.google.gerrit.server.mail.EmailModule;
import com.google.gerrit.server.mail.SignedTokenEmailTokenVerifier.SignedTokenEmailTokenVerifierModule;
import com.google.gerrit.server.mail.receive.MailReceiver.MailReceiverModule;
import com.google.gerrit.server.mail.send.SmtpEmailSender.SmtpEmailSenderModule;
import com.google.gerrit.server.mime.MimeUtil2Module;
import com.google.gerrit.server.patch.DiffExecutorModule;
import com.google.gerrit.server.permissions.DefaultPermissionBackendModule;
import com.google.gerrit.server.plugins.PluginGuiceEnvironment;
import com.google.gerrit.server.plugins.PluginModule;
import com.google.gerrit.server.project.DefaultLockManager.DefaultLockManagerModule;
import com.google.gerrit.server.restapi.RestApiModule;
import com.google.gerrit.server.schema.JdbcAccountPatchReviewStore.JdbcAccountPatchReviewStoreModule;
import com.google.gerrit.server.schema.NoteDbSchemaVersionCheck;
import com.google.gerrit.server.schema.SchemaModule;
import com.google.gerrit.server.securestore.SecureStoreClassName;
import com.google.gerrit.server.ssh.NoSshModule;
import com.google.gerrit.server.ssh.SshAddressesModule;
import com.google.gerrit.server.submit.LocalMergeSuperSetComputation.LocalMergeSuperSetComputationModule;
import com.google.gerrit.server.submit.SubscriptionGraph.SubscriptionGraphModule;
import com.google.gerrit.server.update.SuperprojectUpdateSubmissionListener.SuperprojectUpdateSubmissionListenerModule;
import com.google.gerrit.sshd.SshHostKeyModule;
import com.google.gerrit.sshd.SshKeyCacheImpl;
import com.google.gerrit.sshd.SshModule;
import com.google.gerrit.sshd.SshSessionFactoryInitializer;
import com.google.gerrit.sshd.commands.DefaultCommandModule;
import com.google.gerrit.sshd.commands.ExternalIdCommandsModule;
import com.google.gerrit.sshd.commands.IndexCommandsModule;
import com.google.gerrit.sshd.commands.SequenceCommandsModule;
import com.google.gerrit.sshd.plugin.LfsPluginAuthCommand.LfsPluginAuthCommandModule;
import com.google.inject.AbstractModule;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jgit.lib.Config;

/** Configures the web application environment for Gerrit Code Review. */
public class WebAppInitializer extends GuiceServletContextListener implements Filter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String GERRIT_SITE_PATH = "gerrit.site_path";

  private Path sitePath;
  private Injector dbInjector;
  private Injector cfgInjector;
  private Config config;
  private AuthConfig authConfig;
  private Injector sysInjector;
  private Injector webInjector;
  private Injector sshInjector;
  private LifecycleManager manager;
  private GuiceFilter filter;

  private ServletContext servletContext;
  private IndexType indexType;

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    filter.doFilter(req, res, chain);
  }

  private synchronized void init() {
    if (manager == null) {
      String path = System.getProperty(GERRIT_SITE_PATH);
      if (path != null) {
        sitePath = Path.of(path);
      } else {
        throw new ProvisionException(GERRIT_SITE_PATH + " must be defined");
      }

      if (System.getProperty("gerrit.init") != null) {
        List<String> pluginsToInstall;
        String installPlugins = System.getProperty("gerrit.install_plugins");
        if (installPlugins == null) {
          pluginsToInstall = null;
        } else {
          pluginsToInstall =
              Splitter.on(",").trimResults().omitEmptyStrings().splitToList(installPlugins);
        }
        new SiteInitializer(
                path,
                System.getProperty(GERRIT_SITE_PATH),
                new UnzippedDistribution(servletContext),
                pluginsToInstall)
            .init();
      }

      try {
        cfgInjector = createCfgInjector();
      } catch (CreationException ce) {
        final Message first = ce.getErrorMessages().iterator().next();
        final StringBuilder buf = new StringBuilder();
        buf.append(first.getMessage());
        Throwable why = first.getCause();
        while (why != null) {
          buf.append("\n  caused by ");
          buf.append(why.toString());
          why = why.getCause();
        }
        if (first.getCause() != null) {
          buf.append("\n");
          buf.append("\nResolve above errors before continuing.");
          buf.append("\nComplete stack trace follows:");
        }
        logger.atSevere().withCause(first.getCause()).log("%s", buf);
        throw new CreationException(Collections.singleton(first));
      }

      dbInjector = createDbInjector();
      initIndexType();
      config = cfgInjector.getInstance(Key.get(Config.class, GerritServerConfig.class));
      authConfig = cfgInjector.getInstance(AuthConfig.class);
      sysInjector = createSysInjector();
      if (!sshdOff()) {
        sshInjector = createSshInjector();
      }
      webInjector = createWebInjector();

      PluginGuiceEnvironment env = sysInjector.getInstance(PluginGuiceEnvironment.class);
      env.setDbCfgInjector(dbInjector, cfgInjector);
      if (sshInjector != null) {
        env.setSshInjector(sshInjector);
      }
      env.setHttpInjector(webInjector);

      // Push the Provider<HttpServletRequest> down into the canonical
      // URL provider. Its optional for that provider, but since we can
      // supply one we should do so, in case the administrator has not
      // setup the canonical URL in the configuration file.
      //
      // Note we have to do this manually as Guice failed to do the
      // injection here because the HTTP environment is not visible
      // to the core server modules.
      //
      sysInjector
          .getInstance(HttpCanonicalWebUrlProvider.class)
          .setHttpServletRequest(webInjector.getProvider(HttpServletRequest.class));

      filter = webInjector.getInstance(GuiceFilter.class);
      manager = new LifecycleManager();
      manager.add(dbInjector);
      manager.add(cfgInjector);
      manager.add(sysInjector);
      if (sshInjector != null) {
        manager.add(sshInjector);
      }
      manager.add(webInjector);
    }
  }

  private boolean sshdOff() {
    return new SshAddressesModule().provideListenAddresses(config).isEmpty();
  }

  private Injector createCfgInjector() {
    final List<Module> modules = new ArrayList<>();
    AbstractModule secureStore = createSecureStoreModule();
    modules.add(secureStore);
    Module sitePathModule =
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(Path.class).annotatedWith(SitePath.class).toInstance(sitePath);
          }
        };
    modules.add(sitePathModule);

    Module configModule = new GerritServerConfigModule();
    modules.add(configModule);
    modules.add(
        new LifecycleModule() {
          @Override
          protected void configure() {
            listener().to(SystemReaderInstaller.class);
          }
        });
    modules.add(new DropWizardMetricMaker.ApiModule());
    return Guice.createInjector(PRODUCTION, modules);
  }

  private Injector createDbInjector() {
    final List<Module> modules = new ArrayList<>();
    modules.add(cfgInjector.getInstance(SchemaModule.class));
    modules.add(NoteDbSchemaVersionCheck.module());
    modules.add(new AuthConfigModule());
    return cfgInjector.createChildInjector(
        ModuleOverloader.override(
            modules, LibModuleLoader.loadModules(cfgInjector, LibModuleType.DB_MODULE_TYPE)));
  }

  private Injector createSysInjector() {
    final List<Module> modules = new ArrayList<>();
    modules.add(new DropWizardMetricMaker.RestModule());
    modules.add(new LogFileManagerModule());
    modules.add(new EventBrokerModule());
    modules.add(new JdbcAccountPatchReviewStoreModule(config));
    modules.add(cfgInjector.getInstance(GitRepositoryManagerModule.class));
    modules.add(new StreamEventsApiListenerModule(config));
    modules.add(new SysExecutorModule());
    modules.add(new DiffExecutorModule());
    modules.add(new MimeUtil2Module());

    modules.add(cfgInjector.getInstance(AccountCacheImpl.AccountCacheModule.class));
    modules.add(cfgInjector.getInstance(AccountCacheImpl.AccountCacheBindingModule.class));

    modules.add(cfgInjector.getInstance(GerritGlobalModule.class));
    modules.add(new GerritApiModule());
    modules.add(new ProjectQueryBuilderModule());
    modules.add(new DefaultRefLogIdentityProvider.Module());
    modules.add(new PluginApiModule());
    modules.add(new ChangesByProjectCache.Module(ChangesByProjectCache.UseIndex.TRUE, config));
    modules.add(new InternalAccountDirectoryModule());
    modules.add(new DefaultPermissionBackendModule());
    modules.add(new DefaultMemoryCacheModule());
    modules.add(new H2CacheModule());
    modules.add(cfgInjector.getInstance(MailReceiverModule.class));
    modules.add(new EmailModule());
    modules.add(new SmtpEmailSenderModule());
    modules.add(new SignedTokenEmailTokenVerifierModule());
    modules.add(new LocalMergeSuperSetComputationModule());
    modules.add(new AuditModule());
    modules.add(new GpgModule(config));
    modules.add(new StartupChecksModule());

    // Index module shutdown must happen before work queue shutdown, otherwise
    // work queue can get stuck waiting on index futures that will never return.
    modules.add(createIndexModule());

    modules.add(new PluginModule());
    if (VersionManager.shouldPerformOnlineUpgrade(config)) {
      modules.add(new OnlineUpgraderModule());
    }
    modules.add(new OAuthRestModule());
    modules.add(new RestApiModule());
    modules.add(new SubscriptionGraphModule());
    modules.add(new SuperprojectUpdateSubmissionListenerModule());
    modules.add(new WorkQueueModule());
    modules.add(new GerritInstanceNameModule());
    modules.add(
        new CanonicalWebUrlModule() {
          @Override
          protected Class<? extends Provider<String>> provider() {
            return HttpCanonicalWebUrlProvider.class;
          }
        });
    modules.add(new DefaultUrlFormatterModule());

    SshSessionFactoryInitializer.init();
    modules.add(SshKeyCacheImpl.module());

    boolean useAuthTokenCache =
        authConfig.getGitBasicAuthPolicy() == GitBasicAuthPolicy.HTTP
            || authConfig.getGitBasicAuthPolicy() == GitBasicAuthPolicy.HTTP_LDAP;
    if (useAuthTokenCache) {
      modules.add(new CachingAuthTokenModule());
    } else {
      modules.add(new AuthTokenModule());
    }

    modules.add(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(GerritOptions.class).toInstance(GerritOptions.DEFAULT);
            bind(GerritRuntime.class).toInstance(GerritRuntime.DAEMON);
          }
        });
    modules.add(new GarbageCollectionModule());
    modules.add(new AttentionSetOwnerAdderModule());
    modules.add(new ChangeCleanupRunnerModule());
    modules.add(new DraftCommentsCleanupRunner.Module());
    modules.add(new AccountDeactivatorModule());
    modules.add(new DefaultLockManagerModule());
    modules.add(new ExternalIdCaseSensitivityMigrator.ExternalIdCaseSensitivityMigratorModule());
    return dbInjector.createChildInjector(
        ModuleOverloader.override(
            modules, LibModuleLoader.loadModules(cfgInjector, LibModuleType.SYS_MODULE_TYPE)));
  }

  private Module createIndexModule() {
    if (indexType.isLucene()) {
      return LuceneIndexModule.latestVersion(false, AutoFlush.ENABLED);
    } else if (indexType.isFake()) {
      // Use Reflection so that we can omit the fake index binary in production code. Test code does
      // compile the component in.
      try {
        Class<?> clazz = Class.forName("com.google.gerrit.index.testing.FakeIndexModule");
        Method m = clazz.getMethod("latestVersion", boolean.class);
        return (Module) m.invoke(null, false);
      } catch (NoSuchMethodException
          | ClassNotFoundException
          | IllegalAccessException
          | InvocationTargetException e) {
        throw new IllegalStateException("can't create index", e);
      }
    } else {
      throw new IllegalStateException("unsupported index.type = " + indexType);
    }
  }

  private void initIndexType() {
    indexType = IndexModule.getIndexType(cfgInjector);
  }

  private Injector createSshInjector() {
    final List<Module> modules = new ArrayList<>();
    modules.add(sysInjector.getInstance(SshModule.class));
    modules.add(new SshHostKeyModule());
    modules.add(
        new DefaultCommandModule(
            false,
            sysInjector.getInstance(DownloadConfig.class),
            sysInjector.getInstance(LfsPluginAuthCommandModule.class)));
    modules.add(new IndexCommandsModule(sysInjector));
    modules.add(new SequenceCommandsModule());
    modules.add(new ExternalIdCommandsModule());
    return sysInjector.createChildInjector(modules);
  }

  private Injector createWebInjector() {
    final List<Module> modules = new ArrayList<>();
    modules.add(RequestContextFilter.module());
    modules.add(RequestMetricsFilter.module());
    modules.add(sysInjector.getInstance(GerritAuthModule.class));
    modules.add(sysInjector.getInstance(GitOverHttpModule.class));
    modules.add(sysInjector.getInstance(HttpdModule.class));
    modules.add(RequestCleanupFilter.module());
    modules.add(SetThreadNameFilter.module());
    modules.add(AllRequestFilter.module());
    modules.add(sysInjector.getInstance(WebModule.class));
    modules.add(sysInjector.getInstance(RequireSslFilterModule.class));
    if (sshInjector != null) {
      modules.add(sshInjector.getInstance(WebSshGlueModule.class));
    } else {
      modules.add(new NoSshModule());
    }
    modules.add(H2CacheBasedWebSession.module());
    modules.add(new HttpPluginModule());

    AuthConfig authConfig = cfgInjector.getInstance(AuthConfig.class);
    if (authConfig.getAuthType() == AuthType.OPENID) {
      modules.add(new OpenIdModule());
    } else if (authConfig.getAuthType() == AuthType.OAUTH) {
      modules.add(new OAuthModule());
    }
    modules.add(new AuthModule(authConfig));

    modules.add(sysInjector.getInstance(GetUserFilter.GetUserFilterModule.class));

    // StaticModule contains a "/*" wildcard, place it last.
    GerritOptions opts = sysInjector.getInstance(GerritOptions.class);
    if (opts.enableMasterFeatures()) {
      modules.add(sysInjector.getInstance(StaticModule.class));
    }

    return sysInjector.createChildInjector(modules);
  }

  @Override
  protected Injector getInjector() {
    init();
    return webInjector;
  }

  @Override
  public void init(FilterConfig cfg) throws ServletException {
    servletContext = cfg.getServletContext();
    contextInitialized(new ServletContextEvent(servletContext));
    init();
    manager.start();
  }

  @Override
  public void destroy() {
    if (manager != null) {
      manager.stop();
      manager = null;
    }
  }

  private AbstractModule createSecureStoreModule() {
    return new AbstractModule() {
      @Override
      public void configure() {
        String secureStoreClassName = GerritServerConfigModule.getSecureStoreClassName(sitePath);
        bind(String.class)
            .annotatedWith(SecureStoreClassName.class)
            .toProvider(Providers.of(secureStoreClassName));
      }
    };
  }
}
