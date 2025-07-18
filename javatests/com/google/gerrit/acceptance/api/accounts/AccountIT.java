// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.acceptance.api.accounts;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.GitUtil.deleteRef;
import static com.google.gerrit.acceptance.GitUtil.fetch;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.deny;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.permissionKey;
import static com.google.gerrit.gpg.PublicKeyStore.REFS_GPG_KEYS;
import static com.google.gerrit.gpg.PublicKeyStore.keyToString;
import static com.google.gerrit.gpg.testing.TestKeys.allValidKeys;
import static com.google.gerrit.gpg.testing.TestKeys.validKeyWithExpiration;
import static com.google.gerrit.gpg.testing.TestKeys.validKeyWithSecondUserId;
import static com.google.gerrit.gpg.testing.TestKeys.validKeyWithoutExpiration;
import static com.google.gerrit.server.account.AccountProperties.ACCOUNT;
import static com.google.gerrit.server.account.AccountProperties.ACCOUNT_CONFIG;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_GPGKEY;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_MAILTO;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.ProjectCache.illegalState;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;
import static com.google.gerrit.truth.ConfigSubject.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.StopStrategies;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import com.google.common.truth.Correspondence;
import com.google.common.util.concurrent.AtomicLongMap;
import com.google.common.util.concurrent.Runnables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.AccountIndexedCounter;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.UseClockStep;
import com.google.gerrit.acceptance.UseSsh;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.account.TestSshKeys;
import com.google.gerrit.acceptance.testsuite.group.GroupOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.acceptance.testsuite.request.SshSessionFactory;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.PermissionRule.Action;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.accounts.AccountApi;
import com.google.gerrit.extensions.api.accounts.AccountInput;
import com.google.gerrit.extensions.api.accounts.DeleteDraftCommentsInput;
import com.google.gerrit.extensions.api.accounts.DeletedDraftCommentInfo;
import com.google.gerrit.extensions.api.accounts.EmailInput;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewerInput;
import com.google.gerrit.extensions.api.config.ConsistencyCheckInfo;
import com.google.gerrit.extensions.api.config.ConsistencyCheckInfo.ConsistencyProblemInfo;
import com.google.gerrit.extensions.api.config.ConsistencyCheckInput;
import com.google.gerrit.extensions.api.config.ConsistencyCheckInput.CheckAccountsInput;
import com.google.gerrit.extensions.client.ProjectWatchInfo;
import com.google.gerrit.extensions.common.AccountDetailInfo;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.AccountStateInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.EmailInfo;
import com.google.gerrit.extensions.common.GpgKeyInfo;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.common.MetadataInfo;
import com.google.gerrit.extensions.common.SshKeyInfo;
import com.google.gerrit.extensions.events.AccountActivationListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.gpg.Fingerprint;
import com.google.gerrit.gpg.PublicKeyStore;
import com.google.gerrit.gpg.testing.TestKey;
import com.google.gerrit.httpd.CacheBasedWebSession;
import com.google.gerrit.server.ExceptionHook;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.Sequence;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.ServerInitiated;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.account.AccountLimits;
import com.google.gerrit.server.account.AccountProperties;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.AccountStateProvider;
import com.google.gerrit.server.account.AccountsUpdate;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.VersionedAuthorizedKeys;
import com.google.gerrit.server.account.externalids.DuplicateExternalIdKeyException;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdFactoryNoteDbImpl;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdNotes;
import com.google.gerrit.server.account.externalids.storage.notedb.ExternalIdsNoteDbImpl;
import com.google.gerrit.server.account.storage.notedb.AccountsUpdateNoteDbImpl;
import com.google.gerrit.server.change.AccountPatchReviewStore;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.meta.MetaDataUpdate;
import com.google.gerrit.server.group.testing.TestGroupBackend;
import com.google.gerrit.server.index.account.AccountIndexer;
import com.google.gerrit.server.index.account.StalenessChecker;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.plugincontext.PluginSetContext;
import com.google.gerrit.server.project.RefPattern;
import com.google.gerrit.server.query.account.InternalAccountQuery;
import com.google.gerrit.server.restapi.account.GetCapabilities;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.update.RetryListener;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.server.validators.AccountActivationValidationListener;
import com.google.gerrit.server.validators.ValidationException;
import com.google.gerrit.testing.ConfigSuite;
import com.google.gerrit.testing.FakeEmailSender.Message;
import com.google.gerrit.truth.NullAwareCorrespondence;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushCertificateIdent;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AccountIT extends AbstractDaemonTest {
  @ConfigSuite.Default
  public static Config enableSignedPushConfig() {
    Config cfg = new Config();
    cfg.setBoolean("receive", null, "enableSignedPush", true);

    // Disable the staleness checker so that tests that verify the number of expected index events
    // are stable.
    cfg.setBoolean("index", null, "autoReindexIfStale", false);

    return cfg;
  }

  @Inject protected ProjectOperations projectOperations;
  @Inject protected Emails emails;
  @Inject protected ExtensionRegistry extensionRegistry;
  @Inject protected RequestScopeOperations requestScopeOperations;

  @Inject protected GroupOperations groupOperations;

  @Inject private @ServerInitiated Provider<AccountsUpdate> accountsUpdateProvider;
  @Inject private AccountIndexer accountIndexer;
  @Inject private ExternalIdNotes.Factory extIdNotesFactory;
  @Inject private ExternalIdsNoteDbImpl externalIdsNoteDbImpl;
  @Inject private GitReferenceUpdated gitReferenceUpdated;
  @Inject private Provider<InternalAccountQuery> accountQueryProvider;
  @Inject private Provider<MetaDataUpdate.InternalFactory> metaDataUpdateInternalFactory;
  @Inject private Provider<PublicKeyStore> publicKeyStoreProvider;
  @Inject private RetryHelper.Metrics retryMetrics;
  @Inject private Sequences seq;
  @Inject private StalenessChecker stalenessChecker;
  @Inject private VersionedAuthorizedKeys.Accessor authorizedKeys;
  @Inject private PluginSetContext<ExceptionHook> exceptionHooks;
  @Inject private PluginSetContext<RetryListener> retryListeners;
  @Inject private ExternalIdKeyFactory externalIdKeyFactory;
  @Inject private ExternalIdFactoryNoteDbImpl externalIdFactoryNoteDbImpl;
  @Inject private AuthConfig authConfig;
  @Inject private AccountControl.Factory accountControlFactory;
  @Inject private AccountOperations accountOperations;
  @Inject private AccountLimits.Factory limitsFactory;
  @Inject private AccountPatchReviewStore accountPatchReviewStore;
  @Inject private IdentifiedUser.GenericFactory genericUserFactory;
  @Inject private PermissionBackend permissionBackend;

  private BasicCookieStore httpCookieStore;
  private CloseableHttpClient httpclient;

  @After
  public void clearPublicKeyStore() throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers)) {
      Ref ref = repo.exactRef(REFS_GPG_KEYS);
      if (ref != null) {
        RefUpdate ru = repo.updateRef(REFS_GPG_KEYS);
        ru.setForceUpdate(true);
        testRefAction(() -> assertThat(ru.delete()).isEqualTo(RefUpdate.Result.FORCED));
      }
    }
  }

  @After
  public void deleteGpgKeys() throws Exception {
    String ref = REFS_GPG_KEYS;
    try (Repository repo = repoManager.openRepository(allUsers)) {
      if (repo.getRefDatabase().exactRef(ref) != null) {
        RefUpdate ru = repo.updateRef(ref);
        ru.setForceUpdate(true);
        assertWithMessage("Failed to delete " + ref)
            .that(ru.delete())
            .isEqualTo(RefUpdate.Result.FORCED);
      }
    }
  }

  @Before
  public void createHttpClient() {
    httpCookieStore = new BasicCookieStore();
    httpclient =
        HttpClientBuilder.create()
            .disableRedirectHandling()
            .setDefaultCookieStore(httpCookieStore)
            .build();
  }

  protected void assertLabelPermission(
      Project.NameKey project,
      GroupReference groupReference,
      String ref,
      boolean exclusive,
      String labelName,
      int min,
      int max) {
    Optional<AccessSection> accessSection =
        projectCache
            .get(project)
            .orElseThrow(illegalState(project))
            .getConfig()
            .getAccessSection(ref);
    assertThat(accessSection).isPresent();

    String permissionName = Permission.LABEL + labelName;
    Permission permission = accessSection.get().getPermission(permissionName);
    assertPermission(permission, permissionName, exclusive, labelName);
    assertPermissionRule(
        permission.getRule(groupReference), groupReference, Action.ALLOW, false, min, max);
  }

  @Test
  public void createByAccountCreator() throws Exception {
    RefUpdateCounter refUpdateCounter = createRefUpdateCounter();
    try (Registration registration = extensionRegistry.newRegistration().add(refUpdateCounter)) {
      Account.Id accountId = createByAccountCreator(1);
      refUpdateCounter.assertRefUpdateFor(
          RefUpdateCounter.projectRef(allUsers, RefNames.refsUsers(accountId)),
          RefUpdateCounter.projectRef(allUsers, RefNames.REFS_EXTERNAL_IDS),
          RefUpdateCounter.projectRef(allUsers, RefNames.REFS_SEQUENCES + Sequence.NAME_ACCOUNTS));
    }
  }

  @Test
  public void createWithInvalidEmailAddress() throws Exception {
    AccountInput input = new AccountInput();
    input.username = name("test");
    input.email = "invalid email address";

    // Invalid email address should cause the creation to fail
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> gApi.accounts().create(input));
    assertThat(thrown).hasMessageThat().isEqualTo("invalid email address");

    // The account should not have been created
    assertThrows(ResourceNotFoundException.class, () -> gApi.accounts().id(input.username).get());
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected AccountIndexedCounter getAccountIndexedCounter() {
    return new AccountIndexedCounter();
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected Account.Id createByAccountCreator(int expectedAccountReindexCalls) throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String name = "foo";
      TestAccount foo = accountCreator.create(name);
      AccountInfo info = gApi.accounts().id(foo.id().get()).get();
      if (server.isUsernameSupported()) {
        assertThat(info.username).isEqualTo(name);
      } else {
        assertThat(info.email).isEqualTo(foo.email());
      }
      assertThat(info.name).isEqualTo(name);
      accountIndexedCounter.assertReindexOf(foo, expectedAccountReindexCalls);
      assertUserBranch(foo.id(), name, null);
      return foo.id();
    }
  }

  @Test
  public void createAnonymousCowardByAccountCreator() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      TestAccount anonymousCoward = accountCreator.create();
      accountIndexedCounter.assertReindexOf(anonymousCoward);
      assertUserBranchWithoutAccountConfig(anonymousCoward.id());
    }
  }

  @Test
  public void create() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      AccountInput input = new AccountInput();
      input.username = "foo";
      input.name = "Foo";
      input.email = "foo@example.com";
      AccountInfo accountInfo = gApi.accounts().create(input).get();
      assertThat(accountInfo._accountId).isNotNull();
      assertThat(accountInfo.username).isEqualTo(input.username);
      assertThat(accountInfo.name).isEqualTo(input.name);
      assertThat(accountInfo.email).isEqualTo(input.email);
      assertThat(accountInfo.status).isNull();
      assertThat(accountInfo.tags).isNull();

      Account.Id accountId = Account.id(accountInfo._accountId);
      accountIndexedCounter.assertReindexOf(accountId, 1);
      assertThat(getExternalIdsReader().byAccount(accountId))
          .containsExactly(
              getExternalIdFactory().createUsername(input.username, accountId),
              getExternalIdFactory().createEmail(accountId, input.email));
    }
  }

  @Test
  public void createAccountUsernameAlreadyTaken() throws Exception {
    AccountInput input = new AccountInput();
    input.username = admin.username();

    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> gApi.accounts().create(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains("username '" + admin.username() + "' already exists");
  }

  @Test
  public void createAccountEmailAlreadyTaken() throws Exception {
    AccountInput input = new AccountInput();
    input.username = "foo";
    input.email = admin.email();

    UnprocessableEntityException thrown =
        assertThrows(UnprocessableEntityException.class, () -> gApi.accounts().create(input));
    assertThat(thrown).hasMessageThat().contains("email '" + admin.email() + "' already exists");
  }

  @Test
  public void commitMessageOnAccountUpdates() throws Exception {
    AccountsUpdate au = accountsUpdateProvider.get();
    Account.Id accountId = Account.id(seq.nextAccountId());
    au.insert("Create Test Account", accountId, u -> {});
    assertLastCommitMessageOfUserBranch(accountId, "Create Test Account");

    au.update("Set Status", accountId, u -> u.setStatus("Foo"));
    assertLastCommitMessageOfUserBranch(accountId, "Set Status");
  }

  private void assertLastCommitMessageOfUserBranch(Account.Id accountId, String expectedMessage)
      throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(repo)) {
      Ref exactRef = repo.exactRef(RefNames.refsUsers(accountId));
      assertThat(rw.parseCommit(exactRef.getObjectId()).getShortMessage())
          .isEqualTo(expectedMessage);
    }
  }

  @Test
  @UseClockStep
  public void createAtomically() throws Exception {
    Account.Id accountId = Account.id(seq.nextAccountId());
    String fullName = "Foo";
    ExternalId extId = getExternalIdFactory().createEmail(accountId, "foo@example.com");
    AccountState accountState =
        accountsUpdateProvider
            .get()
            .insert(
                "Create Account Atomically",
                accountId,
                u -> u.setFullName(fullName).addExternalId(extId));
    assertThat(accountState.account().fullName()).isEqualTo(fullName);

    AccountInfo info = gApi.accounts().id(accountId.get()).get();
    assertThat(info.name).isEqualTo(fullName);

    List<EmailInfo> emails = gApi.accounts().id(accountId.get()).getEmails();
    assertThat(emails.stream().map(e -> e.email).collect(toSet())).containsExactly(extId.email());

    RevCommit commitUserBranch =
        projectOperations.project(allUsers).getHead(RefNames.refsUsers(accountId));
    RevCommit commitRefsMetaExternalIds =
        projectOperations.project(allUsers).getHead(RefNames.REFS_EXTERNAL_IDS);
    assertThat(commitUserBranch.getCommitTime())
        .isEqualTo(commitRefsMetaExternalIds.getCommitTime());
  }

  @Test
  public void updateNonExistingAccount() throws Exception {
    Account.Id nonExistingAccountId = Account.id(999999);
    AtomicBoolean consumerCalled = new AtomicBoolean();
    Optional<AccountState> accountState =
        accountsUpdateProvider
            .get()
            .update(
                "Update Non-Existing Account", nonExistingAccountId, a -> consumerCalled.set(true));
    assertThat(accountState).isEmpty();
    assertThat(consumerCalled.get()).isFalse();
  }

  @Test
  public void updateAccountWithoutAccountConfigNoteDb() throws Exception {
    TestAccount anonymousCoward = accountCreator.create();
    assertUserBranchWithoutAccountConfig(anonymousCoward.id());

    String status = "OOO";
    Optional<AccountState> accountState =
        accountsUpdateProvider
            .get()
            .update("Set status", anonymousCoward.id(), u -> u.setStatus(status));
    assertThat(accountState).isPresent();
    Account account = accountState.get().account();
    assertThat(account.fullName()).isNull();
    assertThat(account.status()).isEqualTo(status);
    assertUserBranch(anonymousCoward.id(), null, status);
  }

  private void assertUserBranchWithoutAccountConfig(Account.Id accountId) throws Exception {
    assertUserBranch(accountId, null, null);
  }

  private void assertUserBranch(
      Account.Id accountId, @Nullable String name, @Nullable String status) throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(repo);
        ObjectReader or = repo.newObjectReader()) {
      Ref ref = repo.exactRef(RefNames.refsUsers(accountId));
      assertThat(ref).isNotNull();
      RevCommit c = rw.parseCommit(ref.getObjectId());
      long timestampDiffMs =
          Math.abs(c.getCommitTime() * 1000L - getAccount(accountId).registeredOn().toEpochMilli());
      assertThat(timestampDiffMs).isAtMost(SECONDS.toMillis(1));

      // Check the 'account.config' file.
      try (TreeWalk tw = TreeWalk.forPath(or, ACCOUNT_CONFIG, c.getTree())) {
        if (name != null || status != null) {
          assertThat(tw).isNotNull();
          Config cfg = new Config();
          cfg.fromText(new String(or.open(tw.getObjectId(0), OBJ_BLOB).getBytes(), UTF_8));
          assertThat(cfg)
              .stringValue(ACCOUNT, null, AccountProperties.KEY_FULL_NAME)
              .isEqualTo(name);
          assertThat(cfg)
              .stringValue(ACCOUNT, null, AccountProperties.KEY_STATUS)
              .isEqualTo(status);
        } else {
          // No account properties were set, hence an 'account.config' file was not created.
          assertThat(tw).isNull();
        }
      }
    }
  }

  @Test
  public void get() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      AccountInfo info = gApi.accounts().id(admin.id().get()).get();
      assertThat(info.name).isEqualTo("Administrator");
      assertThat(info.email).isEqualTo("admin@example.com");
      if (server.isUsernameSupported()) {
        assertThat(info.username).isEqualTo("admin");
      }
      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void getByIntId() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      assertAccountFound("admin");
      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  @GerritConfig(name = "accounts.caseInsensitiveLocalPart", value = "example.com")
  public void getByEmailIdCaseInsensitive() throws Exception {
    AccountIndexedCounter accountIndexedCounter = new AccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {

      String email = "admin@example.com";
      assertAccountFound(email);
      assertAccountFound(email.toUpperCase(Locale.US));

      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void getByEmailIdCaseSensitive() throws Exception {
    AccountIndexedCounter accountIndexedCounter = new AccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {

      String email = "admin@example.com";
      assertAccountFound(email);
      assertAccountNotFound(email.toUpperCase(Locale.US));

      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  @GerritConfig(name = "accounts.caseInsensitiveLocalPart", value = "example.com")
  public void lookUpByEmailCaseInsensitive() throws Exception {
    assertThat(emails.getAccountFor(admin.email().toUpperCase(Locale.US))).isNotEmpty();
  }

  @Test
  public void lookUpByEmailCaseSensitive() throws Exception {
    assertThat(emails.getAccountFor(admin.email().toUpperCase(Locale.US))).isEmpty();
  }

  @Test
  @GerritConfig(name = "accounts.caseInsensitiveLocalPart", value = "example.com")
  public void lookUpByEmailsCaseInsensitive() throws Exception {
    assertThat(emails.getAccountsFor(admin.email().toUpperCase(Locale.US))).isNotEmpty();
  }

  @Test
  public void lookUpByEmailsCaseSensitive() throws Exception {
    assertThat(emails.getAccountsFor(admin.email().toUpperCase(Locale.US))).isEmpty();
  }

  private void assertAccountNotFound(String mail) {
    ResourceNotFoundException thrown =
        assertThrows(ResourceNotFoundException.class, () -> gApi.accounts().id(mail));
    assertThat(thrown).hasMessageThat().isEqualTo("Account '" + mail + "' not found");
  }

  @Test
  @GerritConfig(name = "accounts.caseInsensitiveLocalPart", value = "example.com")
  public void getByNameAndEmailIdCaseInsensitive() throws Exception {
    AccountIndexedCounter accountIndexedCounter = new AccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {

      String nameAndEmail = "Admin <admin@example.com>";
      assertAccountFound(nameAndEmail);
      assertAccountFound(nameAndEmail.toUpperCase(Locale.US));

      accountIndexedCounter.assertNoReindex();
    }
  }

  @CanIgnoreReturnValue
  private AccountInfo assertAccountFound(String id) throws RestApiException {
    AccountInfo infoByEmailLowerCase = gApi.accounts().id(id).get();
    AccountInfo infoByIntId = gApi.accounts().id(infoByEmailLowerCase._accountId).get();
    assertThat(infoByEmailLowerCase.name).isEqualTo(infoByIntId.name);
    return infoByIntId;
  }

  @Test
  public void getByNameAndEmailIdCaseSensitive() throws Exception {
    AccountIndexedCounter accountIndexedCounter = new AccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {

      String nameAndEmail = "Admin <admin@example.com>";
      assertAccountFound(nameAndEmail);
      assertAccountNotFound(nameAndEmail.toUpperCase(Locale.US));

      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void self() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      AccountInfo info = gApi.accounts().self().get();
      assertUser(info, admin);

      info = gApi.accounts().self().get();
      assertUser(info, admin);
      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void active() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      int id = gApi.accounts().id(user.id().get()).get()._accountId;
      assertThat(gApi.accounts().id(user.id().get()).getActive()).isTrue();
      gApi.accounts().id(user.id().get()).setActive(false);
      accountIndexedCounter.assertReindexOf(user);

      // Inactive users may only be resolved by ID.
      ResourceNotFoundException thrown =
          assertThrows(ResourceNotFoundException.class, () -> gApi.accounts().id(user.email()));
      assertThat(thrown)
          .hasMessageThat()
          .isEqualTo(
              "Account '"
                  + user.email()
                  + "' only matches inactive accounts. To use an inactive account, retry"
                  + " with one of the following exact account IDs:\n"
                  + id
                  + ": User1 <user1@example.com>");
      assertThat(gApi.accounts().id(id).getActive()).isFalse();

      gApi.accounts().id(id).setActive(true);
      assertThat(gApi.accounts().id(user.id().get()).getActive()).isTrue();
      accountIndexedCounter.assertReindexOf(user);
    }
  }

  @Test
  public void shouldAllowQueryByEmailForInactiveUser() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      Account.Id activatableAccountId =
          accountOperations.newAccount().inactive().preferredEmail("foo@activatable.com").create();
      // Implementation of the accountOperations can create an account in several steps,
      // with more than one reindexing.
      accountIndexedCounter.assertReindexAtLeastOnceOf(activatableAccountId);
    }

    @SuppressWarnings("unused")
    var unused = gApi.changes().query("owner:foo@activatable.com").get();
  }

  @Test
  public void shouldAllowQueryByUserNameForInactiveUser() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      Account.Id activatableAccountId =
          accountOperations.newAccount().inactive().username("foo").create();
      // Implementation of the accountOperations can create an account in several steps,
      // with more than one reindexing.
      accountIndexedCounter.assertReindexAtLeastOnceOf(activatableAccountId);
    }

    @SuppressWarnings("unused")
    var unused = gApi.changes().query("owner:foo").get();
  }

  @Test
  @GerritConfig(name = "auth.type", value = "DEVELOPMENT_BECOME_ANY_ACCOUNT")
  public void activeUserGetSessionCookieOnLogin() throws Exception {
    Integer accountId = accountIdApi().get()._accountId;
    assertThat(accountIdApi().getActive()).isTrue();

    webLogin(accountId);
    assertThat(getCookiesNames()).contains(CacheBasedWebSession.ACCOUNT_COOKIE);
  }

  @Test
  @GerritConfig(name = "auth.type", value = "DEVELOPMENT_BECOME_ANY_ACCOUNT")
  public void inactiveUserDoesNotGetCookieOnLogin() throws Exception {
    Integer accountId = accountIdApi().get()._accountId;
    accountIdApi().setActive(false);
    assertThat(accountIdApi().getActive()).isFalse();

    webLogin(accountId);
    assertThat(getCookiesNames()).isEmpty();
  }

  @Test
  @GerritConfig(name = "auth.type", value = "DEVELOPMENT_BECOME_ANY_ACCOUNT")
  public void userDeactivatedAfterLoginDoesNotGetCookie() throws Exception {
    Integer accountId = accountIdApi().get()._accountId;
    assertThat(accountIdApi().getActive()).isTrue();

    webLogin(accountId);
    assertThat(getCookiesNames()).contains(CacheBasedWebSession.ACCOUNT_COOKIE);
    httpGetAndAssertStatus("accounts/self/detail", HttpServletResponse.SC_OK);

    accountIdApi().setActive(false);
    assertThat(accountIdApi().getActive()).isFalse();

    httpGetAndAssertStatus("accounts/self/detail", HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  public void validateAccountActivation() throws Exception {
    Account.Id activatableAccountId =
        accountOperations.newAccount().inactive().preferredEmail("foo@activatable.com").create();
    Account.Id deactivatableAccountId =
        accountOperations.newAccount().preferredEmail("foo@deactivatable.com").create();

    AccountActivationValidationListener validationListener =
        new AccountActivationValidationListener() {
          @Override
          public void validateActivation(AccountState account) throws ValidationException {
            String preferredEmail = account.account().preferredEmail();
            if (preferredEmail == null || !preferredEmail.endsWith("@activatable.com")) {
              throw new ValidationException("not allowed to active account");
            }
          }

          @Override
          public void validateDeactivation(AccountState account) throws ValidationException {
            String preferredEmail = account.account().preferredEmail();
            if (preferredEmail == null || !preferredEmail.endsWith("@deactivatable.com")) {
              throw new ValidationException("not allowed to deactive account");
            }
          }
        };

    AccountActivationListener listener = mock(AccountActivationListener.class);

    try (Registration registration =
        extensionRegistry.newRegistration().add(validationListener).add(listener)) {
      /* Test account that can be activated, but not deactivated */
      // Deactivate account that is already inactive
      ResourceConflictException thrown =
          assertThrows(
              ResourceConflictException.class,
              () -> gApi.accounts().id(activatableAccountId.get()).setActive(false));
      assertThat(thrown).hasMessageThat().isEqualTo("account not active");
      assertThat(accountOperations.account(activatableAccountId).get().active()).isFalse();
      verifyNoInteractions(listener);

      // Activate account that can be activated
      gApi.accounts().id(activatableAccountId.get()).setActive(true);
      assertThat(accountOperations.account(activatableAccountId).get().active()).isTrue();
      verify(listener).onAccountActivated(activatableAccountId.get());
      verifyNoMoreInteractions(listener);

      // Activate account that is already active
      gApi.accounts().id(activatableAccountId.get()).setActive(true);
      assertThat(accountOperations.account(activatableAccountId).get().active()).isTrue();
      verifyNoMoreInteractions(listener);

      // Try deactivating account that cannot be deactivated
      thrown =
          assertThrows(
              ResourceConflictException.class,
              () -> gApi.accounts().id(activatableAccountId.get()).setActive(false));
      assertThat(thrown).hasMessageThat().isEqualTo("not allowed to deactive account");
      assertThat(accountOperations.account(activatableAccountId).get().active()).isTrue();
      verifyNoMoreInteractions(listener);

      /* Test account that can be deactivated, but not activated */
      // Activate account that is already inactive
      gApi.accounts().id(deactivatableAccountId.get()).setActive(true);
      assertThat(accountOperations.account(deactivatableAccountId).get().active()).isTrue();
      verifyNoMoreInteractions(listener);

      // Deactivate account that can be deactivated
      gApi.accounts().id(deactivatableAccountId.get()).setActive(false);
      assertThat(accountOperations.account(deactivatableAccountId).get().active()).isFalse();
      verify(listener).onAccountDeactivated(deactivatableAccountId.get());
      verifyNoMoreInteractions(listener);

      // Deactivate account that is already inactive
      thrown =
          assertThrows(
              ResourceConflictException.class,
              () -> gApi.accounts().id(deactivatableAccountId.get()).setActive(false));
      assertThat(thrown).hasMessageThat().isEqualTo("account not active");
      assertThat(accountOperations.account(deactivatableAccountId).get().active()).isFalse();
      verifyNoMoreInteractions(listener);

      // Try activating account that cannot be activated
      thrown =
          assertThrows(
              ResourceConflictException.class,
              () -> gApi.accounts().id(deactivatableAccountId.get()).setActive(true));
      assertThat(thrown).hasMessageThat().isEqualTo("not allowed to active account");
      assertThat(accountOperations.account(deactivatableAccountId).get().active()).isFalse();
      verifyNoMoreInteractions(listener);
    }
  }

  @Test
  public void deactivateSelf() throws Exception {
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> gApi.accounts().self().setActive(false));
    assertThat(thrown).hasMessageThat().contains("cannot deactivate own account");
  }

  @Test
  public void deactivateNotActive() throws Exception {
    int id = gApi.accounts().id(user.id().get()).get()._accountId;
    assertThat(gApi.accounts().id(user.id().get()).getActive()).isTrue();
    gApi.accounts().id(user.id().get()).setActive(false);
    assertThat(gApi.accounts().id(id).getActive()).isFalse();
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> gApi.accounts().id(id).setActive(false));
    assertThat(thrown).hasMessageThat().isEqualTo("account not active");
    gApi.accounts().id(id).setActive(true);
  }

  @Test
  public void starUnstarChange() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    RefUpdateCounter refUpdateCounter = createRefUpdateCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter).add(refUpdateCounter)) {
      PushOneCommit.Result r = createChange();
      String triplet = project.get() + "~master~" + r.getChangeId();
      refUpdateCounter.clear();

      gApi.accounts().self().starChange(triplet);
      ChangeInfo change = info(triplet);
      assertThat(change.starred).isTrue();
      refUpdateCounter.assertRefUpdateFor(
          RefUpdateCounter.projectRef(
              allUsers, RefNames.refsStarredChanges(Change.id(change._number), admin.id())));

      gApi.accounts().self().unstarChange(triplet);
      change = info(triplet);
      assertThat(change.starred).isNull();
      refUpdateCounter.assertRefUpdateFor(
          RefUpdateCounter.projectRef(
              allUsers, RefNames.refsStarredChanges(Change.id(change._number), admin.id())));

      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void addExistingReviewersUsingPostReview() throws Exception {
    PushOneCommit.Result r = createChange();

    // First reviewer added to the change
    ReviewInput input = new ReviewInput();
    input.reviewers = new ArrayList<>(1);
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.reviewer = user.email();
    input.reviewers.add(reviewerInput);
    gApi.changes().id(r.getChangeId()).current().review(input);
    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message message = messages.get(0);
    assertThat(message.rcpt()).containsExactly(user.getNameEmail());
    assertMailReplyTo(message, admin.email());

    sender.clear();

    // Second reviewer and existing reviewer added to the change
    ReviewInput input2 = new ReviewInput();
    input2.reviewers = new ArrayList<>(2);
    ReviewerInput reviewerInput2 = new ReviewerInput();
    reviewerInput2.reviewer = user.email();
    input2.reviewers.add(reviewerInput2);
    ReviewerInput reviewerInput3 = new ReviewerInput();

    TestAccount user2 = accountCreator.user2();
    reviewerInput3.reviewer = user2.email();
    input2.reviewers.add(reviewerInput3);

    gApi.changes().id(r.getChangeId()).current().review(input2);
    ImmutableList<Message> messages2 = sender.getMessages();
    assertThat(messages2).hasSize(1);
    Message message2 = messages2.get(0);
    assertThat(message2.rcpt()).containsExactly(user2.getNameEmail());
    assertMailReplyTo(message, admin.email());

    sender.clear();

    // Existing reviewers re-added to the change: no notifications
    ReviewInput input3 = new ReviewInput();
    input3.reviewers = new ArrayList<>(2);
    ReviewerInput reviewerInput4 = new ReviewerInput();
    reviewerInput4.reviewer = user.email();
    input3.reviewers.add(reviewerInput4);
    ReviewerInput reviewerInput5 = new ReviewerInput();

    reviewerInput5.reviewer = user2.email();
    input3.reviewers.add(reviewerInput5);

    gApi.changes().id(r.getChangeId()).current().review(input3);
    ImmutableList<Message> messages3 = sender.getMessages();
    assertThat(messages3).isEmpty();
  }

  @Test
  public void addExistingReviewersUsingAddReviewer() throws Exception {
    PushOneCommit.Result r = createChange();

    // First reviewer added to the change
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput);
    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message message = messages.get(0);
    assertThat(message.rcpt()).containsExactly(user.getNameEmail());
    assertMailReplyTo(message, admin.email());

    sender.clear();

    // Second reviewer added to the change
    TestAccount user2 = accountCreator.user2();
    ReviewerInput reviewerInput2 = new ReviewerInput();
    reviewerInput2.reviewer = user2.email();
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput2);
    ImmutableList<Message> messages2 = sender.getMessages();
    assertThat(messages2).hasSize(1);
    Message message2 = messages2.get(0);
    assertThat(message2.rcpt()).containsExactly(user2.getNameEmail());
    assertMailReplyTo(message2, admin.email());

    sender.clear();

    // Exiting reviewer re-added to the change: no notifications
    ReviewerInput reviewerInput3 = new ReviewerInput();
    reviewerInput3.reviewer = user2.email();
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput3);
    ImmutableList<Message> messages3 = sender.getMessages();
    assertThat(messages3).isEmpty();
  }

  @Test
  public void suggestAccounts() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String adminUsername = "admin";
      List<AccountInfo> result = gApi.accounts().suggestAccounts().withQuery(adminUsername).get();
      assertThat(result).hasSize(1);
      if (server.isUsernameSupported()) {
        assertThat(result.get(0).username).isEqualTo(adminUsername);
      }
      assertThat(result.get(0).email).isEqualTo("admin@example.com");

      List<AccountInfo> resultShortcutApi = gApi.accounts().suggestAccounts(adminUsername).get();
      assertThat(resultShortcutApi).hasSize(result.size());

      List<AccountInfo> emptyResult = gApi.accounts().suggestAccounts("unknown").get();
      assertThat(emptyResult).isEmpty();
      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void getOwnDetail() throws Exception {
    String email = "preferred@example.com";
    String name = "Foo";
    String username = name("foo");
    TestAccount foo = accountCreator.create(username, email, name, null);
    String secondaryEmail = "secondary@example.com";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    String status = "OOO";
    gApi.accounts().id(foo.id().get()).setStatus(status);

    requestScopeOperations.setApiUser(foo.id());
    AccountDetailInfo detail = gApi.accounts().id(foo.id().get()).detail();
    assertThat(detail._accountId).isEqualTo(foo.id().get());
    assertThat(detail.name).isEqualTo(name);
    if (server.isUsernameSupported()) {
      assertThat(detail.username).isEqualTo(username);
    }
    assertThat(detail.email).isEqualTo(email);
    assertThat(detail.secondaryEmails).containsExactly(secondaryEmail);
    assertThat(detail.status).isEqualTo(status);
    assertThat(detail.registeredOn.getTime())
        .isEqualTo(getAccount(foo.id()).registeredOn().toEpochMilli());
    assertThat(detail.inactive).isNull();
    assertThat(detail._moreAccounts).isNull();
  }

  @Test
  public void detailOfOtherAccountDoesntIncludeSecondaryEmailsWithoutModifyAccount()
      throws Exception {
    String email = "preferred@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);
    String secondaryEmail = "secondary@example.com";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    requestScopeOperations.setApiUser(user.id());
    AccountDetailInfo detail = gApi.accounts().id(foo.id().get()).detail();
    assertThat(detail.secondaryEmails).isNull();
  }

  @Test
  public void detailOfOtherAccountIncludeSecondaryEmailsWithModifyAccount() throws Exception {
    String email = "preferred@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);
    String secondaryEmail = "secondary@example.com";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    AccountDetailInfo detail = gApi.accounts().id(foo.id().get()).detail();
    assertThat(detail.secondaryEmails).containsExactly(secondaryEmail);
  }

  @Test
  public void getOwnEmails() throws Exception {
    String email = "preferred@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);

    requestScopeOperations.setApiUser(foo.id());
    assertThat(getEmails()).containsExactly(email);

    requestScopeOperations.setApiUser(admin.id());
    String secondaryEmail = "secondary@example.com";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    requestScopeOperations.setApiUser(foo.id());
    assertThat(getEmails()).containsExactly(email, secondaryEmail);
  }

  @Test
  public void cannotGetEmailsOfOtherAccountWithoutViewSecondaryEmailsAndWithoutModifyAccount()
      throws Exception {
    String email = "preferred2@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.accounts().id(foo.id().get()).getEmails());
    assertThat(thrown).hasMessageThat().contains("view secondary emails not permitted");
  }

  @Test
  public void getEmailsOfOtherAccount() throws Exception {
    String email = "preferred3@example.com";
    String secondaryEmail = "secondary3@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    assertThat(
            gApi.accounts().id(foo.id().get()).getEmails().stream()
                .map(e -> e.email)
                .collect(toSet()))
        .containsExactly(email, secondaryEmail);
  }

  @Test
  public void getEmailsOfOtherAccountIncludesEmailsFromNonMailToExternalIds() throws Exception {
    String email = "preferred4@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);

    String secondaryEmail = "secondary4@example.com";
    accountsUpdateProvider
        .get()
        .update(
            "Add External ID",
            foo.id(),
            u ->
                u.addExternalId(
                    getExternalIdFactory().createWithEmail("x", "1", foo.id(), secondaryEmail)));

    assertThat(
            gApi.accounts().id(foo.id().get()).getEmails().stream()
                .map(e -> e.email)
                .collect(toSet()))
        .containsExactly(email, secondaryEmail);
  }

  @Test
  public void addEmail() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      ImmutableList<String> emails =
          ImmutableList.of("new.email@example.com", "new.email@example.systems");
      ImmutableSet<String> currentEmails = getEmails();
      for (String email : emails) {
        assertThat(currentEmails).doesNotContain(email);
        EmailInput input = newEmailInput(email);
        gApi.accounts().self().addEmail(input);
        accountIndexedCounter.assertReindexOf(admin);
      }

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getEmails()).containsAtLeastElementsIn(emails);
    }
  }

  @Test
  public void addInvalidEmail() throws Exception {
    ImmutableList<String> emails =
        ImmutableList.of(
            // Missing domain part
            "new.email",

            // Missing domain part
            "new.email@",

            // Missing user part
            "@example.com",

            // Non-supported TLD  (see tlds-alpha-by-domain.txt)
            "new.email@example.africa");
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      for (String email : emails) {
        EmailInput input = newEmailInput(email);
        BadRequestException thrown =
            assertThrows(BadRequestException.class, () -> gApi.accounts().self().addEmail(input));
        assertWithMessage(email).that(thrown).hasMessageThat().isEqualTo("invalid email address");
      }
      accountIndexedCounter.assertNoReindex();
    }
  }

  @Test
  public void cannotAddNonConfirmedEmailWithoutModifyAccountPermission() throws Exception {
    TestAccount account = accountCreator.create(name("user"));
    EmailInput input = newEmailInput("test@example.com");
    requestScopeOperations.setApiUser(user.id());
    assertThrows(AuthException.class, () -> gApi.accounts().id(account.id().get()).addEmail(input));
  }

  @Test
  public void cannotAddEmailAddressUsedByAnotherAccount() throws Exception {
    String email = "new.email@example.com";
    EmailInput input = newEmailInput(email);
    gApi.accounts().self().addEmail(input);
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.accounts().id(user.id().get()).addEmail(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Identity 'mailto:" + email + "' in use by another account");
  }

  @Test
  @GerritConfig(
      name = "auth.registerEmailPrivateKey",
      value = "HsOc6l_2lhS9G7sE_RsnS7Z6GJjdRDX14co=")
  public void addEmailSendsConfirmationEmail() throws Exception {
    String email = "new.email@example.com";
    EmailInput input = newEmailInput(email, false);
    gApi.accounts().self().addEmail(input);

    assertThat(sender.getMessages()).hasSize(1);
    Message m = sender.getMessages().get(0);
    assertThat(m.rcpt()).containsExactly(Address.create(email));
  }

  @Test
  @GerritConfig(
      name = "auth.registerEmailPrivateKey",
      value = "HsOc6l_2lhS9G7sE-RsnS7Z6GJjdRDX14co=")
  public void addEmailToBeConfirmedToOwnAccount() throws Exception {
    TestAccount user = accountCreator.create();
    requestScopeOperations.setApiUser(user.id());

    String email = "self@example.com";
    EmailInput input = newEmailInput(email, false);
    gApi.accounts().self().addEmail(input);
  }

  @Test
  public void cannotAddEmailToBeConfirmedToOtherAccountWithoutModifyAccountPermission()
      throws Exception {
    TestAccount user = accountCreator.create();
    requestScopeOperations.setApiUser(user.id());

    AuthException thrown =
        assertThrows(
            AuthException.class,
            () ->
                gApi.accounts()
                    .id(admin.id().get())
                    .addEmail(newEmailInput("foo@example.com", false)));
    assertThat(thrown).hasMessageThat().contains("modify account not permitted");
  }

  @Test
  @GerritConfig(
      name = "auth.registerEmailPrivateKey",
      value = "HsOc6l_2lhS9G7sE-RsnS7Z6GJjdRDX14co=")
  public void addEmailToBeConfirmedToOtherAccount() throws Exception {
    TestAccount user = accountCreator.create();
    String email = "me@example.com";
    gApi.accounts().id(user.id().get()).addEmail(newEmailInput(email, false));
  }

  @Test
  public void addEmailAndSetPreferred() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String email = "foo.bar@example.com";
      EmailInput input = new EmailInput();
      input.email = email;
      input.noConfirmation = true;
      input.preferred = true;
      gApi.accounts().self().addEmail(input);

      // Account is reindexed twice; once on adding the new email,
      // and then again on setting the email preferred.
      accountIndexedCounter.assertReindexOf(admin, 2);

      String preferred = gApi.accounts().self().get().email;
      assertThat(preferred).isEqualTo(email);
    }
  }

  @Test
  public void deleteEmail() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String email = "foo.bar@example.com";
      EmailInput input = newEmailInput(email);
      gApi.accounts().self().addEmail(input);

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getEmails()).contains(email);

      accountIndexedCounter.clear();
      gApi.accounts().self().deleteEmail(input.email);
      accountIndexedCounter.assertReindexOf(admin);

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getEmails()).doesNotContain(email);
    }
  }

  @Test
  public void deletePreferredEmail() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      ImmutableSet<String> previous = getEmails();
      String email = "foo.bar.baz@example.com";
      EmailInput input = new EmailInput();
      input.email = email;
      input.noConfirmation = true;
      input.preferred = true;
      gApi.accounts().self().addEmail(input);

      // Account is reindexed twice; once on adding the new email,
      // and then again on setting the email preferred.
      accountIndexedCounter.assertReindexOf(admin, 2);

      // The new preferred email is set
      assertThat(gApi.accounts().self().get().email).isEqualTo(email);

      accountIndexedCounter.clear();
      gApi.accounts().self().deleteEmail(input.email);
      accountIndexedCounter.assertReindexOf(admin);

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getEmails()).isEqualTo(previous);
      assertThat(gApi.accounts().self().get().email).isNull();
    }
  }

  @Test
  @Sandboxed
  public void deleteAllEmails() throws Exception {
    EmailInput input = new EmailInput();
    input.email = "foo.bar@example.com";
    input.noConfirmation = true;
    gApi.accounts().self().addEmail(input);

    requestScopeOperations.resetCurrentApiUser();
    ImmutableSet<String> allEmails = getEmails();
    assertThat(allEmails).hasSize(2);

    for (String email : allEmails) {
      gApi.accounts().self().deleteEmail(email);
    }

    requestScopeOperations.resetCurrentApiUser();
    assertThat(getEmails()).isEmpty();
    assertThat(gApi.accounts().self().get().email).isNull();
  }

  @Test
  public void deleteEmailFromCustomExternalIdSchemes() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String email = "foo.bar@example.com";
      String extId1 = "foo:bar";
      String extId2 = "foo:baz";
      accountsUpdateProvider
          .get()
          .update(
              "Add External IDs",
              admin.id(),
              u ->
                  u.addExternalId(
                          getExternalIdFactory()
                              .createWithEmail(
                                  externalIdKeyFactory.parse(extId1), admin.id(), email))
                      .addExternalId(
                          getExternalIdFactory()
                              .createWithEmail(
                                  externalIdKeyFactory.parse(extId2), admin.id(), email)));
      accountIndexedCounter.assertReindexOf(admin);
      assertThat(
              gApi.accounts().self().getExternalIds().stream()
                  .map(e -> e.identity)
                  .collect(toSet()))
          .containsAtLeast(extId1, extId2);

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getExtIdsEmail()).contains(email);

      gApi.accounts().self().deleteEmail(email);
      accountIndexedCounter.assertReindexOf(admin);

      requestScopeOperations.resetCurrentApiUser();
      assertThat(getExtIdsEmail()).doesNotContain(email);
      assertThat(
              gApi.accounts().self().getExternalIds().stream()
                  .map(e -> e.identity)
                  .collect(toSet()))
          .containsNoneOf(extId1, extId2);
    }
  }

  @Test
  @GerritConfig(name = "auth.type", value = "LDAP")
  public void deleteEmailShouldNotRemoveLdapExternalIdScheme() throws Exception {
    String ldapEmail = "foo@company.com";
    String ldapExternalId = ExternalId.SCHEME_GERRIT + ":foo";
    accountsUpdateProvider
        .get()
        .update(
            "Add External IDs",
            admin.id(),
            u ->
                u.addExternalId(
                    getExternalIdFactory()
                        .createWithEmail(
                            externalIdKeyFactory.parse(ldapExternalId), admin.id(), ldapEmail)));
    assertThat(
            gApi.accounts().self().getExternalIds().stream()
                .map(e -> e.identity)
                .collect(toImmutableSet()))
        .contains(ldapExternalId);

    assertThat(getExtIdsEmail()).contains(ldapEmail);

    requestScopeOperations.resetCurrentApiUser();
    assertThat(getEmails()).contains(ldapEmail);

    ResourceConflictException exception =
        assertThrows(
            ResourceConflictException.class, () -> gApi.accounts().self().deleteEmail(ldapEmail));
    assertThat(exception).hasMessageThat().contains(ldapEmail);

    requestScopeOperations.resetCurrentApiUser();
    assertThat(getEmails()).contains(ldapEmail);
    assertThat(
            gApi.accounts().self().getExternalIds().stream().map(e -> e.identity).collect(toSet()))
        .contains(ldapExternalId);
  }

  @Test
  @GerritConfig(name = "auth.type", value = "LDAP")
  public void deleteEmailShouldRemoveNonLdapExternalIdScheme() throws Exception {
    String ldapEmail = "foo@company.com";
    String ldapExternalId = ExternalId.SCHEME_GERRIT + ":foo";
    String nonLdapEMail = "foo@example.com";
    String nonLdapExternalId = ExternalId.SCHEME_MAILTO + ":foo@example.com";
    accountsUpdateProvider
        .get()
        .update(
            "Add External IDs",
            admin.id(),
            u ->
                u.addExternalId(
                        getExternalIdFactory()
                            .createWithEmail(
                                externalIdKeyFactory.parse(nonLdapExternalId),
                                admin.id(),
                                nonLdapEMail))
                    .addExternalId(
                        getExternalIdFactory()
                            .createWithEmail(
                                externalIdKeyFactory.parse(ldapExternalId),
                                admin.id(),
                                ldapEmail)));
    assertThat(
            gApi.accounts().self().getExternalIds().stream().map(e -> e.identity).collect(toSet()))
        .containsAtLeast(ldapExternalId, nonLdapExternalId);

    requestScopeOperations.resetCurrentApiUser();
    assertThat(getExtIdsEmail()).containsAtLeast(ldapEmail, nonLdapEMail);

    gApi.accounts().self().deleteEmail(nonLdapEMail);

    requestScopeOperations.resetCurrentApiUser();
    assertThat(getExtIdsEmail()).doesNotContain(nonLdapEMail);
    assertThat(
            gApi.accounts().self().getExternalIds().stream().map(e -> e.identity).collect(toSet()))
        .contains(ldapExternalId);
  }

  @Test
  public void deleteEmailOfOtherUser() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      String email = "foo.bar@example.com";
      EmailInput input = new EmailInput();
      input.email = email;
      input.noConfirmation = true;
      gApi.accounts().id(user.id().get()).addEmail(input);
      accountIndexedCounter.assertReindexOf(user);

      requestScopeOperations.setApiUser(user.id());
      assertThat(getEmails()).contains(email);

      // admin can delete email of user
      requestScopeOperations.setApiUser(admin.id());
      gApi.accounts().id(user.id().get()).deleteEmail(email);
      accountIndexedCounter.assertReindexOf(user);

      requestScopeOperations.setApiUser(user.id());
      assertThat(getEmails()).doesNotContain(email);

      // user cannot delete email of admin
      AuthException thrown =
          assertThrows(
              AuthException.class,
              () -> gApi.accounts().id(admin.id().get()).deleteEmail(admin.email()));
      assertThat(thrown).hasMessageThat().contains("modify account not permitted");
    }
  }

  @Test
  public void lookUpByEmail() throws Exception {
    // exact match with scheme "mailto:"
    assertEmail(emails.getAccountFor(admin.email()), admin);

    // exact match with other scheme
    String email = "foo.bar@example.com";
    accountsUpdateProvider
        .get()
        .update(
            "Add Email",
            admin.id(),
            u ->
                u.addExternalId(
                    getExternalIdFactory()
                        .createWithEmail(
                            externalIdKeyFactory.parse("foo:bar"), admin.id(), email)));
    assertEmail(emails.getAccountFor(email), admin);

    // wrong case doesn't match
    assertThat(emails.getAccountFor(admin.email().toUpperCase(Locale.US))).isEmpty();

    // prefix doesn't match
    assertThat(emails.getAccountFor(admin.email().substring(0, admin.email().indexOf('@'))))
        .isEmpty();

    // non-existing doesn't match
    assertThat(emails.getAccountFor("non-existing@example.com")).isEmpty();

    // lookup several accounts by email at once
    ImmutableSetMultimap<String, Account.Id> byEmails =
        emails.getAccountsFor(admin.email(), user.email());
    assertEmail(byEmails.get(admin.email()), admin);
    assertEmail(byEmails.get(user.email()), user);
  }

  @Test
  public void lookUpByPreferredEmail() throws Exception {
    // create an inconsistent account that has a preferred email without external ID
    String prefix = "foo.preferred";
    String prefEmail = prefix + "@example.com";
    TestAccount foo = accountCreator.create(name("foo"));
    accountsUpdateProvider
        .get()
        .update("Set Preferred Email", foo.id(), u -> u.setPreferredEmail(prefEmail));

    // verify that the account is still found when using the preferred email to lookup the account
    ImmutableSet<Account.Id> accountsByPrefEmail = emails.getAccountFor(prefEmail);
    assertThat(accountsByPrefEmail).hasSize(1);
    assertThat(Iterables.getOnlyElement(accountsByPrefEmail)).isEqualTo(foo.id());

    // look up by email prefix doesn't find the account
    accountsByPrefEmail = emails.getAccountFor(prefix);
    assertThat(accountsByPrefEmail).isEmpty();

    // look up by other case doesn't find the account
    accountsByPrefEmail = emails.getAccountFor(prefEmail.toUpperCase(Locale.US));
    assertThat(accountsByPrefEmail).isEmpty();
  }

  @Test
  public void putStatus() throws Exception {
    ImmutableList<String> statuses = ImmutableList.of("OOO", "Busy");
    AccountInfo info;
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      for (String status : statuses) {
        gApi.accounts().self().setStatus(status);
        info = gApi.accounts().self().get();
        assertUser(info, admin, status);
        accountIndexedCounter.assertReindexOf(admin);
      }

      gApi.accounts().self().setStatus(null);
      info = gApi.accounts().self().get();
      assertUser(info, admin);
      accountIndexedCounter.assertReindexOf(admin);
    }
  }

  @Test
  public void setName() throws Exception {
    gApi.accounts().self().setName("Admin McAdminface");
    assertThat(gApi.accounts().self().get().name).isEqualTo("Admin McAdminface");
  }

  @Test
  public void adminCanSetNameOfOtherUser() throws Exception {
    gApi.accounts().id(user.id().get()).setName("User McUserface");
    assertThat(gApi.accounts().id(user.id().get()).get().name).isEqualTo("User McUserface");
  }

  @Test
  public void userCannotSetNameOfOtherUser() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        AuthException.class,
        () -> gApi.accounts().id(admin.id().get()).setName("Admin McAdminface"));
  }

  @Test
  @Sandboxed
  public void userCanSetNameOfOtherUserWithModifyAccountPermission() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(allowCapability(GlobalCapability.MODIFY_ACCOUNT).group(REGISTERED_USERS))
        .update();
    gApi.accounts().id(admin.id().get()).setName("Admin McAdminface");
    assertThat(gApi.accounts().id(admin.id().get()).get().name).isEqualTo("Admin McAdminface");
  }

  @Test
  public void fetchUserBranch() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      requestScopeOperations.setApiUser(user.id());

      TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers, user);
      String userRefName = RefNames.refsUsers(user.id());

      // remove default READ permissions
      try (ProjectConfigUpdate u = updateProject(allUsers)) {
        u.getConfig()
            .upsertAccessSection(
                RefNames.REFS_USERS + "${" + RefPattern.USERID_SHARDED + "}",
                as -> {
                  as.remove(Permission.builder(Permission.READ));
                });
        u.save();
      }

      // deny READ permission that is inherited from All-Projects
      projectOperations
          .project(allUsers)
          .forUpdate()
          .add(deny(Permission.READ).ref(RefNames.REFS + "*").group(ANONYMOUS_USERS))
          .update();

      // fetching user branch without READ permission fails
      assertThrows(TransportException.class, () -> fetch(allUsersRepo, userRefName + ":userRef"));

      // allow each user to read its own user branch
      projectOperations
          .project(allUsers)
          .forUpdate()
          .add(
              allow(Permission.READ)
                  .ref(RefNames.REFS_USERS + "${" + RefPattern.USERID_SHARDED + "}")
                  .group(REGISTERED_USERS))
          .update();

      // fetch user branch using refs/users/YY/XXXXXXX
      fetch(allUsersRepo, userRefName + ":userRef");
      Ref userRef = allUsersRepo.getRepository().exactRef("userRef");
      assertThat(userRef).isNotNull();

      // fetch user branch using refs/users/self
      fetch(allUsersRepo, RefNames.REFS_USERS_SELF + ":userSelfRef");
      Ref userSelfRef = allUsersRepo.getRepository().getRefDatabase().exactRef("userSelfRef");
      assertThat(userSelfRef).isNotNull();
      assertThat(userSelfRef.getObjectId()).isEqualTo(userRef.getObjectId());

      accountIndexedCounter.assertNoReindex();

      // fetching user branch of another user fails
      String otherUserRefName = RefNames.refsUsers(admin.id());
      TransportException thrown =
          assertThrows(
              TransportException.class,
              () -> fetch(allUsersRepo, otherUserRefName + ":otherUserRef"));
      assertThat(thrown)
          .hasMessageThat()
          .contains("Remote does not have " + otherUserRefName + " available for fetch.");
    }
  }

  @Test
  public void refsUsersSelfIsAdvertised() throws Exception {
    TestRepository<?> testRepository = cloneProject(allUsers, user);
    try (Git git = testRepository.git()) {
      List<String> advertisedRefs =
          git.lsRemote().call().stream().map(Ref::getName).collect(toList());
      assertThat(advertisedRefs).contains(RefNames.REFS_USERS_SELF);
    }
  }

  @Test
  public void createDefaultUserBranch() throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.exactRef(RefNames.REFS_USERS_DEFAULT)).isNull();
    }

    projectOperations
        .project(allUsers)
        .forUpdate()
        .add(allow(Permission.CREATE).ref(RefNames.REFS_USERS_DEFAULT).group(adminGroupUuid()))
        .add(allow(Permission.PUSH).ref(RefNames.REFS_USERS_DEFAULT).group(adminGroupUuid()))
        .update();

    TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers);
    pushFactory
        .create(admin.newIdent(), allUsersRepo)
        .to(RefNames.REFS_USERS_DEFAULT)
        .assertOkStatus();

    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.exactRef(RefNames.REFS_USERS_DEFAULT)).isNotNull();
    }
  }

  @Test
  public void cannotDeleteUserBranch() throws Exception {
    projectOperations
        .project(allUsers)
        .forUpdate()
        .add(
            allow(Permission.DELETE)
                .ref(RefNames.REFS_USERS + "${" + RefPattern.USERID_SHARDED + "}")
                .group(REGISTERED_USERS)
                .force(true))
        .update();

    TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers);
    String userRef = RefNames.refsUsers(admin.id());
    PushResult r = deleteRef(allUsersRepo, userRef);
    RemoteRefUpdate refUpdate = r.getRemoteUpdate(userRef);
    assertThat(refUpdate.getStatus()).isEqualTo(RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
    assertThat(refUpdate.getMessage()).contains("Not allowed to delete user branch.");

    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.exactRef(userRef)).isNotNull();
    }
  }

  @Test
  public void deleteUserBranchWithAccessDatabaseCapability() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(allowCapability(GlobalCapability.ACCESS_DATABASE).group(REGISTERED_USERS))
        .update();
    projectOperations
        .project(allUsers)
        .forUpdate()
        .add(
            allow(Permission.DELETE)
                .ref(RefNames.REFS_USERS + "${" + RefPattern.USERID_SHARDED + "}")
                .group(REGISTERED_USERS)
                .force(true))
        .update();

    TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers);
    String userRef = RefNames.refsUsers(admin.id());
    PushResult r = deleteRef(allUsersRepo, userRef);
    RemoteRefUpdate refUpdate = r.getRemoteUpdate(userRef);
    assertThat(refUpdate.getStatus()).isEqualTo(RemoteRefUpdate.Status.OK);

    try (Repository repo = repoManager.openRepository(allUsers)) {
      assertThat(repo.exactRef(userRef)).isNull();
    }

    assertThat(accountCache.get(admin.id())).isEmpty();
    assertThat(accountQueryProvider.get().byDefault(admin.id().toString(), true)).isEmpty();
  }

  @Test
  public void addGpgKey() throws Exception {
    TestKey key = validKeyWithoutExpiration();
    String id = key.getKeyIdString();
    addExternalIdEmail(admin, "test1@example.com");

    sender.clear();
    assertKeyMapContains(key, addGpgKey(key.getPublicKeyArmored()));
    assertKeys(key);
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body()).contains("new GPG keys have been added");

    requestScopeOperations.setApiUser(user.id());
    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class, () -> gApi.accounts().self().gpgKey(id).get());
    assertThat(thrown).hasMessageThat().contains(id);
  }

  @Test
  public void adminCannotAddGpgKeyToOtherAccount() throws Exception {
    TestKey key = validKeyWithoutExpiration();
    addExternalIdEmail(user, "test1@example.com");

    sender.clear();
    requestScopeOperations.setApiUser(admin.id());
    assertThrows(ResourceNotFoundException.class, () -> addGpgKey(user, key.getPublicKeyArmored()));
  }

  @Test
  public void reAddExistingGpgKey() throws Exception {
    addExternalIdEmail(admin, "test5@example.com");
    TestKey key = validKeyWithSecondUserId();
    String id = key.getKeyIdString();
    PGPPublicKey pk = key.getPublicKey();

    sender.clear();
    GpgKeyInfo info = addGpgKey(armor(pk)).get(id);
    assertThat(info.userIds).hasSize(2);
    assertIteratorSize(2, getOnlyKeyFromStore(key).getUserIDs());
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body()).contains("new GPG keys have been added");

    pk = PGPPublicKey.removeCertification(pk, "foo:myId");
    sender.clear();
    info = addGpgKeyNoReindex(armor(pk)).get(id);
    assertThat(info.userIds).hasSize(1);
    assertIteratorSize(1, getOnlyKeyFromStore(key).getUserIDs());
    // TODO: Issue 10769: Adding an already existing key should not result in a notification email
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body()).contains("new GPG keys have been added");
  }

  @Test
  public void addOtherUsersGpgKey_Conflict() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      // Both users have a matching external ID for this key.
      addExternalIdEmail(admin, "test5@example.com");
      accountIndexedCounter.clear();
      accountsUpdateProvider
          .get()
          .update(
              "Add External ID",
              user.id(),
              u -> u.addExternalId(getExternalIdFactory().create("foo", "myId", user.id())));
      accountIndexedCounter.assertReindexOf(user);

      TestKey key = validKeyWithSecondUserId();
      addGpgKey(key.getPublicKeyArmored());
      requestScopeOperations.setApiUser(user.id());

      ResourceConflictException thrown =
          assertThrows(
              ResourceConflictException.class, () -> addGpgKey(user, key.getPublicKeyArmored()));
      assertThat(thrown)
          .hasMessageThat()
          .contains("GPG key already associated with another account");
    }
  }

  @Test
  public void listGpgKeys() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      ImmutableList<TestKey> keys = allValidKeys();
      List<String> toAdd = new ArrayList<>(keys.size());
      for (TestKey key : keys) {
        addExternalIdEmail(
            admin, PushCertificateIdent.parse(key.getFirstUserId()).getEmailAddress());
        toAdd.add(key.getPublicKeyArmored());
      }
      accountIndexedCounter.clear();
      gApi.accounts().self().putGpgKeys(toAdd, ImmutableList.of());
      assertKeys(keys);
      accountIndexedCounter.assertReindexOf(admin);
    }
  }

  @Test
  public void deleteGpgKey() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      TestKey key = validKeyWithoutExpiration();
      String id = key.getKeyIdString();
      addExternalIdEmail(admin, "test1@example.com");
      addGpgKey(key.getPublicKeyArmored());
      assertKeys(key);
      accountIndexedCounter.clear();

      sender.clear();
      gApi.accounts().self().gpgKey(id).delete();
      accountIndexedCounter.assertReindexOf(admin);
      assertKeys();
      assertThat(sender.getMessages()).hasSize(1);
      assertThat(sender.getMessages().get(0).body()).contains("GPG keys have been deleted");

      ResourceNotFoundException thrown =
          assertThrows(
              ResourceNotFoundException.class, () -> gApi.accounts().self().gpgKey(id).get());
      assertThat(thrown).hasMessageThat().contains(id);
    }
  }

  @Test
  public void addAndRemoveGpgKeys() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      for (TestKey key : allValidKeys()) {
        addExternalIdEmail(
            admin, PushCertificateIdent.parse(key.getFirstUserId()).getEmailAddress());
      }
      accountIndexedCounter.clear();
      TestKey key1 = validKeyWithoutExpiration();
      TestKey key2 = validKeyWithExpiration();
      TestKey key5 = validKeyWithSecondUserId();

      Map<String, GpgKeyInfo> infos =
          gApi.accounts()
              .self()
              .putGpgKeys(
                  ImmutableList.of(key1.getPublicKeyArmored(), key2.getPublicKeyArmored()),
                  ImmutableList.of(key5.getKeyIdString()));
      assertThat(infos.keySet()).containsExactly(key1.getKeyIdString(), key2.getKeyIdString());
      assertKeys(key1, key2);
      accountIndexedCounter.assertReindexOf(admin);

      infos =
          gApi.accounts()
              .self()
              .putGpgKeys(
                  ImmutableList.of(key5.getPublicKeyArmored()),
                  ImmutableList.of(key1.getKeyIdString()));
      assertThat(infos.keySet()).containsExactly(key1.getKeyIdString(), key5.getKeyIdString());
      assertKeyMapContains(key5, infos);
      assertThat(infos.get(key1.getKeyIdString()).key).isNull();
      assertKeys(key2, key5);
      accountIndexedCounter.assertReindexOf(admin);

      BadRequestException thrown =
          assertThrows(
              BadRequestException.class,
              () ->
                  gApi.accounts()
                      .self()
                      .putGpgKeys(
                          ImmutableList.of(key2.getPublicKeyArmored()),
                          ImmutableList.of(key2.getKeyIdString())));
      assertThat(thrown)
          .hasMessageThat()
          .contains("Cannot both add and delete key: " + keyToString(key2.getPublicKey()));
    }
  }

  @Test
  public void addMalformedGpgKey() throws Exception {
    String key = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\ntest\n-----END PGP PUBLIC KEY BLOCK-----";
    BadRequestException thrown = assertThrows(BadRequestException.class, () -> addGpgKey(key));
    assertThat(thrown).hasMessageThat().contains("Failed to parse GPG keys");
  }

  @Test
  @UseSsh
  public void sshKeys() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      // The test account should initially have exactly one ssh key
      List<SshKeyInfo> info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(1);
      assertSequenceNumbers(info);
      SshKeyInfo key = info.get(0);
      KeyPair keyPair = sshKeys.getKeyPair(admin);
      String initial = TestSshKeys.publicKey(keyPair, admin.email());
      assertThat(key.sshPublicKey).isEqualTo(initial);
      accountIndexedCounter.assertNoReindex();

      // Add a new key
      sender.clear();
      String newKey = TestSshKeys.publicKey(SshSessionFactory.genSshKey(), admin.email());
      gApi.accounts().self().addSshKey(newKey);
      info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(2);
      assertSequenceNumbers(info);
      accountIndexedCounter.assertReindexOf(admin);
      assertThat(sender.getMessages()).hasSize(1);
      assertThat(sender.getMessages().get(0).body()).contains("new SSH keys have been added");

      // Add an existing key (the request succeeds, but the key isn't added again)
      sender.clear();
      gApi.accounts().self().addSshKey(initial);
      info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(2);
      assertSequenceNumbers(info);
      accountIndexedCounter.assertNoReindex();
      // TODO: Issue 10769: Adding an already existing key should not result in a notification email
      assertThat(sender.getMessages()).hasSize(1);
      assertThat(sender.getMessages().get(0).body()).contains("new SSH keys have been added");

      // Add another new key
      sender.clear();
      String newKey2 = TestSshKeys.publicKey(SshSessionFactory.genSshKey(), admin.email());
      gApi.accounts().self().addSshKey(newKey2);
      info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(3);
      assertSequenceNumbers(info);
      accountIndexedCounter.assertReindexOf(admin);
      assertThat(sender.getMessages()).hasSize(1);
      assertThat(sender.getMessages().get(0).body()).contains("new SSH keys have been added");

      // Delete second key
      sender.clear();
      gApi.accounts().self().deleteSshKey(2);
      info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(2);
      assertThat(info.get(0).seq).isEqualTo(1);
      assertThat(info.get(1).seq).isEqualTo(3);
      accountIndexedCounter.assertReindexOf(admin);

      assertThat(sender.getMessages()).hasSize(1);
      assertThat(sender.getMessages().get(0).body()).contains("SSH keys have been deleted");

      // Mark first key as invalid
      assertThat(info.get(0).valid).isTrue();
      testRefAction(() -> authorizedKeys.markKeyInvalid(admin.id(), 1));
      info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(2);
      assertThat(info.get(0).seq).isEqualTo(1);
      assertThat(info.get(0).valid).isFalse();
      assertThat(info.get(1).seq).isEqualTo(3);
      accountIndexedCounter.assertReindexOf(admin);
    }
  }

  @Test
  @UseSsh
  public void adminCanAddOrRemoveSshKeyOnOtherAccount() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      // The test account should initially have exactly one ssh key
      List<SshKeyInfo> info = gApi.accounts().self().listSshKeys();
      assertThat(info).hasSize(1);
      assertSequenceNumbers(info);
      SshKeyInfo key = info.get(0);
      KeyPair keyPair = sshKeys.getKeyPair(admin);
      String initial = TestSshKeys.publicKey(keyPair, admin.email());
      assertThat(key.sshPublicKey).isEqualTo(initial);
      accountIndexedCounter.assertNoReindex();

      // Add a new key
      sender.clear();
      String newKey = TestSshKeys.publicKey(SshSessionFactory.genSshKey(), user.email());
      gApi.accounts().id(user.id().get()).addSshKey(newKey);
      info = gApi.accounts().id(user.id().get()).listSshKeys();
      assertThat(info).hasSize(2);
      assertSequenceNumbers(info);
      accountIndexedCounter.assertReindexOf(user);

      assertThat(sender.getMessages()).hasSize(1);
      Message message = sender.getMessages().get(0);
      assertThat(message.rcpt()).containsExactly(user.getNameEmail());
      assertThat(message.body()).contains("new SSH keys have been added");

      // Delete key
      sender.clear();
      gApi.accounts().id(user.id().get()).deleteSshKey(1);
      info = gApi.accounts().id(user.id().get()).listSshKeys();
      assertThat(info).hasSize(1);
      accountIndexedCounter.assertReindexOf(user);

      assertThat(sender.getMessages()).hasSize(1);
      message = sender.getMessages().get(0);
      assertThat(message.rcpt()).containsExactly(user.getNameEmail());
      assertThat(message.body()).contains("SSH keys have been deleted");
    }
  }

  @Test
  @UseSsh
  public void userCannotAddSshKeyToOtherAccount() throws Exception {
    String newKey = TestSshKeys.publicKey(SshSessionFactory.genSshKey(), admin.email());
    requestScopeOperations.setApiUser(user.id());
    assertThrows(AuthException.class, () -> gApi.accounts().id(admin.id().get()).addSshKey(newKey));
  }

  @Test
  @UseSsh
  public void userCannotDeleteSshKeyOfOtherAccount() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        ResourceNotFoundException.class,
        () -> gApi.accounts().id(admin.id().get()).deleteSshKey(0));
  }

  // reindex is tested by {@link AbstractQueryAccountsTest#reindex}
  @Test
  public void reindexPermissions() throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      // admin can reindex any account
      requestScopeOperations.setApiUser(admin.id());
      gApi.accounts().id(user.id().get()).index();
      accountIndexedCounter.assertReindexOf(user);

      // user can reindex own account
      requestScopeOperations.setApiUser(user.id());
      gApi.accounts().self().index();
      accountIndexedCounter.assertReindexOf(user);

      // user cannot reindex any account
      AuthException thrown =
          assertThrows(AuthException.class, () -> gApi.accounts().id(admin.id().get()).index());
      assertThat(thrown).hasMessageThat().contains("modify account not permitted");
    }
  }

  @Test
  public void checkConsistency() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(allowCapability(GlobalCapability.ACCESS_DATABASE).group(REGISTERED_USERS))
        .update();
    requestScopeOperations.resetCurrentApiUser();

    // Create an account with a preferred email.
    String username = name("foo");
    String email = username + "@example.com";
    TestAccount account = accountCreator.create(username, email, "Foo Bar", null);

    ConsistencyCheckInput input = new ConsistencyCheckInput();
    input.checkAccounts = new CheckAccountsInput();
    ConsistencyCheckInfo checkInfo = gApi.config().server().checkConsistency(input);
    assertThat(checkInfo.checkAccountsResult.problems).isEmpty();

    Set<ConsistencyProblemInfo> expectedProblems = new HashSet<>();

    // Delete the external ID for the preferred email. This makes the account inconsistent since it
    // now doesn't have an external ID for its preferred email.
    accountsUpdateProvider
        .get()
        .update(
            "Delete External ID",
            account.id(),
            u -> u.deleteExternalId(getExternalIdFactory().createEmail(account.id(), email)));
    expectedProblems.add(
        new ConsistencyProblemInfo(
            ConsistencyProblemInfo.Status.ERROR,
            "Account '"
                + account.id().get()
                + "' has no external ID for its preferred email '"
                + email
                + "'"));

    checkInfo = gApi.config().server().checkConsistency(input);
    assertThat(checkInfo.checkAccountsResult.problems).hasSize(expectedProblems.size());
    assertThat(checkInfo.checkAccountsResult.problems).containsExactlyElementsIn(expectedProblems);
  }

  @Test
  public void internalQueryFindActiveAndInactiveAccounts() throws Exception {
    String name = name("foo");
    assertThat(accountQueryProvider.get().byDefault(name, true)).isEmpty();

    TestAccount foo1 = accountCreator.create(name + "-1");
    assertThat(gApi.accounts().id(foo1.id().get()).getActive()).isTrue();

    TestAccount foo2 = accountCreator.create(name + "-2");
    gApi.accounts().id(foo2.id().get()).setActive(false);
    assertThat(gApi.accounts().id(foo2.id().get()).getActive()).isFalse();

    assertThat(accountQueryProvider.get().byDefault(name, true)).hasSize(2);
  }

  @Test
  public void checkMetaIdAndUniqueTag() throws Exception {
    // In open-source Gerrit, the uniqueTag and metaId are always the same. Check them together
    // in this test.
    // metaId and uniqueTag are set when account is loaded
    assertThat(accounts.get(admin.id()).get().account().metaId()).isEqualTo(getMetaId(admin.id()));
    assertThat(accounts.get(admin.id()).get().account().uniqueTag())
        .isEqualTo(getMetaId(admin.id()));

    // metaId and uniqueTag are set when account is created
    AccountsUpdate au = accountsUpdateProvider.get();
    Account.Id accountId = Account.id(seq.nextAccountId());
    AccountState accountState = au.insert("Create Test Account", accountId, u -> {});
    assertThat(accountState.account().metaId()).isEqualTo(getMetaId(accountId));
    assertThat(accountState.account().uniqueTag()).isEqualTo(getMetaId(accountId));

    // metaId and uniqueTag are set when account is updated
    Optional<AccountState> updatedAccountState =
        au.update("Set Full Name", accountId, u -> u.setFullName("foo"));
    assertThat(updatedAccountState).isPresent();
    Account updatedAccount = updatedAccountState.get().account();
    assertThat(accountState.account().metaId()).isNotEqualTo(updatedAccount.metaId());
    assertThat(accountState.account().uniqueTag()).isNotEqualTo(updatedAccount.uniqueTag());
    assertThat(updatedAccount.metaId()).isEqualTo(getMetaId(accountId));
    assertThat(updatedAccount.uniqueTag()).isEqualTo(getMetaId(accountId));
  }

  private EmailInput newEmailInput(String email, boolean noConfirmation) {
    EmailInput input = new EmailInput();
    input.email = email;
    input.noConfirmation = noConfirmation;
    return input;
  }

  protected EmailInput newEmailInput(String email) {
    return newEmailInput(email, true);
  }

  @Nullable
  private String getMetaId(Account.Id accountId) throws IOException {
    try (Repository repo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(repo);
        ObjectReader or = repo.newObjectReader()) {
      Ref ref = repo.exactRef(RefNames.refsUsers(accountId));
      return ref != null ? ref.getObjectId().name() : null;
    }
  }

  @Test
  public void allGroupsForAnAdminAccountCanBeRetrieved() throws Exception {
    List<GroupInfo> groups = gApi.accounts().id(admin.id().get()).getGroups();
    assertThat(groups)
        .comparingElementsUsing(getGroupToNameCorrespondence())
        .containsAtLeast("Anonymous Users", "Registered Users", "Administrators");
  }

  @Test
  public void createUserWithValidUsername() throws Exception {
    ImmutableList<String> names =
        ImmutableList.of(
            "user@domain",
            "user-name",
            "user_name",
            "1234",
            "user1234",
            "1234@domain",
            "user!+alias{*}#$%&’^=~|@domain");
    for (String name : names) {
      gApi.accounts().create(name);
    }
  }

  @Test
  public void createUserWithInvalidUsername() throws Exception {
    ImmutableList<String> invalidNames =
        ImmutableList.of(
            "@", "@foo", "-", "-foo", "_", "_foo", "!", "+", "{", "}", "*", "%", "#", "$", "&", "’",
            "^", "=", "~");
    for (String name : invalidNames) {
      BadRequestException thrown =
          assertThrows(BadRequestException.class, () -> gApi.accounts().create(name));
      assertThat(thrown).hasMessageThat().isEqualTo(String.format("Invalid username '%s'", name));
    }
  }

  @Test
  public void allGroupsForAUserAccountCanBeRetrieved() throws Exception {
    String username = name("user1");
    Account.Id id = accountOperations.newAccount().username(username).create();
    AccountGroup.UUID groupID = groupOperations.newGroup().name("group").create();
    String group = groupOperations.group(groupID).get().name();

    gApi.groups().id(group).addMembers(username);

    List<GroupInfo> allGroups = gApi.accounts().id(id.get()).getGroups();
    assertThat(allGroups)
        .comparingElementsUsing(getGroupToNameCorrespondence())
        .containsExactly("Anonymous Users", "Registered Users", group);
  }

  @Test
  public void defaultPermissionsOnUserBranches() throws Exception {
    String userRef = RefNames.REFS_USERS + "${" + RefPattern.USERID_SHARDED + "}";
    assertPermissions(
        allUsers,
        groupRef(REGISTERED_USERS),
        userRef,
        true,
        Permission.READ,
        Permission.PUSH,
        Permission.SUBMIT);

    assertLabelPermission(
        allUsers, groupRef(REGISTERED_USERS), userRef, true, "Code-Review", -2, 2);
  }

  @Test
  public void defaultPermissionsOnUserDefaultBranches() throws Exception {
    assertPermissions(
        allUsers,
        adminGroupRef(),
        RefNames.REFS_USERS_DEFAULT,
        true,
        Permission.READ,
        Permission.PUSH,
        Permission.CREATE);
  }

  @Test
  public void retryOnLockFailure() throws Exception {
    String status = "happy";
    String fullName = "Foo";
    AtomicBoolean doneBgUpdate = new AtomicBoolean(false);
    AccountsUpdate update =
        getAccountsUpdateWithRunnables(
            () -> {
              if (!doneBgUpdate.getAndSet(true)) {
                try {
                  accountsUpdateProvider
                      .get()
                      .update("Set Status", admin.id(), u -> u.setStatus(status));
                } catch (IOException | ConfigInvalidException | StorageException e) {
                  // Ignore, the successful update of the account is asserted later
                }
              }
            },
            Runnables.doNothing());
    assertThat(doneBgUpdate.get()).isFalse();
    AccountInfo accountInfo = gApi.accounts().id(admin.id().get()).get();
    assertThat(accountInfo.status).isNull();
    assertThat(accountInfo.name).isNotEqualTo(fullName);

    Optional<AccountState> updatedAccountState =
        update.update("Set Full Name", admin.id(), u -> u.setFullName(fullName));
    assertThat(doneBgUpdate.get()).isTrue();

    assertThat(updatedAccountState).isPresent();
    Account updatedAccount = updatedAccountState.get().account();
    assertThat(updatedAccount.status()).isEqualTo(status);
    assertThat(updatedAccount.fullName()).isEqualTo(fullName);

    accountInfo = gApi.accounts().id(admin.id().get()).get();
    assertThat(accountInfo.status).isEqualTo(status);
    assertThat(accountInfo.name).isEqualTo(fullName);
  }

  @Test
  public void failAfterRetryerGivesUp() throws Exception {
    ImmutableList<String> status = ImmutableList.of("foo", "bar", "baz");
    String fullName = "Foo";
    AtomicInteger bgCounter = new AtomicInteger(0);
    AccountsUpdate update =
        getAccountsUpdateWithRunnables(
            () -> {
              try {
                accountsUpdateProvider
                    .get()
                    .update(
                        "Set Status",
                        admin.id(),
                        u -> u.setStatus(status.get(bgCounter.getAndAdd(1))));
              } catch (IOException | ConfigInvalidException | StorageException e) {
                // Ignore, the expected exception is asserted later
              }
            },
            Runnables.doNothing(),
            new RetryHelper(
                cfg,
                retryMetrics,
                null,
                null,
                null,
                null,
                exceptionHooks,
                retryListeners,
                r ->
                    r.withStopStrategy(StopStrategies.stopAfterAttempt(status.size()))
                        .withBlockStrategy(noSleepBlockStrategy)));
    assertThat(bgCounter.get()).isEqualTo(0);
    AccountInfo accountInfo = gApi.accounts().id(admin.id().get()).get();
    assertThat(accountInfo.status).isNull();
    assertThat(accountInfo.name).isNotEqualTo(fullName);

    StorageException exception =
        assertThrows(
            StorageException.class,
            () -> update.update("Set Full Name", admin.id(), u -> u.setFullName(fullName)));
    assertThat(exception).hasCauseThat().isInstanceOf(RetryException.class);
    assertThat(exception.getCause()).hasCauseThat().isInstanceOf(LockFailureException.class);
    assertThat(bgCounter.get()).isEqualTo(status.size());

    Account updatedAccount = accounts.get(admin.id()).get().account();
    assertThat(updatedAccount.status()).isEqualTo(Iterables.getLast(status));
    assertThat(updatedAccount.fullName()).isEqualTo(admin.fullName());

    accountInfo = gApi.accounts().id(admin.id().get()).get();
    assertThat(accountInfo.status).isEqualTo(Iterables.getLast(status));
    assertThat(accountInfo.name).isEqualTo(admin.fullName());
  }

  @Test
  public void atomicReadModifyWrite() throws Exception {
    gApi.accounts().id(admin.id().get()).setStatus("A-1");

    AtomicBoolean bgIndicatorA1ToA2 = new AtomicBoolean(false);
    AccountsUpdate update =
        getAccountsUpdateWithRunnables(
            Runnables.doNothing(),
            () -> {
              if (bgIndicatorA1ToA2.get()) {
                // In the Google architecture, this runnable might be called multiple times. Only
                // do the replacement once.
                return;
              }
              try {
                accountsUpdateProvider
                    .get()
                    .update("Set Status", admin.id(), u -> u.setStatus("A-2"));
              } catch (IOException | ConfigInvalidException | StorageException e) {
                // Ignore, the expected exception is asserted later
              }
              bgIndicatorA1ToA2.set(true);
            });

    assertThat(gApi.accounts().id(admin.id().get()).get().status).isEqualTo("A-1");

    AtomicBoolean bgIndicatorA1ToB1 = new AtomicBoolean(false);
    AtomicBoolean bgIndicatorA2ToB2 = new AtomicBoolean(false);
    Optional<AccountState> updatedAccountState =
        update.update(
            "Set Status",
            admin.id(),
            (a, u) -> {
              if ("A-1".equals(a.account().status())) {
                bgIndicatorA1ToB1.set(true);
                u.setStatus("B-1");
              }

              if ("A-2".equals(a.account().status())) {
                bgIndicatorA2ToB2.set(true);
                u.setStatus("B-2");
              }
            });

    assertThat(bgIndicatorA1ToB1.get()).isTrue();
    assertThat(bgIndicatorA2ToB2.get()).isTrue();

    assertThat(updatedAccountState).isPresent();
    assertThat(updatedAccountState.get().account().status()).isEqualTo("B-2");
    assertThat(accounts.get(admin.id()).get().account().status()).isEqualTo("B-2");
    assertThat(gApi.accounts().id(admin.id().get()).get().status).isEqualTo("B-2");
  }

  @Test
  public void atomicReadModifyWriteExternalIds() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(allowCapability(GlobalCapability.ACCESS_DATABASE).group(REGISTERED_USERS))
        .update();

    Account.Id accountId = Account.id(seq.nextAccountId());
    ExternalId extIdA1 = getExternalIdFactory().create("foo", "A-1", accountId);
    accountsUpdateProvider
        .get()
        .insert("Create Test Account", accountId, u -> u.addExternalId(extIdA1));

    ExternalId extIdA2 = getExternalIdFactory().create("foo", "A-2", accountId);
    AtomicBoolean bgIndicatorA1ToA2 = new AtomicBoolean(false);
    AccountsUpdate update =
        getAccountsUpdateWithRunnables(
            Runnables.doNothing(),
            () -> {
              if (bgIndicatorA1ToA2.get()) {
                // In the Google architecture, this runnable might be called multiple times. Only
                // do the replacement once.
                return;
              }
              try {
                accountsUpdateProvider
                    .get()
                    .update(
                        "Update External ID A1->A2",
                        accountId,
                        u -> u.replaceExternalId(extIdA1, extIdA2));
              } catch (IOException | ConfigInvalidException | StorageException e) {
                // Ignore, the expected exception is asserted later
              }
              bgIndicatorA1ToA2.set(true);
            });

    assertThat(
            gApi.accounts().id(accountId.get()).getExternalIds().stream()
                .map(i -> i.identity)
                .collect(toSet()))
        .containsExactly(extIdA1.key().get());

    ExternalId extIdB1 = getExternalIdFactory().create("foo", "B-1", accountId);
    ExternalId extIdB2 = getExternalIdFactory().create("foo", "B-2", accountId);
    AtomicBoolean bgIndicatorA1ToB1 = new AtomicBoolean(false);
    AtomicBoolean bgIndicatorA2ToB2 = new AtomicBoolean(false);
    Optional<AccountState> updatedAccount =
        update.update(
            "Conditionally update External IDs: A1->B1, A2->B2",
            accountId,
            (a, u) -> {
              if (a.externalIds().contains(extIdA1)) {
                bgIndicatorA1ToB1.set(true);
                u.replaceExternalId(extIdA1, extIdB1);
              }

              if (a.externalIds().contains(extIdA2)) {
                bgIndicatorA2ToB2.set(true);
                u.replaceExternalId(extIdA2, extIdB2);
              }
            });

    assertThat(bgIndicatorA1ToB1.get()).isTrue();
    assertThat(bgIndicatorA2ToB2.get()).isTrue();

    assertThat(updatedAccount).isPresent();
    assertThat(updatedAccount.get().externalIds()).containsExactly(extIdB2);
    assertThat(accounts.get(accountId).get().externalIds()).containsExactly(extIdB2);
    assertThat(
            gApi.accounts().id(accountId.get()).getExternalIds().stream()
                .map(i -> i.identity)
                .collect(toSet()))
        .containsExactly(extIdB2.key().get());
  }

  @Test
  public void stalenessChecker() throws Exception {
    // Newly created account is not stale.
    AccountInfo accountInfo = gApi.accounts().create(name("foo")).get();
    Account.Id accountId = Account.id(accountInfo._accountId);
    assertThat(stalenessChecker.check(accountId).isStale()).isFalse();

    // Manually updating the user ref makes the index document stale.
    String userRef = RefNames.refsUsers(accountId);
    testRefAction(
        () -> {
          try (Repository repo = repoManager.openRepository(allUsers);
              ObjectInserter oi = repo.newObjectInserter();
              RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(repo.exactRef(userRef).getObjectId());

            PersonIdent ident = new PersonIdent(serverIdent.get(), TimeUtil.now());
            CommitBuilder cb = new CommitBuilder();
            cb.setTreeId(commit.getTree());
            cb.setCommitter(ident);
            cb.setAuthor(ident);
            cb.setMessage(commit.getFullMessage());
            ObjectId emptyCommit = oi.insert(cb);
            oi.flush();

            RefUpdate updateRef = repo.updateRef(userRef);
            updateRef.setExpectedOldObjectId(commit.toObjectId());
            updateRef.setNewObjectId(emptyCommit);
            assertThat(updateRef.forceUpdate()).isEqualTo(RefUpdate.Result.FORCED);
          }
        });
    assertStaleAccountAndReindex(accountId);

    // Manually inserting/updating/deleting an external ID of the user makes the index document
    // stale.
    try (Repository repo = repoManager.openRepository(allUsers)) {
      testRefAction(
          () -> {
            ExternalIdNotes extIdNotes = getExternalIdNotes(repo);

            ExternalId.Key key = externalIdKeyFactory.create("foo", "foo");
            extIdNotes.insert(getExternalIdFactory().create(key, accountId));
            try (MetaDataUpdate update = metaDataUpdateFactory.create(allUsers)) {
              extIdNotes.commit(update);
            }
            assertStaleAccountAndReindex(accountId);

            extIdNotes = getExternalIdNotes(repo);
            extIdNotes.upsert(
                getExternalIdFactory().createWithEmail(key, accountId, "foo@example.com"));
            try (MetaDataUpdate update = metaDataUpdateFactory.create(allUsers)) {
              extIdNotes.commit(update);
            }
            assertStaleAccountAndReindex(accountId);

            extIdNotes = getExternalIdNotes(repo);
            extIdNotes.delete(accountId, key);
            try (MetaDataUpdate update = metaDataUpdateFactory.create(allUsers)) {
              extIdNotes.commit(update);
            }
          });
      assertStaleAccountAndReindex(accountId);
    }

    // Manually delete account
    testRefAction(
        () -> {
          try (Repository repo = repoManager.openRepository(allUsers);
              RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(repo.exactRef(userRef).getObjectId());
            RefUpdate updateRef = repo.updateRef(userRef);
            updateRef.setExpectedOldObjectId(commit.toObjectId());
            updateRef.setNewObjectId(ObjectId.zeroId());
            updateRef.setForceUpdate(true);
            assertThat(updateRef.delete()).isEqualTo(RefUpdate.Result.FORCED);
          }
        });
    assertStaleAccountAndReindex(accountId);
  }

  private void assertStaleAccountAndReindex(Account.Id accountId) throws IOException {
    assertThat(stalenessChecker.check(accountId).isStale()).isTrue();

    // Reindex fixes staleness
    accountIndexer.index(accountId);
    assertThat(stalenessChecker.check(accountId).isStale()).isFalse();
  }

  @Test
  @UseClockStep
  public void deleteAllDraftComments() throws Exception {
    try {
      Project.NameKey project2 = projectOperations.newProject().create();
      PushOneCommit.Result r1 = createChange();

      TestRepository<?> tr2 = cloneProject(project2);
      PushOneCommit.Result r2 =
          createChange(
              tr2,
              "refs/heads/master",
              "Change in project2",
              PushOneCommit.FILE_NAME,
              "content2",
              null);

      // Create 2 drafts each on both changes for user.
      requestScopeOperations.setApiUser(user.id());
      createDraft(r1, PushOneCommit.FILE_NAME, "draft 1a");
      createDraft(r1, PushOneCommit.FILE_NAME, "draft 1b");
      createDraft(r2, PushOneCommit.FILE_NAME, "draft 2a");
      createDraft(r2, PushOneCommit.FILE_NAME, "draft 2b");
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(2);
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).hasSize(2);

      // Create 1 draft on first change for admin.
      requestScopeOperations.setApiUser(admin.id());
      createDraft(r1, PushOneCommit.FILE_NAME, "admin draft");
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(1);

      // Delete user's draft comments; leave admin's alone.
      requestScopeOperations.setApiUser(user.id());
      List<DeletedDraftCommentInfo> result =
          gApi.accounts().self().deleteDraftComments(new DeleteDraftCommentsInput());

      // Results are ordered according to the change search, most recently updated first.
      assertThat(result).hasSize(2);
      DeletedDraftCommentInfo del2 = result.get(0);
      assertThat(del2.change.changeId).isEqualTo(r2.getChangeId());
      assertThat(del2.deleted.stream().map(c -> c.message)).containsExactly("draft 2a", "draft 2b");
      DeletedDraftCommentInfo del1 = result.get(1);
      assertThat(del1.change.changeId).isEqualTo(r1.getChangeId());
      assertThat(del1.deleted.stream().map(c -> c.message)).containsExactly("draft 1a", "draft 1b");

      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).isEmpty();
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).isEmpty();

      requestScopeOperations.setApiUser(admin.id());
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(1);
    } finally {
      cleanUpDrafts();
    }
  }

  @Test
  public void deleteDraftCommentsByQuery() throws Exception {
    try {
      PushOneCommit.Result r1 = createChange();
      PushOneCommit.Result r2 = createChange();

      createDraft(r1, PushOneCommit.FILE_NAME, "draft a");
      createDraft(r2, PushOneCommit.FILE_NAME, "draft b");
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(1);
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).hasSize(1);

      List<DeletedDraftCommentInfo> result =
          gApi.accounts()
              .self()
              .deleteDraftComments(new DeleteDraftCommentsInput("change:" + r1.getChangeId()));
      assertThat(result).hasSize(1);
      assertThat(result.get(0).change.changeId).isEqualTo(r1.getChangeId());
      assertThat(result.get(0).deleted.stream().map(c -> c.message)).containsExactly("draft a");

      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).isEmpty();
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).hasSize(1);
    } finally {
      cleanUpDrafts();
    }
  }

  @Test
  public void deleteOtherUsersDraftCommentsDisallowed() throws Exception {
    try {
      PushOneCommit.Result r = createChange();
      requestScopeOperations.setApiUser(user.id());
      createDraft(r, PushOneCommit.FILE_NAME, "draft");
      requestScopeOperations.setApiUser(admin.id());
      AuthException thrown =
          assertThrows(
              AuthException.class,
              () ->
                  gApi.accounts()
                      .id(user.id().get())
                      .deleteDraftComments(new DeleteDraftCommentsInput()));
      assertThat(thrown).hasMessageThat().isEqualTo("Cannot delete drafts of other user");
    } finally {
      cleanUpDrafts();
    }
  }

  @Test
  public void deleteDraftCommentsSkipsInvisibleChanges() throws Exception {
    try {
      createBranch(BranchNameKey.create(project, "secret"));
      PushOneCommit.Result r1 = createChange();
      PushOneCommit.Result r2 = createChange("refs/for/secret");

      requestScopeOperations.setApiUser(user.id());
      createDraft(r1, PushOneCommit.FILE_NAME, "draft a");
      createDraft(r2, PushOneCommit.FILE_NAME, "draft b");
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(1);
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).hasSize(1);

      projectOperations
          .project(project)
          .forUpdate()
          .add(block(Permission.READ).ref("refs/heads/secret").group(REGISTERED_USERS))
          .update();
      List<DeletedDraftCommentInfo> result =
          gApi.accounts().self().deleteDraftComments(new DeleteDraftCommentsInput());
      assertThat(result).hasSize(1);
      assertThat(result.get(0).change.changeId).isEqualTo(r1.getChangeId());
      assertThat(result.get(0).deleted.stream().map(c -> c.message)).containsExactly("draft a");

      projectOperations
          .project(project)
          .forUpdate()
          .remove(permissionKey(Permission.READ).ref("refs/heads/secret"))
          .update();
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).isEmpty();
      // Draft still exists since change wasn't visible when drafts where deleted.
      assertThat(gApi.changes().id(r2.getChangeId()).current().draftsAsList()).hasSize(1);
    } finally {
      cleanUpDrafts();
    }
  }

  @Test
  @UseClockStep
  public void updateDrafts() throws Exception {
    try {
      PushOneCommit.Result r1 = createChange();

      requestScopeOperations.setApiUser(user.id());
      CommentInfo draft = createDraft(r1, PushOneCommit.FILE_NAME, "draft creation");
      updateDraft(r1, draft, "draft update");
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList()).hasSize(1);
      assertThat(gApi.changes().id(r1.getChangeId()).current().draftsAsList().get(0).message)
          .isEqualTo("draft update");
    } finally {
      cleanUpDrafts();
    }
  }

  @Test
  public void userCanGenerateNewHttpPassword() throws Exception {
    sender.clear();
    String newPassword = gApi.accounts().self().generateHttpPassword();
    assertThat(newPassword).isNotNull();
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body())
        .contains("The authentication token with id \"default\" was added");
  }

  @Test
  public void adminCanGenerateNewHttpPasswordForUser() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    String newPassword = gApi.accounts().id(user.id().get()).generateHttpPassword();
    assertThat(newPassword).isNotNull();
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body())
        .contains("The authentication token with id \"default\" was added");
  }

  @Test
  public void userCannotGenerateNewHttpPasswordForOtherUser() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        AuthException.class, () -> gApi.accounts().id(admin.id().get()).generateHttpPassword());
  }

  @Test
  public void userCannotExplicitlySetHttpPassword() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        AuthException.class, () -> gApi.accounts().self().setHttpPassword("my-new-password"));
  }

  @Test
  public void userCannotExplicitlySetHttpPasswordForOtherUser() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        AuthException.class,
        () -> gApi.accounts().id(admin.id().get()).setHttpPassword("my-new-password"));
  }

  @Test
  public void userCanRemoveHttpPassword() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    sender.clear();
    assertThat(gApi.accounts().self().setHttpPassword(null)).isNull();
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body()).contains("HTTP password was deleted");
  }

  @Test
  public void userCannotRemoveHttpPasswordForOtherUser() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        AuthException.class, () -> gApi.accounts().id(admin.id().get()).setHttpPassword(null));
  }

  @Test
  public void adminCanExplicitlySetHttpPasswordForUser() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    String httpPassword = "new-password-for-user";
    sender.clear();
    assertThat(gApi.accounts().id(user.id().get()).setHttpPassword(httpPassword))
        .isEqualTo(httpPassword);
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body())
        .contains("The authentication token with id \"default\" was added");
  }

  @Test
  public void adminCanRemoveHttpPasswordForUser() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    assertThat(gApi.accounts().id(user.id().get()).setHttpPassword(null)).isNull();
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).body()).contains("HTTP password was deleted");
  }

  @Test
  public void cannotGenerateHttpPasswordWhenUsernameIsNotSet() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    int userId = accountCreator.create().id().get();
    assertThat(gApi.accounts().id(userId).get().username).isNull();
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.accounts().id(userId).generateHttpPassword());
    assertThat(thrown).hasMessageThat().contains("username");
  }

  @Test
  public void externalIdBatchUpdates() throws Exception {
    String extId1String = "foo:bar";
    String extId2String = "foo:baz";
    ExternalId extId1 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse(extId1String), admin.id(), "1@foo.com");
    ExternalId extId2 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse(extId2String), user.id(), "2@foo.com");

    int initialCommits = countExternalIdsCommits();
    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", admin.id(), u -> u.addExternalId(extId1));
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", user.id(), u -> u.addExternalId(extId2));
    ImmutableList<Optional<AccountState>> accountStates =
        accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2));
    assertThat(accountStates).hasSize(2);
    assertThat(accountStates.get(0).get().externalIds()).contains(extId1);
    assertThat(accountStates.get(1).get().externalIds()).contains(extId2);
    assertThat(
            gApi.accounts().id(admin.id().get()).getExternalIds().stream()
                .map(e -> e.identity)
                .collect(toSet()))
        .contains(extId1String);
    assertThat(
            gApi.accounts().id(user.id().get()).getExternalIds().stream()
                .map(e -> e.identity)
                .collect(toSet()))
        .contains(extId2String);

    // Ensure that we only applied one single commit.
    int afterUpdateCommits = countExternalIdsCommits();
    assertThat(afterUpdateCommits).isEqualTo(initialCommits + 1);
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected int countExternalIdsCommits() throws Exception {
    try (Repository allUsersRepo = repoManager.openRepository(allUsers);
        Git git = new Git(allUsersRepo)) {
      ObjectId refsMetaExternalIdsHead =
          allUsersRepo.exactRef(RefNames.REFS_EXTERNAL_IDS).getObjectId();
      return Iterables.size(git.log().add(refsMetaExternalIdsHead).call());
    }
  }

  @Test
  public void externalIdBatchUpdates_fail_sameAccount() {
    ExternalId extId1 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:bar"), admin.id(), "1@foo.com");
    ExternalId extId2 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:baz"), user.id(), "2@foo.com");

    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", admin.id(), u -> u.addExternalId(extId1));
    // Another update for the same account is not allowed.
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", admin.id(), u -> u.addExternalId(extId2));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2)));
    assertThat(e).hasMessageThat().contains("updates must all be for different accounts");
  }

  @Test
  public void externalIdBatchUpdates_fail_duplicateKey() {
    ExternalId extIdAdmin =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:bar"), admin.id(), "1@foo.com");
    ExternalId extIdUser =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:bar"), user.id(), "2@foo.com");

    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", admin.id(), u -> u.addExternalId(extIdAdmin));
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", user.id(), u -> u.addExternalId(extIdUser));
    DuplicateExternalIdKeyException e =
        assertThrows(
            DuplicateExternalIdKeyException.class,
            () -> accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2)));
    assertThat(e).hasMessageThat().contains("foo:bar");
  }

  @Test
  public void externalIdBatchUpdates_commitMsg_multipleAccounts() throws Exception {
    ExternalId extId1 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:bar"), admin.id(), "1@foo.com");
    ExternalId extId2 =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:baz"), user.id(), "2@foo.com");

    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "first message", admin.id(), u -> u.addExternalId(extId1));
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "second message", user.id(), u -> u.addExternalId(extId2));
    accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2));

    try (Repository allUsersRepo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(allUsersRepo)) {
      RevCommit commit =
          rw.parseCommit(allUsersRepo.exactRef(RefNames.REFS_EXTERNAL_IDS).getObjectId());

      assertThat(commit.getFullMessage()).isEqualTo("Batch update for 2 accounts\n");
    }
  }

  @Test
  public void externalIdBatchUpdates_commitMsg_singleAccount() throws Exception {
    ExternalId extId =
        getExternalIdFactory()
            .createWithEmail(externalIdKeyFactory.parse("foo:bar"), admin.id(), "1@foo.com");

    accountsUpdateProvider.get().update("foobar", admin.id(), u -> u.addExternalId(extId));

    try (Repository allUsersRepo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(allUsersRepo)) {
      RevCommit commit =
          rw.parseCommit(allUsersRepo.exactRef(RefNames.REFS_EXTERNAL_IDS).getObjectId());

      assertThat(commit.getFullMessage()).isEqualTo("foobar\n");
    }
  }

  @Test
  public void searchForSecondaryEmailRequiresModifyAccountPermission() throws Exception {
    String email = "preferred@example.com";
    TestAccount foo = accountCreator.create(name("foo"), email, "Foo", null);
    String secondaryEmail = "secondary@example.com";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    requestScopeOperations.setApiUser(user.id());
    if (server.isUsernameSupported()) {
      assertThrows(ResourceNotFoundException.class, () -> gApi.accounts().id("secondary"));
    }
    assertThrows(
        ResourceNotFoundException.class, () -> gApi.accounts().id("secondary@example.com"));
    requestScopeOperations.setApiUser(admin.id());
    if (server.isUsernameSupported()) {
      assertThat(gApi.accounts().id("secondary").get()._accountId).isEqualTo(foo.id().get());
    }
    assertThat(gApi.accounts().id("secondary@example.com").get()._accountId)
        .isEqualTo(foo.id().get());
  }

  @Test
  public void getAccountFromMetaId() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());
    gApi.accounts().self().setStatus("New status");

    AccountState postUpdateStatus = accountCache.get(admin.id()).get();
    assertThat(postUpdateStatus).isNotEqualTo(preUpdateState);
    assertThat(
            accountCache.getFromMetaId(
                admin.id(), ObjectId.fromString(preUpdateState.account().metaId())))
        .isEqualTo(preUpdateState);
    assertThat(
            accountCache.getFromMetaId(
                admin.id(), ObjectId.fromString(postUpdateStatus.account().metaId())))
        .isEqualTo(postUpdateStatus);
  }

  @CanIgnoreReturnValue
  private CommentInfo createDraft(Result r, String path, String message) throws Exception {
    DraftInput in = new DraftInput();
    in.path = path;
    in.line = 1;
    in.message = message;
    return gApi.changes().id(r.getChangeId()).current().createDraft(in).get();
  }

  @CanIgnoreReturnValue
  private CommentInfo updateDraft(Result r, CommentInfo orig, String newMessage) throws Exception {
    DraftInput in = new DraftInput();
    in.path = orig.path;
    in.line = orig.line;
    in.message = newMessage;
    return gApi.changes().id(r.getChangeId()).current().draft(orig.id).update(in);
  }

  private void cleanUpDrafts() throws Exception {
    for (TestAccount testAccount : accountCreator.getAll()) {
      requestScopeOperations.setApiUser(testAccount.id());
      for (ChangeInfo changeInfo : gApi.changes().query("has:draft").get()) {
        for (CommentInfo c :
            gApi.changes().id(changeInfo.id).drafts().values().stream()
                .flatMap(List::stream)
                .collect(toImmutableList())) {
          gApi.changes().id(changeInfo.id).revision(c.patchSet).draft(c.id).delete();
        }
      }
    }
  }

  @Test
  public void projectWatchesUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    ProjectWatchInfo projectWatchInfo = new ProjectWatchInfo();
    projectWatchInfo.project = project.get();
    projectWatchInfo.notifyAllComments = true;
    gApi.accounts().self().setWatchedProjects(ImmutableList.of(projectWatchInfo));

    AccountState updatedState1 = accountCache.get(admin.id()).get();
    assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState1.account().metaId());

    gApi.accounts().self().deleteWatchedProjects(ImmutableList.of(projectWatchInfo));

    AccountState updatedState2 = accountCache.get(admin.id()).get();
    assertThat(updatedState1.account().metaId()).isNotEqualTo(updatedState2.account().metaId());
  }

  @Test
  public void updateExternalId_externalIdApiUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    gApi.accounts().self().addEmail(newEmailInput("secondary@non.google"));
    assertExternalIds(
        admin.id(),
        ImmutableSet.of(
            "mailto:admin@example.com", "username:admin", "mailto:secondary@non.google"));

    AccountState updatedState1 = accountCache.get(admin.id()).get();
    assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState1.account().metaId());

    gApi.accounts().self().deleteExternalIds(ImmutableList.of("mailto:secondary@non.google"));

    AccountState updatedState2 = accountCache.get(admin.id()).get();
    assertThat(updatedState1.account().metaId()).isNotEqualTo(updatedState2.account().metaId());
  }

  @Test
  public void addExternalId_accountUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    ExternalId externalId = getExternalIdFactory().create("custom", "value", admin.id());
    accountsUpdateProvider
        .get()
        .update("Add External ID", admin.id(), u -> u.addExternalId(externalId));
    assertExternalIds(
        admin.id(), ImmutableSet.of("mailto:admin@example.com", "username:admin", "custom:value"));

    AccountState updatedState = accountCache.get(admin.id()).get();
    assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState.account().metaId());
  }

  @Test
  public void deleteExternalId_accountUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    ExternalId externalId = createEmailExternalId(admin.id(), "admin@example.com");
    accountsUpdateProvider
        .get()
        .update("Remove External ID", admin.id(), u -> u.deleteExternalId(externalId));
    assertExternalIds(admin.id(), ImmutableSet.of("username:admin"));

    AccountState updatedState = accountCache.get(admin.id()).get();
    assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState.account().metaId());
  }

  @Test
  public void updateExternalId_accountUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    ExternalId externalId =
        getExternalIdFactory()
            .createWithEmail(
                SCHEME_MAILTO, "secondary@non.google", admin.id(), "secondary@non.google");
    accountsUpdateProvider
        .get()
        .update("Update External ID", admin.id(), u -> u.updateExternalId(externalId));
    assertExternalIds(
        admin.id(),
        ImmutableSet.of(
            "mailto:admin@example.com", "username:admin", "mailto:secondary@non.google"));

    AccountState updatedState = accountCache.get(admin.id()).get();
    assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState.account().metaId());
  }

  @Test
  public void replaceExternalId_accountUpdate_refsUsersUpdated() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    ExternalId externalId =
        getExternalIdFactory()
            .createWithEmail(
                SCHEME_MAILTO, "secondary@non.google", admin.id(), "secondary@non.google");
    ExternalId oldExternalId =
        getExternalIdsReader().get(createEmailExternalId(admin.id(), admin.email()).key()).get();
    accountsUpdateProvider
        .get()
        .update(
            "Replace External ID", admin.id(), u -> u.replaceExternalId(oldExternalId, externalId));
    assertExternalIds(admin.id(), ImmutableSet.of("mailto:secondary@non.google", "username:admin"));

    AccountState updatedState = accountCache.get(admin.id()).get();
    assertThat(accountCache.get(admin.id()).get()).isNotSameInstanceAs(preUpdateState);
    if (preUpdateState.account().metaId() == null) {
      // When the test is executed on google infrastructure, metaId should be either always set
      // or always be null.
      assertThat(updatedState.account().metaId()).isNull();
    } else {
      assertThat(preUpdateState.account().metaId()).isNotEqualTo(updatedState.account().metaId());
    }
  }

  @Test
  public void accountUpdate_updateBatch_allUsersExternalIdsUpdated_refsUsersUpdated()
      throws Exception {
    AccountState preUpdateAdminState = accountCache.get(admin.id()).get();
    AccountState preUpdateUserState = accountCache.get(user.id()).get();

    requestScopeOperations.setApiUser(admin.id());
    ExternalId extId1 =
        getExternalIdFactory()
            .createWithEmail("custom", "admin-id", admin.id(), "admin-id@test.com");

    ExternalId extId2 =
        getExternalIdFactory().createWithEmail("custom", "user-id", user.id(), "user-id@test.com");

    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", admin.id(), u -> u.addExternalId(extId1));
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "Add External ID", user.id(), u -> u.addExternalId(extId2));
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2));
    }
    accountIndexedCounter.assertReindexOf(admin.id(), 1);
    accountIndexedCounter.assertReindexOf(user.id(), 1);

    assertExternalIds(
        admin.id(),
        ImmutableSet.of("mailto:admin@example.com", "username:admin", "custom:admin-id"));
    assertExternalIds(
        user.id(), ImmutableSet.of("username:user1", "mailto:user1@example.com", "custom:user-id"));
    // Assert reindexing has worked on the updated accounts.
    assertThat(
            Iterables.getOnlyElement(gApi.accounts().query("admin-id@test.com").get())._accountId)
        .isEqualTo(admin.id().get());
    assertThat(Iterables.getOnlyElement(gApi.accounts().query("user-id@test.com").get())._accountId)
        .isEqualTo(user.id().get());
    AccountState updatedAdminState = accountCache.get(admin.id()).get();
    AccountState updatedUserState = accountCache.get(user.id()).get();
    assertThat(preUpdateAdminState.account().metaId())
        .isNotEqualTo(updatedAdminState.account().metaId());
    assertThat(preUpdateUserState.account().metaId())
        .isNotEqualTo(updatedUserState.account().metaId());
  }

  @Test
  public void accountUpdate_updateBatch_someUsersExternalIdsUpdated_refsUsersUpdated()
      throws Exception {
    AccountState preUpdateAdminState = accountCache.get(admin.id()).get();
    AccountState preUpdateUserState = accountCache.get(user.id()).get();

    requestScopeOperations.setApiUser(admin.id());
    AccountsUpdate.UpdateArguments ua1 =
        new AccountsUpdate.UpdateArguments(
            "Update Display Name", admin.id(), u -> u.setDisplayName("DN"));
    AccountsUpdate.UpdateArguments ua2 =
        new AccountsUpdate.UpdateArguments(
            "Remove external Id",
            user.id(),
            u -> u.deleteExternalId(createEmailExternalId(user.id(), user.email())));
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      accountsUpdateProvider.get().updateBatch(ImmutableList.of(ua1, ua2));
    }
    accountIndexedCounter.assertReindexOf(admin.id(), 1);
    accountIndexedCounter.assertReindexOf(user.id(), 1);

    // Only the version in config of the user with external id update was updated.
    AccountState updatedAdminState = accountCache.get(admin.id()).get();
    AccountState updatedUserState = accountCache.get(user.id()).get();
    assertThat(preUpdateAdminState.account().metaId())
        .isNotEqualTo(updatedAdminState.account().metaId());
    assertThat(preUpdateUserState.account().metaId())
        .isNotEqualTo(updatedUserState.account().metaId());
  }

  @Test
  public void accountUpdate_emptyStringsToUnset() throws Exception {
    AccountState preUpdateState = accountCache.get(admin.id()).get();
    requestScopeOperations.setApiUser(admin.id());

    accountsUpdateProvider
        .get()
        .update(
            "Replace External ID",
            admin.id(),
            u -> u.setFullName("").setDisplayName("").setPreferredEmail("").setStatus(""));

    AccountState updatedState = accountCache.get(admin.id()).get();
    assertThat(accountCache.get(admin.id()).get()).isNotSameInstanceAs(preUpdateState);
    assertThat(updatedState.account().fullName()).isNull();
    assertThat(updatedState.account().displayName()).isNull();
    assertThat(updatedState.account().preferredEmail()).isNull();
    assertThat(updatedState.account().status()).isNull();
  }

  protected ExternalId createEmailExternalId(Account.Id accountId, String email) {
    return getExternalIdFactory().createWithEmail(SCHEME_MAILTO, email, accountId, email);
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void accountsCanSeeEachOtherThroughASharedExternalGroupOnlyWhenTheGroupIsMentionedInAcls()
      throws Exception {
    TestAccount user2 = accountCreator.user2();

    // user and user2 cannot see each other because they do not share a Gerrit internal group
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
        .isFalse();
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user2.id())).canSee(user.id()))
        .isFalse();

    // Configure an external group backend that has a single group that contains all users.
    TestGroupBackend testGroupBackend = createTestGroupBackendWithAllUsersGroup("AllUsers");
    try (Registration registration = extensionRegistry.newRegistration().add(testGroupBackend)) {
      // user and user2 cannot see each other although the external AllUsers group contains both
      // users. That's because this group is not detected as relevant and hence its memberships are
      // not checked.
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
          .isFalse();
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user2.id())).canSee(user.id()))
          .isFalse();

      // Add ACL for the external group.
      projectOperations
          .project(project)
          .forUpdate()
          .add(
              TestProjectUpdate.allowLabel("Code-Review")
                  .range(0, 1)
                  .ref("refs/heads/*")
                  .group(AccountGroup.uuid(TestGroupBackend.PREFIX + "AllUsers"))
                  .build())
          .update();

      // user and user2 can now see each other because the external AllUsers group that contains
      // both users is guessed as relevant now that permissions are assigned to this group.
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
          .isTrue();
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user2.id())).canSee(user.id()))
          .isTrue();
    }
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  @GerritConfig(name = "groups.relevantGroup", value = "testbackend:AllUsers")
  public void accountsCanSeeEachOtherThroughASharedExternalGroupThatIsConfiguredAsRelevant()
      throws Exception {
    TestAccount user2 = accountCreator.user2();

    // user and user2 cannot see each other because they do not share a Gerrit internal group
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
        .isFalse();
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user2.id())).canSee(user.id()))
        .isFalse();

    // Check that the configured relevant group is included into the guessed groups.
    assertThat(projectCache.guessRelevantGroupUUIDs())
        .contains(AccountGroup.uuid("testbackend:AllUsers"));

    // Configure an external group backend that has a single group that contains all users.
    TestGroupBackend testGroupBackend = createTestGroupBackendWithAllUsersGroup("AllUsers");

    try (Registration registration = extensionRegistry.newRegistration().add(testGroupBackend)) {
      // user and user2 can see each other since the external AllUsers that contains both users has
      // been configured as a relevant group.
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
          .isTrue();
      assertThat(
              accountControlFactory.get(identifiedUserFactory.create(user2.id())).canSee(user.id()))
          .isTrue();
    }
  }

  @Test
  public void deleteAccount_deletesAccountIdentifiers() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    String secondaryEmail = "secondary@email.com";
    gApi.accounts().id(deleted.id().get()).addEmail(newEmailInput(secondaryEmail));

    requestScopeOperations.setApiUser(deleted.id());
    gApi.accounts().self().delete();

    requestScopeOperations.setApiUser(admin.id());
    assertThrows(
        ResourceNotFoundException.class, () -> gApi.accounts().id(deleted.id().get()).get());
    assertThrows(NoSuchElementException.class, () -> accountCache.get(deleted.id()).get());

    // Verifies the account is not queryable
    assertThat(gApi.accounts().query(deleted.id().toString()).get()).isEmpty();
    assertThat(gApi.accounts().query(deleted.username()).get()).isEmpty();
    assertThat(gApi.accounts().query(deleted.fullName()).get()).isEmpty();
    assertThat(gApi.accounts().query(deleted.displayName()).get()).isEmpty();
    assertThat(gApi.accounts().query(deleted.email()).get()).isEmpty();
    assertThat(gApi.accounts().query(secondaryEmail).get()).isEmpty();

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_deletesAccountExternalIds() throws Exception {
    TestAccount deleted =
        accountCreator.create("deleted", "deleted@internal.com", "Full Name", "Display");
    requestScopeOperations.setApiUser(deleted.id());
    addExternalIdEmail(deleted, "deleted@external.com");
    assertExternalEmails(
        deleted.id(), ImmutableSet.of("deleted@internal.com", "deleted@external.com"));

    gApi.accounts().self().delete();

    requestScopeOperations.setApiUser(admin.id());
    assertThat(getExternalIdsReader().byEmails("deleted@internal.com", "deleted@external.com"))
        .isEmpty();

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  @UseSsh
  public void deleteAccount_deletesSshKeys() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    requestScopeOperations.setApiUser(deleted.id());
    String newKey = TestSshKeys.publicKey(SshSessionFactory.genSshKey(), deleted.email());
    gApi.accounts().self().addSshKey(newKey);
    assertThat(gApi.accounts().self().listSshKeys()).hasSize(1);
    assertThat(authorizedKeys.getKeys(deleted.id())).hasSize(1);

    gApi.accounts().self().delete();

    requestScopeOperations.setApiUser(admin.id());
    assertThat(authorizedKeys.getKeys(deleted.id())).isEmpty();

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_deletesGpgKeys() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));

    requestScopeOperations.setApiUser(deleted.id());
    addExternalIdEmail(
        deleted,
        PushCertificateIdent.parse(validKeyWithoutExpiration().getFirstUserId()).getEmailAddress());
    TestKey key = validKeyWithoutExpiration();
    addGpgKey(deleted, key.getPublicKeyArmored());
    assertKeys(key);
    assertIteratorSize(1, getOnlyKeyFromStore(key).getUserIDs());

    gApi.accounts().self().delete();

    requestScopeOperations.setApiUser(admin.id());
    try (PublicKeyStore store = publicKeyStoreProvider.get()) {
      Iterable<PGPPublicKeyRing> keys = store.get(key.getKeyId());
      assertThat(keys).isEmpty();
    }

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_deletesStarredChanges() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();

    requestScopeOperations.setApiUser(deleted.id());

    gApi.accounts().self().starChange(triplet);
    assertThat(getStarredChangesCount(r.getChange().getId())).isEqualTo(1);

    gApi.accounts().self().delete();

    // Reopen the repo to refresh RefDb
    assertThat(getStarredChangesCount(r.getChange().getId())).isEqualTo(0);
    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_deletesChangeEdits() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    PushOneCommit.Result r = createChange();

    requestScopeOperations.setApiUser(deleted.id());

    gApi.changes().id(r.getChangeId()).edit().create();
    gApi.changes()
        .id(r.getChangeId())
        .edit()
        .modifyFile(PushOneCommit.FILE_NAME, RawInputUtil.create("foo".getBytes(UTF_8)));
    try (Repository repo = repoManager.openRepository(r.getChange().change().getProject())) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(RefNames.refsEditPrefix(deleted.id())))
          .hasSize(1);

      gApi.accounts().self().delete();
    }

    // Reopen the repo to refresh RefDb
    try (Repository repo = repoManager.openRepository(r.getChange().change().getProject())) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(RefNames.refsEditPrefix(deleted.id())))
          .isEmpty();
    }

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_deletesDraftComments() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    PushOneCommit.Result r = createChange();

    requestScopeOperations.setApiUser(deleted.id());

    createDraft(r, PushOneCommit.FILE_NAME, "draft");
    assertThat(getUsersWithDraftsCount(r.getChange().getId())).isEqualTo(1);
    gApi.accounts().self().delete();

    assertThat(getUsersWithDraftsCount(r.getChange().getId())).isEqualTo(0);

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_removeUserFromGroups() throws Exception {
    TestAccount userAccount = accountCreator.create("user-remove-user-from-groups");
    TestAccount userAccount2 = accountCreator.create("user-2");
    AccountGroup.UUID engineers1Group =
        groupOperations
            .newGroup()
            .addMember(userAccount.id())
            .addMember(userAccount2.id())
            .create();
    AccountGroup.UUID engineers2Group =
        groupOperations.newGroup().addMember(userAccount.id()).create();

    assertThat(groupOperations.group(engineers1Group).get().members())
        .containsExactly(userAccount.id(), userAccount2.id());
    assertThat(groupOperations.group(engineers2Group).get().members())
        .containsExactly(userAccount.id());

    requestScopeOperations.setApiUser(userAccount.id());
    gApi.accounts().self().delete();

    requestScopeOperations.setApiUser(admin.id());
    assertThat(groupOperations.group(engineers1Group).get().members())
        .containsExactly(userAccount2.id());
    assertThat(groupOperations.group(engineers2Group).get().members()).isEmpty();

    // Clean up the test framework
    accountCreator.evict(userAccount.id());
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected int getUsersWithDraftsCount(Change.Id changeId) throws Exception {
    // The getStarredChangesCount and getUsersWithDraftsCount should be 2 distinct methods,
    // because in google they can query data from a different storage (i.e. not from noteDb).
    return getRefCount(RefNames.refsDraftCommentsPrefix(changeId));
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected int getStarredChangesCount(Change.Id changeId) throws Exception {
    // The getStarredChangesCount and getDraftsCommentsCount should be 2 distinct methods,
    // because in google they can query data from a different storage (i.e. not from noteDb).
    return getRefCount(RefNames.refsStarredChangesPrefix(changeId));
  }

  private int getRefCount(String refPrefix) throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers)) {
      return repo.getRefDatabase().getRefsByPrefix(refPrefix).size();
    }
  }

  @Test
  @SuppressWarnings("unused")
  public void deleteAccount_deletesReviewedFlags() throws Exception {
    PushOneCommit.Result r = createChange();
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    ReviewerInput in = new ReviewerInput();
    in.reviewer = deleted.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    requestScopeOperations.setApiUser(deleted.id());

    var unused =
        accountPatchReviewStore.markReviewed(
            r.getPatchSetId(), deleted.id(), PushOneCommit.FILE_NAME);
    assertThat(accountPatchReviewStore.findReviewed(r.getPatchSetId(), deleted.id())).isPresent();

    gApi.accounts().self().delete();

    assertThat(accountPatchReviewStore.findReviewed(r.getPatchSetId(), deleted.id())).isEmpty();

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_appliesForSelfById() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    requestScopeOperations.setApiUser(deleted.id());
    gApi.accounts().id(deleted.id().get()).delete();

    // Clean up the test framework
    accountCreator.evict(deleted.id());
  }

  @Test
  public void deleteAccount_throwsForOtherUsers() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.accounts().id(deleted.id().get()).delete());
    assertThat(thrown).hasMessageThat().isEqualTo("Delete account is only permitted for self");
  }

  @Test
  @GerritConfig(name = "accounts.enableDelete", value = "false")
  public void deleteAccount_throwsForSelfIfConfigenableDeleteIsDisabled() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    requestScopeOperations.setApiUser(deleted.id());
    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class, () -> gApi.accounts().id(deleted.id().get()).delete());
    assertThat(thrown).hasMessageThat().isEqualTo("Delete account is not enabled");
  }

  @Test
  @GerritConfig(name = "accounts.enableDelete", value = "false")
  public void deleteAccount_throwsForOthersIfConfigenableDeleteIsDisabled() throws Exception {
    TestAccount deleted = accountCreator.createValid(name("deleted"));
    requestScopeOperations.setApiUser(user.id());
    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class, () -> gApi.accounts().id(deleted.id().get()).delete());
    assertThat(thrown).hasMessageThat().isEqualTo("Delete account is not enabled");
  }

  @Test
  public void getOwnAccountState() throws Exception {
    String email = "preferred@example.com";
    String name = "Foo";
    String username = name("foo");
    TestAccount foo = accountCreator.create(username, email, name, null);
    String secondaryEmail = "secondary@non.google";
    EmailInput input = newEmailInput(secondaryEmail);
    gApi.accounts().id(foo.id().get()).addEmail(input);

    String status = "OOO";
    gApi.accounts().id(foo.id().get()).setStatus(status);

    String groupName = "SomeGroup";
    groupOperations.newGroup().name(groupName).addMember(foo.id()).create();

    TestAccountStateProvider testAccountStateProvider = new TestAccountStateProvider();
    MetadataInfo metadata1 = testAccountStateProvider.addMetadata("employee_id", "123456", null);
    MetadataInfo metadata2 = testAccountStateProvider.addMetadata("role", null, "role name");
    MetadataInfo metadata3 = testAccountStateProvider.addMetadata("team", "Bar", "team name");
    MetadataInfo metadata4 = testAccountStateProvider.addMetadata("team", "Foo", "team name");
    try (Registration registration =
        extensionRegistry.newRegistration().add(testAccountStateProvider)) {
      requestScopeOperations.setApiUser(foo.id());
      AccountStateInfo state = gApi.accounts().id(foo.id().get()).state();

      AccountDetailInfo detail = state.account;
      assertThat(detail._accountId).isEqualTo(foo.id().get());
      assertThat(detail.name).isEqualTo(name);
      if (server.isUsernameSupported()) {
        assertThat(detail.username).isEqualTo(username);
      }
      assertThat(detail.email).isEqualTo(email);
      assertThat(detail.secondaryEmails).containsExactly(secondaryEmail);
      assertThat(detail.status).isEqualTo(status);
      assertThat(detail.registeredOn.getTime())
          .isEqualTo(getAccount(foo.id()).registeredOn().toEpochMilli());
      assertThat(detail.inactive).isNull();
      assertThat(detail._moreAccounts).isNull();

      if (permissionBackend.usesDefaultCapabilities()) {
        AccountLimits limits = limitsFactory.create(genericUserFactory.create(foo.id()));
        GetCapabilities.Range queryLimitRange =
            new GetCapabilities.Range(limits.getRange("queryLimit"));
        assertThat(state.capabilities)
            .containsExactly("emailReviewers", true, "queryLimit", queryLimitRange);
      } else {
        assertThat(state.capabilities).isNull();
      }

      assertThat(state.groups)
          .comparingElementsUsing(getGroupToNameCorrespondence())
          .containsAtLeast("Anonymous Users", "Registered Users", groupName);

      assertExternalIds(
          state.externalIds.stream().map(e -> e.identity).collect(toImmutableSet()),
          ImmutableSet.of("mailto:" + email, "username:" + username, "mailto:" + secondaryEmail));

      // Using containsAtLeast instead of containsExcatly because when the test is run internally at
      // Google additional metadata is returned.
      assertThat(state.metadata)
          .containsAtLeast(metadata1, metadata2, metadata3, metadata4)
          .inOrder();
    }
  }

  @Test
  public void nonAdminCannotGetAccountStateOfOtherUser() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.accounts().id(admin.id().get()).state());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("cannot get account state of other user: administrate server not permitted");
  }

  @Test
  public void adminCanGetAccountStateOfOtherUser() throws Exception {
    AccountStateInfo state = gApi.accounts().id(user.id().get()).state();
    assertThat(state.account._accountId).isEqualTo(user.id().get());
  }

  @Test
  public void getAccountStateRequiresAuthentication() throws Exception {
    requestScopeOperations.setApiUserAnonymous();
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.accounts().id(user.id().get()).state());
    assertThat(thrown).hasMessageThat().isEqualTo("Authentication required");
  }

  private TestGroupBackend createTestGroupBackendWithAllUsersGroup(String nameOfAllUsersGroup)
      throws IOException {
    TestGroupBackend testGroupBackend = new TestGroupBackend();

    AccountGroup.UUID allUsersGroupUuid =
        testGroupBackend.create(nameOfAllUsersGroup).getGroupUUID();

    GroupMembership testGroupMembership =
        new GroupMembership() {
          @Override
          public Set<AccountGroup.UUID> intersection(Iterable<AccountGroup.UUID> groupUuids) {
            return StreamSupport.stream(groupUuids.spliterator(), /* parallel= */ false)
                .filter(this::contains)
                .collect(toSet());
          }

          @Override
          public ImmutableSet<AccountGroup.UUID> getKnownGroups() {
            // Typically for external group backends it's too expensive to query all groups that the
            // user is a member of. Instead limit the group membership check to groups that are
            // guessed to be relevant.
            return projectCache.guessRelevantGroupUUIDs().stream()
                // filter out groups of other group backends and groups of this group backend that
                // don't exist
                .filter(
                    uuid -> testGroupBackend.handles(uuid) && testGroupBackend.get(uuid) != null)
                .collect(toImmutableSet());
          }

          @Override
          public boolean containsAnyOf(Iterable<AccountGroup.UUID> groupUuids) {
            return StreamSupport.stream(groupUuids.spliterator(), /* parallel= */ false)
                .anyMatch(this::contains);
          }

          @Override
          public boolean contains(AccountGroup.UUID groupUuid) {
            return allUsersGroupUuid.equals(groupUuid);
          }
        };

    accounts
        .allIds()
        .forEach(accountId -> testGroupBackend.setMembershipsOf(accountId, testGroupMembership));

    return testGroupBackend;
  }

  private void assertExternalIds(Account.Id accountId, ImmutableSet<String> extIds)
      throws Exception {
    assertExternalIds(
        gApi.accounts().id(accountId.get()).getExternalIds().stream()
            .map(e -> e.identity)
            .collect(toImmutableSet()),
        extIds);
  }

  protected void assertExternalIds(
      ImmutableSet<String> actualExternalIds, ImmutableSet<String> expectedExternalIds)
      throws Exception {
    assertThat(actualExternalIds).isEqualTo(expectedExternalIds);
  }

  private void assertExternalEmails(Account.Id accountId, ImmutableSet<String> extIds)
      throws Exception {
    assertThat(
            gApi.accounts().id(accountId.get()).getExternalIds().stream()
                .map(e -> e.emailAddress)
                .filter(Objects::nonNull)
                .collect(toImmutableSet()))
        .isEqualTo(extIds);
  }

  private static Correspondence<GroupInfo, String> getGroupToNameCorrespondence() {
    return NullAwareCorrespondence.transforming(groupInfo -> groupInfo.name, "has name");
  }

  private void assertSequenceNumbers(List<SshKeyInfo> sshKeys) {
    int seq = 1;
    for (SshKeyInfo key : sshKeys) {
      assertThat(key.seq).isEqualTo(seq++);
    }
  }

  private PGPPublicKey getOnlyKeyFromStore(TestKey key) throws Exception {
    try (PublicKeyStore store = publicKeyStoreProvider.get()) {
      Iterable<PGPPublicKeyRing> keys = store.get(key.getKeyId());
      assertThat(keys).hasSize(1);
      return keys.iterator().next().getPublicKey();
    }
  }

  private static String armor(PGPPublicKey key) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
    try (ArmoredOutputStream aout = new ArmoredOutputStream(out)) {
      key.encode(aout);
    }
    return new String(out.toByteArray(), UTF_8);
  }

  private static void assertIteratorSize(int size, Iterator<?> it) {
    ImmutableList<?> lst = ImmutableList.copyOf(it);
    assertThat(lst).hasSize(size);
  }

  private static void assertKeyMapContains(TestKey expected, Map<String, GpgKeyInfo> actualMap) {
    GpgKeyInfo actual = actualMap.get(expected.getKeyIdString());
    assertThat(actual).isNotNull();
    assertThat(actual.id).isNull();
    actual.id = expected.getKeyIdString();
    assertKeyEquals(expected, actual);
  }

  private void assertKeys(TestKey... expectedKeys) throws Exception {
    assertKeys(Arrays.asList(expectedKeys));
  }

  private void assertKeys(Iterable<TestKey> expectedKeys) throws Exception {
    // Check via API.
    FluentIterable<TestKey> expected = FluentIterable.from(expectedKeys);
    Map<String, GpgKeyInfo> keyMap = gApi.accounts().self().listGpgKeys();
    assertWithMessage("keys returned by listGpgKeys()")
        .that(keyMap.keySet())
        .containsExactlyElementsIn(expected.transform(TestKey::getKeyIdString));

    for (TestKey key : expected) {
      assertKeyEquals(key, gApi.accounts().self().gpgKey(key.getKeyIdString()).get());
      assertKeyEquals(
          key,
          gApi.accounts()
              .self()
              .gpgKey(Fingerprint.toString(key.getPublicKey().getFingerprint()))
              .get());
      assertKeyMapContains(key, keyMap);
    }

    // Check raw external IDs.
    Account.Id currAccountId = localCtx.getContext().getUser().getAccountId();
    Iterable<String> expectedFps =
        expected.transform(k -> BaseEncoding.base16().encode(k.getPublicKey().getFingerprint()));
    Set<String> actualFps =
        getExternalIdsReader().byAccount(currAccountId, SCHEME_GPGKEY).stream()
            .map(e -> e.key().id())
            .collect(toSet());
    assertWithMessage("external IDs in database")
        .that(actualFps)
        .containsExactlyElementsIn(expectedFps);

    // Check raw stored keys.
    for (TestKey key : expected) {
      try (PublicKeyStore store = publicKeyStoreProvider.get()) {
        assertThat(store.get(key.getKeyId())).hasSize(1);
      }
    }
  }

  private static void assertKeyEquals(TestKey expected, GpgKeyInfo actual) {
    String id = expected.getKeyIdString();
    assertWithMessage(id).that(actual.id).isEqualTo(id);
    assertWithMessage(id)
        .that(actual.fingerprint)
        .isEqualTo(Fingerprint.toString(expected.getPublicKey().getFingerprint()));
    ImmutableList<String> userIds = ImmutableList.copyOf(expected.getPublicKey().getUserIDs());
    assertWithMessage(id).that(actual.userIds).containsExactlyElementsIn(userIds);
    String key = actual.key;
    assertWithMessage(id).that(key).startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----\n");
    assertWithMessage(id).that(key).endsWith("-----END PGP PUBLIC KEY BLOCK-----\n");
    assertThat(actual.status).isEqualTo(GpgKeyInfo.Status.TRUSTED);
    assertThat(actual.problems).isEmpty();
  }

  private void addExternalIdEmail(TestAccount account, String email) throws Exception {
    AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(accountIndexedCounter)) {
      requireNonNull(email);
      accountsUpdateProvider
          .get()
          .update(
              "Add Email",
              account.id(),
              u ->
                  u.addExternalId(
                      getExternalIdFactory()
                          .createWithEmail(name("test"), email, account.id(), email)));
      accountIndexedCounter.assertReindexOf(account);
      requestScopeOperations.setApiUser(account.id());
    }
  }

  @CanIgnoreReturnValue
  private Map<String, GpgKeyInfo> addGpgKey(String armored) throws Exception {
    return addGpgKey(admin, armored);
  }

  @CanIgnoreReturnValue
  private Map<String, GpgKeyInfo> addGpgKey(TestAccount account, String armored) throws Exception {
    return testRefAction(
        () -> {
          AccountIndexedCounter accountIndexedCounter = getAccountIndexedCounter();
          try (Registration registration =
              extensionRegistry.newRegistration().add(accountIndexedCounter)) {
            Map<String, GpgKeyInfo> gpgKeys =
                gApi.accounts()
                    .id(account.id().get())
                    .putGpgKeys(ImmutableList.of(armored), ImmutableList.<String>of());
            accountIndexedCounter.assertReindexOf(gApi.accounts().id(account.id().get()).get());
            return gpgKeys;
          }
        });
  }

  private Map<String, GpgKeyInfo> addGpgKeyNoReindex(String armored) throws Exception {
    return gApi.accounts().self().putGpgKeys(ImmutableList.of(armored), ImmutableList.of());
  }

  private void assertUser(AccountInfo info, TestAccount account) throws Exception {
    assertUser(info, account, null);
  }

  private void assertUser(AccountInfo info, TestAccount account, @Nullable String expectedStatus)
      throws Exception {
    assertThat(info.name).isEqualTo(account.fullName());
    assertThat(info.email).isEqualTo(account.email());
    if (server.isUsernameSupported()) {
      assertThat(info.username).isEqualTo(account.username());
    }
    assertThat(info.status).isEqualTo(expectedStatus);
  }

  private ImmutableSet<String> getEmails() throws RestApiException {
    return gApi.accounts().self().getEmails().stream().map(e -> e.email).collect(toImmutableSet());
  }

  private ImmutableSet<String> getExtIdsEmail() throws RestApiException {
    return gApi.accounts().self().getExternalIds().stream()
        .map(e -> e.emailAddress)
        .filter(Objects::nonNull)
        .collect(toImmutableSet());
  }

  private void assertEmail(Set<Account.Id> accounts, TestAccount expectedAccount) {
    assertThat(accounts).hasSize(1);
    assertThat(Iterables.getOnlyElement(accounts)).isEqualTo(expectedAccount.id());
  }

  private AccountApi accountIdApi() throws RestApiException {
    return gApi.accounts().id(user.id().get());
  }

  private Set<String> getCookiesNames() {
    Set<String> cookieNames =
        httpCookieStore.getCookies().stream()
            .map(cookie -> cookie.getName())
            .collect(Collectors.toSet());
    return cookieNames;
  }

  private void webLogin(Integer accountId) throws IOException, ClientProtocolException {
    httpGetAndAssertStatus(
        "login?account_id=" + accountId, HttpServletResponse.SC_MOVED_TEMPORARILY);
  }

  private AccountsUpdate getAccountsUpdateWithRunnables(
      Runnable afterReadRevision, Runnable beforeCommit) {
    return getAccountsUpdateWithRunnables(
        afterReadRevision,
        beforeCommit,
        new RetryHelper(
            cfg,
            retryMetrics,
            null,
            null,
            null,
            null,
            exceptionHooks,
            retryListeners,
            r -> r.withBlockStrategy(noSleepBlockStrategy)));
  }

  private ExternalIdNotes getExternalIdNotes(Repository allUsersRepo)
      throws ConfigInvalidException, IOException {
    return ExternalIdNotes.load(
        allUsers,
        allUsersRepo,
        externalIdFactoryNoteDbImpl,
        authConfig.isUserNameCaseInsensitiveMigrationMode());
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected ExternalIdFactory getExternalIdFactory() {
    return externalIdFactoryNoteDbImpl;
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected ExternalIds getExternalIdsReader() {
    return externalIdsNoteDbImpl;
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected AccountsUpdate getAccountsUpdateWithRunnables(
      Runnable afterReadRevision, Runnable beforeCommit, RetryHelper retryHelper) {
    return getAccountsUpdateNoteDbImplWithRunnables(afterReadRevision, beforeCommit, retryHelper);
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected final AccountsUpdateNoteDbImpl getAccountsUpdateNoteDbImplWithRunnables(
      Runnable afterReadRevision, Runnable beforeCommit, RetryHelper retryHelper) {
    return new AccountsUpdateNoteDbImpl(
        repoManager,
        gitReferenceUpdated,
        Optional.empty(),
        allUsers,
        externalIdsNoteDbImpl,
        extIdNotesFactory,
        metaDataUpdateInternalFactory,
        retryHelper,
        serverIdent.get(),
        afterReadRevision,
        beforeCommit);
  }

  private void httpGetAndAssertStatus(String urlPath, int expectedHttpStatus)
      throws ClientProtocolException, IOException {
    HttpGet httpGet = new HttpGet(canonicalWebUrl.get() + urlPath);
    HttpResponse loginResponse = httpclient.execute(httpGet);
    assertThat(loginResponse.getStatusLine().getStatusCode()).isEqualTo(expectedHttpStatus);
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected RefUpdateCounter createRefUpdateCounter() {
    return new RefUpdateCounter();
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  public static class RefUpdateCounter implements GitReferenceUpdatedListener {
    private final AtomicLongMap<String> countsByProjectRefs = AtomicLongMap.create();

    @UsedAt(UsedAt.Project.GOOGLE)
    public static String projectRef(Project.NameKey project, String ref) {
      return projectRef(project.get(), ref);
    }

    static String projectRef(String project, String ref) {
      return project + ":" + ref;
    }

    @Override
    public void onGitReferenceUpdated(Event event) {
      countsByProjectRefs.incrementAndGet(projectRef(event.getProjectName(), event.getRefName()));
    }

    void clear() {
      countsByProjectRefs.clear();
    }

    @UsedAt(UsedAt.Project.GOOGLE)
    public void assertRefUpdateFor(String... projectRefs) {
      Map<String, Long> expectedRefUpdateCounts = new HashMap<>();
      for (String projectRef : projectRefs) {
        expectedRefUpdateCounts.put(projectRef, 1L);
      }
      assertRefUpdateFor(expectedRefUpdateCounts);
    }

    protected void assertRefUpdateFor(Map<String, Long> expectedProjectRefUpdateCounts) {
      ImmutableMap<String, Long> exprectedFiltered =
          expectedProjectRefUpdateCounts.entrySet().stream()
              .filter(entry -> isRefSupported(entry.getKey()))
              .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
      assertThat(countsByProjectRefs.asMap()).containsExactlyEntriesIn(exprectedFiltered);
      clear();
    }

    @UsedAt(UsedAt.Project.GOOGLE)
    protected boolean isRefSupported(String expectedRefEntryKey) {
      return true;
    }
  }

  public static class TestAccountStateProvider implements AccountStateProvider {
    private ArrayList<MetadataInfo> metadataList = new ArrayList<>();

    public MetadataInfo addMetadata(
        String name, @Nullable String value, @Nullable String description) {
      MetadataInfo metadata = new MetadataInfo();
      metadata.name = name;
      metadata.value = value;
      metadata.description = description;
      metadataList.add(metadata);
      return metadata;
    }

    @Override
    public ImmutableList<MetadataInfo> getMetadata(Account.Id accountId) {
      return ImmutableList.copyOf(metadataList);
    }
  }
}
