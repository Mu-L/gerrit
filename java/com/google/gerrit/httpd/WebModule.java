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

package com.google.gerrit.httpd;

import static com.google.gerrit.extensions.registration.PrivateInternals_DynamicTypes.registerInParentInjectors;

import com.google.gerrit.httpd.GitOverHttpServlet.GitOverHttpServletModule;
import com.google.gerrit.httpd.auth.become.BecomeAnyAccountModule;
import com.google.gerrit.httpd.auth.container.HttpAuthModule;
import com.google.gerrit.httpd.auth.container.HttpsClientSslCertModule;
import com.google.gerrit.httpd.auth.ldap.LdapAuthModule;
import com.google.gerrit.httpd.gitweb.GitwebModule;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.RemotePeer;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.config.GerritOptions;
import com.google.gerrit.server.config.GerritRequestModule;
import com.google.gerrit.server.config.GitwebCgiConfig;
import com.google.gerrit.server.git.receive.AsyncReceiveCommits.AsyncReceiveCommitsModule;
import com.google.gerrit.server.util.RequestScopePropagator;
import com.google.inject.Inject;
import com.google.inject.ProvisionException;
import com.google.inject.servlet.RequestScoped;
import java.net.SocketAddress;

public class WebModule extends LifecycleModule {
  private final AuthConfig authConfig;
  private final GitwebCgiConfig gitwebCgiConfig;
  private final GerritOptions options;

  @Inject
  WebModule(AuthConfig authConfig, GerritOptions options, GitwebCgiConfig gitwebCgiConfig) {
    this.authConfig = authConfig;
    this.options = options;
    this.gitwebCgiConfig = gitwebCgiConfig;
  }

  @Override
  protected void configure() {
    install(new HttpRequestTraceModule());

    bind(RequestScopePropagator.class).to(GuiceRequestScopePropagator.class);
    bind(HttpRequestContext.class);

    installAuthModule();
    if (options.enableMasterFeatures()) {
      install(new UrlModule(authConfig));
    }
    install(new GerritRequestModule());
    install(new GitOverHttpServletModule(options.enableMasterFeatures()));

    if (gitwebCgiConfig.getGitwebCgi() != null) {
      install(new GitwebModule());
    }

    install(new AsyncReceiveCommitsModule());

    bind(SocketAddress.class)
        .annotatedWith(RemotePeer.class)
        .toProvider(HttpRemotePeerProvider.class)
        .in(RequestScoped.class);

    bind(ProxyProperties.class).toProvider(ProxyPropertiesProvider.class);

    listener().toInstance(registerInParentInjectors());

    install(UniversalWebLoginFilter.module());

    // Static injection was unfortunately the best solution in this place. However, it is to be
    // avoided if possible.
    requestStaticInjection(WebSessionManager.Val.class);
  }

  private void installAuthModule() {
    switch (authConfig.getAuthType()) {
      case HTTP, HTTP_LDAP -> install(new HttpAuthModule(authConfig));
      case CLIENT_SSL_CERT_LDAP -> install(new HttpsClientSslCertModule());
      case LDAP, LDAP_BIND -> install(new LdapAuthModule());
      case DEVELOPMENT_BECOME_ANY_ACCOUNT -> install(new BecomeAnyAccountModule());
      case OAUTH, OPENID, OPENID_SSO, CUSTOM_EXTENSION -> {
        // OAuth support is bound in WebAppInitializer and Daemon.
        // OpenID support is bound in WebAppInitializer and Daemon.
      }
      default -> throw new ProvisionException("Unsupported loginType: " + authConfig.getAuthType());
    }
  }
}
