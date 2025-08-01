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

package com.google.gerrit.server.restapi.config;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.toList;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.ContributorAgreement;
import com.google.gerrit.extensions.common.AccountDefaultDisplayName;
import com.google.gerrit.extensions.common.AccountsInfo;
import com.google.gerrit.extensions.common.AuthInfo;
import com.google.gerrit.extensions.common.ChangeConfigInfo;
import com.google.gerrit.extensions.common.DownloadInfo;
import com.google.gerrit.extensions.common.DownloadSchemeInfo;
import com.google.gerrit.extensions.common.GerritInfo;
import com.google.gerrit.extensions.common.MetadataInfo;
import com.google.gerrit.extensions.common.PluginConfigInfo;
import com.google.gerrit.extensions.common.ReceiveInfo;
import com.google.gerrit.extensions.common.ServerInfo;
import com.google.gerrit.extensions.common.SshdInfo;
import com.google.gerrit.extensions.common.SuggestInfo;
import com.google.gerrit.extensions.common.UserConfigInfo;
import com.google.gerrit.extensions.config.CloneCommand;
import com.google.gerrit.extensions.config.DownloadCommand;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.server.EnableSignedPush;
import com.google.gerrit.server.ServerStateProvider;
import com.google.gerrit.server.account.AccountVisibilityProvider;
import com.google.gerrit.server.account.Realm;
import com.google.gerrit.server.avatar.AvatarProvider;
import com.google.gerrit.server.change.ArchiveFormatInternal;
import com.google.gerrit.server.change.MergeabilityComputationBehavior;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.ConfigResource;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.documentation.QueryDocumentationExecutor;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.gerrit.server.plugincontext.PluginMapContext;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.restapi.change.AllowedFormats;
import com.google.gerrit.server.submit.MergeSuperSet;
import com.google.inject.Inject;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

public class GetServerInfo implements RestReadView<ConfigResource> {
  private final Config config;
  private final AccountVisibilityProvider accountVisibilityProvider;
  private final AccountDefaultDisplayName accountDefaultDisplayName;
  private final AuthConfig authConfig;
  private final Realm realm;
  private final PluginMapContext<DownloadScheme> downloadSchemes;
  private final PluginMapContext<DownloadCommand> downloadCommands;
  private final PluginMapContext<CloneCommand> cloneCommands;
  private final PluginSetContext<WebUiPlugin> plugins;
  private final AllowedFormats archiveFormats;
  private final AllProjectsName allProjectsName;
  private final AllUsersName allUsersName;
  private final String anonymousCowardName;
  private final PluginItemContext<AvatarProvider> avatar;
  private final boolean enableSignedPush;
  private final QueryDocumentationExecutor docSearcher;
  private final ProjectCache projectCache;
  private final AgreementJson agreementJson;
  private final SitePaths sitePaths;
  private final @Nullable @GerritInstanceId String instanceId;
  private final PluginSetContext<ServerStateProvider> serverStateProviders;

  @Inject
  public GetServerInfo(
      @GerritServerConfig Config config,
      AccountVisibilityProvider accountVisibilityProvider,
      AccountDefaultDisplayName accountDefaultDisplayName,
      AuthConfig authConfig,
      Realm realm,
      PluginMapContext<DownloadScheme> downloadSchemes,
      PluginMapContext<DownloadCommand> downloadCommands,
      PluginMapContext<CloneCommand> cloneCommands,
      PluginSetContext<WebUiPlugin> webUiPlugins,
      AllowedFormats archiveFormats,
      AllProjectsName allProjectsName,
      AllUsersName allUsersName,
      @AnonymousCowardName String anonymousCowardName,
      PluginItemContext<AvatarProvider> avatar,
      @EnableSignedPush boolean enableSignedPush,
      QueryDocumentationExecutor docSearcher,
      ProjectCache projectCache,
      AgreementJson agreementJson,
      SitePaths sitePaths,
      @Nullable @GerritInstanceId String instanceId,
      PluginSetContext<ServerStateProvider> serverStateProviders) {
    this.config = config;
    this.accountVisibilityProvider = accountVisibilityProvider;
    this.accountDefaultDisplayName = accountDefaultDisplayName;
    this.authConfig = authConfig;
    this.realm = realm;
    this.downloadSchemes = downloadSchemes;
    this.downloadCommands = downloadCommands;
    this.cloneCommands = cloneCommands;
    this.plugins = webUiPlugins;
    this.archiveFormats = archiveFormats;
    this.allProjectsName = allProjectsName;
    this.allUsersName = allUsersName;
    this.anonymousCowardName = anonymousCowardName;
    this.avatar = avatar;
    this.enableSignedPush = enableSignedPush;
    this.docSearcher = docSearcher;
    this.projectCache = projectCache;
    this.agreementJson = agreementJson;
    this.sitePaths = sitePaths;
    this.instanceId = instanceId;
    this.serverStateProviders = serverStateProviders;
  }

  @Override
  public Response<ServerInfo> apply(ConfigResource rsrc) throws PermissionBackendException {
    ServerInfo info = new ServerInfo();
    info.accounts = getAccountsInfo();
    info.auth = getAuthInfo();
    info.change = getChangeInfo();
    info.download = getDownloadInfo();
    info.gerrit = getGerritInfo();
    info.noteDbEnabled = true;
    info.plugin = getPluginInfo();
    info.defaultTheme = getDefaultTheme();
    info.sshd = getSshdInfo();
    info.suggest = getSuggestInfo();

    info.user = getUserInfo();
    info.receive = getReceiveInfo();
    info.submitRequirementDashboardColumns = getSubmitRequirementDashboardColumns();
    info.dashboardShowAllLabels = getDashboardShowAllLabels();
    info.metadata = getMetadata();
    return Response.ok(info);
  }

  private AccountsInfo getAccountsInfo() {
    AccountsInfo info = new AccountsInfo();
    info.visibility = accountVisibilityProvider.get();
    info.defaultDisplayName = accountDefaultDisplayName;
    return info;
  }

  private AuthInfo getAuthInfo() throws PermissionBackendException {
    AuthInfo info = new AuthInfo();
    info.authType = authConfig.getAuthType();
    info.useContributorAgreements = toBoolean(authConfig.isUseContributorAgreements());
    info.editableAccountFields = new ArrayList<>(realm.getEditableFields());
    info.switchAccountUrl = authConfig.getSwitchAccountUrl();
    info.gitBasicAuthPolicy = authConfig.getGitBasicAuthPolicy();
    if (authConfig.getMaxAuthTokenLifetime().isPresent()) {
      info.maxTokenLifetime = authConfig.getMaxAuthTokenLifetime().get().toMinutes();
    }

    if (info.useContributorAgreements != null) {
      ImmutableCollection<ContributorAgreement> agreements =
          projectCache.getAllProjects().getConfig().getContributorAgreements().values();
      if (!agreements.isEmpty()) {
        info.contributorAgreements = Lists.newArrayListWithCapacity(agreements.size());
        for (ContributorAgreement agreement : agreements) {
          info.contributorAgreements.add(agreementJson.format(agreement));
        }
      }
    }

    switch (info.authType) {
      case LDAP, LDAP_BIND -> {
        info.registerUrl = authConfig.getRegisterUrl();
        info.registerText = authConfig.getRegisterText();
        info.editFullNameUrl = authConfig.getEditFullNameUrl();
      }
      case CUSTOM_EXTENSION -> {
        info.registerUrl = authConfig.getRegisterUrl();
        info.registerText = authConfig.getRegisterText();
        info.editFullNameUrl = authConfig.getEditFullNameUrl();
        info.httpPasswordUrl = authConfig.getHttpPasswordUrl();
      }
      case HTTP, HTTP_LDAP -> {
        info.loginUrl = authConfig.getLoginUrl();
        info.loginText = authConfig.getLoginText();
      }
      case CLIENT_SSL_CERT_LDAP, DEVELOPMENT_BECOME_ANY_ACCOUNT, OAUTH, OPENID, OPENID_SSO -> {}
    }
    return info;
  }

  private ChangeConfigInfo getChangeInfo() {
    ChangeConfigInfo info = new ChangeConfigInfo();
    info.allowBlame = toBoolean(config.getBoolean("change", "allowBlame", true));
    info.updateDelay =
        (int) ConfigUtil.getTimeUnit(config, "change", null, "updateDelay", 300, TimeUnit.SECONDS);
    info.submitWholeTopic = toBoolean(MergeSuperSet.wholeTopicEnabled(config));
    info.disablePrivateChanges =
        toBoolean(this.config.getBoolean("change", null, "disablePrivateChanges", false));
    info.mergeabilityComputationBehavior =
        MergeabilityComputationBehavior.fromConfig(config).name();
    info.conflictsPredicateEnabled =
        toBoolean(config.getBoolean("change", "conflictsPredicateEnabled", true));
    info.allowMarkdownBase64ImagesInComments =
        toBoolean(config.getBoolean("change", "allowMarkdownBase64ImagesInComments", false));
    return info;
  }

  private DownloadInfo getDownloadInfo() {
    DownloadInfo info = new DownloadInfo();
    info.schemes = new HashMap<>();
    downloadSchemes.runEach(
        extension -> {
          DownloadScheme scheme = extension.get();
          if (scheme.isEnabled() && !scheme.isHidden() && scheme.getUrl("${project}") != null) {
            info.schemes.put(extension.getExportName(), getDownloadSchemeInfo(scheme));
          }
        });
    info.archives =
        archiveFormats.getAllowed().stream()
            .map(ArchiveFormatInternal::getShortName)
            .collect(toList());
    return info;
  }

  private DownloadSchemeInfo getDownloadSchemeInfo(DownloadScheme scheme) {
    DownloadSchemeInfo info = new DownloadSchemeInfo();
    info.url = scheme.getUrl("${project}");
    info.description = scheme.getDescription();
    info.isAuthRequired = toBoolean(scheme.isAuthRequired());
    info.isAuthSupported = toBoolean(scheme.isAuthSupported());

    info.commands = new HashMap<>();
    downloadCommands.runEach(
        extension -> {
          String commandName = extension.getExportName();
          DownloadCommand command = extension.get();
          String c = command.getCommand(scheme, "${project}", "${ref}");
          if (c != null) {
            info.commands.put(commandName, c);
          }
        });

    info.cloneCommands = new HashMap<>();
    cloneCommands.runEach(
        extension -> {
          String commandName = extension.getExportName();
          CloneCommand command = extension.getProvider().get();
          String c = command.getCommand(scheme, "${project-path}/${project-base-name}");
          if (c != null) {
            c =
                c.replaceAll(
                    "\\$\\{project-path\\}/\\$\\{project-base-name\\}", "\\$\\{project\\}");
            info.cloneCommands.put(commandName, c);
          }
        });

    return info;
  }

  private GerritInfo getGerritInfo() {
    GerritInfo info = new GerritInfo();
    info.allProjects = allProjectsName.get();
    info.allUsers = allUsersName.get();
    info.reportBugUrl = config.getString("gerrit", null, "reportBugUrl");
    info.docUrl = getDocUrl();
    info.docSearch = docSearcher.isAvailable();
    info.editGpgKeys =
        toBoolean(enableSignedPush && config.getBoolean("gerrit", null, "editGpgKeys", true));
    info.primaryWeblinkName = config.getString("gerrit", null, "primaryWeblinkName");
    info.instanceId = instanceId;
    info.defaultBranch = config.getString("gerrit", null, "defaultBranch");
    info.projectStatePredicateEnabled =
        config.getBoolean("gerrit", null, "projectStatePredicateEnabled", true);
    return info;
  }

  @Nullable
  private String getDocUrl() {
    String docUrl = config.getString("gerrit", null, "docUrl");
    if (Strings.isNullOrEmpty(docUrl)) {
      return null;
    }
    return CharMatcher.is('/').trimTrailingFrom(docUrl) + '/';
  }

  private PluginConfigInfo getPluginInfo() {
    PluginConfigInfo info = new PluginConfigInfo();
    info.hasAvatars = toBoolean(avatar.hasImplementation());
    info.jsResourcePaths = new ArrayList<>();
    plugins.runEach(
        plugin -> {
          String path =
              String.format(
                  "plugins/%s/%s", plugin.getPluginName(), plugin.getJavaScriptResourcePath());
          info.jsResourcePaths.add(path);
        });
    return info;
  }

  private static final String DEFAULT_THEME_JS = "/static/" + SitePaths.THEME_JS_FILENAME;

  @Nullable
  private String getDefaultTheme() {
    if (config.getString("theme", null, "enableDefault") == null) {
      // If not explicitly enabled or disabled, check for the existence of the theme file.
      return Files.exists(sitePaths.site_theme_js) ? DEFAULT_THEME_JS : null;
    }
    if (config.getBoolean("theme", null, "enableDefault", true)) {
      // Return non-null theme path without checking for file existence. Even if the file doesn't
      // exist under the site path, it may be served from a CDN (in which case it's up to the admin
      // to also pass a proper asset path to the index Soy template).
      return DEFAULT_THEME_JS;
    }
    return null;
  }

  @Nullable
  private SshdInfo getSshdInfo() {
    String[] addr = config.getStringList("sshd", null, "listenAddress");
    if (addr.length == 1 && isOff(addr[0])) {
      return null;
    }
    return new SshdInfo();
  }

  private static boolean isOff(String listenHostname) {
    return "off".equalsIgnoreCase(listenHostname)
        || "none".equalsIgnoreCase(listenHostname)
        || "no".equalsIgnoreCase(listenHostname);
  }

  private SuggestInfo getSuggestInfo() {
    SuggestInfo info = new SuggestInfo();
    info.from = config.getInt("suggest", "from", 0);
    return info;
  }

  private UserConfigInfo getUserInfo() {
    UserConfigInfo info = new UserConfigInfo();
    info.anonymousCowardName = anonymousCowardName;
    return info;
  }

  private ReceiveInfo getReceiveInfo() {
    ReceiveInfo info = new ReceiveInfo();
    info.enableSignedPush = enableSignedPush;
    return info;
  }

  private List<String> getSubmitRequirementDashboardColumns() {
    return Arrays.asList(config.getStringList("dashboard", null, "submitRequirementColumns"));
  }

  private Boolean getDashboardShowAllLabels() {
    return toBoolean(config.getBoolean("dashboard", null, "showAllLabels", false));
  }

  private ImmutableList<MetadataInfo> getMetadata() {
    ArrayList<MetadataInfo> metadataList = new ArrayList<>();
    serverStateProviders.runEach(
        serverStateProvider -> metadataList.addAll(serverStateProvider.getMetadata()));
    return metadataList.stream()
        .sorted(
            Comparator.comparing((MetadataInfo metadata) -> metadata.name)
                .thenComparing(
                    (MetadataInfo metadata) -> metadata.value != null ? metadata.value : ""))
        .collect(toImmutableList());
  }

  @Nullable
  private static Boolean toBoolean(boolean v) {
    return v ? Boolean.TRUE : null;
  }
}
