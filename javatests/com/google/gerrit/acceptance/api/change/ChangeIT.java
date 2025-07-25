// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.acceptance.api.change;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.GitUtil.assertPushOk;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_CONTENT;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_NAME;
import static com.google.gerrit.acceptance.PushOneCommit.SUBJECT;
import static com.google.gerrit.acceptance.TestExtensions.TestCommitValidationListener;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.blockLabel;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.blockLabelRemoval;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.labelPermissionKey;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.permissionKey;
import static com.google.gerrit.entities.RefNames.changeMetaRef;
import static com.google.gerrit.extensions.client.ChangeStatus.ABANDONED;
import static com.google.gerrit.extensions.client.ChangeStatus.MERGED;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CHANGE_ACTIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CHECK;
import static com.google.gerrit.extensions.client.ListChangesOption.COMMIT_FOOTERS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_ACTIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;
import static com.google.gerrit.extensions.client.ListChangesOption.MESSAGES;
import static com.google.gerrit.extensions.client.ListChangesOption.PUSH_CERTIFICATES;
import static com.google.gerrit.extensions.client.ListChangesOption.REVIEWED;
import static com.google.gerrit.extensions.client.ListChangesOption.TRACKING_IDS;
import static com.google.gerrit.extensions.client.ReviewerState.CC;
import static com.google.gerrit.extensions.client.ReviewerState.REMOVED;
import static com.google.gerrit.extensions.client.ReviewerState.REVIEWER;
import static com.google.gerrit.server.group.SystemGroupBackend.ANONYMOUS_USERS;
import static com.google.gerrit.server.group.SystemGroupBackend.CHANGE_OWNER;
import static com.google.gerrit.server.group.SystemGroupBackend.PROJECT_OWNERS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.server.project.ProjectConfig.RULES_PL_FILE;
import static com.google.gerrit.server.project.testing.TestLabels.label;
import static com.google.gerrit.server.project.testing.TestLabels.value;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.google.gerrit.testing.TestActionRefUpdateContext.openTestRefUpdateContext;
import static com.google.gerrit.truth.CacheStatsSubject.assertThat;
import static com.google.gerrit.truth.CacheStatsSubject.cloneStats;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.truth.ThrowableSubject;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ChangeIndexedCounter;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.UseClockStep;
import com.google.gerrit.acceptance.UseTimezone;
import com.google.gerrit.acceptance.VerifyNoPiiInChangeNotes;
import com.google.gerrit.acceptance.api.change.ChangeIT.TestAttentionSetListenerModule.TestAttentionSetListener;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.server.change.CommentsUtil;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.change.ChangeOperations;
import com.google.gerrit.acceptance.testsuite.change.IndexOperations;
import com.google.gerrit.acceptance.testsuite.change.TestChange;
import com.google.gerrit.acceptance.testsuite.group.GroupOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.EmailHeader.StringEmailHeader;
import com.google.gerrit.entities.LabelFunction;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.LabelType;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.api.accounts.DeleteDraftCommentsInput;
import com.google.gerrit.extensions.api.changes.AttentionSetInput;
import com.google.gerrit.extensions.api.changes.ChangeIdentifier;
import com.google.gerrit.extensions.api.changes.DeleteReviewerInput;
import com.google.gerrit.extensions.api.changes.DeleteVoteInput;
import com.google.gerrit.extensions.api.changes.DraftApi;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.NotifyInfo;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.extensions.api.changes.RelatedChangesInfo;
import com.google.gerrit.extensions.api.changes.RevertInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.DraftHandling;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.api.changes.ReviewerInfo;
import com.google.gerrit.extensions.api.changes.ReviewerInput;
import com.google.gerrit.extensions.api.changes.ReviewerResult;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.groups.GroupApi;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.ProjectInput;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.Comment.Range;
import com.google.gerrit.extensions.client.InheritableBoolean;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.CommitMessageInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.common.TrackingIdInfo;
import com.google.gerrit.extensions.events.AttentionSetListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.git.ObjectIds;
import com.google.gerrit.httpd.raw.IndexPreloadingUtil;
import com.google.gerrit.index.IndexConfig;
import com.google.gerrit.index.query.PostFilterPredicate;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.ChangeMessages;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.index.change.ChangeIndex;
import com.google.gerrit.server.index.change.ChangeIndexCollection;
import com.google.gerrit.server.index.change.IndexedChangeQuery;
import com.google.gerrit.server.patch.DiffSummary;
import com.google.gerrit.server.patch.DiffSummaryKey;
import com.google.gerrit.server.patch.IntraLineDiff;
import com.google.gerrit.server.patch.IntraLineDiffKey;
import com.google.gerrit.server.project.testing.TestLabels;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeNumberVirtualIdAlgorithm;
import com.google.gerrit.server.query.change.ChangeQueryBuilder.ChangeOperatorFactory;
import com.google.gerrit.server.restapi.change.PostReview;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.AccountTemplateUtil;
import com.google.gerrit.server.util.CommitMessageUtil;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.gerrit.testing.FakeEmailSender.Message;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Test;

@NoHttpd
@UseTimezone(timezone = "US/Eastern")
@VerifyNoPiiInChangeNotes(true)
public class ChangeIT extends AbstractDaemonTest {
  @Inject private AccountOperations accountOperations;
  @Inject private ChangeIndexCollection changeIndexCollection;
  @Inject private GroupOperations groupOperations;
  @Inject private IndexConfig indexConfig;
  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private IndexOperations.Change changeIndexOperations;
  @Inject private AccountControl.Factory accountControlFactory;
  @Inject private ChangeOperations changeOperations;

  @Inject
  @Named("diff_intraline")
  private Cache<IntraLineDiffKey, IntraLineDiff> intraCache;

  @Inject
  @Named("diff_summary")
  private Cache<DiffSummaryKey, DiffSummary> diffSummaryCache;

  @Inject private ChangeNumberVirtualIdAlgorithm changeNumberVirtualIdAlgorithm;

  @Test
  public void get() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();

    ChangeInfo changeInfo = gApi.changes().id(change.id()).info();

    assertThat(changeInfo.id).isEqualTo(change.project() + "~" + change.numericChangeId());
    assertThat(changeInfo.project).isEqualTo(change.project().get());
    assertThat(changeInfo.branch).isEqualTo(change.dest().shortName());
    assertThat(changeInfo.status).isEqualTo(ChangeStatus.NEW);
    assertThat(changeInfo.subject).isEqualTo(change.subject());
    assertThat(changeInfo.submitType).isEqualTo(SubmitType.MERGE_IF_NECESSARY);
    assertThat(changeInfo.mergeable).isNull();
    assertThat(changeInfo.changeId).isEqualTo(change.changeId());
    assertThat(changeInfo._number).isEqualTo(change.numericChangeId().get());
    assertThat(changeInfo.currentRevisionNumber)
        .isEqualTo(changeOperations.change(change.id()).currentPatchset().get().patchsetId().get());

    // With NoteDb timestamps are rounded to seconds.
    assertThat(changeInfo.created)
        .isEqualTo(Timestamp.from(change.createdOn().truncatedTo(ChronoUnit.SECONDS)));
    assertThat(changeInfo.created).isEqualTo(changeInfo.updated);

    assertThat(changeInfo.owner._accountId).isEqualTo(change.owner().get());
    assertThat(changeInfo.owner.name).isNull();
    assertThat(changeInfo.owner.email).isNull();
    assertThat(changeInfo.owner.username).isNull();
    assertThat(changeInfo.owner.avatars).isNull();
    assertThat(changeInfo.submissionId).isNull();
  }

  @Test
  public void cannotGetInvisibleChange() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();

    // Remove read access
    projectOperations
        .project(change.project())
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    requestScopeOperations.setApiUser(user.id());
    ResourceNotFoundException thrown =
        assertThrows(ResourceNotFoundException.class, () -> gApi.changes().id(change.id()).get());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Not found: %s~%d", change.project().get(), change.numericChangeId().get()));
  }

  @Test
  public void adminCanGetChangeWithoutExplicitReadPermission() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();

    // Remove read access
    projectOperations
        .project(change.project())
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    requestScopeOperations.setApiUser(admin.id());
    ChangeInfo changeInfo = gApi.changes().id(change.id()).get();
    assertThat(changeInfo.id)
        .isEqualTo(String.format("%s~%d", change.project().get(), change.numericChangeId().get()));
  }

  @Test
  public void diffStatShouldComputeInsertionsAndDeletions() throws Exception {
    ChangeIdentifier changeIdentifier =
        changeOperations
            .newChange()
            .file("a_new_file.txt")
            .content("First line\nSecond line\n")
            .create();

    ChangeInfo changeInfo = gApi.changes().id(changeIdentifier).get();
    assertThat(changeInfo.insertions).isEqualTo(2);
    assertThat(changeInfo.deletions).isEqualTo(0);
  }

  @Test
  public void diffStatShouldSkipInsertionsAndDeletions() throws Exception {
    ChangeIdentifier changeIdentifier =
        changeOperations
            .newChange()
            .file("a_new_file.txt")
            .content("First line\nSecond line\n")
            .create();
    ChangeInfo changeInfo =
        gApi.changes().id(changeIdentifier).get(ImmutableList.of(ListChangesOption.SKIP_DIFFSTAT));
    assertThat(changeInfo.insertions).isNull();
    assertThat(changeInfo.deletions).isNull();
  }

  @Test
  public void skipDiffstatOptionAvoidsAllDiffComputations() throws Exception {
    ChangeIdentifier changeIdentifier =
        changeOperations
            .newChange()
            .file("a_new_file.txt")
            .content("First line\nSecond line\n")
            .create();

    CacheStats startIntra = cloneStats(intraCache.stats());
    CacheStats startSummary = cloneStats(diffSummaryCache.stats());

    @SuppressWarnings("unused")
    var unused =
        gApi.changes().id(changeIdentifier).get(ImmutableList.of(ListChangesOption.SKIP_DIFFSTAT));

    assertThat(intraCache.stats()).since(startIntra).hasMissCount(0);
    assertThat(intraCache.stats()).since(startIntra).hasHitCount(0);
    assertThat(diffSummaryCache.stats()).since(startSummary).hasMissCount(0);
    assertThat(diffSummaryCache.stats()).since(startSummary).hasHitCount(0);
  }

  @Test
  @GerritConfig(name = "change.mergeabilityComputationBehavior", value = "NEVER")
  public void excludeMergeableInChangeInfo() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    ChangeInfo c = gApi.changes().id(changeIdentifier).get();
    assertThat(c.mergeable).isNull();
  }

  @Test
  public void getSubmissionId() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    assertThat(gApi.changes().id(changeIdentifier).get().submissionId).isNull();

    gApi.changes().id(changeIdentifier).current().review(ReviewInput.approve());
    gApi.changes().id(changeIdentifier).current().submit();

    assertThat(gApi.changes().id(changeIdentifier).get().submissionId).isNotNull();
  }

  @Test
  public void setWorkInProgressNotAllowedWithoutPermission() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeIdentifier).setWorkInProgress());
    assertThat(thrown).hasMessageThat().contains("toggle work in progress state not permitted");
  }

  @Test
  public void setWorkInProgressAllowedAsAdmin() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeIdentifier).setWorkInProgress();
    assertThat(gApi.changes().id(changeIdentifier).get().workInProgress).isTrue();
  }

  @Test
  public void setWorkInProgressAllowedAsProjectOwner() throws Exception {
    TestChange change = changeOperations.newChange().owner(user.id()).createAndGet();

    com.google.gerrit.acceptance.TestAccount user2 = accountCreator.user2();
    projectOperations
        .project(change.project())
        .forUpdate()
        .add(allow(Permission.OWNER).ref("refs/*").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user2.id());
    gApi.changes().id(change.id()).setWorkInProgress();
    assertThat(gApi.changes().id(change.id()).get().workInProgress).isTrue();
  }

  @Test
  public void createWipChangeWithWorkInProgressByDefaultForProject() throws Exception {
    projectOperations.project(project).forUpdate().workInProgressByDefault().update();

    String changeId =
        gApi.changes().create(new ChangeInput(project.get(), "master", "Test Change")).get().id;
    assertThat(gApi.changes().id(changeId).get().workInProgress).isTrue();
  }

  @Test
  public void setReadyForReviewNotAllowedWithoutPermission() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    gApi.changes().id(changeIdentifier).setWorkInProgress();

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeIdentifier).setReadyForReview());
    assertThat(thrown).hasMessageThat().contains("toggle work in progress state not permitted");
  }

  @Test
  public void setReadyForReviewAllowedAsAdmin() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();
    gApi.changes().id(changeIdentifier).setWorkInProgress();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeIdentifier).setReadyForReview();
    assertThat(gApi.changes().id(changeIdentifier).get().workInProgress).isNull();
  }

  @Test
  public void setReadyForReviewAllowedAsProjectOwner() throws Exception {
    TestChange change = changeOperations.newChange().owner(user.id()).createAndGet();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(change.id()).setWorkInProgress();

    TestAccount user2 = accountCreator.user2();
    projectOperations
        .project(change.project())
        .forUpdate()
        .add(allow(Permission.OWNER).ref("refs/*").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user2.id());
    gApi.changes().id(change.id()).setReadyForReview();
    assertThat(gApi.changes().id(change.id()).get().workInProgress).isNull();
  }

  @Test
  public void setReadyForReviewSendsNotificationsForRevertChange() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    gApi.changes().id(changeIdentifier).current().review(ReviewInput.approve());
    gApi.changes().id(changeIdentifier).current().submit();
    RevertInput in = new RevertInput();
    in.workInProgress = true;
    String changeId = gApi.changes().id(changeIdentifier).revert(in).get().changeId;
    requestScopeOperations.setApiUser(admin.id());

    gApi.changes().id(changeId).setReadyForReview();

    assertThat(gApi.changes().id(changeId).get().workInProgress).isNull();
    // expected messages on source change:
    // 1. Uploaded patch set 1.
    // 2. Patch Set 1: Code-Review+2
    // 3. Change has been successfully merged by Administrator
    // 4. Patch Set 1: Reverted
    List<ChangeMessageInfo> sourceMessages =
        new ArrayList<>(gApi.changes().id(changeIdentifier).get().messages);
    assertThat(sourceMessages).hasSize(4);
    String expectedMessage = String.format("Created a revert of this change as %s", changeId);
    assertThat(sourceMessages.get(3).message).isEqualTo(expectedMessage);
  }

  @Test
  public void hasReviewStarted() throws Exception {
    PushOneCommit.Result r = createWorkInProgressChange();
    String changeId = r.getChangeId();
    ChangeInfo info = gApi.changes().id(changeId).get();
    assertThat(info.hasReviewStarted).isFalse();

    gApi.changes().id(changeId).setReadyForReview();
    info = gApi.changes().id(changeId).get();
    assertThat(info.hasReviewStarted).isTrue();
  }

  @Test
  public void pendingReviewers() throws Exception {
    projectOperations.project(project).forUpdate().enableReviewerByEmail().update();

    ChangeIdentifier changeIdentifier =
        changeOperations.newChange().project(project).owner(admin.id()).create();
    gApi.changes().id(changeIdentifier).setWorkInProgress();
    assertThat(gApi.changes().id(changeIdentifier).get().pendingReviewers).isEmpty();

    // Add some pending reviewers.
    String email1 = name("user1") + "@example.com";
    String email2 = name("user2") + "@example.com";
    String email3 = name("user3") + "@example.com";
    String email4 = name("user4") + "@example.com";
    accountOperations
        .newAccount()
        .username(name("user1"))
        .preferredEmail(email1)
        .fullname("User1")
        .create();
    accountOperations
        .newAccount()
        .username(name("user2"))
        .preferredEmail(email2)
        .fullname("User2")
        .create();
    accountOperations
        .newAccount()
        .username(name("user3"))
        .preferredEmail(email3)
        .fullname("User3")
        .create();
    accountOperations
        .newAccount()
        .username(name("user4"))
        .preferredEmail(email4)
        .fullname("User4")
        .create();
    ReviewInput in =
        ReviewInput.noScore()
            .reviewer(email1)
            .reviewer(email2)
            .reviewer(email3, CC, false)
            .reviewer(email4, CC, false)
            .reviewer("byemail1@example.com")
            .reviewer("byemail2@example.com")
            .reviewer("byemail3@example.com", CC, false)
            .reviewer("byemail4@example.com", CC, false);
    ReviewResult result = gApi.changes().id(changeIdentifier).current().review(in);
    assertThat(result.changeInfo).isNotNull();
    assertThat(result.reviewers).isNotEmpty();
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    Function<Collection<AccountInfo>, Collection<String>> toEmails =
        ais -> ais.stream().map(ai -> ai.email).collect(toSet());
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(email1, email2, "byemail1@example.com", "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email3, email4, "byemail3@example.com", "byemail4@example.com");
    assertThat(info.pendingReviewers.get(REMOVED)).isNull();

    // Stage some pending reviewer removals.
    gApi.changes().id(changeIdentifier).reviewer(email1).remove();
    gApi.changes().id(changeIdentifier).reviewer(email3).remove();
    gApi.changes().id(changeIdentifier).reviewer("byemail1@example.com").remove();
    gApi.changes().id(changeIdentifier).reviewer("byemail3@example.com").remove();
    info = gApi.changes().id(changeIdentifier).get();
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(REMOVED)))
        .containsExactly(email1, email3, "byemail1@example.com", "byemail3@example.com");

    // "Undo" a removal.
    in = ReviewInput.noScore().reviewer(email1);
    gApi.changes().id(changeIdentifier).current().review(in);
    info = gApi.changes().id(changeIdentifier).get();
    assertThat(toEmails.apply(info.pendingReviewers.get(REVIEWER)))
        .containsExactly(email1, email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(toEmails.apply(info.pendingReviewers.get(REMOVED)))
        .containsExactly(email3, "byemail1@example.com", "byemail3@example.com");

    // "Commit" by moving out of WIP.
    gApi.changes().id(changeIdentifier).setReadyForReview();
    info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.pendingReviewers).isEmpty();
    assertThat(toEmails.apply(info.reviewers.get(REVIEWER)))
        .containsExactly(email1, email2, "byemail2@example.com");
    assertThat(toEmails.apply(info.reviewers.get(CC)))
        .containsExactly(email4, "byemail4@example.com");
    assertThat(info.reviewers.get(REMOVED)).isNull();
  }

  @Test
  public void toggleWorkInProgressState() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    // With message
    gApi.changes().id(changeIdentifier).setWorkInProgress("Needs some refactoring");

    ChangeInfo info = gApi.changes().id(changeIdentifier).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).contains("Needs some refactoring");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(changeIdentifier).setReadyForReview("PTAL");

    info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).contains("PTAL");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);

    // No message
    gApi.changes().id(changeIdentifier).setWorkInProgress();

    info = gApi.changes().id(changeIdentifier).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).isEqualTo("Set Work In Progress");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(changeIdentifier).setReadyForReview();

    info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).isEqualTo("Set Ready For Review");
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);
  }

  @Test
  public void toggleWorkInProgressStateByNonOwnerWithPermission() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();
    String refactor = "Needs some refactoring";
    String ptal = "PTAL";

    projectOperations
        .project(change.project())
        .forUpdate()
        .add(
            allow(Permission.TOGGLE_WORK_IN_PROGRESS_STATE)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(change.id()).setWorkInProgress(refactor);

    ChangeInfo info = gApi.changes().id(change.id()).get();

    assertThat(info.workInProgress).isTrue();
    assertThat(Iterables.getLast(info.messages).message).contains(refactor);
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_WIP);

    gApi.changes().id(change.id()).setReadyForReview(ptal);

    info = gApi.changes().id(change.id()).get();
    assertThat(info.workInProgress).isNull();
    assertThat(Iterables.getLast(info.messages).message).contains(ptal);
    assertThat(Iterables.getLast(info.messages).tag).contains(ChangeMessagesUtil.TAG_SET_READY);
  }

  @Test
  public void reviewAndStartReview() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    gApi.changes().id(changeIdentifier).setWorkInProgress();

    ReviewInput in = ReviewInput.noScore().setWorkInProgress(false);
    ReviewResult result = gApi.changes().id(changeIdentifier).current().review(in);
    assertThat(result.ready).isTrue();

    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isNull();
  }

  @Test
  public void reviewAndMoveToWorkInProgress() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    ReviewResult result = gApi.changes().id(changeIdentifier).current().review(in);
    assertThat(result.ready).isNull();

    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewAndSetWorkInProgressAndAddReviewerAndVote() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    ReviewInput in =
        ReviewInput.approve()
            .reviewer(user.email())
            .label(LabelId.CODE_REVIEW, 1)
            .setWorkInProgress(true);
    gApi.changes().id(changeIdentifier).current().review(in);

    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isTrue();
    assertThat(info.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(admin.id().get(), user.id().get());
    assertThat(info.labels.get(LabelId.CODE_REVIEW).recommended._accountId)
        .isEqualTo(admin.id().get());
  }

  @Test
  public void reviewRemoveInactiveReviewer() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    ReviewInput in = ReviewInput.approve().reviewer(user.email());
    gApi.changes().id(changeIdentifier).current().review(in);

    accountOperations.account(user.id()).forUpdate().inactive().update();
    in = ReviewInput.noScore().reviewer(Integer.toString(user.id().get()), REMOVED, false);

    gApi.changes().id(changeIdentifier).current().review(in);
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(admin.id().get());
  }

  @Test
  public void removeReviewerWithoutPermissionsOnChangePostReview_allowed() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    ReviewInput in = ReviewInput.approve().reviewer(user.email());
    gApi.changes().id(changeIdentifier).current().review(in);
    AccountGroup.UUID restrictedGroup =
        groupOperations.newGroup().name("restricted-group").addMember(user.id()).create();

    // revoke permissions to see the change from the reviewer
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/*").group(restrictedGroup))
        .update();

    in = ReviewInput.noScore().reviewer(Integer.toString(user.id().get()), REMOVED, false);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeIdentifier).current().review(in);
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(admin.id().get());
  }

  @Test
  public void removeReviewerWithoutPermissionsOnChange_allowed() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    ReviewInput in = ReviewInput.approve().reviewer(user.email());
    gApi.changes().id(changeIdentifier).current().review(in);
    AccountGroup.UUID restrictedGroup =
        groupOperations.newGroup().name("restricted-group").addMember(user.id()).create();

    // revoke permissions to see the change from the reviewer
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/*").group(restrictedGroup))
        .update();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeIdentifier).reviewer(user.id().toString()).remove();
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(admin.id().get());
  }

  @Test
  public void reviewWithWorkInProgressAndReadyReturnsError() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    ReviewInput in = ReviewInput.noScore();
    in.ready = true;
    in.workInProgress = true;
    ReviewResult result = gApi.changes().id(changeIdentifier).current().review(in);
    assertThat(result.error).isEqualTo(PostReview.ERROR_WIP_READY_MUTUALLY_EXCLUSIVE);
  }

  @Test
  public void reviewWithWorkInProgressChangeOwner() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    requestScopeOperations.setApiUser(user.id());
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    gApi.changes().id(changeIdentifier).current().review(in);
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewWithWithWorkInProgressAdmin() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    requestScopeOperations.setApiUser(admin.id());
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    gApi.changes().id(changeIdentifier).current().review(in);
    ChangeInfo info = gApi.changes().id(changeIdentifier).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewWithWorkInProgressByNonOwnerReturnsError() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeIdentifier).current().review(in));
    assertThat(thrown).hasMessageThat().contains("toggle work in progress state not permitted");
  }

  @Test
  public void reviewWithWorkInProgressByNonOwnerWithPermission() throws Exception {
    TestChange change = changeOperations.newChange().owner(admin.id()).createAndGet();
    ReviewInput in = ReviewInput.noScore().setWorkInProgress(true);
    projectOperations
        .project(change.project())
        .forUpdate()
        .add(
            allow(Permission.TOGGLE_WORK_IN_PROGRESS_STATE)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(change.id()).current().review(in);
    ChangeInfo info = gApi.changes().id(change.id()).get();
    assertThat(info.workInProgress).isTrue();
  }

  @Test
  public void reviewWithReadyByNonOwnerReturnsError() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();
    gApi.changes().id(changeIdentifier).setWorkInProgress();

    ReviewInput in = ReviewInput.noScore().setReady(true);
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeIdentifier).current().review(in));
    assertThat(thrown).hasMessageThat().contains("toggle work in progress state not permitted");
  }

  @Test
  public void getAmbiguous() throws Exception {
    TestChange change =
        changeOperations.newChange().project(project).branch("master").createAndGet();

    @SuppressWarnings("unused")
    var unused = gApi.changes().id(change.id()).get();

    BranchInput b = new BranchInput();
    b.revision = repo().exactRef("HEAD").getObjectId().name();
    gApi.projects().name(project.get()).branch("other").create(b);

    TestChange otherChange =
        changeOperations
            .newChange()
            .project(project)
            .branch("other")
            .commitMessage("Summary line\n\nChange-Id: " + change.changeId())
            .createAndGet();
    assertThat(otherChange.changeId()).isEqualTo(change.changeId());

    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class, () -> gApi.changes().id(change.changeId()).get());
    assertThat(thrown).hasMessageThat().contains("Multiple changes found for " + change.changeId());
  }

  @Test
  public void deleteNewChangeAsAdmin() throws Exception {
    deleteChangeAsUser(admin, admin);
  }

  @Test
  public void deleteNewChangeAsNormalUser() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.changes().id(changeIdentifier).delete());
    assertThat(thrown).hasMessageThat().contains("delete not permitted");
  }

  @Test
  public void deleteNewChangeAsUserWithDeleteChangesPermissionForGroup() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.DELETE_CHANGES).ref("refs/*").group(REGISTERED_USERS))
        .update();
    deleteChangeAsUser(project, admin, user);
  }

  @Test
  public void deleteNewChangeAsUserWithDeleteChangesPermissionForProjectOwners() throws Exception {
    GroupApi groupApi = gApi.groups().create(name("delete-change"));
    groupApi.addMembers("user1");

    Project.NameKey nameKey = Project.nameKey(name("delete-change"));
    ProjectInput in = new ProjectInput();
    in.name = nameKey.get();
    in.owners = Lists.newArrayListWithCapacity(1);
    in.owners.add(groupApi.name());
    in.createEmptyCommit = true;
    gApi.projects().create(in);

    projectOperations
        .project(nameKey)
        .forUpdate()
        .add(allow(Permission.DELETE_CHANGES).ref("refs/*").group(PROJECT_OWNERS))
        .update();

    deleteChangeAsUser(nameKey, admin, user);
  }

  @Test
  public void deleteChangeAsUserWithDeleteOwnChangesPermissionForGroup() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.DELETE_OWN_CHANGES).ref("refs/*").group(REGISTERED_USERS))
        .update();
    deleteChangeAsUser(project, user, user);
  }

  @Test
  public void deleteChangeAsUserWithDeleteOwnChangesPermissionForOwners() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.DELETE_OWN_CHANGES).ref("refs/*").group(CHANGE_OWNER))
        .update();
    deleteChangeAsUser(project, user, user);
  }

  private void deleteChangeAsUser(
      com.google.gerrit.acceptance.TestAccount owner,
      com.google.gerrit.acceptance.TestAccount deleteAs)
      throws Exception {
    deleteChangeAsUser(project, owner, deleteAs);
  }

  private void deleteChangeAsUser(
      Project.NameKey projectName,
      com.google.gerrit.acceptance.TestAccount owner,
      com.google.gerrit.acceptance.TestAccount deleteAs)
      throws Exception {
    try {
      projectOperations
          .project(projectName)
          .forUpdate()
          .add(allow(Permission.VIEW_PRIVATE_CHANGES).ref("refs/*").group(ANONYMOUS_USERS))
          .update();
      TestChange change =
          changeOperations.newChange().project(projectName).owner(owner.id()).createAndGet();
      requestScopeOperations.setApiUser(owner.id());

      String commit =
          changeOperations.change(change.id()).currentPatchset().get().commitId().getName();

      assertThat(gApi.changes().id(change.id()).info().owner._accountId)
          .isEqualTo(owner.id().get());

      requestScopeOperations.setApiUser(deleteAs.id());
      gApi.changes().id(change.id()).delete();

      assertThat(query(change.id().id())).isEmpty();

      String ref = change.numericChangeId().toRefPrefix() + "1";
      eventRecorder.assertRefUpdatedEvents(projectName.get(), ref, null, commit, commit, null);
      eventRecorder.assertChangeDeletedEvents(change.changeId(), deleteAs.email());
    } finally {
      projectOperations
          .project(project)
          .forUpdate()
          .remove(permissionKey(Permission.DELETE_OWN_CHANGES).ref("refs/*"))
          .remove(permissionKey(Permission.DELETE_CHANGES).ref("refs/*"))
          .update();
    }
  }

  @Test
  public void deleteNewChangeOfAnotherUserAsAdmin() throws Exception {
    deleteChangeAsUser(user, admin);
  }

  @Test
  public void deleteNewChangeOfAnotherUserWithDeleteOwnChangesPermission() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.DELETE_OWN_CHANGES).ref("refs/*").group(REGISTERED_USERS))
        .update();

    try {
      ChangeIdentifier changeIdentifier = changeOperations.newChange().project(project).create();

      requestScopeOperations.setApiUser(user.id());
      AuthException thrown =
          assertThrows(AuthException.class, () -> gApi.changes().id(changeIdentifier).delete());
      assertThat(thrown).hasMessageThat().contains("delete not permitted");
    } finally {
      projectOperations
          .project(project)
          .forUpdate()
          .remove(permissionKey(Permission.DELETE_OWN_CHANGES).ref("refs/*"))
          .update();
    }
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void deleteNewChangeForBranchWithoutCommits() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().project(project).create();

    gApi.changes().id(changeIdentifier).delete();

    assertThat(query(changeIdentifier.id())).isEmpty();
  }

  @Test
  public void deleteAbandonedChangeAsNormalUser() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeIdentifier).abandon();

    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.changes().id(changeIdentifier).delete());
    assertThat(thrown).hasMessageThat().contains("delete not permitted");
  }

  @Test
  public void deleteAbandonedChangeOfAnotherUserAsAdmin() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(user.id()).create();

    gApi.changes().id(changeIdentifier).abandon();

    gApi.changes().id(changeIdentifier).delete();

    assertThat(query(changeIdentifier.id())).isEmpty();
  }

  @Test
  public void deleteMergedChange() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();

    gApi.changes().id(changeIdentifier).current().review(ReviewInput.approve());
    gApi.changes().id(changeIdentifier).current().submit();

    MethodNotAllowedException thrown =
        assertThrows(
            MethodNotAllowedException.class, () -> gApi.changes().id(changeIdentifier).delete());
    assertThat(thrown).hasMessageThat().contains("delete not permitted");
  }

  @Test
  public void deleteMergedChangeWithDeleteOwnChangesPermission() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.DELETE_OWN_CHANGES).ref("refs/*").group(REGISTERED_USERS))
        .update();

    try {
      ChangeIdentifier changeIdentifier = changeOperations.newChange().project(project).create();

      gApi.changes().id(changeIdentifier).current().review(ReviewInput.approve());
      gApi.changes().id(changeIdentifier).current().submit();

      requestScopeOperations.setApiUser(user.id());
      MethodNotAllowedException thrown =
          assertThrows(
              MethodNotAllowedException.class, () -> gApi.changes().id(changeIdentifier).delete());
      assertThat(thrown).hasMessageThat().contains("delete not permitted");
    } finally {
      projectOperations
          .project(project)
          .forUpdate()
          .remove(permissionKey(Permission.DELETE_OWN_CHANGES).ref("refs/*"))
          .update();
    }
  }

  @Test
  public void deleteNewChangeWithMergedPatchSet() throws Exception {
    TestChange change = changeOperations.newChange().project(project).createAndGet();

    gApi.changes().id(change.id()).current().review(ReviewInput.approve());
    gApi.changes().id(change.id()).current().submit();

    setChangeStatus(change.numericChangeId(), Change.Status.NEW);

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> gApi.changes().id(change.id()).delete());
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Cannot delete change %s: patch set 1 is already merged",
                change.numericChangeId()));
  }

  @Test
  public void deleteChangeUpdatesIndex() throws Exception {
    TestChange change = changeOperations.newChange().project(project).createAndGet();

    ChangeIndex idx = changeIndexCollection.getSearchIndex();

    Optional<ChangeData> result =
        idx.get(
            change.numericChangeId(),
            IndexedChangeQuery.createOptions(indexConfig, 0, 1, ImmutableSet.of()));

    assertThat(result).isPresent();
    gApi.changes().id(change.id()).delete();
    result =
        idx.get(
            change.numericChangeId(),
            IndexedChangeQuery.createOptions(indexConfig, 0, 1, ImmutableSet.of()));
    assertThat(result).isEmpty();
  }

  @Test
  public void deleteChangeRemovesDraftComment() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();

    requestScopeOperations.setApiUser(user.id());

    DraftInput dri = new DraftInput();
    dri.message = "hello";
    dri.path = "a.txt";
    dri.line = 1;

    gApi.changes().id(change.id()).current().createDraft(dri);

    assertThat(getDraftsCountForChange(change.numericChangeId(), user.id())).isGreaterThan(0);

    requestScopeOperations.setApiUser(admin.id());

    gApi.changes().id(change.id()).delete();
    assertThat(getDraftsCountForChange(change.numericChangeId(), user.id())).isEqualTo(0);
  }

  @UsedAt(UsedAt.Project.GOOGLE)
  protected int getDraftsCountForChange(Change.Id changeId, Account.Id accountId) throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers)) {
      return repo.getRefDatabase()
          .getRefsByPrefix(RefNames.refsDraftComments(changeId, accountId))
          .size();
    }
  }

  @Test
  public void deleteChangeRemovesItsChangeEdit() throws Exception {
    TestChange change = changeOperations.newChange().createAndGet();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(change.id()).edit().create();
    gApi.changes()
        .id(change.id())
        .edit()
        .modifyFile(FILE_NAME, RawInputUtil.create("foo".getBytes(UTF_8)));

    requestScopeOperations.setApiUser(admin.id());
    String expected = RefNames.refsUsers(user.id()) + "/edit-" + change.numericChangeId() + "/1";
    try (Repository repo = repoManager.openRepository(change.project())) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(expected)).isNotEmpty();
      gApi.changes().id(change.id()).delete();
    }
    // On google infra, repo should be reopened for getting updated refs.
    try (Repository repo = repoManager.openRepository(change.project())) {
      assertThat(repo.getRefDatabase().getRefsByPrefix(expected)).isEmpty();
    }
  }

  @Test
  public void deleteChangeDoesntRemoveOtherChangeEdits() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    ChangeIdentifier irrelevanetChangeIdentifier = changeOperations.newChange().create();

    requestScopeOperations.setApiUser(admin.id());

    gApi.changes().id(irrelevanetChangeIdentifier).edit().create();
    gApi.changes()
        .id(irrelevanetChangeIdentifier)
        .edit()
        .modifyFile(FILE_NAME, RawInputUtil.create("foo".getBytes(UTF_8)));

    gApi.changes().id(changeIdentifier).delete();

    assertThat(gApi.changes().id(irrelevanetChangeIdentifier).edit().get()).isPresent();
  }

  @Test
  public void attentionSetListener_firesOnChange() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().create();
    TestAttentionSetListener attentionSetListener = new TestAttentionSetListener();

    try (Registration registration =
        extensionRegistry.newRegistration().add(attentionSetListener)) {

      gApi.changes().id(changeIdentifier).addReviewer(user.email());
      assertThat(attentionSetListener.firedCount).isEqualTo(1);
      assertThat(attentionSetListener.lastEvent.usersAdded().size()).isEqualTo(1);
      attentionSetListener
          .lastEvent
          .usersAdded()
          .forEach(u -> assertThat(u).isEqualTo(user.id().get()));

      // Adding the user with the same reason doesn't fire an event.
      AttentionSetInput addUser = new AttentionSetInput(user.email(), "Reviewer was added");
      gApi.changes().id(changeIdentifier).addToAttentionSet(addUser);
      assertThat(attentionSetListener.firedCount).isEqualTo(1);

      // Adding the user with a different reason fires an event.
      addUser = new AttentionSetInput(user.email(), "some reason");
      gApi.changes().id(changeIdentifier).addToAttentionSet(addUser);
      assertThat(attentionSetListener.firedCount).isEqualTo(2);
      assertThat(attentionSetListener.lastEvent.usersAdded().size()).isEqualTo(1);
      attentionSetListener
          .lastEvent
          .usersAdded()
          .forEach(u -> assertThat(u).isEqualTo(user.id().get()));

      // Removing the user fires an event.
      gApi.changes().id(changeIdentifier).attention(user.username()).remove(addUser);
      assertThat(attentionSetListener.firedCount).isEqualTo(3);
      assertThat(attentionSetListener.lastEvent.usersAdded()).isEmpty();
      assertThat(attentionSetListener.lastEvent.usersRemoved().size()).isEqualTo(1);
      attentionSetListener
          .lastEvent
          .usersRemoved()
          .forEach(u -> assertThat(u).isEqualTo(user.id().get()));
    }
  }

  @Test
  @TestProjectInput(createEmptyCommit = false)
  public void changeNoParentToOneParent() throws Exception {
    // create initial commit with no parent and push it as change, so that patch
    // set 1 has no parent
    RevCommit c = testRepo.commit().message("Initial commit").insertChangeId().create();
    String id = GitUtil.getChangeId(testRepo, c).get();
    testRepo.reset(c);

    PushResult pr = pushHead(testRepo, "refs/for/master", false);
    assertPushOk(pr, "refs/for/master");

    ChangeInfo change = gApi.changes().id(id).get();
    assertThat(change.revisions.get(change.currentRevision).commit.parents).isEmpty();

    // create another initial commit with no parent and push it directly into
    // the remote repository
    c = testRepo.amend(c.getId()).message("Initial Empty Commit").create();
    testRepo.reset(c);
    pr = pushHead(testRepo, "refs/heads/master", false);
    assertPushOk(pr, "refs/heads/master");

    // create a successor commit and push it as second patch set to the change,
    // so that patch set 2 has 1 parent
    RevCommit c2 =
        testRepo
            .commit()
            .message("Initial commit")
            .parent(c)
            .insertChangeId(id.substring(1))
            .create();
    testRepo.reset(c2);

    pr = pushHead(testRepo, "refs/for/master", false);
    assertPushOk(pr, "refs/for/master");

    change = gApi.changes().id(id).get();
    RevisionInfo rev = change.revisions.get(change.currentRevision);
    assertThat(rev.commit.parents).hasSize(1);
    assertThat(rev.commit.parents.get(0).commit).isEqualTo(c.name());

    // check that change kind is correctly detected as REWORK
    assertThat(rev.kind).isEqualTo(ChangeKind.REWORK);
  }

  @Test
  public void pushCommitOfOtherUser() throws Exception {
    // admin pushes commit of user
    PushOneCommit push = pushFactory.create(user.newIdent(), testRepo);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(admin.id().get());
    CommitInfo commit = change.revisions.get(change.currentRevision).commit;
    assertThat(commit.author.email).isEqualTo(user.email());
    assertThat(commit.committer.email).isEqualTo(user.email());

    // check that the author/committer was added as cc
    Collection<AccountInfo> reviewers = change.reviewers.get(CC);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());
    assertThat(change.reviewers.get(REVIEWER)).isNull();

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.from().name()).isEqualTo("Administrator (Code Review)");
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
    assertThat(m.body()).contains("has uploaded this change for review");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, admin.email());
  }

  @Test
  public void pushCommitOfOtherUserThatCannotSeeChange() throws Exception {
    // create hidden project that is only visible to administrators
    Project.NameKey p = projectOperations.newProject().create();
    projectOperations
        .project(p)
        .forUpdate()
        .add(allow(Permission.READ).ref("refs/*").group(adminGroupUuid()))
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();

    // admin pushes commit of user
    TestRepository<InMemoryRepository> repo = cloneProject(p, admin);
    PushOneCommit push = pushFactory.create(user.newIdent(), repo);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get();
    assertThat(change.owner._accountId).isEqualTo(admin.id().get());
    CommitInfo commit = change.revisions.get(change.currentRevision).commit;
    assertThat(commit.author.email).isEqualTo(user.email());
    assertThat(commit.committer.email).isEqualTo(user.email());

    // check the user cannot see the change
    requestScopeOperations.setApiUser(user.id());
    assertThrows(
        ResourceNotFoundException.class, () -> gApi.changes().id(result.getChangeId()).get());

    // check that the author/committer was NOT added as reviewer (he can't see
    // the change)
    assertThat(change.reviewers.get(REVIEWER)).isNull();
    assertThat(change.reviewers.get(CC)).isNull();
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void addReviewerThatCannotSeeChange() throws Exception {
    // create hidden project that is only visible to administrators
    Project.NameKey p = projectOperations.newProject().create();
    projectOperations
        .project(p)
        .forUpdate()
        .add(allow(Permission.READ).ref("refs/*").group(adminGroupUuid()))
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();

    // create change
    ChangeIdentifier changeIdentifier =
        changeOperations.newChange().project(p).owner(admin.id()).create();

    // check the user cannot see the change
    requestScopeOperations.setApiUser(user.id());
    assertThrows(ResourceNotFoundException.class, () -> gApi.changes().id(changeIdentifier).get());

    // try to add user as reviewer
    requestScopeOperations.setApiUser(admin.id());
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    ReviewerResult r = gApi.changes().id(changeIdentifier).addReviewer(in);

    assertThat(r.input).isEqualTo(user.email());
    assertThat(r.error).contains("does not have permission to see this change");
    assertThat(r.reviewers).isNull();
  }

  @Test
  public void addReviewerThatIsInactiveByUsername() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();

    String username = name("new-user");
    Account.Id id = accountOperations.newAccount().username(username).inactive().create();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = username;
    ReviewerResult r = gApi.changes().id(changeIdentifier).addReviewer(in);

    assertThat(r.input).isEqualTo(in.reviewer);
    assertThat(r.error).isNull();
    assertThat(r.reviewers).hasSize(1);
    ReviewerInfo reviewer = r.reviewers.get(0);
    assertThat(reviewer._accountId).isEqualTo(id.get());
    if (server.isUsernameSupported()) {
      assertThat(reviewer.username).isEqualTo(username);
    }
  }

  @Test
  public void addReviewerThatIsInactiveById() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();

    String username = name("new-user");
    Account.Id id = accountOperations.newAccount().username(username).inactive().create();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = Integer.toString(id.get());
    ReviewerResult r = gApi.changes().id(changeIdentifier).addReviewer(in);

    assertThat(r.input).isEqualTo(in.reviewer);
    assertThat(r.error).isNull();
    assertThat(r.reviewers).hasSize(1);
    ReviewerInfo reviewer = r.reviewers.get(0);
    assertThat(reviewer._accountId).isEqualTo(id.get());
    if (server.isUsernameSupported()) {
      assertThat(reviewer.username).isEqualTo(username);
    }
  }

  @Test
  public void addReviewerThatIsInactiveByEmail() throws Exception {
    projectOperations.project(project).forUpdate().enableReviewerByEmail().update();

    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();
    String email = "user@domain.com";
    Account.Id id = accountOperations.newAccount().preferredEmail(email).inactive().create();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = email;
    in.state = ReviewerState.CC;
    ReviewerResult r = gApi.changes().id(changeIdentifier).addReviewer(in);

    assertThat(r.input).isEqualTo(email);
    assertThat(r.error).isNull();
    assertThat(r.ccs).hasSize(1);
    AccountInfo reviewer = r.ccs.get(0);
    assertThat(reviewer._accountId).isEqualTo(id.get());
    assertThat(reviewer.email).isEqualTo(email);
  }

  @Test
  @UseClockStep
  public void addReviewer() throws Exception {
    testAddReviewerViaPostReview(
        (changeIdentifier, reviewer) -> {
          ReviewerInput in = new ReviewerInput();
          in.reviewer = reviewer;
          gApi.changes().id(changeIdentifier).addReviewer(in);
        });
  }

  @Test
  @UseClockStep
  public void addReviewerViaPostReview() throws Exception {
    testAddReviewerViaPostReview(
        (changeIdentifier, reviewer) -> {
          ReviewerInput reviewerInput = new ReviewerInput();
          reviewerInput.reviewer = reviewer;
          ReviewInput reviewInput = new ReviewInput();
          reviewInput.reviewers = ImmutableList.of(reviewerInput);
          gApi.changes().id(changeIdentifier).current().review(reviewInput);
        });
  }

  private void testAddReviewerViaPostReview(AddReviewerCaller addReviewer) throws Exception {
    TestChange change =
        changeOperations
            .newChange()
            .owner(admin.id())
            .commitMessage(PushOneCommit.SUBJECT)
            .createAndGet();
    Instant oldTs = change.lastUpdatedOn();

    addReviewer.call(change.id(), user.email());

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
    assertThat(m.body()).contains("Hello " + user.fullName() + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, admin.email());
    ChangeInfo c = gApi.changes().id(change.id()).get();

    // Adding a reviewer records that user as reviewer.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());

    // Nobody was added as CC.
    assertThat(c.reviewers.get(CC)).isNull();

    // Ensure lastUpdatedOn is updated.
    assertThat(changeOperations.change(change.id()).get().lastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void postingMessageOnOwnChangeDoesntAddCallerAsReviewer() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();

    requestScopeOperations.setApiUser(admin.id());
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.message = "Foo Bar";
    gApi.changes().id(changeIdentifier).current().review(reviewInput);

    ChangeInfo c = gApi.changes().id(changeIdentifier).get();
    assertThat(c.reviewers.get(REVIEWER)).isNull();
    assertThat(c.reviewers.get(CC)).isNull();
  }

  @Test
  public void listReviewers() throws Exception {
    ChangeIdentifier changeIdentifier = changeOperations.newChange().owner(admin.id()).create();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeIdentifier).addReviewer(in);
    assertThat(gApi.changes().id(changeIdentifier).reviewers()).hasSize(1);

    String username1 = name("user1");
    String email1 = username1 + "@example.com";
    Account.Id user1Id =
        accountOperations
            .newAccount()
            .username(username1)
            .preferredEmail(email1)
            .fullname("User1")
            .create();
    in.reviewer = email1;
    in.state = ReviewerState.CC;
    gApi.changes().id(changeIdentifier).addReviewer(in);
    if (server.isUsernameSupported()) {
      assertThat(gApi.changes().id(changeIdentifier).reviewers().stream().map(a -> a.username))
          .containsExactly(user.username(), username1);
    }
    assertThat(gApi.changes().id(changeIdentifier).reviewers().stream().map(a -> a._accountId))
        .containsExactly(user.id().get(), user1Id.get());
  }

  @Test
  public void notificationsForAddedWorkInProgressReviewers() throws Exception {
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    ReviewInput batchIn = new ReviewInput();
    batchIn.reviewers = ImmutableList.of(in);

    // Added reviewers not notified by default.
    PushOneCommit.Result r = createWorkInProgressChange();
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(sender.getMessages()).isEmpty();

    // Default notification handling can be overridden.
    r = createWorkInProgressChange();
    in.notify = NotifyHandling.OWNER_REVIEWERS;
    gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(sender.getMessages()).hasSize(1);
    sender.clear();

    // Reviewers added via PostReview also not notified by default.
    // In this case, the child ReviewerInput has a notify=OWNER_REVIEWERS
    // that should be ignored.
    r = createWorkInProgressChange();
    gApi.changes().id(r.getChangeId()).current().review(batchIn);
    assertThat(sender.getMessages()).isEmpty();

    // Top-level notify property can force notifications when adding reviewer
    // via PostReview.
    r = createWorkInProgressChange();
    batchIn.notify = NotifyHandling.OWNER_REVIEWERS;
    gApi.changes().id(r.getChangeId()).current().review(batchIn);
    assertThat(sender.getMessages()).hasSize(1);
  }

  @Test
  @UseClockStep
  public void addReviewerThatIsNotPerfectMatch() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    Instant oldTs = rsrc.getChange().getLastUpdatedOn();

    // create a group named "ab" with one user: testUser
    String email = "abcd@example.com";
    String fullname = "abcd";
    Account.Id accountIdOfTestUser =
        accountOperations
            .newAccount()
            .username("abcd")
            .preferredEmail(email)
            .fullname(fullname)
            .create();
    String testGroup = groupOperations.newGroup().name("ab").create().get();
    GroupApi groupApi = gApi.groups().id(testGroup);
    groupApi.description("test group");
    groupApi.addMembers(user.fullName());

    ReviewerInput in = new ReviewerInput();
    in.reviewer = "abc";
    gApi.changes().id(r.getChangeId()).addReviewer(in.reviewer);

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(Address.create(fullname, email));
    assertThat(m.body()).contains("Hello " + fullname + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, email);
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    // Adding a reviewer records that user as reviewer.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(accountIdOfTestUser.get());

    // Ensure lastUpdatedOn is updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  @UseClockStep
  public void addGroupAsReviewersWhenANotPerfectMatchedUserExists() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    Instant oldTs = rsrc.getChange().getLastUpdatedOn();

    // create a group named "kobe" with one user: lee
    String testUserFullname = "kobebryant";
    accountOperations
        .newAccount()
        .username("kobebryant")
        .preferredEmail("kobebryant@example.com")
        .fullname(testUserFullname)
        .create();

    String myGroupUserEmail = "lee@example.com";
    String myGroupUserFullname = "lee";
    Account.Id accountIdOfGroupUser =
        accountOperations
            .newAccount()
            .username("lee")
            .preferredEmail(myGroupUserEmail)
            .fullname(myGroupUserFullname)
            .create();

    String testGroup = groupOperations.newGroup().name("kobe").create().get();
    GroupApi groupApi = gApi.groups().id(testGroup);
    groupApi.description("test group");
    groupApi.addMembers(myGroupUserFullname);

    // ensure that user "user" is not in the group
    groupApi.removeMembers(testUserFullname);

    ReviewerInput in = new ReviewerInput();
    in.reviewer = testGroup;
    gApi.changes().id(r.getChangeId()).addReviewer(in.reviewer);

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(Address.create(myGroupUserFullname, myGroupUserEmail));
    assertThat(m.body()).contains("Hello " + myGroupUserFullname + ",\n");
    assertThat(m.body()).contains("I'd like you to do a code review.");
    assertThat(m.body()).contains("Change subject: " + PushOneCommit.SUBJECT + "\n");
    assertMailReplyTo(m, myGroupUserEmail);
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    // Adding a reviewer records that user as reviewer.
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(accountIdOfGroupUser.get());

    // Ensure lastUpdatedOn is updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void deleteGroupFromReviewersFails() throws Exception {
    PushOneCommit.Result r = createChange();

    // create a group named "kobe" with one user: lee
    String myGroupUserEmail = "lee@example.com";
    String myGroupUserFullname = "lee";
    accountOperations
        .newAccount()
        .username("lee")
        .preferredEmail(myGroupUserEmail)
        .fullname(myGroupUserFullname)
        .create();

    String groupName = "kobe";
    String testGroup = groupOperations.newGroup().name(groupName).create().get();
    GroupApi groupApi = gApi.groups().id(testGroup);
    groupApi.description("test group");
    groupApi.addMembers(myGroupUserFullname);

    // add the user as reviewer.
    gApi.changes().id(r.getChangeId()).addReviewer(myGroupUserFullname);

    // fail to remove that user via group.
    ReviewResult reviewResult =
        gApi.changes()
            .id(r.getChangeId())
            .current()
            .review(ReviewInput.create().reviewer(testGroup, REMOVED, /* confirmed= */ true));

    assertThat(reviewResult.error).isEqualTo("error adding reviewer");

    ReviewerInput in = new ReviewerInput();
    in.reviewer = testGroup;
    in.state = REMOVED;
    ReviewerResult reviewerResult = gApi.changes().id(r.getChangeId()).addReviewer(in);
    assertThat(reviewerResult.error)
        .isEqualTo(MessageFormat.format(ChangeMessages.groupRemovalIsNotAllowed, groupName));
  }

  @Test
  @UseClockStep
  public void addSelfAsReviewer() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeResource rsrc = parseResource(r);
    Instant oldTs = rsrc.getChange().getLastUpdatedOn();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    // There should be no email notification when adding self
    assertThat(sender.getMessages()).isEmpty();

    // Adding a reviewer records that user as reviewer.
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).isNotNull();
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(user.id().get());

    // Ensure lastUpdatedOn is updated.
    rsrc = parseResource(r);
    assertThat(rsrc.getChange().getLastUpdatedOn()).isNotEqualTo(oldTs);
  }

  @Test
  public void implicitlyCcOnNonVotingReviewPgStyle() throws Exception {
    testImplicitlyCcOnNonVotingReviewPgStyle(user);
  }

  @Test
  public void implicitlyCcOnNonVotingReviewForUserWithoutUserNamePgStyle() throws Exception {
    com.google.gerrit.acceptance.TestAccount accountWithoutUsername = accountCreator.create();
    if (server.isUsernameSupported()) {
      assertThat(accountWithoutUsername.username()).isNull();
    }
    testImplicitlyCcOnNonVotingReviewPgStyle(accountWithoutUsername);
  }

  private void testImplicitlyCcOnNonVotingReviewPgStyle(
      com.google.gerrit.acceptance.TestAccount testAccount) throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(testAccount.id());
    assertThat(getReviewerState(r.getChangeId(), testAccount.id())).isEmpty();

    // Exact request format made by PG UI at ddc6b7160fe416fed9e7e3180489d44c82fd64f8.
    ReviewInput in = new ReviewInput();
    in.drafts = DraftHandling.PUBLISH_ALL_REVISIONS;
    in.labels = ImmutableMap.of();
    in.message = "comment";
    in.reviewers = ImmutableList.of();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(in);

    assertThat(getReviewerState(r.getChangeId(), testAccount.id())).hasValue(CC);
  }

  @Test
  public void implicitlyAddReviewerOnVotingReview() throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(ReviewInput.recommend().message("LGTM"));

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.get(REVIEWER).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(user.id().get());

    // Further test: remove the vote, then comment again. The user should be
    // implicitly re-added to the ReviewerSet, as a CC.
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).remove();
    c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.values()).isEmpty();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(new ReviewInput().message("hi"));
    c = gApi.changes().id(r.getChangeId()).get();
    assertThat(c.reviewers.get(CC).stream().map(ai -> ai._accountId).collect(toList()))
        .containsExactly(user.id().get());
  }

  @Test
  public void addReviewerToClosedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    Collection<AccountInfo> reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(1);
    assertThat(reviewers.iterator().next()._accountId).isEqualTo(admin.id().get());
    assertThat(c.reviewers).doesNotContainKey(CC);

    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    c = gApi.changes().id(r.getChangeId()).get();
    reviewers = c.reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(2);
    Iterator<AccountInfo> reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());
    assertThat(reviewerIt.next()._accountId).isEqualTo(user.id().get());
    assertThat(c.reviewers).doesNotContainKey(CC);
  }

  @Test
  public void emailNotificationForFileLevelComment() throws Exception {
    String changeId = createChange().getChangeId();

    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);
    sender.clear();

    ReviewInput review = new ReviewInput();
    ReviewInput.CommentInput comment = new ReviewInput.CommentInput();
    comment.path = PushOneCommit.FILE_NAME;
    comment.side = Side.REVISION;
    comment.message = "comment 1";
    review.comments = new HashMap<>();
    review.comments.put(comment.path, Lists.newArrayList(comment));
    gApi.changes().id(changeId).current().review(review);

    assertThat(sender.getMessages()).hasSize(1);
    Message m = sender.getMessages().get(0);
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
  }

  @Test
  public void invalidRange() throws Exception {
    String changeId = createChange().getChangeId();

    ReviewInput review = new ReviewInput();
    ReviewInput.CommentInput comment = new ReviewInput.CommentInput();

    comment.range = new Range();
    comment.range.startLine = 1;
    comment.range.endLine = 1;
    comment.range.startCharacter = -1;
    comment.range.endCharacter = 0;

    comment.path = PushOneCommit.FILE_NAME;
    comment.side = Side.REVISION;
    comment.message = "comment 1";
    review.comments = ImmutableMap.of(comment.path, Lists.newArrayList(comment));

    assertThrows(
        BadRequestException.class, () -> gApi.changes().id(changeId).current().review(review));
  }

  @Test
  public void listVotes() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    Map<String, Short> m =
        gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).votes();

    assertThat(m).hasSize(1);
    assertThat(m).containsExactly(LabelId.CODE_REVIEW, Short.valueOf((short) 2));

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.dislike());

    m = gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).votes();

    assertThat(m).hasSize(1);
    assertThat(m).containsExactly(LabelId.CODE_REVIEW, Short.valueOf((short) -1));
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "NONE")
  public void listVotesEvenWhenAccountsAreNotVisible() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());

    // check finding by address works
    Map<String, Short> m = gApi.changes().id(r.getChangeId()).reviewer(admin.email()).votes();
    assertThat(m).hasSize(1);
    assertThat(m).containsEntry(LabelId.CODE_REVIEW, Short.valueOf((short) 2));

    // check finding by id works
    m = gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).votes();
    assertThat(m).hasSize(1);
    assertThat(m).containsEntry(LabelId.CODE_REVIEW, Short.valueOf((short) 2));
  }

  @Test
  public void removeReviewerNoVotes() throws Exception {
    LabelType verified =
        label(LabelId.VERIFIED, value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(verified.getName())
                .ref(RefNames.REFS_HEADS + "*")
                .group(REGISTERED_USERS)
                .range(-1, 1))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).addReviewer(user.id().toString());

    ChangeInfo c = gApi.changes().id(changeId).get(ListChangesOption.DETAILED_LABELS);
    assertThat(getReviewers(c.reviewers.get(CC))).isEmpty();
    assertThat(getReviewers(c.reviewers.get(REVIEWER))).containsExactly(user.id());

    sender.clear();
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();

    assertThat(sender.getMessages()).hasSize(1);
    Message message = sender.getMessages().get(0);
    assertThat(message.body()).contains("Removed reviewer " + user.getNameEmail() + ".");
    assertThat(message.body()).doesNotContain("with the following votes");

    // Make sure the change message for removing a reviewer is correct.
    assertThat(Iterables.getLast(gApi.changes().id(changeId).messages()).message)
        .isEqualTo("Removed reviewer " + user.getNameEmail() + ".");
    ChangeMessageInfo changeMessageInfo =
        Iterables.getLast(gApi.changes().id(changeId).get().messages);
    assertThat(changeMessageInfo.message)
        .isEqualTo("Removed reviewer " + AccountTemplateUtil.getAccountTemplate(user.id()) + ".");
    assertThat(changeMessageInfo.accountsInMessage).containsExactly(getAccountInfo(user.id()));

    // Make sure the reviewer can still be added again.
    gApi.changes().id(changeId).addReviewer(user.id().toString());
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(CC))).isEmpty();
    assertThat(getReviewers(c.reviewers.get(REVIEWER))).containsExactly(user.id());

    // Remove again, and then try to remove once more to verify 404 is
    // returned.
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    assertThrows(
        ResourceNotFoundException.class,
        () -> gApi.changes().id(changeId).reviewer(user.id().toString()).remove());
  }

  @Test
  public void removeChangeOwnerAsReviewerByDelete() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // vote on the change so that the change owner becomes a reviewer
    approve(changeId);
    assertThat(getReviewers(gApi.changes().id(changeId).get().reviewers.get(REVIEWER)))
        .containsExactly(admin.id());

    gApi.changes().id(changeId).reviewer(admin.id().toString()).remove();
    assertThat(getReviewers(gApi.changes().id(changeId).get().reviewers.get(REVIEWER))).isEmpty();
  }

  @Test
  public void removeChangeOwnerAsReviewerByPostReview() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // vote on the change so that the change owner becomes a reviewer
    approve(changeId);
    assertThat(getReviewers(gApi.changes().id(changeId).get().reviewers.get(REVIEWER)))
        .containsExactly(admin.id());

    ReviewInput reviewInput = new ReviewInput();
    reviewInput.reviewer(admin.id().toString(), ReviewerState.REMOVED, /* confirmed= */ false);
    gApi.changes().id(changeId).current().review(reviewInput);
    assertThat(getReviewers(gApi.changes().id(changeId).get().reviewers.get(REVIEWER))).isEmpty();
  }

  @Test
  public void removeCC() throws Exception {
    PushOneCommit.Result result = createChange();
    String changeId = result.getChangeId();
    // Add a cc
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = user.id().toString();
    gApi.changes().id(changeId).addReviewer(reviewerInput);

    // Remove a cc
    sender.clear();
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();

    // Make sure the email for removing a cc is correct.
    assertThat(sender.getMessages()).hasSize(1);
    Message message = sender.getMessages().get(0);
    assertThat(message.body()).contains("Removed cc " + user.getNameEmail() + ".");

    // Make sure the change message for removing a reviewer is correct.
    assertThat(Iterables.getLast(gApi.changes().id(changeId).messages()).message)
        .isEqualTo("Removed cc " + user.getNameEmail() + ".");

    ChangeMessageInfo changeMessageInfo =
        Iterables.getLast(gApi.changes().id(changeId).get().messages);
    assertThat(changeMessageInfo.message)
        .isEqualTo("Removed cc " + AccountTemplateUtil.getAccountTemplate(user.id()) + ".");
    assertThat(changeMessageInfo.accountsInMessage).containsExactly(getAccountInfo(user.id()));
  }

  @Test
  public void cannotRemoveCCWithoutRemoveReviewerPermission() throws Exception {
    PushOneCommit.Result result = createChange();
    String changeId = result.getChangeId();

    // Add a cc
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = user.id().toString();
    gApi.changes().id(changeId).addReviewer(reviewerInput);

    // Try removing the cc as a user that doesn't have the Remove Reviewer permission:
    requestScopeOperations.setApiUser(accountCreator.create().id());

    // a) via the Delete Reviewer endpoint:
    AuthException exception =
        assertThrows(
            AuthException.class,
            () -> gApi.changes().id(changeId).reviewer(user.id().toString()).remove());
    assertThat(exception).hasMessageThat().isEqualTo("remove reviewer not permitted");

    // b) via the Post Review endpoint:
    reviewerInput = new ReviewerInput();
    reviewerInput.reviewer = user.id().toString();
    reviewerInput.state = ReviewerState.REMOVED;
    ReviewInput input = new ReviewInput();
    input.reviewers = ImmutableList.of(reviewerInput);
    exception =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeId).current().review(input));
    assertThat(exception).hasMessageThat().isEqualTo("remove reviewer not permitted");
  }

  @Test
  public void removeSelfFromCCPossibleWithoutRemoveReviewerPermission() throws Exception {
    PushOneCommit.Result result = createChange();
    String changeId = result.getChangeId();

    // Add a cc
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = user.id().toString();
    gApi.changes().id(changeId).addReviewer(reviewerInput);
    assertThat(gApi.changes().id(changeId).get().reviewers).isNotEmpty();

    // Try removing the cc as that user should work since users can always remove themselves
    requestScopeOperations.setApiUser(user.id());

    // a) via the Delete Reviewer endpoint:
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove();
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();

    // Add the user back to cc
    gApi.changes().id(changeId).addReviewer(reviewerInput);
    assertThat(gApi.changes().id(changeId).get().reviewers).isNotEmpty();

    // b) via the Post Review endpoint:
    reviewerInput = new ReviewerInput();
    reviewerInput.reviewer = user.id().toString();
    reviewerInput.state = ReviewerState.REMOVED;
    ReviewInput input = new ReviewInput();
    input.reviewers = ImmutableList.of(reviewerInput);
    gApi.changes().id(changeId).current().review(input);
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();
  }

  @Test
  public void cannotRemoveSelfFromCCTogetherWithOtherCCWithoutRemoveReviewerPermission()
      throws Exception {
    PushOneCommit.Result result = createChange();
    String changeId = result.getChangeId();

    // Add a cc
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = user.id().toString();
    gApi.changes().id(changeId).addReviewer(reviewerInput);
    assertThat(gApi.changes().id(changeId).get().reviewers.get(CC)).hasSize(1);

    // Add another cc
    TestAccount otherCC = accountCreator.create();
    reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = otherCC.id().toString();
    gApi.changes().id(changeId).addReviewer(reviewerInput);
    assertThat(gApi.changes().id(changeId).get().reviewers.get(CC)).hasSize(2);

    // Trying to remove themselves as cc and other ccs should not work since the user cannot remove
    // other users from cc.
    requestScopeOperations.setApiUser(user.id());
    ReviewerInput reviewerInputRemoveSelfCC = new ReviewerInput();
    reviewerInputRemoveSelfCC.reviewer = user.id().toString();
    reviewerInputRemoveSelfCC.state = ReviewerState.REMOVED;
    ReviewerInput reviewerInputRemoveOtherCC = new ReviewerInput();
    reviewerInputRemoveOtherCC.reviewer = otherCC.id().toString();
    reviewerInputRemoveOtherCC.state = ReviewerState.REMOVED;
    ReviewInput input = new ReviewInput();
    input.reviewers = ImmutableList.of(reviewerInputRemoveSelfCC, reviewerInputRemoveOtherCC);
    AuthException exception =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(changeId).current().review(input));
    assertThat(exception).hasMessageThat().isEqualTo("remove reviewer not permitted");
  }

  @Test
  public void removeReviewer() throws Exception {
    testRemoveReviewer(true);
  }

  @Test
  public void removeNoNotify() throws Exception {
    testRemoveReviewer(false);
  }

  private void testRemoveReviewer(boolean notify) throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.recommend());

    Collection<AccountInfo> reviewers = gApi.changes().id(changeId).get().reviewers.get(REVIEWER);

    assertThat(reviewers).hasSize(2);
    Iterator<AccountInfo> reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());
    assertThat(reviewerIt.next()._accountId).isEqualTo(user.id().get());

    sender.clear();
    requestScopeOperations.setApiUser(admin.id());
    DeleteReviewerInput input = new DeleteReviewerInput();
    if (!notify) {
      input.notify = NotifyHandling.NONE;
    }
    gApi.changes().id(changeId).reviewer(user.id().toString()).remove(input);

    if (notify) {
      assertThat(sender.getMessages()).hasSize(1);
      Message message = sender.getMessages().get(0);
      assertThat(message.body())
          .contains("Removed reviewer " + user.getNameEmail() + " with the following votes");
      assertThat(message.body()).contains("* Code-Review+1 by " + user.getNameEmail());
      ChangeMessageInfo changeMessageInfo =
          Iterables.getLast(gApi.changes().id(changeId).messages());
      assertThat(changeMessageInfo.message)
          .contains("Removed reviewer " + user.getNameEmail() + " with the following votes");
      assertThat(changeMessageInfo.message).contains("* Code-Review+1 by " + user.getNameEmail());
      changeMessageInfo = Iterables.getLast(gApi.changes().id(changeId).get().messages);
      assertThat(changeMessageInfo.message)
          .contains(
              "Removed reviewer "
                  + AccountTemplateUtil.getAccountTemplate(user.id())
                  + " with the following votes");
      assertThat(changeMessageInfo.message)
          .contains("* Code-Review+1 by " + AccountTemplateUtil.getAccountTemplate(user.id()));
      assertThat(changeMessageInfo.accountsInMessage).containsExactly(getAccountInfo(user.id()));
    } else {
      assertThat(sender.getMessages()).isEmpty();
    }

    reviewers = gApi.changes().id(changeId).get().reviewers.get(REVIEWER);
    assertThat(reviewers).hasSize(1);
    reviewerIt = reviewers.iterator();
    assertThat(reviewerIt.next()._accountId).isEqualTo(admin.id().get());

    eventRecorder.assertReviewerDeletedEvents(changeId, user.email());
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void removeNonVisibleReviewer() throws Exception {
    // allow all users to remove reviewers
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.REMOVE_REVIEWER).ref("refs/*").group(REGISTERED_USERS))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).addReviewer(user.email());
    AccountInfo reviewerInfo =
        Iterables.getOnlyElement(
            gApi.changes().id(changeId).get().reviewers.get(ReviewerState.REVIEWER));
    assertThat(reviewerInfo._accountId).isEqualTo(user.id().get());

    TestAccount user2 = accountCreator.user2();
    requestScopeOperations.setApiUser(user2.id());

    // user2 cannot see user
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
        .isFalse();

    gApi.changes().id(changeId).reviewer(user.id().toString()).remove(new DeleteReviewerInput());
    assertThat(gApi.changes().id(changeId).get().reviewers).isEmpty();
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void removeNonVisibleReviewerThroughPostReview() throws Exception {
    // allow all users to remove reviewers
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.REMOVE_REVIEWER).ref("refs/*").group(REGISTERED_USERS))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).addReviewer(user.email());
    AccountInfo reviewerInfo =
        Iterables.getOnlyElement(
            gApi.changes().id(changeId).get().reviewers.get(ReviewerState.REVIEWER));
    assertThat(reviewerInfo._accountId).isEqualTo(user.id().get());

    TestAccount user2 = accountCreator.user2();
    requestScopeOperations.setApiUser(user2.id());

    // user2 cannot see user
    assertThat(
            accountControlFactory.get(identifiedUserFactory.create(user.id())).canSee(user2.id()))
        .isFalse();

    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.reviewer = user.email();
    reviewerInput.state = ReviewerState.REMOVED;
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.reviewers = ImmutableList.of(reviewerInput);
    ReviewResult reviewResult = gApi.changes().id(changeId).current().review(reviewInput);
    assertThat(reviewResult.error).isNull();

    // user is removed as a reviewer, user2 is added as a CC by doing the post review request that
    // removed user as a reviewer
    assertThat(gApi.changes().id(changeId).get().reviewers.get(ReviewerState.REVIEWER)).isNull();
    reviewerInfo =
        Iterables.getOnlyElement(gApi.changes().id(changeId).get().reviewers.get(ReviewerState.CC));
    assertThat(reviewerInfo._accountId).isEqualTo(user2.id().get());
  }

  @Test
  public void removeReviewerNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    gApi.changes().id(changeId).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () -> gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).remove());
    assertThat(thrown).hasMessageThat().contains("remove reviewer not permitted");
  }

  @Test
  public void removeReviewerSelfFromMergedChangeNotPossible() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    approve(changeId);
    gApi.changes().id(changeId).revision(r.getCommit().name()).submit();

    requestScopeOperations.setApiUser(user.id());
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).reviewer("self").remove());
    assertThat(thrown).hasMessageThat().contains("cannot remove votes from merged change");
  }

  @Test
  public void removeReviewerSelfFromAbandonedChangePermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).abandon();

    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).reviewer("self").remove();
    eventRecorder.assertReviewerDeletedEvents(changeId, user.email());
  }

  @Test
  public void removeOtherReviewerFromAbandonedChangeNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    recommend(changeId);

    requestScopeOperations.setApiUser(admin.id());
    approve(changeId);
    gApi.changes().id(changeId).abandon();

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () -> gApi.changes().id(r.getChangeId()).reviewer(admin.id().toString()).remove());
    assertThat(thrown).hasMessageThat().contains("remove reviewer not permitted");
  }

  @Test
  public void deleteVote() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    gApi.changes()
        .id(r.getChangeId())
        .reviewer(user.id().toString())
        .deleteVote(LabelId.CODE_REVIEW);

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message msg = messages.get(0);
    assertThat(msg.rcpt()).containsExactly(user.getNameEmail());
    assertThat(msg.body()).contains(admin.fullName() + " has removed a vote from this change.");
    assertThat(msg.body())
        .contains("Removed Code-Review+1 by " + user.fullName() + " <" + user.email() + ">\n");

    Map<String, Short> m =
        gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).votes();

    // Dummy 0 approval on the change to block vote copying to this patch set.
    assertThat(m).containsExactly(LabelId.CODE_REVIEW, Short.valueOf((short) 0));

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();

    ChangeMessageInfo message = Iterables.getLast(c.messages);
    assertThat(message.author._accountId).isEqualTo(admin.id().get());
    assertThat(message.message)
        .isEqualTo(
            "Removed Code-Review+1 by " + AccountTemplateUtil.getAccountTemplate(user.id()) + "\n");
    assertThat(message.accountsInMessage).containsExactly(getAccountInfo(user.id()));
    assertThat(gApi.changes().id(r.getChangeId()).message(message.id).get().message)
        .isEqualTo("Removed Code-Review+1 by User1 <user1@example.com>\n");
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));
  }

  @Test
  public void deleteVoteNotifyNone() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    DeleteVoteInput in = new DeleteVoteInput();
    in.label = LabelId.CODE_REVIEW;
    in.notify = NotifyHandling.NONE;
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void deleteVoteWithReason() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    DeleteVoteInput in = new DeleteVoteInput();
    in.label = LabelId.CODE_REVIEW;
    in.reason = "Internal conflict resolved";
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertThat(Iterables.getLast(gApi.changes().id(r.getChangeId()).messages()).message)
        .isEqualTo(
            "Removed Code-Review+1 by User1 <user1@example.com>\n"
                + "\n"
                + "Internal conflict resolved\n");
  }

  @Test
  public void deleteVoteNotifyAccount() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    DeleteVoteInput in = new DeleteVoteInput();
    in.label = LabelId.CODE_REVIEW;
    in.notify = NotifyHandling.NONE;

    // notify unrelated account as TO
    String email = "user2@example.com";
    accountOperations
        .newAccount()
        .username("user2")
        .preferredEmail(email)
        .fullname("User2")
        .create();
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.TO, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyTo(email, "User2");

    // notify unrelated account as CC
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.CC, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyCc(email, "User2");

    // notify unrelated account as BCC
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());
    requestScopeOperations.setApiUser(admin.id());
    sender.clear();
    in.notifyDetails = new HashMap<>();
    in.notifyDetails.put(RecipientType.BCC, new NotifyInfo(ImmutableList.of(email)));
    gApi.changes().id(r.getChangeId()).reviewer(user.id().toString()).deleteVote(in);
    assertNotifyBcc(email, "User2");
  }

  @Test
  public void deleteVoteNotPermitted() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .reviewer(admin.id().toString())
                    .deleteVote(LabelId.CODE_REVIEW));
    assertThat(thrown).hasMessageThat().contains("Delete vote not permitted");
  }

  @Test
  public void deleteVoteFromMergedChangeNotPossible() throws Exception {
    PushOneCommit.Result r = createChange();
    approve(r.getChangeId());
    gApi.changes().id(r.getChangeId()).current().submit();

    requestScopeOperations.setApiUser(user.id());
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .reviewer(admin.id().toString())
                    .deleteVote(LabelId.CODE_REVIEW));
    assertThat(thrown).hasMessageThat().contains("cannot remove votes from merged change");
  }

  @Test
  public void deleteVoteFromOpenChangeAlwaysPermittedForSelfVotes() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .add(
            blockLabelRemoval(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .add(block(Permission.REMOVE_REVIEWER).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    approve(changeId);

    gApi.changes()
        .id(r.getChangeId())
        .reviewer(user.id().toString())
        .deleteVote(LabelId.CODE_REVIEW);
  }

  @Test
  public void deleteVoteFromClosedChangeNotPossibleForSelfVotes() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/heads/*").group(REGISTERED_USERS))
        .add(
            allowLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    approve(changeId);
    gApi.changes().id(changeId).current().submit();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .reviewer(user.id().toString())
                    .deleteVote(LabelId.CODE_REVIEW));
    assertThat(thrown).hasMessageThat().contains("cannot remove votes from merged change");
  }

  @Test
  public void deleteVoteFromOpenChangeAlwaysPermittedForAdmin() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .add(
            blockLabelRemoval(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .add(block(Permission.REMOVE_REVIEWER).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    approve(changeId);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes()
        .id(r.getChangeId())
        .reviewer(user.id().toString())
        .deleteVote(LabelId.CODE_REVIEW);
  }

  @Test
  public void deleteVoteFromMergedChangeNotPossibleForAdmin() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-2, 2))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    requestScopeOperations.setApiUser(user.id());
    approve(changeId);

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).current().submit();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .reviewer(user.id().toString())
                    .deleteVote(LabelId.CODE_REVIEW));
    assertThat(thrown).hasMessageThat().contains("cannot remove votes from merged change");
  }

  @Test
  public void nonVotingReviewerStaysAfterSubmit() throws Exception {
    LabelType verified =
        label(LabelId.VERIFIED, value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    String heads = "refs/heads/*";
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(CHANGE_OWNER).range(-1, 1))
        .add(allowLabel(LabelId.CODE_REVIEW).ref(heads).group(REGISTERED_USERS).range(-2, +2))
        .update();

    // Set Code-Review+2 and Verified+1 as admin (change owner)
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();
    String commit = r.getCommit().name();
    ReviewInput input = ReviewInput.approve();
    input.label(verified.getName(), 1);
    gApi.changes().id(changeId).revision(commit).review(input);

    // Reviewers should only be "admin"
    ChangeInfo c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id()));
    assertThat(c.reviewers.get(CC)).isNull();

    // Add the user as reviewer
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(changeId).addReviewer(in);
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));

    // Approve the change as user, then remove the approval
    // (only to confirm that the user does have Code-Review+2 permission)
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).revision(commit).review(ReviewInput.approve());
    gApi.changes().id(changeId).revision(commit).review(ReviewInput.noScore());

    // Submit the change
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).revision(commit).submit();

    // User should still be on the change
    c = gApi.changes().id(changeId).get();
    assertThat(getReviewers(c.reviewers.get(REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));
  }

  @Test
  public void labelPermissionsChange_doesNotAffectCurrentVotes() throws Exception {
    String heads = "refs/heads/*";
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(LabelId.CODE_REVIEW).ref(heads).group(REGISTERED_USERS).range(-2, +2))
        .update();

    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // Approve the change as user
    requestScopeOperations.setApiUser(user.id());
    approve(changeId);
    assertThat(
            gApi.changes().id(changeId).get(DETAILED_LABELS).labels.get("Code-Review").all.stream()
                .collect(toImmutableMap(vote -> Account.id(vote._accountId), vote -> vote.value)))
        .isEqualTo(ImmutableMap.of(user.id(), 2));

    // Remove permissions for CODE_REVIEW. The user still has [-1,+1], inherited from All-Projects.
    projectOperations
        .project(project)
        .forUpdate()
        .remove(labelPermissionKey(LabelId.CODE_REVIEW).ref(heads).group(REGISTERED_USERS))
        .update();

    // No permissions to vote +2
    assertThrows(AuthException.class, () -> approve(changeId));

    assertThat(
            get(changeId, DETAILED_LABELS).labels.get(LabelId.CODE_REVIEW).all.stream()
                .map(vote -> vote.value))
        .containsExactly(2);

    // The change is still submittable
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).current().submit();
    assertThat(info(changeId).status).isEqualTo(MERGED);

    // The +2 vote out of permissions range is still present.
    assertThat(
            get(changeId, DETAILED_LABELS).labels.get(LabelId.CODE_REVIEW).all.stream()
                .collect(toImmutableMap(vote -> Account.id(vote._accountId), vote -> vote.value)))
        .isEqualTo(ImmutableMap.of(user.id(), 2, admin.id(), 0));
  }

  @Test
  public void createEmptyChange() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = Constants.MASTER;
    in.subject = "Create a change from the API";
    in.project = project.get();
    ChangeInfo info = gApi.changes().create(in).get();
    assertThat(info.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(info.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(info.subject).isEqualTo(in.subject);
    assertThat(Iterables.getOnlyElement(info.messages).message).isEqualTo("Uploaded patch set 1.");
  }

  @Test
  public void queryChangesNoQuery() throws Exception {
    PushOneCommit.Result r = createChange();
    List<ChangeInfo> results = gApi.changes().query().get();
    assertThat(results.size()).isAtLeast(1);
    List<Integer> ids = new ArrayList<>(results.size());
    for (int i = 0; i < results.size(); i++) {
      ChangeInfo info = results.get(i);
      if (i == 0) {
        assertThat(info._number).isEqualTo(r.getChange().getId().get());
      }
      assertThat(Change.Status.forChangeStatus(info.status).isOpen()).isTrue();
      ids.add(info._number);
    }
    assertThat(ids).contains(r.getChange().getId().get());
  }

  @Test
  public void queryChangesNoResults() throws Exception {
    createChange();
    assertThat(query("message:test")).isNotEmpty();
    assertThat(query("message:{" + getClass().getName() + "fhqwhgads}")).isEmpty();
  }

  @Test
  public void queryChanges() throws Exception {
    PushOneCommit.Result r1 = createChange();
    createChange();
    List<ChangeInfo> results = query("project:{" + project.get() + "} " + r1.getChangeId());
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r1.getChangeId());
  }

  @Test
  public void queryChangesLimit() throws Exception {
    for (int i = 0; i < 3; i++) {
      createChange();
    }
    List<ChangeInfo> resultsLimited = gApi.changes().query().withLimit(1).get();
    List<ChangeInfo> resultsUnlimited = gApi.changes().query().get();
    assertThat(resultsLimited).hasSize(1);
    assertThat(resultsUnlimited.size()).isAtLeast(3);
  }

  @Test
  @GerritConfig(name = "index.defaultLimit", value = "2")
  public void queryChangesLimitDefault() throws Exception {
    for (int i = 0; i < 4; i++) {
      createChange();
    }
    List<ChangeInfo> resultsLimited = gApi.changes().query().withLimit(1).get();
    List<ChangeInfo> resultsUnlimited = gApi.changes().query().get();
    List<ChangeInfo> resultsLimitedAboveDefault = gApi.changes().query().withLimit(3).get();
    assertThat(resultsLimited).hasSize(1);
    assertThat(resultsUnlimited).hasSize(2);
    assertThat(resultsLimitedAboveDefault).hasSize(3);
  }

  @Test
  public void queryChangesNoLimitRegisteredUser() throws Exception {
    projectOperations
        .allProjectsForUpdate()
        .add(
            allowCapability(GlobalCapability.QUERY_LIMIT)
                .group(SystemGroupBackend.REGISTERED_USERS)
                .range(0, 2))
        .update();
    for (int i = 0; i < 3; i++) {
      createChange();
    }
    List<ChangeInfo> resultsWithDefaultLimit = gApi.changes().query().get();
    List<ChangeInfo> resultsWithNoLimit = gApi.changes().query().withNoLimit().get();
    assertThat(resultsWithDefaultLimit).hasSize(2);
    assertThat(resultsWithNoLimit.size()).isAtLeast(3);
  }

  @Test
  public void queryChangesNoLimitIgnoredForAnonymousUser() throws Exception {
    int limit = 2;
    projectOperations
        .allProjectsForUpdate()
        .add(
            allowCapability(GlobalCapability.QUERY_LIMIT)
                .group(SystemGroupBackend.ANONYMOUS_USERS)
                .range(0, limit))
        .update();
    for (int i = 0; i < 3; i++) {
      createChange();
    }
    requestScopeOperations.setApiUserAnonymous();
    List<ChangeInfo> resultsWithDefaultLimit = gApi.changes().query().get();
    List<ChangeInfo> resultsWithNoLimit = gApi.changes().query().withNoLimit().get();
    assertThat(resultsWithDefaultLimit).hasSize(limit);
    assertThat(resultsWithNoLimit).hasSize(limit);
  }

  @Test
  public void queryChangesStart() throws Exception {
    PushOneCommit.Result r1 = createChange();
    createChange();
    List<ChangeInfo> results =
        gApi.changes().query("project:{" + project.get() + "}").withStart(1).get();
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r1.getChangeId());
  }

  @Test
  public void queryChangesNoOptions() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeInfo result = Iterables.getOnlyElement(query(r.getChangeId()));
    assertThat(result.labels).isNull();
    assertThat(result.messages).isNull();
    assertThat(result.revisions).isNull();
    assertThat(result.actions).isNull();
  }

  @Test
  public void queryChangesOptions() throws Exception {
    PushOneCommit.Result r = createChange();

    ChangeInfo result = Iterables.getOnlyElement(gApi.changes().query(r.getChangeId()).get());
    assertThat(result.labels).isNull();
    assertThat(result.messages).isNull();
    assertThat(result.actions).isNull();
    assertThat(result.revisions).isNull();

    result =
        Iterables.getOnlyElement(
            gApi.changes()
                .query(r.getChangeId())
                .withOptions(
                    ALL_REVISIONS, CHANGE_ACTIONS, CURRENT_ACTIONS, DETAILED_LABELS, MESSAGES)
                .get());
    assertThat(Iterables.getOnlyElement(result.labels.keySet())).isEqualTo(LabelId.CODE_REVIEW);
    assertThat(result.messages).hasSize(1);
    assertThat(result.actions).isNotEmpty();

    RevisionInfo rev = Iterables.getOnlyElement(result.revisions.values());
    assertThat(rev._number).isEqualTo(r.getPatchSetId().get());
    assertThat(rev.created).isNotNull();
    assertThat(rev.uploader._accountId).isEqualTo(admin.id().get());
    assertThat(rev.ref).isEqualTo(r.getPatchSetId().toRefName());
    assertThat(rev.actions).isNotEmpty();
  }

  @Test
  public void queryChangesOwnerWithDifferentUsers() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(
            Iterables.getOnlyElement(query("project:{" + project.get() + "} owner:self")).changeId)
        .isEqualTo(r.getChangeId());
    requestScopeOperations.setApiUser(user.id());
    assertThat(query("owner:self project:{" + project.get() + "}")).isEmpty();
  }

  private static class OperatorModule extends AbstractModule {
    @Override
    public void configure() {
      bind(ChangeOperatorFactory.class)
          .annotatedWith(Exports.named("mytopic"))
          .toInstance((cqb, value) -> new MyTopicPredicate(value));
    }

    private static class MyTopicPredicate extends PostFilterPredicate<ChangeData> {
      MyTopicPredicate(String value) {
        super("mytopic", value);
      }

      @Override
      public boolean match(ChangeData cd) {
        return Objects.equals(cd.change().getTopic(), value);
      }

      @Override
      public int getCost() {
        return 2;
      }
    }
  }

  @Test
  public void queryChangesPluginOperator() throws Exception {
    PushOneCommit.Result r = createChange();
    String query = "mytopic_myplugin:foo";
    String expectedMessage = "Unsupported operator mytopic_myplugin:foo";
    assertThatQueryException(query).hasMessageThat().isEqualTo(expectedMessage);

    try (AutoCloseable ignored = installPlugin("myplugin", OperatorModule.class)) {
      assertThat(query(query)).isEmpty();
      gApi.changes().id(r.getChangeId()).topic("foo");
      assertThat(query(query).stream().map(i -> i.changeId)).containsExactly(r.getChangeId());
    }

    assertThatQueryException(query).hasMessageThat().isEqualTo(expectedMessage);
  }

  @Test
  public void queryChangesDefaultFieldMatchesOwner() throws Exception {
    // We have to create a new user since changes are not deleted between tests, which means
    // querying the standard users will lead to dirty results.
    TestAccount changeOwner = accountCreator.createValid("changeOwner");
    requestScopeOperations.setApiUser(changeOwner.id());
    // Creating a change through the API since PushOneCommit changes are always owned by admin().
    ChangeInput in = new ChangeInput();
    in.branch = Constants.MASTER;
    in.subject = "subject";
    in.project = project.get();
    ChangeInfo info = gApi.changes().createAsInfo(in);
    assertThat(info.owner._accountId).isEqualTo(changeOwner.id().get());
    requestScopeOperations.setApiUser(user.id());
    List<ChangeInfo> results = query(changeOwner.email());
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(info.changeId);
  }

  @Test
  public void queryChangesDefaultFieldMatchesReviewer() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, "subject", "a.txt", "a1")
            .to("refs/for/master");
    // We have to create a new user since changes are not deleted between tests, which means
    // querying the standard users will lead to dirty results.
    TestAccount changeReviewer = accountCreator.createValid("changeReviewer");
    gApi.changes().id(r.getChangeId()).addReviewer(changeReviewer.email());
    requestScopeOperations.setApiUser(user.id());
    List<ChangeInfo> results = query(changeReviewer.email());
    assertThat(Iterables.getOnlyElement(results).changeId).isEqualTo(r.getChangeId());
  }

  @Test
  public void checkReviewedFlagBeforeAndAfterReview() throws Exception {
    PushOneCommit.Result r = createChange();
    ReviewerInput in = new ReviewerInput();
    in.reviewer = user.email();
    gApi.changes().id(r.getChangeId()).addReviewer(in);

    requestScopeOperations.setApiUser(user.id());
    assertThat(get(r.getChangeId(), REVIEWED).reviewed).isNull();

    revision(r).review(ReviewInput.recommend());
    assertThat(get(r.getChangeId(), REVIEWED).reviewed).isTrue();
  }

  @Test
  public void topic() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    gApi.changes().id(r.getChangeId()).topic("mytopic");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("mytopic");
    gApi.changes().id(r.getChangeId()).topic("");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
  }

  @Test
  @GerritConfig(name = "change.topicLimit", value = "3")
  public void topicSizeLimit() throws Exception {
    for (int i = 0; i < 3; i++) {
      createChangeWithTopic(testRepo, "limitedTopic", "message", "a.txt", "content\n");
    }
    PushOneCommit.Result rLimited =
        pushFactory
            .create(user.newIdent(), testRepo)
            .to("refs/for/master%topic=" + name("limitedTopic"));
    rLimited.assertErrorStatus("topicLimit");

    PushOneCommit.Result rOther =
        createChangeWithTopic(testRepo, "otherTopic", "message", "a.txt", "content\n");
    assertThat(gApi.changes().id(rOther.getChangeId()).topic()).contains("otherTopic");
  }

  @Test
  public void editTopicWithoutPermissionNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(r.getChangeId()).topic("mytopic"));
    assertThat(thrown).hasMessageThat().contains("edit topic name not permitted");
  }

  @Test
  public void deleteTopicWithoutPermissionNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.EDIT_TOPIC_NAME).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).topic("mytopic");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("mytopic");
    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .remove(
            permissionKey(Permission.EDIT_TOPIC_NAME)
                .ref("refs/heads/master")
                .group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> gApi.changes().id(r.getChangeId()).topic(""));
    assertThat(thrown).hasMessageThat().contains("edit topic name not permitted");
  }

  @Test
  public void editTopicWithPermissionAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("");
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.EDIT_TOPIC_NAME).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).topic("mytopic");
    assertThat(gApi.changes().id(r.getChangeId()).topic()).isEqualTo("mytopic");
  }

  @Test
  public void submitted() throws Exception {
    PushOneCommit.Result r = createChange();
    String id = r.getChangeId();

    ChangeInfo c = gApi.changes().id(r.getChangeId()).info();
    assertThat(c.submitted).isNull();
    assertThat(c.submitter).isNull();

    gApi.changes().id(id).current().review(ReviewInput.approve());
    gApi.changes().id(id).current().submit();

    c = gApi.changes().id(r.getChangeId()).info();
    assertThat(c.submitted).isNotNull();
    assertThat(c.submitter).isNotNull();
    assertThat(c.submitter._accountId)
        .isEqualTo(localCtx.getContext().getUser().getAccountId().get());
  }

  @Test
  public void submitStaleChange() throws Exception {
    PushOneCommit.Result r = createChange();

    try (AutoCloseable ignored = changeIndexOperations.disableWrites()) {
      r = amendChange(r.getChangeId());
    }

    gApi.changes().id(r.getChangeId()).current().review(ReviewInput.approve());

    gApi.changes().id(r.getChangeId()).current().submit();
    assertThat(gApi.changes().id(r.getChangeId()).info().status).isEqualTo(MERGED);
  }

  @Test
  public void submitNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () -> gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit());
    assertThat(thrown).hasMessageThat().contains("submit not permitted");
  }

  @Test
  public void submitAllowedWithPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();
    assertThat(gApi.changes().id(r.getChangeId()).info().status).isEqualTo(MERGED);
  }

  @Test
  public void submitToSymref() throws Exception {
    // Create symref in the origin repository (testRepo references to a local repository)
    try (RefUpdateContext ctx = openTestRefUpdateContext()) {
      try (Repository repo = repoManager.openRepository(project)) {
        RefUpdate u = repo.updateRef("refs/heads/master_symref");
        assertThat(u.link("refs/heads/master")).isEqualTo(Result.NEW);
      }
    }

    PushOneCommit.Result r = createChange("refs/for/master_symref");
    String id = r.getChangeId();

    gApi.changes().id(id).current().review(ReviewInput.approve());
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class, () -> gApi.changes().id(id).current().submit());
    assertThat(thrown).hasMessageThat().contains("the target branch is a symbolic ref");
  }

  @Test
  public void check() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(gApi.changes().id(r.getChangeId()).get().problems).isNull();
    assertThat(gApi.changes().id(r.getChangeId()).get(CHECK).problems).isEmpty();
  }

  @Test
  public void commitFooters() throws Exception {
    LabelType verified =
        label(LabelId.VERIFIED, value(1, "Passes"), value(0, "No score"), value(-1, "Failed"));
    LabelType custom1 =
        label("Custom1", value(1, "Positive"), value(0, "No score"), value(-1, "Negative"));
    LabelType custom2 =
        label("Custom2", value(1, "Positive"), value(0, "No score"), value(-1, "Negative"));
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.getConfig().upsertLabelType(custom1);
      u.getConfig().upsertLabelType(custom2);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref("refs/heads/*").group(ANONYMOUS_USERS).range(-1, 1))
        .add(allowLabel(custom1.getName()).ref("refs/heads/*").group(ANONYMOUS_USERS).range(-1, 1))
        .add(allowLabel(custom2.getName()).ref("refs/heads/*").group(ANONYMOUS_USERS).range(-1, 1))
        .update();

    PushOneCommit.Result r1 = createChange();
    r1.assertOkStatus();
    PushOneCommit.Result r2 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "new content", r1.getChangeId())
            .to("refs/for/master");
    r2.assertOkStatus();

    ReviewInput in = new ReviewInput();
    in.label(LabelId.CODE_REVIEW, 1);
    in.label(LabelId.VERIFIED, 1);
    in.label("Custom1", -1);
    in.label("Custom2", 1);
    gApi.changes().id(r2.getChangeId()).current().review(in);

    ChangeInfo actual = gApi.changes().id(r2.getChangeId()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    assertThat(actual.revisions).hasSize(2);

    // No footers except on latest patch set.
    assertThat(actual.revisions.get(r1.getCommit().getName()).commitWithFooters).isNull();

    List<String> footers =
        new ArrayList<>(
            Arrays.asList(
                actual.revisions.get(r2.getCommit().getName()).commitWithFooters.split("\\n")));
    // remove subject + blank line
    footers.remove(0);
    footers.remove(0);

    List<String> expectedFooters =
        Arrays.asList(
            "Change-Id: " + r2.getChangeId(),
            "Reviewed-on: "
                + canonicalWebUrl.get()
                + "c/"
                + project.get()
                + "/+/"
                + r2.getChange().getId(),
            "Reviewed-by: Administrator <admin@example.com>",
            "Custom2: Administrator <admin@example.com>",
            "Tested-by: Administrator <admin@example.com>");

    assertThat(footers).containsExactlyElementsIn(expectedFooters);
  }

  @Test
  public void customCommitFooters() throws Exception {
    PushOneCommit.Result change = createChange();
    ChangeInfo actual;
    ChangeMessageModifier link =
        new ChangeMessageModifier() {
          @Override
          public String onSubmit(
              String newCommitMessage,
              RevCommit original,
              RevCommit mergeTip,
              BranchNameKey destination) {
            assertThat(original.getName()).isNotEqualTo(mergeTip.getName());
            return newCommitMessage + "Custom: " + destination.branch();
          }
        };
    try (Registration registration = extensionRegistry.newRegistration().add(link)) {
      actual = gApi.changes().id(change.getChangeId()).get(ALL_REVISIONS, COMMIT_FOOTERS);
    }
    List<String> footers =
        new ArrayList<>(
            Arrays.asList(
                actual.revisions.get(change.getCommit().getName()).commitWithFooters.split("\\n")));
    // remove subject + blank line
    footers.remove(0);
    footers.remove(0);

    List<String> expectedFooters =
        Arrays.asList(
            "Change-Id: " + change.getChangeId(),
            "Reviewed-on: "
                + canonicalWebUrl.get()
                + "c/"
                + project.get()
                + "/+/"
                + change.getChange().getId(),
            "Custom: refs/heads/master");
    assertThat(footers).containsExactlyElementsIn(expectedFooters);
  }

  @Test
  public void stableRevisionSort() throws Exception {
    PushOneCommit.Result r1 = createChange();
    r1.assertOkStatus();
    PushOneCommit.Result r2 = amendChange(r1.getChangeId());
    r2.assertOkStatus();

    ChangeInfo actual = gApi.changes().id(r2.getChangeId()).get(ALL_REVISIONS, CURRENT_REVISION);
    assertThat(actual.revisions).hasSize(2);
    assertThat(actual.revisions.values().stream().map(r -> r._number)).isInOrder();
  }

  @Test
  public void defaultSearchDoesNotTouchDatabase() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    PushOneCommit.Result r1 = createChange();
    gApi.changes()
        .id(r1.getChangeId())
        .revision(r1.getCommit().name())
        .review(ReviewInput.approve());
    gApi.changes().id(r1.getChangeId()).revision(r1.getCommit().name()).submit();

    PushOneCommit.Result change = createChange();
    // Populate change with a reasonable set of fields. We can't exhaustively
    // test all possible variations, but can try to cover a reasonable set.
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).addReviewer(user.email());

    requestScopeOperations.setApiUser(user.id());
    try (AutoCloseable ignored = disableNoteDb()) {
      assertThat(
              gApi.changes()
                  .query()
                  .withQuery("project:{" + project.get() + "} (status:open OR status:closed)")
                  .withOptions(IndexPreloadingUtil.DASHBOARD_OPTIONS)
                  .get())
          .hasSize(2);
    }
  }

  @Test
  public void nonLazyloadQueryOptionsDoNotTouchDatabase() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    PushOneCommit.Result r1 = createChange();
    gApi.changes()
        .id(r1.getChangeId())
        .revision(r1.getCommit().name())
        .review(ReviewInput.approve());
    gApi.changes().id(r1.getChangeId()).revision(r1.getCommit().name()).submit();

    PushOneCommit.Result change = createChange();
    // Populate change with a reasonable set of fields. We can't exhaustively
    // test all possible variations, but can try to cover a reasonable set.
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).addReviewer(user.email());

    requestScopeOperations.setApiUser(user.id());
    try (AutoCloseable ignored = disableNoteDb()) {
      assertThat(
              gApi.changes()
                  .query()
                  .withQuery("project:{" + project.get() + "} (status:open OR status:closed)")
                  .withOptions(EnumSet.complementOf(EnumSet.copyOf(ChangeJson.REQUIRE_LAZY_LOAD)))
                  .get())
          .hasSize(2);
    }
  }

  @Test
  public void votable() throws Exception {
    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();
    gApi.changes().id(triplet).addReviewer(user.username());
    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get(LabelId.CODE_REVIEW);
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.value).isEqualTo(0);

    projectOperations
        .project(project)
        .forUpdate()
        .add(
            blockLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-1, 1))
        .update();

    c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    codeReview = c.labels.get(LabelId.CODE_REVIEW);
    assertThat(codeReview.all).hasSize(1);
    approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.value).isNull();
  }

  @Test
  @GerritConfig(name = "gerrit.editGpgKeys", value = "true")
  @GerritConfig(name = "receive.enableSignedPush", value = "true")
  public void pushCertificates() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = amendChange(r1.getChangeId());

    ChangeInfo info = gApi.changes().id(r1.getChangeId()).get(ALL_REVISIONS, PUSH_CERTIFICATES);

    RevisionInfo rev1 = info.revisions.get(r1.getCommit().name());
    assertThat(rev1).isNotNull();
    assertThat(rev1.pushCertificate).isNotNull();
    assertThat(rev1.pushCertificate.certificate).isNull();
    assertThat(rev1.pushCertificate.key).isNull();

    RevisionInfo rev2 = info.revisions.get(r2.getCommit().name());
    assertThat(rev2).isNotNull();
    assertThat(rev2.pushCertificate).isNotNull();
    assertThat(rev2.pushCertificate.certificate).isNull();
    assertThat(rev2.pushCertificate.key).isNull();
  }

  @Test
  public void anonymousRestApi() throws Exception {
    requestScopeOperations.setApiUserAnonymous();
    PushOneCommit.Result r = createChange();

    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());

    String triplet = project.get() + "~master~" + r.getChangeId();
    info = gApi.changes().id(triplet).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());

    info = gApi.changes().id(info._number).get();
    assertThat(info.changeId).isEqualTo(r.getChangeId());
    assertThrows(
        AuthException.class,
        () -> gApi.changes().id(triplet).current().review(ReviewInput.approve()));
  }

  @Test
  public void commitsOnPatchSetCreation() throws Exception {
    PushOneCommit.Result r = createChange();
    pushFactory
        .create(admin.newIdent(), testRepo, PushOneCommit.SUBJECT, "b.txt", "4711", r.getChangeId())
        .to("refs/for/master")
        .assertOkStatus();
    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit commitPatchSetCreation =
          rw.parseCommit(repo.exactRef(changeMetaRef(Change.id(c._number))).getObjectId());

      assertThat(commitPatchSetCreation.getShortMessage()).isEqualTo("Create patch set 2");
      PersonIdent expectedAuthor =
          changeNoteUtil.newAccountIdIdent(
              getAccount(admin.id()).id(), c.updated.toInstant(), serverIdent.get());
      assertThat(commitPatchSetCreation.getAuthorIdent()).isEqualTo(expectedAuthor);
      assertThat(commitPatchSetCreation.getCommitterIdent())
          .isEqualTo(new PersonIdent(serverIdent.get(), c.updated));
      assertThat(commitPatchSetCreation.getParentCount()).isEqualTo(1);

      RevCommit commitChangeCreation = rw.parseCommit(commitPatchSetCreation.getParent(0));
      assertThat(commitChangeCreation.getShortMessage()).isEqualTo("Create change");
      expectedAuthor =
          changeNoteUtil.newAccountIdIdent(
              getAccount(admin.id()).id(), c.created.toInstant(), serverIdent.get());
      assertThat(commitChangeCreation.getAuthorIdent()).isEqualTo(expectedAuthor);
      assertThat(commitChangeCreation.getCommitterIdent())
          .isEqualTo(new PersonIdent(serverIdent.get(), c.created));
      assertThat(commitChangeCreation.getParentCount()).isEqualTo(0);
    }
  }

  @Test
  public void createEmptyChangeOnNonExistingBranch() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = "foo";
    in.subject = "Create a change on new branch from the API";
    in.project = project.get();
    in.newBranch = true;
    ChangeInfo info = gApi.changes().create(in).get();
    assertThat(info.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(info.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(info.subject).isEqualTo(in.subject);
    assertThat(Iterables.getOnlyElement(info.messages).message).isEqualTo("Uploaded patch set 1.");
  }

  @Test
  public void createEmptyChangeOnExistingBranchWithNewBranch() throws Exception {
    ChangeInput in = new ChangeInput();
    in.branch = Constants.MASTER;
    in.subject = "Create a change on new branch from the API";
    in.project = project.get();
    in.newBranch = true;

    assertThrows(ResourceConflictException.class, () -> gApi.changes().create(in).get());
  }

  @Test
  public void createNewPatchSetWithoutPermission() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();

    // Clone separate repositories of the same project as admin and as user
    TestRepository<InMemoryRepository> adminTestRepo = cloneProject(p, admin);
    TestRepository<InMemoryRepository> userTestRepo = cloneProject(p, user);

    // Block default permission
    projectOperations
        .project(p)
        .forUpdate()
        .add(block(Permission.ADD_PATCH_SET).ref("refs/for/*").group(REGISTERED_USERS))
        .update();

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(userTestRepo, r1.getPatchSet().refName() + ":ps");
    userTestRepo.reset("ps");

    // Amend change as user
    PushOneCommit.Result r2 = amendChange(r1.getChangeId(), "refs/for/master", user, userTestRepo);
    r2.assertErrorStatus("cannot add patch set to " + r1.getChange().getId().get() + ".");
  }

  @Test
  public void createNewSetPatchWithPermission() throws Exception {
    // Clone separate repositories of the same project as admin and as user
    TestRepository<?> adminTestRepo = cloneProject(project, admin);
    TestRepository<?> userTestRepo = cloneProject(project, user);

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(userTestRepo, r1.getPatchSet().refName() + ":ps");
    userTestRepo.reset("ps");

    // Amend change as user
    PushOneCommit.Result r2 = amendChange(r1.getChangeId(), "refs/for/master", user, userTestRepo);
    r2.assertOkStatus();
  }

  @Test
  public void createNewPatchSetAsOwnerWithoutPermission() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();
    // Clone separate repositories of the same project as admin and as user
    TestRepository<?> adminTestRepo = cloneProject(project, admin);

    // Block default permission
    projectOperations
        .project(p)
        .forUpdate()
        .add(block(Permission.ADD_PATCH_SET).ref("refs/for/*").group(REGISTERED_USERS))
        .update();

    // Create change as admin
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    r1.assertOkStatus();

    // Fetch change
    GitUtil.fetch(adminTestRepo, r1.getPatchSet().refName() + ":ps");
    adminTestRepo.reset("ps");

    // Amend change as admin
    PushOneCommit.Result r2 =
        amendChange(r1.getChangeId(), "refs/for/master", admin, adminTestRepo);
    r2.assertOkStatus();
  }

  private void assertLabelDescription(ChangeInfo changeInfo, String labelName, String description) {
    assertThat(changeInfo.labels.get(labelName).description).isEqualTo(description);
  }

  @Test
  public void checkLabelVotesForUnsubmittedChange() throws Exception {
    List<ListChangesOption> options =
        EnumSet.complementOf(
                EnumSet.of(
                    ListChangesOption.CHECK,
                    ListChangesOption.SKIP_DIFFSTAT,
                    ListChangesOption.DETAILED_LABELS))
            .stream()
            .collect(Collectors.toList());
    PushOneCommit.Result r = createChange();
    ChangeInfo change = gApi.changes().id(r.getChangeId()).get(options);
    assertThat(change.status).isEqualTo(ChangeStatus.NEW);
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.labels.get(LabelId.CODE_REVIEW).all).isNull();

    voteLabel(r.getChangeId(), LabelId.CODE_REVIEW, +1);
    change = gApi.changes().id(r.getChangeId()).get(options);
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    List<ApprovalInfo> codeReviewApprovals = change.labels.get(LabelId.CODE_REVIEW).all;
    assertThat(codeReviewApprovals).hasSize(1);
    ApprovalInfo codeReviewApproval = codeReviewApprovals.get(0);
    // permittedVotingRange is not served if DETAILED_LABELS is not requested.
    assertThat(codeReviewApproval.permittedVotingRange).isNull();
    assertThat(codeReviewApproval.value).isEqualTo(1);
    if (server.isUsernameSupported()) {
      assertThat(codeReviewApproval.username).isEqualTo(admin.username());
    }

    // Add another +1 vote as user
    requestScopeOperations.setApiUser(user.id());
    voteLabel(r.getChangeId(), LabelId.CODE_REVIEW, +1);
    change = gApi.changes().id(r.getChangeId()).get(options);
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.labels.get(LabelId.CODE_REVIEW).all).hasSize(2);
    // All available label votes and their meanings are also served if DETAILED_LABELS is not
    // requested.
    assertThat(change.labels.get(LabelId.CODE_REVIEW).values).isNotNull();
    codeReviewApprovals = change.labels.get(LabelId.CODE_REVIEW).all;
    assertThat(codeReviewApprovals.stream().map(a -> a.permittedVotingRange).collect(toList()))
        .containsExactly(null, null);
    assertThat(codeReviewApprovals.stream().map(a -> a.value).collect(toList()))
        .containsExactly(1, 1);
    if (server.isUsernameSupported()) {
      assertThat(codeReviewApprovals.stream().map(a -> a.username).collect(toList()))
          .containsExactly(admin.username(), user.username());
    }
    assertThat(codeReviewApprovals.stream().map(a -> a._accountId).collect(toList()))
        .containsExactly(admin.id().get(), user.id().get());
  }

  @Test
  public void checkLabelsForUnsubmittedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.NEW);
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.removableLabels).isEmpty();

    // add new label and assert that it's returned for existing changes
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    LabelType verified = TestLabels.verified();
    String heads = RefNames.REFS_HEADS + "*";

    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(registeredUsers).range(-1, 1))
        .update();

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW, LabelId.VERIFIED);
    assertThat(change.permittedLabels.keySet())
        .containsExactly(LabelId.CODE_REVIEW, LabelId.VERIFIED);
    assertPermitted(change, LabelId.CODE_REVIEW, -2, -1, 0, 1, 2);
    assertPermitted(change, LabelId.VERIFIED, -1, 0, 1);
    assertLabelDescription(change, LabelId.VERIFIED, TestLabels.VERIFIED_LABEL_DESCRIPTION);

    // add an approval on the new label
    gApi.changes()
        .id(r.getChangeId())
        .revision(r.getCommit().name())
        .review(new ReviewInput().label(verified.getName(), verified.getMax().getValue()));
    change = gApi.changes().id(r.getChangeId()).get();
    assertPermitted(change, LabelId.VERIFIED, -1, 0, 1);
    assertOnlyRemovableLabel(change, LabelId.VERIFIED, "+1", admin);

    try (ProjectConfigUpdate u = updateProject(project)) {
      // remove label and assert that it's no longer returned for existing
      // changes, even if there is an approval for it
      u.getConfig().getLabelSections().remove(verified.getName());
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .remove(
            permissionKey(Permission.forLabel(verified.getName()))
                .ref(heads)
                .group(registeredUsers))
        .update();

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.removableLabels).isEmpty();

    // abandon the change and see that the returned labels stay the same
    // while all permitted labels disappear.
    gApi.changes().id(r.getChangeId()).abandon();
    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ChangeStatus.ABANDONED);
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels).isEmpty();
    assertThat(change.removableLabels).isEmpty();
  }

  @Test
  public void checkLabelVotesForMergedChange() throws Exception {
    List<ListChangesOption> options =
        EnumSet.complementOf(
                EnumSet.of(
                    ListChangesOption.CHECK,
                    ListChangesOption.SKIP_DIFFSTAT,
                    ListChangesOption.DETAILED_LABELS))
            .stream()
            .collect(Collectors.toList());
    PushOneCommit.Result r = createChange();
    voteLabel(r.getChangeId(), LabelId.CODE_REVIEW, +2);

    // Add another label for 'Verified'
    LabelType verified = TestLabels.verified();
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(registeredUsers).range(-1, 1))
        .update();

    // Submit the change
    voteLabel(r.getChangeId(), TestLabels.verified().getName(), 1);
    gApi.changes().id(r.getChangeId()).current().submit();

    // Make sure label votes are available if DETAILED_LABELS is not requested.
    ChangeInfo change = gApi.changes().id(r.getChangeId()).get(options);
    assertThat(change.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(change.labels.keySet())
        .containsExactly(LabelId.CODE_REVIEW, TestLabels.verified().getName());
    List<ApprovalInfo> codeReviewApprovals = change.labels.get(LabelId.CODE_REVIEW).all;
    List<ApprovalInfo> verifiedApprovals = change.labels.get(TestLabels.verified().getName()).all;

    assertThat(codeReviewApprovals).hasSize(1);
    assertThat(codeReviewApprovals.get(0).value).isEqualTo(2);
    if (server.isUsernameSupported()) {
      assertThat(codeReviewApprovals.get(0).username).isEqualTo(admin.username());
    }
    assertThat(codeReviewApprovals.get(0).permittedVotingRange).isNull();

    assertThat(verifiedApprovals).hasSize(1);
    assertThat(verifiedApprovals.get(0).value).isEqualTo(1);
    if (server.isUsernameSupported()) {
      assertThat(verifiedApprovals.get(0).username).isEqualTo(admin.username());
    }
    assertThat(codeReviewApprovals.get(0).permittedVotingRange).isNull();
  }

  @Test
  public void checkLabelsForMergedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(MERGED);
    assertThat(change.submissionId).isNotNull();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertPermitted(change, LabelId.CODE_REVIEW, 2);

    LabelType verified = TestLabels.verified();
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";

    // add new label and assert that it's returned for existing changes
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(registeredUsers).range(-1, 1))
        .update();

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW, LabelId.VERIFIED);
    assertThat(change.permittedLabels.keySet())
        .containsExactly(LabelId.CODE_REVIEW, LabelId.VERIFIED);
    assertPermitted(change, LabelId.CODE_REVIEW, 2);
    assertPermitted(change, LabelId.VERIFIED, 0, 1);
    assertLabelDescription(change, LabelId.VERIFIED, TestLabels.VERIFIED_LABEL_DESCRIPTION);
  }

  @Test
  @GerritConfig(name = "change.skipCurrentRulesEvaluationOnClosedChanges", value = "true")
  public void checkLabelsNotUpdatedForMergedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).submit();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(MERGED);
    assertThat(change.submissionId).isNotNull();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertPermitted(change, LabelId.CODE_REVIEW, 2);

    LabelType verified = TestLabels.verified();
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";

    // add new label and assert that it's returned for existing changes
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(registeredUsers).range(-1, 1))
        .update();

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(change.permittedLabels.keySet())
        .containsExactly(LabelId.CODE_REVIEW, LabelId.VERIFIED);
    assertPermitted(change, LabelId.CODE_REVIEW, 2);
  }

  @Test
  @GerritConfig(name = "change.skipCurrentRulesEvaluationOnClosedChanges", value = "true")
  public void checkLabelsNotUpdatedForAbandonedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).abandon();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(ABANDONED);
    assertThat(change.labels.keySet()).isEmpty();
    assertThat(change.submitRecords).isEmpty();

    LabelType verified = TestLabels.verified();
    AccountGroup.UUID registeredUsers = systemGroupBackend.getGroup(REGISTERED_USERS).getUUID();
    String heads = RefNames.REFS_HEADS + "*";

    // add new label and assert that it's returned for existing changes
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig().upsertLabelType(verified);
      u.save();
    }
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(verified.getName()).ref(heads).group(registeredUsers).range(-1, 1))
        .update();

    change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.labels.keySet()).isEmpty();
    assertThat(change.permittedLabels.keySet()).isEmpty();
    assertThat(change.submitRecords).isEmpty();
  }

  @Test
  public void notifyConfigForDirectoryTriggersEmail() throws Exception {
    // Configure notifications on project level.
    RevCommit oldHead = projectOperations.project(project).getHead("master");
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "Configure Notifications",
            "project.config",
            "[notify \"my=notify-config\"]\n"
                + "  email = foo@example.com\n"
                + "  filter = dir:\\\"foo/bar/baz\\\"");
    push.to(RefNames.REFS_CONFIG);
    testRepo.reset(oldHead);

    // Push a change that matches the filter.
    sender.clear();
    push =
        pushFactory.create(
            admin.newIdent(), testRepo, "Test change", "foo/bar/baz/test.txt", "some content");
    PushOneCommit.Result r = push.to("refs/for/master");
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).rcpt())
        .containsExactly(Address.parse("foo@example.com"));

    // Comment on the change.
    sender.clear();
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.message = "some message";
    gApi.changes().id(r.getChangeId()).current().review(reviewInput);
    assertThat(sender.getMessages()).hasSize(1);
    assertThat(sender.getMessages().get(0).rcpt())
        .containsExactly(Address.parse("foo@example.com"));
  }

  @Test
  @GerritConfig(name = "rules.allowNewRules", value = "true")
  public void uploadingRulesPlIsNotAllowed() throws Exception {
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    PushOneCommit.Result pushResult =
        pushFactory
            .create(
                admin.newIdent(),
                testRepo,
                "Add prolog rules",
                RULES_PL_FILE,
                "submit_rule(S) :-\n"
                    + "  gerrit:default_submit(X),\n"
                    + "  X =.. [submit | Ls],\n"
                    + "  add_non_author_approval(Ls, R),\n"
                    + "  S =.. [submit | R].\n"
                    + "\n"
                    + "add_non_author_approval(S1, S2) :-\n"
                    + "  gerrit:commit_author(A),\n"
                    + "  gerrit:commit_label(label('Code-Review', 2), R),\n"
                    + "  R \\= A, !,\n"
                    + "  S2 = [label('Non-Author-Code-Review', ok(R)) | S1].\n"
                    + "add_non_author_approval(S1,"
                    + " [label('Non-Author-Code-Review', need(_)) | S1]).")
            .to(RefNames.REFS_CONFIG);
    pushResult.assertOkStatus();
    pushResult.assertMessage(
        String.format(
            "WARNING: commit %s: Uploading a new 'rules.pl' file is discouraged. "
                + "Please consider adding submit-requirements instead.",
            ObjectIds.abbreviateName(pushResult.getCommit())));
  }

  @Test
  public void modifyingRulesPlIsAllowed() throws Exception {
    // Committing the rules.pl change directly to the repository to bypass gerrit validation.
    modifySubmitRules(
        "submit_rule(submit(R)) :- \n"
            + "gerrit:unresolved_comments_count(2), \n"
            + "!,"
            + "gerrit:uploader(U), \n"
            + "R = label('All-Comments-Resolved', ok(U)).\n");
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    pushFactory
        .create(
            admin.newIdent(),
            testRepo,
            "Update prolog rules",
            RULES_PL_FILE,
            "submit_rule(submit(R)) :- \n"
                + "gerrit:unresolved_comments_count(0), \n"
                + "!,"
                + "gerrit:uploader(U), \n"
                + "R = label('All-Comments-Resolved', ok(U)).\n")
        .to(RefNames.REFS_CONFIG)
        .assertOkStatus();
  }

  @Test
  public void deletingRulesPlIsAllowed() throws Exception {
    // Committing the rules.pl change directly to the repository to bypass gerrit validation.
    modifySubmitRules(
        "submit_rule(submit(R)) :- \n"
            + "gerrit:unresolved_comments_count(2), \n"
            + "!,"
            + "gerrit:uploader(U), \n"
            + "R = label('All-Comments-Resolved', ok(U)).\n");
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":config");
    testRepo.reset("config");
    pushFactory
        .create(
            admin.newIdent(),
            testRepo,
            /* subject= */ "Remove prolog rules",
            /* files= */ ImmutableMap.of())
        .rmFile(RULES_PL_FILE)
        .to(RefNames.REFS_CONFIG)
        .assertOkStatus();
  }

  @Test
  public void checkLabelsForAutoClosedChange() throws Exception {
    PushOneCommit.Result r = createChange();

    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result result = push.to("refs/heads/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(r.getChangeId()).get();
    assertThat(change.status).isEqualTo(MERGED);
    assertThat(change.submissionId).isNotNull();
    assertThat(change.labels.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertPermitted(change, LabelId.CODE_REVIEW, 0, 1, 2);
    assertThat(change.removableLabels).isEmpty();
  }

  @Test
  public void checkSubmissionIdForAutoClosedChange() throws Exception {
    PushOneCommit.Result first = createChange();
    PushOneCommit.Result second = createChange();

    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);

    PushOneCommit.Result result = push.to("refs/heads/master");
    result.assertOkStatus();

    ChangeInfo firstChange = gApi.changes().id(first.getChangeId()).get();
    assertThat(firstChange.status).isEqualTo(MERGED);
    assertThat(firstChange.submissionId).isNotNull();

    ChangeInfo secondChange = gApi.changes().id(second.getChangeId()).get();
    assertThat(secondChange.status).isEqualTo(MERGED);
    assertThat(secondChange.submissionId).isNotNull();

    assertThat(secondChange.submissionId).isEqualTo(firstChange.submissionId);
    assertThat(gApi.changes().id(second.getChangeId()).submittedTogether()).hasSize(2);
  }

  @Test
  public void maxPermittedValueAllowed() throws Exception {
    final int minPermittedValue = -2;
    final int maxPermittedValue = +2;
    String heads = "refs/heads/*";

    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();

    gApi.changes().id(triplet).addReviewer(user.username());

    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get(LabelId.CODE_REVIEW);
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNotNull();
    // default values
    assertThat(approval.permittedVotingRange.min).isEqualTo(-1);
    assertThat(approval.permittedVotingRange.max).isEqualTo(1);

    projectOperations
        .project(project)
        .forUpdate()
        .add(
            allowLabel(LabelId.CODE_REVIEW)
                .ref(heads)
                .group(REGISTERED_USERS)
                .range(minPermittedValue, maxPermittedValue))
        .update();

    c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    codeReview = c.labels.get(LabelId.CODE_REVIEW);
    assertThat(codeReview.all).hasSize(1);
    approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNotNull();
    assertThat(approval.permittedVotingRange.min).isEqualTo(minPermittedValue);
    assertThat(approval.permittedVotingRange.max).isEqualTo(maxPermittedValue);
  }

  @Test
  public void maxPermittedValueBlocked() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            blockLabel(LabelId.CODE_REVIEW)
                .ref("refs/heads/*")
                .group(REGISTERED_USERS)
                .range(-1, 1))
        .update();

    PushOneCommit.Result r = createChange();
    String triplet = project.get() + "~master~" + r.getChangeId();

    gApi.changes().id(triplet).addReviewer(user.username());

    ChangeInfo c = gApi.changes().id(triplet).get(DETAILED_LABELS);
    LabelInfo codeReview = c.labels.get(LabelId.CODE_REVIEW);
    assertThat(codeReview.all).hasSize(1);
    ApprovalInfo approval = codeReview.all.get(0);
    assertThat(approval._accountId).isEqualTo(user.id().get());
    assertThat(approval.permittedVotingRange).isNull();
  }

  @Test
  public void nonStrictLabelWithInvalidLabelPerDefault() throws Exception {
    String changeId = createChange().getChangeId();

    // Add a review with invalid labels.
    ReviewInput input = ReviewInput.approve().label("Code-Style", 1);
    gApi.changes().id(changeId).current().review(input);

    Map<String, Short> votes =
        gApi.changes().id(changeId).current().reviewer(admin.email()).votes();
    assertThat(votes.keySet()).containsExactly(LabelId.CODE_REVIEW);
    assertThat(votes.values()).containsExactly((short) 2);
  }

  @Test
  public void nonStrictLabelWithInvalidValuePerDefault() throws Exception {
    String changeId = createChange().getChangeId();

    // Add a review with invalid label values.
    ReviewInput input = new ReviewInput().label(LabelId.CODE_REVIEW, 3);
    gApi.changes().id(changeId).current().review(input);

    assertThrows(
        ResourceNotFoundException.class,
        () -> gApi.changes().id(changeId).current().reviewer(admin.email()));
  }

  @Test
  @GerritConfig(name = "change.strictLabels", value = "true")
  public void strictLabelWithInvalidLabel() throws Exception {
    String changeId = createChange().getChangeId();
    ReviewInput in = new ReviewInput().label("Code-Style", 1);

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(changeId).current().review(in));
    assertThat(thrown).hasMessageThat().contains("label \"Code-Style\" is not a configured label");
  }

  @Test
  @GerritConfig(name = "change.strictLabels", value = "true")
  public void strictLabelWithInvalidValue() throws Exception {
    String changeId = createChange().getChangeId();
    ReviewInput in = new ReviewInput().label(LabelId.CODE_REVIEW, 3);

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(changeId).current().review(in));
    assertThat(thrown).hasMessageThat().contains("label \"Code-Review\": 3 is not a valid value");
  }

  @Test
  public void getCommitMessage() throws Exception {
    String subject = "Change Subject";
    String changeId = "I" + ObjectId.toString(CommitMessageUtil.generateChangeId());
    String commitMessage =
        String.format(
            "%s\n\nFirst Paragraph.\n\nSecond Paragraph\n\nFoo: Bar\nChange-Id: %s\n",
            subject, changeId);
    changeOperations.newChange().project(project).commitMessage(commitMessage).createV1();

    CommitMessageInfo commitMessageInfo = gApi.changes().id(changeId).getMessage();
    assertThat(commitMessageInfo.subject).isEqualTo(subject);
    assertThat(commitMessageInfo.fullMessage).isEqualTo(commitMessage);
    assertThat(commitMessageInfo.footers).containsExactly("Foo", "Bar", "Change-Id", changeId);
  }

  @Test
  public void getCommitMessageThatHasDuplicateFooters() throws Exception {
    String subject = "Change Subject";
    String changeId = "I" + ObjectId.toString(CommitMessageUtil.generateChangeId());
    String commitMessage =
        String.format(
            "%s\n\nFirst Paragraph.\n\nSecond Paragraph\n\nFoo: Bar\nFoo: Baz\nChange-Id: %s\n",
            subject, changeId);
    changeOperations.newChange().project(project).commitMessage(commitMessage).createV1();

    CommitMessageInfo commitMessageInfo = gApi.changes().id(changeId).getMessage();
    assertThat(commitMessageInfo.subject).isEqualTo(subject);
    assertThat(commitMessageInfo.fullMessage).isEqualTo(commitMessage);

    // only the last "Foo" footer is returned
    assertThat(commitMessageInfo.footers).containsExactly("Foo", "Baz", "Change-Id", changeId);
  }

  @Test
  public void changeCommitMessage() throws Exception {
    // Tests mutating the commit message as both the owner of the change and a regular user with
    // addPatchSet permission. Asserts that both cases succeed.
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    for (com.google.gerrit.acceptance.TestAccount acc : ImmutableList.of(admin, user)) {
      requestScopeOperations.setApiUser(acc.id());
      String newMessage =
          "modified commit by " + acc.id() + "\n\nChange-Id: " + r.getChangeId() + "\n";
      gApi.changes().id(r.getChangeId()).setMessage(newMessage);
      RevisionApi rApi = gApi.changes().id(r.getChangeId()).current();
      assertThat(rApi.files().keySet()).containsExactly("/COMMIT_MSG", "a.txt");
      assertThat(getCommitMessage(r.getChangeId())).isEqualTo(newMessage);
      assertThat(rApi.description()).isEqualTo("Edit commit message");
    }

    // Verify tags, which should differ according to whether the change was WIP
    // at the time the commit message was edited. First, look at the last edit
    // we created above, when the change was not WIP.
    ChangeInfo info = gApi.changes().id(r.getChangeId()).get();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_PATCH_SET);

    // Move the change to WIP and edit the commit message again, to observe a
    // different tag. Must switch to change owner to move into WIP.
    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(r.getChangeId()).setWorkInProgress();
    String newMessage = "modified commit in WIP change\n\nChange-Id: " + r.getChangeId() + "\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);
    info = gApi.changes().id(r.getChangeId()).get();
    assertThat(Iterables.getLast(info.messages).tag)
        .isEqualTo(ChangeMessagesUtil.TAG_UPLOADED_WIP_PATCH_SET);
  }

  @Test
  public void changeCommitMessageAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create change
    Change.Id change = changeOperations.newChange().project(project).owner(testUser).createV1();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Change commit message
    ChangeInfo changeInfo = gApi.changes().id(change.get()).get();
    String msg = String.format("New commit message\n\nChange-Id: %s\n", changeInfo.changeId);
    gApi.changes().id(change.get()).setMessage(msg);

    assertThat(gApi.changes().id(change.get()).get().getCurrentRevision().commit.committer.email)
        .isEqualTo(emailOne);
  }

  @Test
  public void changeCommitMessageFromChangeIdToLinkFooter() throws Exception {
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    requestScopeOperations.setApiUser(admin.id());
    String newMessage =
        "modified commit by "
            + admin.id()
            + "\n\nLink: "
            + canonicalWebUrl.get()
            + "id/"
            + r.getChangeId()
            + "\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);
    RevisionApi rApi = gApi.changes().id(r.getChangeId()).current();
    assertThat(rApi.files().keySet()).containsExactly("/COMMIT_MSG", "a.txt");
    assertThat(getCommitMessage(r.getChangeId())).isEqualTo(newMessage);
    assertThat(rApi.description()).isEqualTo("Edit commit message");
  }

  @Test
  public void changeCommitMessageWithNoChangeIdSucceedsIfChangeIdNotRequired() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .requireChangeId(InheritableBoolean.FALSE)
        .update();

    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    String newMessage = "modified commit\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);
    RevisionApi rApi = gApi.changes().id(r.getChangeId()).current();
    assertThat(rApi.files().keySet()).containsExactly("/COMMIT_MSG", "a.txt");
    assertThat(getCommitMessage(r.getChangeId())).isEqualTo(newMessage);
  }

  @Test
  public void changeCommitMessageNullNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .setMessage("test\0commit\n\nChange-Id: " + r.getChangeId() + "\n"));
    assertThat(thrown).hasMessageThat().contains("NUL character");
  }

  @Test
  public void changeCommitMessageWithWrongChangeIdFails() throws Exception {
    PushOneCommit.Result otherChange = createChange();
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .setMessage(
                        "modified commit\n\nChange-Id: " + otherChange.getChangeId() + "\n"));
    assertThat(thrown).hasMessageThat().contains("wrong Change-Id footer");
  }

  @Test
  public void changeCommitMessageWithoutPermissionFails() throws Exception {
    // Create new project with clean permissions
    Project.NameKey p = projectOperations.newProject().create();
    TestRepository<InMemoryRepository> userTestRepo = cloneProject(p, user);
    // Block default permission
    projectOperations
        .project(p)
        .forUpdate()
        .add(block(Permission.ADD_PATCH_SET).ref("refs/for/*").group(REGISTERED_USERS))
        .update();
    // Create change as user
    PushOneCommit push = pushFactory.create(user.newIdent(), userTestRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();
    assertThat(gApi.changes().id(r.getChangeId()).info().owner._accountId)
        .isEqualTo(user.id().get());
    // Try to change the commit message
    AuthException thrown =
        assertThrows(
            AuthException.class, () -> gApi.changes().id(r.getChangeId()).setMessage("foo"));
    assertThat(thrown).hasMessageThat().contains("modifying commit message not permitted");
  }

  @Test
  public void changeCommitMessageWithSameMessageFails() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).setMessage(getCommitMessage(r.getChangeId())));
    assertThat(thrown).hasMessageThat().contains("new and existing commit message are the same");
  }

  @Test
  public void changeCommitMessageInvokesCommitValidators() throws Exception {
    PushOneCommit.Result r = createChange();
    r.assertOkStatus();
    assertThat(getCommitMessage(r.getChangeId()))
        .isEqualTo("test commit\n\nChange-Id: " + r.getChangeId() + "\n");

    requestScopeOperations.setApiUser(admin.id());
    String newMessage = "modified commit message\nChange-Id: " + r.getChangeId() + "\n";

    TestCommitValidationListener testCommitValidationListener = new TestCommitValidationListener();
    try (Registration registration =
        extensionRegistry.newRegistration().add(testCommitValidationListener)) {
      gApi.changes().id(r.getChangeId()).setMessage(newMessage);
      assertThat(testCommitValidationListener.receiveEvent).isNotNull();
    }
  }

  @Test
  public void fourByteEmoji() throws Exception {
    // U+1F601 GRINNING FACE WITH SMILING EYES
    String smile = new String(Character.toChars(0x1f601));
    assertThat(smile).isEqualTo("😁");
    assertThat(smile).hasLength(2); // Thanks, Java.
    assertThat(smile.getBytes(UTF_8)).hasLength(4);

    String subject = "A happy change " + smile;
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, subject, FILE_NAME, FILE_CONTENT)
            .to("refs/for/master");
    r.assertOkStatus();
    String id = r.getChangeId();

    ReviewInput ri = ReviewInput.approve();
    ri.message = "I like it " + smile;
    ReviewInput.CommentInput ci = new ReviewInput.CommentInput();
    ci.path = FILE_NAME;
    ci.side = Side.REVISION;
    ci.message = "Good " + smile;
    ri.comments = ImmutableMap.of(FILE_NAME, ImmutableList.of(ci));
    gApi.changes().id(id).current().review(ri);

    ChangeInfo info = gApi.changes().id(id).get(MESSAGES, CURRENT_COMMIT, CURRENT_REVISION);
    assertThat(info.subject).isEqualTo(subject);
    assertThat(Iterables.getLast(info.messages).message).endsWith(ri.message);
    assertThat(Iterables.getOnlyElement(info.revisions.values()).commit.message)
        .startsWith(subject);

    List<CommentInfo> comments =
        Iterables.getOnlyElement(gApi.changes().id(id).commentsRequest().get().values());
    assertThat(Iterables.getOnlyElement(comments).message).isEqualTo(ci.message);
  }

  @Test
  public void putTopicExceedLimitFails() throws Exception {
    String changeId = createChange().getChangeId();
    String topic = Stream.generate(() -> "t").limit(2049).collect(joining());

    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> gApi.changes().id(changeId).topic(topic));
    assertThat(thrown).hasMessageThat().contains("topic length exceeds the limit");
  }

  @Test
  public void submittableAfterLosingPermissions_MaxWithBlock() throws Exception {
    configLabel("Label", LabelFunction.MAX_WITH_BLOCK);
    submittableAfterLosingPermissions("Label");
  }

  @Test
  public void submittableAfterLosingPermissions_AnyWithBlock() throws Exception {
    configLabel("Label", LabelFunction.ANY_WITH_BLOCK);
    submittableAfterLosingPermissions("Label");
  }

  @Test
  @GerritConfig(name = "change.submitWholeTopic", value = "true")
  public void cantSubmitWithInvisibleChangesWithTopic() throws Exception {
    createBranch(BranchNameKey.create(project, "secret"));

    // create two changes in the same topic.
    String topic = "topic";
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange("refs/for/secret");
    approve(r1.getChangeId());
    approve(r2.getChangeId());
    gApi.changes().id(r1.getChangeId()).topic(topic);
    gApi.changes().id(r2.getChangeId()).topic(topic);

    // make one of the changes invisible.
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/secret").group(REGISTERED_USERS))
        .update();

    // can't submit with invisible changes.
    requestScopeOperations.setApiUser(user.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/*").group(REGISTERED_USERS))
        .update();
    assertThrows(AuthException.class, () -> gApi.changes().id(r1.getChangeId()).current().submit());
  }

  @Test
  public void cantSubmitWithInvisibleDependentChange() throws Exception {
    // create two dependent changes.
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();
    approve(r1.getChangeId());
    approve(r2.getChangeId());

    // make the dependent change invisible.
    gApi.changes().id(r1.getChangeId()).setPrivate(true);

    // can't submit with invisible changes.
    requestScopeOperations.setApiUser(user.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/*").group(REGISTERED_USERS))
        .update();
    assertThrows(AuthException.class, () -> gApi.changes().id(r2.getChangeId()).current().submit());
  }

  private void submittableAfterLosingPermissions(String label) throws Exception {
    String codeReviewLabel = LabelId.CODE_REVIEW;
    AccountGroup.UUID registered = REGISTERED_USERS;
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel(label).ref("refs/heads/*").group(registered).range(-1, +1))
        .add(allowLabel(codeReviewLabel).ref("refs/heads/*").group(registered).range(-2, +2))
        .update();

    requestScopeOperations.setApiUser(user.id());
    PushOneCommit.Result r = createChange();
    String changeId = r.getChangeId();

    // Verify user's permitted range.
    ChangeInfo change = gApi.changes().id(changeId).get();
    assertPermitted(change, label, -1, 0, 1);
    assertPermitted(change, codeReviewLabel, -2, -1, 0, 1, 2);

    ReviewInput input = new ReviewInput();
    input.label(codeReviewLabel, 2);
    input.label(label, 1);
    gApi.changes().id(changeId).current().review(input);

    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().keySet())
        .containsExactly(codeReviewLabel, label);
    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().values())
        .containsExactly((short) 2, (short) 1);
    assertThat(gApi.changes().id(changeId).get().submittable).isTrue();

    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .remove(labelPermissionKey(label).ref("refs/heads/*").group(registered))
        .remove(labelPermissionKey(codeReviewLabel).ref("refs/heads/*").group(registered))
        .add(allowLabel(codeReviewLabel).ref("refs/heads/*").group(registered).range(-1, +1))
        .update();

    // Verify user's new permitted range.
    requestScopeOperations.setApiUser(user.id());
    change = gApi.changes().id(changeId).get();
    assertPermitted(change, label);
    assertPermitted(change, codeReviewLabel, -1, 0, 1);

    assertThat(gApi.changes().id(changeId).current().reviewer(user.email()).votes().values())
        .containsExactly((short) 2, (short) 1);
    assertThat(gApi.changes().id(changeId).get().submittable).isTrue();

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).current().submit();
  }

  @Test
  public void draftCommentsShouldNotUpdateChangeTimestamp() throws Exception {
    String changeId = createNewChange();
    Timestamp changeTs = getChangeLastUpdate(changeId);
    DraftApi draftApi = addDraftComment(changeId);
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
    draftApi.delete();
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
  }

  @Test
  public void deletingAllDraftCommentsShouldNotUpdateChangeTimestamp() throws Exception {
    String changeId = createNewChange();
    Timestamp changeTs = getChangeLastUpdate(changeId);
    addDraftComment(changeId);
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
    gApi.accounts().self().deleteDraftComments(new DeleteDraftCommentsInput());
    assertThat(getChangeLastUpdate(changeId)).isEqualTo(changeTs);
  }

  private Timestamp getChangeLastUpdate(String changeId) throws RestApiException {
    Timestamp changeTs = gApi.changes().id(changeId).get().updated;
    return changeTs;
  }

  private String createNewChange() throws Exception {
    TestRepository<InMemoryRepository> userRepo = cloneProject(project, user);
    PushOneCommit.Result result =
        pushFactory.create(user.newIdent(), userRepo).to("refs/for/master");
    String changeId = result.getChangeId();
    return changeId;
  }

  private DraftApi addDraftComment(String changeId) throws RestApiException {
    DraftInput comment = new DraftInput();
    comment.message = "foo";
    comment.path = "/foo";
    return gApi.changes().id(changeId).current().createDraft(comment);
  }

  private String getCommitMessage(String changeId) throws RestApiException, IOException {
    return gApi.changes().id(changeId).current().file("/COMMIT_MSG").content().asString();
  }

  private static Iterable<Account.Id> getReviewers(Collection<AccountInfo> r) {
    if (r == null) {
      return ImmutableList.of();
    }
    return Iterables.transform(r, a -> Account.id(a._accountId));
  }

  private ChangeResource parseResource(PushOneCommit.Result r) throws Exception {
    return parseChangeResource(r.getChangeId());
  }

  private Optional<ReviewerState> getReviewerState(String changeId, Account.Id accountId)
      throws Exception {
    ChangeInfo c = gApi.changes().id(changeId).get(DETAILED_LABELS);
    Set<ReviewerState> states =
        c.reviewers.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(a -> a._accountId == accountId.get()))
            .map(Map.Entry::getKey)
            .collect(toSet());
    assertWithMessage(states.toString()).that(states.size()).isAtMost(1);
    return states.stream().findFirst();
  }

  private void setChangeStatus(Change.Id id, Change.Status newStatus) throws Exception {
    try (RefUpdateContext ctx = openTestRefUpdateContext()) {
      try (BatchUpdate batchUpdate =
          batchUpdateFactory.create(project, localCtx.getContext().getUser(), TimeUtil.now())) {
        batchUpdate.addOp(id, new ChangeStatusUpdateOp(newStatus));
        batchUpdate.execute();
      }
    }

    ChangeStatus changeStatus = gApi.changes().id(id.get()).get().status;
    assertThat(changeStatus).isEqualTo(newStatus.asChangeStatus());
  }

  private static class ChangeStatusUpdateOp implements BatchUpdateOp {
    private final Change.Status newStatus;

    ChangeStatusUpdateOp(Change.Status newStatus) {
      this.newStatus = newStatus;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws Exception {
      Change change = ctx.getChange();

      // Change status.
      PatchSet.Id currentPatchSetId = change.currentPatchSetId();
      ctx.getUpdate(currentPatchSetId).setStatus(newStatus);

      return true;
    }
  }

  private void modifySubmitRules(String newContent) throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      testRepo
          .branch(RefNames.REFS_CONFIG)
          .commit()
          .author(admin.newIdent())
          .committer(admin.newIdent())
          .add(RULES_PL_FILE, newContent)
          .message("Modify rules.pl")
          .create();
    }
    projectCache.evict(project);
  }

  @Test
  @GerritConfig(name = "trackingid.jira-bug.footer", value = "Bug:")
  @GerritConfig(name = "trackingid.jira-bug.match", value = "JRA\\d{2,8}")
  @GerritConfig(name = "trackingid.jira-bug.system", value = "JIRA")
  public void trackingIds() throws Exception {
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT + "\n\n" + "Bug:JRA001",
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get(TRACKING_IDS);
    Collection<TrackingIdInfo> trackingIds = change.trackingIds;
    assertThat(trackingIds).isNotNull();
    assertThat(trackingIds).hasSize(1);
    assertThat(trackingIds.iterator().next().system).isEqualTo("JIRA");
    assertThat(trackingIds.iterator().next().id).isEqualTo("JRA001");
  }

  @Test
  @GerritConfig(name = "trackingid.jira-bug.footer", value = "Bug:")
  @GerritConfig(name = "trackingid.jira-bug.match", value = "\\d+")
  @GerritConfig(name = "trackingid.jira-bug.system", value = "JIRA")
  public void multipleTrackingIdsInSingleFooter() throws Exception {
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT + "\n\n" + "Bug: 123, 456",
            PushOneCommit.FILE_NAME,
            PushOneCommit.FILE_CONTENT);
    PushOneCommit.Result result = push.to("refs/for/master");
    result.assertOkStatus();

    ChangeInfo change = gApi.changes().id(result.getChangeId()).get(TRACKING_IDS);
    Collection<TrackingIdInfo> trackingIds = change.trackingIds;
    assertThat(trackingIds).isNotNull();
    assertThat(trackingIds).hasSize(2);
    assertThat(trackingIds.stream().map(t -> t.id)).containsExactly("123", "456");
  }

  @Test
  public void starUnstar() throws Exception {
    ChangeIndexedCounter changeIndexedCounter = new ChangeIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(changeIndexedCounter)) {
      PushOneCommit.Result r = createChange();
      String triplet = project.get() + "~master~" + r.getChangeId();
      changeIndexedCounter.clear();

      gApi.accounts().self().starChange(triplet);
      ChangeInfo change = info(triplet);
      assertThat(change.starred).isTrue();
      // change was not re-indexed
      changeIndexedCounter.assertReindexOf(change, 0);

      gApi.accounts().self().unstarChange(triplet);
      change = info(triplet);
      assertThat(change.starred).isNull();
      // change was not re-indexed
      changeIndexedCounter.assertReindexOf(change, 0);
    }
  }

  @Test
  public void createAndDeleteDraftCommentDoesNotTriggerChangeReindex() throws Exception {
    ChangeIndexedCounter changeIndexedCounter = new ChangeIndexedCounter();
    try (Registration registration =
        extensionRegistry.newRegistration().add(changeIndexedCounter)) {
      PushOneCommit.Result r = createChange();
      String changeId = r.getChangeId();
      String revId = r.getCommit().getName();
      String triplet = project.get() + "~master~" + r.getChangeId();
      changeIndexedCounter.clear();

      // Create the draft. Change is not re-indexed
      DraftInput draftInput =
          CommentsUtil.newDraft("file1", Side.REVISION, /* line= */ 1, "comment 1");
      CommentInfo draftInfo =
          gApi.changes().id(changeId).revision(revId).createDraft(draftInput).get();
      ChangeInfo change = info(triplet);
      changeIndexedCounter.assertReindexOf(change, 0);

      // Delete the draft comment. Change is not re-indexed
      gApi.changes().id(changeId).revision(revId).draft(draftInfo.id).delete();
      changeIndexedCounter.assertReindexOf(change, 0);
    }
  }

  @Test
  public void changeDetailsDoesNotRequireIndex() throws Exception {
    // This set of options must be kept in sync with gr-rest-api-interface.js
    ImmutableSet<ListChangesOption> options =
        ImmutableSet.of(
            ListChangesOption.ALL_COMMITS,
            ListChangesOption.ALL_REVISIONS,
            ListChangesOption.CHANGE_ACTIONS,
            ListChangesOption.DETAILED_LABELS,
            ListChangesOption.DOWNLOAD_COMMANDS,
            ListChangesOption.MESSAGES,
            ListChangesOption.SUBMITTABLE,
            ListChangesOption.WEB_LINKS,
            ListChangesOption.SKIP_DIFFSTAT);

    PushOneCommit.Result change = createChange();
    // Populate change with a reasonable set of fields. We can't exhaustively
    // test all possible variations, but can try to cover a reasonable set.
    approve(change.getChangeId());
    gApi.changes().id(change.getChangeId()).addReviewer(user.email());

    int number = gApi.changes().id(change.getChangeId()).get()._number;

    // Note: Computing the description of some UI actions does access the index. If the index is
    // disabled computing these descriptions fails. UiActions#describe catches and ignores these
    // exceptions so that the request is still successful. In this case the description of the UI
    // action is omitted in the response.
    try (AutoCloseable ignored = changeIndexOperations.disableReadsAndWrites()) {
      assertThat(gApi.changes().id(project.get(), number).get(options).changeId)
          .isEqualTo(change.getChangeId());
    }
  }

  @Test
  @GerritConfig(
      name = "change.mergeabilityComputationBehavior",
      value = "API_REF_UPDATED_AND_CHANGE_REINDEX")
  public void changeQueryReturnsMergeableWhenGerritIndexMergeable() throws Exception {
    String changeId = createChange().getChangeId();
    assertThat(gApi.changes().query(changeId).get().get(0).mergeable).isTrue();
    gApi.changes().id(changeId).setWorkInProgress();
    assertThat(gApi.changes().query(changeId).get().get(0).mergeable).isTrue();
  }

  @Test
  @GerritConfig(name = "change.mergeabilityComputationBehavior", value = "NEVER")
  public void changeQueryDoesNotReturnMergeableWhenGerritDoesNotIndexMergeable() throws Exception {
    String changeId = createChange().getChangeId();
    assertThat(gApi.changes().query(changeId).get().get(0).mergeable).isNull();
  }

  @Test
  public void ccUserThatCannotSeeTheChange() throws Exception {
    // Create a project that is only visible to admin users.
    Project.NameKey project = projectOperations.newProject().create();
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.READ).ref("refs/*").group(adminGroupUuid()))
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();

    // Create a change
    TestRepository<?> adminTestRepo = cloneProject(project, admin);
    PushOneCommit push = pushFactory.create(admin.newIdent(), adminTestRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();

    // Check that the change is not visible to user.
    requestScopeOperations.setApiUser(user.id());
    assertThrows(ResourceNotFoundException.class, () -> gApi.changes().id(r.getChangeId()).get());

    // Add user as a CC.
    requestScopeOperations.setApiUser(admin.id());
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = user.id().toString();
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput);

    // Check that user was not added as a CC since they cannot see the change. Note,
    // ChangeInfo#reviewers is a map that also contains CCs (if any are present).
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewers).isEmpty();

    // Check that the change is still not visible to user.
    requestScopeOperations.setApiUser(user.id());
    assertThrows(ResourceNotFoundException.class, () -> gApi.changes().id(r.getChangeId()).get());
  }

  @Test
  public void ccNonExistentAccountByEmailThenRemoveByDelete() throws Exception {
    // Create a project that allows reviewers by email.
    Project.NameKey project = projectOperations.newProject().create();
    projectOperations.project(project).forUpdate().enableReviewerByEmail().update();

    // Create a change
    TestRepository<?> testRepo = cloneProject(project, admin);
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();

    // Add an email as a CC for which no Gerrit account exists.
    sender.clear();
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = "email-without-account@example.com";
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput);

    // Check that the email was added as a CC and an email was sent.
    AccountInfo ccedAccountInfo =
        Iterables.getOnlyElement(
            gApi.changes().id(r.getChangeId()).get().reviewers.get(ReviewerState.CC));
    assertThat(ccedAccountInfo.email).isEqualTo(reviewerInput.reviewer);
    assertThat(ccedAccountInfo._accountId).isNull();
    assertThat(ccedAccountInfo.name).isNull();
    assertThat(Iterables.getOnlyElement(sender.getMessages()).body())
        .contains(String.format("%s has uploaded this change for review", admin.fullName()));

    // Remove the CC.
    sender.clear();
    gApi.changes().id(r.getChangeId()).reviewer(reviewerInput.reviewer).remove();

    // Check that the email was removed as a CC and an email was sent.
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewers).isEmpty();
    assertThat(Iterables.getOnlyElement(sender.getMessages()).body())
        .contains(String.format("%s has removed %s", admin.fullName(), reviewerInput.reviewer));
  }

  @Test
  public void ccNonExistentAccountByEmailThenRemoveByPostReview() throws Exception {
    // Create a project that allows reviewers by email.
    Project.NameKey project = projectOperations.newProject().create();
    projectOperations.project(project).forUpdate().enableReviewerByEmail().update();

    // Create a change
    TestRepository<?> testRepo = cloneProject(project, admin);
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();

    // Add an email as a CC for which no Gerrit account exists.
    sender.clear();
    ReviewerInput reviewerInput = new ReviewerInput();
    reviewerInput.state = CC;
    reviewerInput.reviewer = "email-without-account@example.com";
    gApi.changes().id(r.getChangeId()).addReviewer(reviewerInput);

    // Check that the email was added as a CC and an email was sent.
    AccountInfo ccedAccountInfo =
        Iterables.getOnlyElement(
            gApi.changes().id(r.getChangeId()).get().reviewers.get(ReviewerState.CC));
    assertThat(ccedAccountInfo.email).isEqualTo(reviewerInput.reviewer);
    assertThat(ccedAccountInfo._accountId).isNull();
    assertThat(ccedAccountInfo.name).isNull();
    assertThat(Iterables.getOnlyElement(sender.getMessages()).body())
        .contains(String.format("%s has uploaded this change for review", admin.fullName()));

    // Remove the CC.
    sender.clear();
    ReviewInput reviewInput = new ReviewInput();
    reviewInput.reviewer(reviewerInput.reviewer, ReviewerState.REMOVED, /* confirmed= */ false);
    gApi.changes().id(r.getChangeId()).current().review(reviewInput);

    // Check that the email was removed as a CC and an email was sent.
    assertThat(gApi.changes().id(r.getChangeId()).get().reviewers).isEmpty();
    assertThat(Iterables.getOnlyElement(sender.getMessages()).body())
        .contains(String.format("%s has removed %s", admin.fullName(), reviewerInput.reviewer));
  }

  @Test
  public void emailSubjectContainsChangeSizeBucket() throws Exception {
    testEmailSubjectContainsChangeSizeBucket(0, "NoOp");
    testEmailSubjectContainsChangeSizeBucket(1, "XS");
    testEmailSubjectContainsChangeSizeBucket(9, "XS");
    testEmailSubjectContainsChangeSizeBucket(10, "S");
    testEmailSubjectContainsChangeSizeBucket(49, "S");
    testEmailSubjectContainsChangeSizeBucket(50, "M");
    testEmailSubjectContainsChangeSizeBucket(249, "M");
    testEmailSubjectContainsChangeSizeBucket(250, "L");
    testEmailSubjectContainsChangeSizeBucket(999, "L");
    testEmailSubjectContainsChangeSizeBucket(1000, "XL");
  }

  @Test
  public void requestFormattedChangeInReview() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(r.getChange().change().isWorkInProgress()).isFalse();

    ReviewInput in = ReviewInput.approve().reviewer(user.email()).label(LabelId.CODE_REVIEW, 1);
    in.responseFormatOptions = ImmutableList.of(ListChangesOption.CURRENT_REVISION);
    ReviewResult result = gApi.changes().id(r.getChangeId()).current().review(in);
    assertThat(result.changeInfo).isNotNull();
    assertThat(result.changeInfo.currentRevision).isNotNull();
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void searchByChangeNumberOnlyReturnsImportedChanges() throws Exception {
    PushOneCommit.Result change = createImportedChange("foo.txt");

    Change.Id id = change.getChange().getId();
    String idAsString = id.toString();
    List<String> r = query(idAsString).stream().map(ci -> ci.changeId).toList();
    assertThat(r).containsExactly(change.getChangeId());
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void searchByChangeNumberPredicateReturnsImportedChanges() throws Exception {
    PushOneCommit.Result change = createImportedChange("foo.txt");

    Change.Id id = change.getChange().getId();
    String idAsString = id.toString();
    List<String> r = query("change:" + idAsString).stream().map(ci -> ci.changeId).toList();
    assertThat(r).containsExactly(change.getChangeId());
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void changesCollectionReturnsImportedChanges() throws Exception {
    PushOneCommit.Result change = createImportedChange("foo.txt");

    String idAsString = change.getChange().getId().toString();
    assertThat(gApi.changes().id(idAsString)).isNotNull();
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void changesCollectionFilesReturnsImportedChanges() throws Exception {
    String fileName = "foo.txt";
    PushOneCommit.Result change = createImportedChange(fileName);

    String idAsString = change.getChange().getId().toString();

    Map<String, FileInfo> files =
        gApi.changes().id(idAsString).revision(change.getPatchSet().number()).files();
    assertThat(files.keySet()).containsExactly(fileName, "/COMMIT_MSG");
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void changesCollectionRelatedReturnsImportedChanges() throws Exception {
    String fileName = "foo.txt";
    PushOneCommit.Result change = createImportedChange(fileName);

    String idAsString = change.getChange().getId().toString();

    RelatedChangesInfo related =
        gApi.changes().id(idAsString).revision(change.getPatchSet().number()).related();
    assertThat(related).isNotNull();
    assertThat(related.changes.size()).isEqualTo(0);
  }

  @Test
  public void changeNumberVirtualIdAlgorithmShouldBeNoopNotSupportVirtualizationByDefault() {
    Change.Id changeNum = Change.id(1);
    assertThat(changeNumberVirtualIdAlgorithm.apply(() -> "server-id", changeNum))
        .isEqualTo(changeNum);
  }

  @Test
  @GerritConfig(name = "gerrit.importedServerId", value = "imported-server-id")
  public void changeNumberShouldBeVirtualizedWithImportedServerId() throws Exception {
    Change.Id changeNum = Change.id(1);
    Change.Id virtualId =
        changeNumberVirtualIdAlgorithm.apply(() -> "imported-server-id", changeNum);
    assertThat(virtualId).isNotEqualTo(changeNum);
  }

  private PushOneCommit.Result createImportedChange(String fileName) throws Exception {
    PushOneCommit.Result change = createChange("subject", fileName, "test content");
    Change.Id changeId = change.getChange().getId();
    String metaRef = changeMetaRef(changeId);

    indexer.delete(changeId);

    try (Repository repo = repoManager.openRepository(project);
        ObjectInserter inserter = repo.newObjectInserter();
        ObjectReader reader = repo.newObjectReader();
        RevWalk revWalk = new RevWalk(reader);
        RefUpdateContext ctx =
            RefUpdateContext.openDirectPush(Optional.of("Create imported change"))) {

      Ref ref = repo.getRefDatabase().exactRef(metaRef);
      RevCommit tip = revWalk.parseCommit(ref.getObjectId());

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(tip.getTree());
      commit.setAuthor(
          new PersonIdent("Gerrit User " + admin.id(), admin.id() + "@imported-server-id"));
      commit.setCommitter(new PersonIdent("Gerrit Code Review", admin.email()));
      commit.setMessage(buildChangeMessage(change));

      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate refUpdate = repo.updateRef(metaRef);
      refUpdate.setNewObjectId(commitId);
      refUpdate.forceUpdate();

      indexer.index(project, changeId);
    }

    return change;
  }

  private String buildChangeMessage(PushOneCommit.Result change) {
    return String.join(
        "\n",
        "Create change",
        "",
        "Uploaded patch set 1.",
        "",
        "Patch-set: 1",
        "Change-id: " + change.getChangeId(),
        "Subject: Test subject",
        "Branch: refs/heads/master",
        "Status: new",
        "Topic:",
        "Commit: " + change.getCommit().name(),
        "Tag: autogenerated:gerrit:newWipPatchSet",
        "Private: false",
        "Work-in-progress: true");
  }

  private void testEmailSubjectContainsChangeSizeBucket(
      int numberOfLines, String expectedSizeBucket) throws Exception {
    String change;
    if (numberOfLines == 0) {
      // create empty change
      ChangeInput in = new ChangeInput();
      in.branch = Constants.MASTER;
      in.subject = "Create a change from the API";
      in.project = project.get();
      ChangeInfo info = gApi.changes().create(in).get();
      change = info.changeId;
    } else {
      change =
          createChange(
                  "subject",
                  expectedSizeBucket + "-file-with-" + numberOfLines + "lines.txt",
                  Collections.nCopies(numberOfLines, "line").stream().collect(joining("\n")))
              .getChangeId();
    }
    sender.clear();
    gApi.changes().id(change).addReviewer(user.email());
    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    assertThat(((StringEmailHeader) messages.get(0).headers().get("Subject")).getString())
        .contains("[" + expectedSizeBucket + "]");
  }

  private PushOneCommit.Result createWorkInProgressChange() throws Exception {
    return pushTo("refs/for/master%wip");
  }

  private ThrowableSubject assertThatQueryException(String query) throws Exception {
    try {
      @SuppressWarnings("unused")
      var unused = query(query);
    } catch (BadRequestException e) {
      return assertThat(e);
    }
    throw new AssertionError("expected BadRequestException");
  }

  @FunctionalInterface
  private interface AddReviewerCaller {
    void call(ChangeIdentifier changeIdentifier, String reviewer) throws RestApiException;
  }

  public static class TestAttentionSetListenerModule extends AbstractModule {
    @Override
    public void configure() {
      DynamicSet.bind(binder(), AttentionSetListener.class).to(TestAttentionSetListener.class);
    }

    public static class TestAttentionSetListener implements AttentionSetListener {
      AttentionSetListener.Event lastEvent;
      int firedCount;

      @Inject
      public TestAttentionSetListener() {}

      @Override
      public void onAttentionSetChanged(AttentionSetListener.Event event) {
        firedCount++;
        lastEvent = event;
      }
    }
  }

  private void voteLabel(String changeId, String labelName, int score) throws RestApiException {
    gApi.changes().id(changeId).current().review(new ReviewInput().label(labelName, score));
  }
}
