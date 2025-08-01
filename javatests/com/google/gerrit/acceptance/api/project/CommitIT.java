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

package com.google.gerrit.acceptance.api.project;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_NAME;
import static com.google.gerrit.acceptance.PushOneCommit.SUBJECT;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.lib.Constants.R_TAGS;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Correspondence;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.change.ChangeOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.IncludedInInfo;
import com.google.gerrit.extensions.api.changes.RelatedChangeAndCommitInfo;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.TagInput;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.CommitMessageInput;
import com.google.gerrit.extensions.common.GitPerson;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.truth.NullAwareCorrespondence;
import com.google.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class CommitIT extends AbstractDaemonTest {
  @Inject private ProjectOperations projectOperations;
  @Inject private AccountOperations accountOperations;
  @Inject private ChangeOperations changeOperations;
  @Inject private RequestScopeOperations requestScopeOperations;

  @Test
  public void getCommitInfo() throws Exception {
    Result result = createChange();
    String commitId = result.getCommit().getId().name();
    CommitInfo info = gApi.projects().name(project.get()).commit(commitId).get();
    assertThat(info.commit).isEqualTo(commitId);
    assertThat(info.parents.stream().map(c -> c.commit).collect(toList()))
        .containsExactly(result.getCommit().getParent(0).name());
    assertThat(info.subject).isEqualTo(result.getCommit().getShortMessage());
    assertPerson(info.author, admin);
    assertPerson(info.committer, admin);
    assertThat(info.webLinks).isNull();
  }

  @Test
  public void includedInOpenChange() throws Exception {
    Result result = createChange();
    assertThat(getIncludedIn(result.getCommit().getId()).branches).isEmpty();
    assertThat(getIncludedIn(result.getCommit().getId()).tags).isEmpty();
  }

  @Test
  public void includedInMergedChange() throws Exception {
    Result result = createChange();
    gApi.changes()
        .id(result.getChangeId())
        .revision(result.getCommit().name())
        .review(ReviewInput.approve());
    gApi.changes().id(result.getChangeId()).revision(result.getCommit().name()).submit();

    assertThat(getIncludedIn(result.getCommit().getId()).branches).containsExactly("master");
    assertThat(getIncludedIn(result.getCommit().getId()).tags).isEmpty();

    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.CREATE_TAG).ref(R_TAGS + "*").group(adminGroupUuid()))
        .update();
    gApi.projects().name(result.getChange().project().get()).tag("test-tag").create(new TagInput());

    assertThat(getIncludedIn(result.getCommit().getId()).tags).containsExactly("test-tag");

    createBranch(BranchNameKey.create(project, "test-branch"));

    assertThat(getIncludedIn(result.getCommit().getId()).branches)
        .containsExactly("master", "test-branch");
  }

  @Test
  public void includedInMergedChange_filtersOutNonVisibleBranches() throws Exception {
    Result baseChange = createAndSubmitChange("refs/for/master");

    createBranch(BranchNameKey.create(project, "test-branch-1"));
    createBranch(BranchNameKey.create(project, "test-branch-2"));
    RevCommit changeCommit = createAndSubmitChange("refs/for/test-branch-1").getCommit();
    // Reset repo back to the original state - otherwise all changes in tests have testChange as a
    // parent.
    testRepo.reset(changeCommit.getParent(0));
    createAndSubmitChange("refs/for/test-branch-2");

    assertThat(getIncludedIn(baseChange.getCommit().getId()).branches)
        .containsExactly("master", "test-branch-1", "test-branch-2");

    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/test-branch-1").group(REGISTERED_USERS))
        .update();

    assertThat(getIncludedIn(baseChange.getCommit().getId()).branches)
        .containsExactly("master", "test-branch-2");
  }

  @Test
  public void includedInMergedChange_filtersOutNonVisibleTags() throws Exception {
    String tagBase = "tag_base";
    String tagBranch1 = "tag_1";

    Result baseChange = createAndSubmitChange("refs/for/master");
    createLightWeightTag(tagBase);
    assertThat(getIncludedIn(baseChange.getCommit().getId()).tags).containsExactly(tagBase);

    createBranch(BranchNameKey.create(project, "test-branch-1"));
    createAndSubmitChange("refs/for/test-branch-1");
    createLightWeightTag(tagBranch1);
    assertThat(getIncludedIn(baseChange.getCommit().getId()).tags)
        .containsExactly(tagBase, tagBranch1);

    projectOperations
        .project(project)
        .forUpdate()
        // Tag permissions are controlled by read permissions on branches. Blocking read permission
        // on test-branch-1 so that tagBranch1 becomes non-visible
        .add(block(Permission.READ).ref("refs/heads/test-branch-1").group(REGISTERED_USERS))
        .update();
    assertThat(getIncludedIn(baseChange.getCommit().getId()).tags).containsExactly(tagBase);
  }

  @Test
  public void cherryPickWithoutConflicts() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    // Create change to cherry-pick
    PushOneCommit.Result r = createChange();
    RevCommit commitToCherryPick = r.getCommit();

    // Cherry-pick to foo branch
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    ChangeInfo cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.name())
            .cherryPick(input)
            .get();

    // Verify the conflicts information
    RevCommit head = projectOperations.project(project).getHead(cherryPickResult.branch);
    RevisionInfo currentRevision =
        gApi.changes()
            .id(cherryPickResult.id)
            .get(CURRENT_REVISION, CURRENT_COMMIT)
            .getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit).isEqualTo(head.name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(initialHead.getName());
    assertThat(currentRevision.conflicts.ours).isEqualTo(head.getName());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(r.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isFalse();
  }

  @Test
  public void cherryPickWithoutMessageSameBranch() throws Exception {
    String destBranch = "master";

    // Create change to cherry-pick
    PushOneCommit.Result r = createChange();
    ChangeInfo changeToCherryPick = info(r.getChangeId());
    RevCommit commitToCherryPick = r.getCommit();

    // Cherry-pick without message.
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    ChangeInfo cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.name())
            .cherryPick(input)
            .get();

    // Expect that the Change-Id of the cherry-picked commit was used for the cherry-pick change.
    // New patch-set to existing change was uploaded.
    assertThat(cherryPickResult._number).isEqualTo(changeToCherryPick._number);
    assertThat(cherryPickResult.revisions).hasSize(2);
    assertThat(cherryPickResult.changeId).isEqualTo(changeToCherryPick.changeId);
    assertThat(cherryPickResult.messages).hasSize(2);

    // Cherry-pick of is not set, because the source change was not provided.
    assertThat(cherryPickResult.cherryPickOfChange).isNull();
    assertThat(cherryPickResult.cherryPickOfPatchSet).isNull();
    // Expect that the message of the cherry-picked commit was used for the cherry-pick change.
    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(commitToCherryPick.getFullMessage());
  }

  @Test
  public void cherryPickCommitAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create target branch to cherry-pick to
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    // Create change to cherry-pick
    Change.Id changeId =
        changeOperations
            .newChange()
            .project(project)
            .file("file")
            .content("content")
            .owner(testUser)
            .createV1();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Cherry-pick the change
    String commit = gApi.changes().id(changeId.get()).get().getCurrentRevision().commit.commit;
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message = "cherry-pick to foo branch";
    ChangeInfo cherryPickResult =
        gApi.projects().name(project.get()).commit(commit).cherryPick(input).get();
    assertThat(cherryPickResult.getCurrentRevision().commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void cherryPickWithoutMessageOtherBranch() throws Exception {
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    // Create change to cherry-pick
    PushOneCommit.Result r = createChange();
    ChangeInfo changeToCherryPick = info(r.getChangeId());
    RevCommit commitToCherryPick = r.getCommit();

    // Cherry-pick without message.
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    ChangeInfo cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.name())
            .cherryPick(input)
            .get();

    // Expect that the Change-Id of the cherry-picked commit was used for the cherry-pick change.
    // New change in destination branch was created.
    assertThat(cherryPickResult._number).isGreaterThan(changeToCherryPick._number);
    assertThat(cherryPickResult.revisions).hasSize(1);
    assertThat(cherryPickResult.changeId).isEqualTo(changeToCherryPick.changeId);
    assertThat(cherryPickResult.messages).hasSize(1);

    // Cherry-pick of is not set, because the source change was not provided.
    assertThat(cherryPickResult.cherryPickOfChange).isNull();
    assertThat(cherryPickResult.cherryPickOfPatchSet).isNull();
    // Expect that the message of the cherry-picked commit was used for the cherry-pick change.
    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(commitToCherryPick.getFullMessage());
  }

  @Test
  public void cherryPickWithAllowConflicts() throws Exception {
    testCherryPickWithAllowConflicts(/* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void cherryPickWithAllowConflictsUsingDiff3() throws Exception {
    testCherryPickWithAllowConflicts(/* useDiff3= */ true);
  }

  private void testCherryPickWithAllowConflicts(boolean useDiff3) throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    // Create a branch and push a commit to it (by-passing review)
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));
    String destContent = "some content";
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            ImmutableMap.of(PushOneCommit.FILE_NAME, destContent, "foo.txt", "foo"));
    push.to("refs/heads/" + destBranch);

    // Create a change on master with a commit that conflicts with the commit on the other branch.
    testRepo.reset(initialHead);
    String changeContent = "another content";
    push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            ImmutableMap.of(PushOneCommit.FILE_NAME, changeContent, "bar.txt", "bar"));
    PushOneCommit.Result r = push.to("refs/for/master");
    RevCommit commitToCherryPick = r.getCommit();

    // Cherry-pick the commit to the other branch, that should fail with a conflict.
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .commit(commitToCherryPick.name())
                    .cherryPick(input));
    assertThat(thrown).hasMessageThat().startsWith("Cherry pick failed: merge conflict");

    // Cherry-pick with auto merge should succeed.
    // We make a REST call here, rather than using CommitApi.cherryPick(CherryPickInput) because we
    // want to validate transient fields of the returned ChangeInfo and
    // CommitApi.cherryPick(CherryPickInput) doesn't return the ChangeInfo, but a ChangeApi.
    // ChangeApi allows us to retrieve the ChangeInfo, but this is a new ChangeInfo instance that
    // doesn't have transient fields like 'containsGitConflicts' set. Hence since we do want to
    // verify the transient fields, we do a REST call instead, where we can get the returned
    // ChangeInfo and verify the transient fields in it.
    input.allowConflicts = true;
    RestResponse response =
        adminRestSession.post(
            "/projects/" + project.get() + "/commits/" + commitToCherryPick.name() + "/cherrypick",
            input);
    response.assertOK();
    ChangeInfo cherryPickChange = newGson().fromJson(response.getReader(), ChangeInfo.class);
    assertThat(cherryPickChange.containsGitConflicts).isTrue();
    assertThat(cherryPickChange.workInProgress).isTrue();

    // Verify the conflicts information
    RevCommit head = projectOperations.project(project).getHead(cherryPickChange.branch);
    RevisionInfo currentRevision =
        gApi.changes()
            .id(cherryPickChange.id)
            .get(CURRENT_REVISION, CURRENT_COMMIT)
            .getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit).isEqualTo(head.name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(initialHead.getName());
    assertThat(currentRevision.conflicts.ours).isEqualTo(head.getName());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(r.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the file content in the cherry-pick change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes()
            .id(project.get(), cherryPickChange._number)
            .current()
            .file(PushOneCommit.FILE_NAME)
            .content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            "<<<<<<< HEAD   ("
                + projectOperations.project(project).getHead(destBranch).getName()
                + " test commit)\n"
                + destContent
                + "\n"
                + (useDiff3
                    ? String.format(
                        "||||||| BASE   (%s %s)\n",
                        initialHead.getName(), initialHead.getShortMessage())
                    : "")
                + "=======\n"
                + changeContent
                + "\n"
                + ">>>>>>> CHANGE ("
                + r.getCommit().getName()
                + " test commit)\n");
  }

  @Test
  public void cherryPickToExistingChangeWithAllowConflicts() throws Exception {
    testCherryPickToExistingChangeWithAllowConflicts(/* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void cherryPickToExistingChangeWithAllowConflictsUsingDiff3() throws Exception {
    testCherryPickToExistingChangeWithAllowConflicts(/* useDiff3= */ true);
  }

  private void testCherryPickToExistingChangeWithAllowConflicts(boolean useDiff3) throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));
    String destContent = "some content";
    PushOneCommit.Result existingChange =
        createChange(testRepo, destBranch, SUBJECT, FILE_NAME, destContent, null);

    testRepo.reset(initialHead);
    String changeContent = "another content";
    PushOneCommit.Result srcChange =
        createChange(testRepo, "master", SUBJECT, FILE_NAME, changeContent, null);
    RevCommit commitToCherryPick = srcChange.getCommit();

    // Cherry-pick the commit to the other branch, that should fail with a conflict.
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.base = existingChange.getCommit().name();
    input.message = "cherry-pick to foo" + "\n\nChange-Id: " + existingChange.getChangeId();
    input.destination = destBranch;
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () ->
                gApi.projects()
                    .name(project.get())
                    .commit(commitToCherryPick.name())
                    .cherryPick(input));
    assertThat(thrown).hasMessageThat().startsWith("Cherry pick failed: merge conflict");

    // Cherry-pick with auto merge should succeed.
    // We make a REST call here, rather than using CommitApi.cherryPick(CherryPickInput) because we
    // want to validate transient fields of the returned ChangeInfo and
    // CommitApi.cherryPick(CherryPickInput) doesn't return the ChangeInfo, but a ChangeApi.
    // ChangeApi allows us to retrieve the ChangeInfo, but this is a new ChangeInfo instance that
    // doesn't have transient fields like 'containsGitConflicts' set. Hence since we do want to
    // verify the transient fields, we do a REST call instead, where we can get the returned
    // ChangeInfo and verify the transient fields in it.
    input.allowConflicts = true;
    RestResponse response =
        adminRestSession.post(
            "/projects/" + project.get() + "/commits/" + commitToCherryPick.name() + "/cherrypick",
            input);
    response.assertOK();
    ChangeInfo cherryPickChange = newGson().fromJson(response.getReader(), ChangeInfo.class);
    assertThat(cherryPickChange.containsGitConflicts).isTrue();
    assertThat(cherryPickChange.workInProgress).isTrue();

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes()
            .id(cherryPickChange.id)
            .get(CURRENT_REVISION, CURRENT_COMMIT)
            .getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(existingChange.getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(initialHead.name());
    assertThat(currentRevision.conflicts.ours).isEqualTo(existingChange.getCommit().name());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(srcChange.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the file content in the cherry-pick change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes()
            .id(project.get(), cherryPickChange._number)
            .current()
            .file(PushOneCommit.FILE_NAME)
            .content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            "<<<<<<< HEAD   ("
                + existingChange.getCommit().getName()
                + " test commit)\n"
                + destContent
                + "\n"
                + (useDiff3
                    ? String.format(
                        "||||||| BASE   (%s %s)\n",
                        initialHead.getName(), initialHead.getShortMessage())
                    : "")
                + "=======\n"
                + changeContent
                + "\n"
                + ">>>>>>> CHANGE ("
                + srcChange.getCommit().getName()
                + " test commit)\n");
  }

  @Test
  public void cherryPickCommitWithoutChangeIdCreateNewChange() throws Exception {
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message = "it goes to foo branch";

    RevCommit commitToCherryPick =
        createNewCommitWithoutChangeId("refs/heads/master", "a.txt", "content");
    ChangeInfo cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.getName())
            .cherryPick(input)
            .get();

    assertThat(cherryPickResult.messages).hasSize(1);
    Iterator<ChangeMessageInfo> messageIterator = cherryPickResult.messages.iterator();
    String expectedMessage =
        String.format("Patch Set 1: Cherry Picked from commit %s.", commitToCherryPick.getName());
    assertThat(messageIterator.next().message).isEqualTo(expectedMessage);

    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    CommitInfo commitInfo = revInfo.commit;
    assertThat(commitInfo.message)
        .isEqualTo(input.message + "\n\nChange-Id: " + cherryPickResult.changeId + "\n");
  }

  @Test
  public void cherryPickCommitWithChangeIdCreateNewChange() throws Exception {
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    PushOneCommit.Result r = createChange();
    ChangeInfo changeToCherryPick = info(r.getChangeId());
    RevCommit commitToCherryPick = r.getCommit();
    List<String> footers = commitToCherryPick.getFooterLines("Change-Id");
    assertThat(footers).hasSize(1);
    String changeId = footers.get(0);

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message =
        String.format(
            "it goes to foo branch\n\n"
                + "Change-Id: Ideadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n\n"
                + "Change-Id: %s\n",
            changeId);

    ChangeInfo cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.getName())
            .cherryPick(input)
            .get();

    // No change was found in destination branch with the provided Change-Id.
    assertThat(cherryPickResult._number).isGreaterThan(changeToCherryPick._number);
    assertThat(cherryPickResult.changeId).isEqualTo(changeId);
    assertThat(cherryPickResult.revisions).hasSize(1);
    assertThat(cherryPickResult.messages).hasSize(1);
    Iterator<ChangeMessageInfo> messageIterator = cherryPickResult.messages.iterator();
    String expectedMessage =
        String.format("Patch Set 1: Cherry Picked from commit %s.", commitToCherryPick.getName());
    assertThat(messageIterator.next().message).isEqualTo(expectedMessage);

    // Cherry-pick of is not set, because the source change was not provided.
    assertThat(cherryPickResult.cherryPickOfChange).isNull();
    assertThat(cherryPickResult.cherryPickOfPatchSet).isNull();
    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(input.message);
  }

  @Test
  public void cherryPickCommitToExistingChange() throws Exception {
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    PushOneCommit.Result r = createChange("refs/for/" + destBranch);
    ChangeInfo existingDestChange = info(r.getChangeId());

    String commitToCherryPick = createChange().getCommit().getName();

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message =
        String.format(
            "it goes to foo branch\n\n"
                + "Change-Id: Ideadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n\n"
                + "Change-Id: %s\n",
            existingDestChange.changeId);
    input.allowEmpty = true;

    ChangeInfo cherryPickResult =
        gApi.projects().name(project.get()).commit(commitToCherryPick).cherryPick(input).get();

    // New patch-set to existing change was uploaded.
    assertThat(cherryPickResult._number).isEqualTo(existingDestChange._number);
    assertThat(cherryPickResult.changeId).isEqualTo(existingDestChange.changeId);
    assertThat(cherryPickResult.messages).hasSize(2);
    assertThat(cherryPickResult.revisions).hasSize(2);
    Iterator<ChangeMessageInfo> messageIterator = cherryPickResult.messages.iterator();

    assertThat(messageIterator.next().message).isEqualTo("Uploaded patch set 1.");
    String expectedMessage =
        String.format("Patch Set 2: Cherry Picked from commit %s.", commitToCherryPick);
    assertThat(messageIterator.next().message).isEqualTo(expectedMessage);
    // Cherry-pick of is not set, because the source change was not provided.
    assertThat(cherryPickResult.cherryPickOfChange).isNull();
    assertThat(cherryPickResult.cherryPickOfPatchSet).isNull();
    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(input.message);
  }

  @Test
  public void cherryPickCommitToExistingCherryPickedChange() throws Exception {
    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));

    PushOneCommit.Result r = createChange("refs/for/" + destBranch);
    ChangeInfo existingDestChange = info(r.getChangeId());

    r = createChange();
    ChangeInfo changeToCherryPick = info(r.getChangeId());
    RevCommit commitToCherryPick = r.getCommit();

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message =
        String.format("it goes to foo branch\n\nChange-Id: %s\n", existingDestChange.changeId);
    input.allowEmpty = true;
    // Use RevisionAPI to submit initial cherryPick.
    ChangeInfo cherryPickResult =
        gApi.changes().id(changeToCherryPick.changeId).current().cherryPick(input).get();
    assertThat(cherryPickResult.changeId).isEqualTo(existingDestChange.changeId);
    // Cherry-pick was set.
    assertThat(cherryPickResult.cherryPickOfChange).isEqualTo(changeToCherryPick._number);
    assertThat(cherryPickResult.cherryPickOfPatchSet).isEqualTo(1);
    RevisionInfo revInfo = cherryPickResult.revisions.get(cherryPickResult.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(input.message);
    // Use CommitApi to update the cherryPick change.
    cherryPickResult =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.getName())
            .cherryPick(input)
            .get();

    assertThat(cherryPickResult.changeId).isEqualTo(existingDestChange.changeId);
    assertThat(cherryPickResult.messages).hasSize(3);
    Iterator<ChangeMessageInfo> messageIterator = cherryPickResult.messages.iterator();

    assertThat(messageIterator.next().message).isEqualTo("Uploaded patch set 1.");
    assertThat(messageIterator.next().message)
        .isEqualTo("Patch Set 2: Cherry Picked from branch master.");
    String expectedMessage =
        String.format("Patch Set 3: Cherry Picked from commit %s.", commitToCherryPick.getName());
    assertThat(messageIterator.next().message).isEqualTo(expectedMessage);
    // Cherry-pick was reset to empty value.
    assertThat(cherryPickResult._number).isEqualTo(existingDestChange._number);
    assertThat(cherryPickResult.cherryPickOfChange).isNull();
    assertThat(cherryPickResult.cherryPickOfPatchSet).isNull();
  }

  @Test
  public void cherryPickCommitWithChangeIdToClosedChange() throws Exception {
    String destBranch = "refs/heads/foo";
    createBranch(BranchNameKey.create(project, destBranch));

    PushOneCommit.Result r = createChange("refs/for/" + destBranch);
    ChangeInfo existingDestChange = info(r.getChangeId());
    String commitToCherryPick = createChange().getCommit().getName();

    gApi.changes().id(existingDestChange.changeId).current().review(ReviewInput.approve());
    gApi.changes().id(existingDestChange.changeId).current().submit();

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.message =
        String.format("it goes to foo branch\n\nChange-Id: %s\n", existingDestChange.changeId);
    input.allowEmpty = true;

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.projects().name(project.get()).commit(commitToCherryPick).cherryPick(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Cherry-pick with Change-Id %s could not update the existing change %d in "
                    + "destination branch %s of project %s, because the change was closed (MERGED)",
                existingDestChange.changeId,
                existingDestChange._number,
                destBranch,
                project.get()));
  }

  @Test
  public void cherryPickCommitWithSetTopic() throws Exception {
    String branch = "foo";
    RevCommit revCommit = createChange().getCommit();
    gApi.projects().name(project.get()).branch(branch).create(new BranchInput());
    CherryPickInput input = new CherryPickInput();
    input.destination = branch;
    input.topic = "topic";
    String changeId =
        gApi.projects().name(project.get()).commit(revCommit.name()).cherryPick(input).get().id;

    ChangeInfo changeInfo = gApi.changes().id(changeId).get();
    assertThat(changeInfo.topic).isEqualTo(input.topic);
  }

  @Test
  public void cherryPickOnTopOfOpenChange() throws Exception {
    BranchNameKey srcBranch = BranchNameKey.create(project, "master");

    // Create a target branch
    BranchNameKey destBranch = BranchNameKey.create(project, "foo");
    createBranch(destBranch);

    // Create base change on the target branch
    PushOneCommit.Result r = createChange("refs/for/" + destBranch.shortName());
    String base = r.getCommit().name();
    int baseChangeNumber = r.getChange().getId().get();

    // Create commit to cherry-pick on the source branch (no change exists for this commit)
    String changeId = "Ideadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    RevCommit commitToCherryPick;
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      commitToCherryPick =
          tr.commit()
              .parent(repo.parseCommit(repo.exactRef(srcBranch.branch()).getObjectId()))
              .message(String.format("Commit to be cherry-picked\n\nChange-Id: %s\n", changeId))
              .add("file.txt", "content")
              .create();
      tr.branch(srcBranch.branch()).update(commitToCherryPick);
    }

    // Perform the cherry-pick (cherry-pick on top of the base change)
    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch.shortName();
    input.base = base;
    ChangeInfo cherryPickChange =
        gApi.projects()
            .name(project.get())
            .commit(commitToCherryPick.name())
            .cherryPick(input)
            .get();

    // Verify that a new change in destination branch was created.
    assertThat(cherryPickChange._number).isGreaterThan(baseChangeNumber);
    assertThat(cherryPickChange.branch).isEqualTo(destBranch.shortName());
    assertThat(cherryPickChange.revisions).hasSize(1);
    assertThat(cherryPickChange.messages).hasSize(1);

    // Verify that the Change-Id of the cherry-picked commit is used for the cherry pick change.
    assertThat(cherryPickChange.changeId).isEqualTo(changeId);

    // Verify that cherry-pick-of is not set, since we cherry-picked a commit and not a change.
    assertThat(cherryPickChange.cherryPickOfChange).isNull();
    assertThat(cherryPickChange.cherryPickOfPatchSet).isNull();

    // Verify that the message of the cherry-picked commit was used for the cherry-pick change.
    RevisionInfo revInfo = cherryPickChange.revisions.get(cherryPickChange.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(commitToCherryPick.getFullMessage());

    // Verify that the provided base commit is the parent commit of the cherry pick revision.
    assertThat(revInfo.commit.parents).hasSize(1);
    assertThat(revInfo.commit.parents.get(0).commit).isEqualTo(input.base);

    // Verify that the related changes contain the base change and the cherry-pick change (no matter
    // for which of these changes the related changes are retrieved).
    assertThat(gApi.changes().id(cherryPickChange._number).current().related().changes)
        .comparingElementsUsing(hasId())
        .containsExactly(baseChangeNumber, cherryPickChange._number);
    assertThat(gApi.changes().id(baseChangeNumber).current().related().changes)
        .comparingElementsUsing(hasId())
        .containsExactly(baseChangeNumber, cherryPickChange._number);
  }

  @Test
  public void editMessageWithSecondaryEmail() throws Exception {
    // Create new user with a secondary email
    Account.Id testUser =
        accountOperations
            .newAccount()
            .preferredEmail("preferred@example.com")
            .addSecondaryEmail("secondary@example.com")
            .create();
    requestScopeOperations.setApiUser(testUser);

    // Create a change and edit its message using secondary email
    PushOneCommit.Result r = createChange();
    RevCommit commit = r.getCommit();
    String message = commit.getFullMessage();
    CommitMessageInput in = new CommitMessageInput();
    in.message = "new message" + message;
    in.committerEmail = "secondary@example.com";
    gApi.changes().id(r.getChangeId()).setMessage(in);
    CommitInfo newCommit = gApi.changes().id(r.getChangeId()).current().commit(false);
    assertThat(newCommit.message).contains("new message");
    assertThat(newCommit.committer.email).isEqualTo("secondary@example.com");
  }

  @Test
  public void cannotEditMessageWithUnregisteredEmail() throws Exception {
    PushOneCommit.Result r = createChange();
    RevCommit commit = r.getCommit();
    String message = commit.getFullMessage();
    CommitMessageInput in = new CommitMessageInput();
    in.message = "new message" + message;
    in.committerEmail = "unregistered@example.com";

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(r.getChangeId()).setMessage(in));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Cannot set commit message using committer email %s,"
                    + " as it is not among the registered emails of account %s",
                in.committerEmail, admin.id()));
  }

  private IncludedInInfo getIncludedIn(ObjectId id) throws Exception {
    return gApi.projects().name(project.get()).commit(id.name()).includedIn();
  }

  private static void assertPerson(GitPerson actual, TestAccount expected) {
    assertThat(actual.email).isEqualTo(expected.email());
    assertThat(actual.name).isEqualTo(expected.fullName());
  }

  private Result createAndSubmitChange(String branch) throws Exception {
    Result r = createChange(branch);
    approve(r.getChangeId());
    gApi.changes().id(r.getChangeId()).current().submit();
    return r;
  }

  private void createLightWeightTag(String tagName) throws Exception {
    pushHead(testRepo, RefNames.REFS_TAGS + tagName, false, false);
  }

  private static Correspondence<RelatedChangeAndCommitInfo, Integer> hasId() {
    return NullAwareCorrespondence.transforming(
        relatedChangeAndCommitInfo -> relatedChangeAndCommitInfo._changeNumber, "hasId");
  }
}
