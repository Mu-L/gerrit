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

package com.google.gerrit.server.mail.send;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.entities.NotifyConfig;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.ProjectWatchKey;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.mail.send.ProjectWatch.Watchers.WatcherList;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.gerrit.server.query.change.GroupBackedUser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

public class ProjectWatch {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected final EmailArguments args;
  protected final ProjectState projectState;
  protected final Project.NameKey project;
  protected final ChangeData changeData;

  public ProjectWatch(
      EmailArguments args,
      Project.NameKey project,
      ProjectState projectState,
      ChangeData changeData) {
    this.args = args;
    this.project = project;
    this.projectState = projectState;
    this.changeData = changeData;
  }

  /** Returns all watchers that are relevant */
  public final Watchers getWatchers(
      NotifyConfig.NotifyType type, boolean includeWatchersFromNotifyConfig) {
    Watchers matching = new Watchers();
    Set<Account.Id> projectWatchers = new HashSet<>();

    for (AccountState a : args.accountQueryProvider.get().byWatchedProject(project)) {
      Account.Id accountId = a.account().id();
      for (Map.Entry<ProjectWatchKey, ImmutableSet<NotifyConfig.NotifyType>> e :
          a.projectWatches().entrySet()) {
        if (project.equals(e.getKey().project())
            && add(matching, accountId, e.getKey(), e.getValue(), type)) {
          // We only want to prevent matching All-Projects if this filter hits
          projectWatchers.add(accountId);
        }
      }
    }

    for (AccountState a : args.accountQueryProvider.get().byWatchedProject(args.allProjectsName)) {
      for (Map.Entry<ProjectWatchKey, ImmutableSet<NotifyConfig.NotifyType>> e :
          a.projectWatches().entrySet()) {
        if (args.allProjectsName.equals(e.getKey().project())) {
          Account.Id accountId = a.account().id();
          if (!projectWatchers.contains(accountId)) {
            add(matching, accountId, e.getKey(), e.getValue(), type);
          }
        }
      }
    }

    if (!includeWatchersFromNotifyConfig) {
      return matching;
    }

    for (ProjectState state : projectState.tree()) {
      for (NotifyConfig nc : state.getConfig().getNotifySections().values()) {
        if (nc.isNotify(type)) {
          try {
            add(matching, state.getNameKey(), nc);
          } catch (QueryParseException e) {
            logger.atInfo().log(
                "Project %s has invalid notify %s filter \"%s\": %s",
                state.getName(), nc.getName(), nc.getFilter(), e.getMessage());
          }
        }
      }
    }

    return matching;
  }

  public static class Watchers {
    public static class WatcherList {
      public final Set<Account.Id> accounts = new HashSet<>();
      public final Set<Address> emails = new HashSet<>();

      private static WatcherList union(WatcherList... others) {
        WatcherList union = new WatcherList();
        for (WatcherList other : others) {
          union.accounts.addAll(other.accounts);
          union.emails.addAll(other.emails);
        }
        return union;
      }
    }

    public final WatcherList to = new WatcherList();
    public final WatcherList cc = new WatcherList();
    public final WatcherList bcc = new WatcherList();

    public WatcherList all() {
      return WatcherList.union(to, cc, bcc);
    }

    public WatcherList list(NotifyConfig.Header header) {
      switch (header) {
        case TO:
          return to;
        case CC:
          return cc;
        default:
        case BCC:
          return bcc;
      }
    }
  }

  private void add(Watchers matching, Project.NameKey projectName, NotifyConfig nc)
      throws QueryParseException {
    logger.atFine().log("Checking watchers for notify config %s from project %s", nc, projectName);
    for (GroupReference groupRef : nc.getGroups()) {
      CurrentUser user = new GroupBackedUser(ImmutableSet.of(groupRef.getUUID()));
      if (filterMatch(user, nc.getFilter())) {
        deliverToMembers(matching.list(nc.getHeader()), groupRef.getUUID());
        logger.atFine().log("Added watchers for group %s", groupRef);
      } else {
        logger.atFine().log("The filter did not match for group %s; skip notification", groupRef);
      }
    }

    if (!nc.getAddresses().isEmpty()) {
      if (filterMatch(null, nc.getFilter())) {
        matching.list(nc.getHeader()).emails.addAll(nc.getAddresses());
        logger.atFine().log("Added watchers for these addresses: %s", nc.getAddresses());
      } else {
        logger.atFine().log(
            "The filter did not match; skip notification for these addresses: %s",
            nc.getAddresses());
      }
    }
  }

  private void deliverToMembers(WatcherList matching, AccountGroup.UUID startUUID) {
    Set<AccountGroup.UUID> seen = new HashSet<>();
    List<AccountGroup.UUID> q = new ArrayList<>();

    seen.add(startUUID);
    q.add(startUUID);

    while (!q.isEmpty()) {
      AccountGroup.UUID uuid = q.remove(q.size() - 1);
      GroupDescription.Basic group = args.groupBackend.get(uuid);
      if (group == null) {
        logger.atFine().log("group %s not found, skip notification", uuid);
        continue;
      }
      if (!Strings.isNullOrEmpty(group.getEmailAddress())) {
        // If the group has an email address, do not expand membership.
        matching.emails.add(Address.create(group.getEmailAddress()));
        logger.atFine().log(
            "notify group email address %s; skip expanding to members", group.getEmailAddress());
        continue;
      }

      if (!(group instanceof GroupDescription.Internal)) {
        // Non-internal groups cannot be expanded by the server.
        logger.atFine().log("group %s is not an internal group, skip notification", uuid);
        continue;
      }

      logger.atFine().log("adding the members of group %s as watchers", uuid);
      GroupDescription.Internal ig = (GroupDescription.Internal) group;
      matching.accounts.addAll(ig.getMembers());
      for (AccountGroup.UUID m : ig.getSubgroups()) {
        if (seen.add(m)) {
          q.add(m);
        }
      }
    }
  }

  @CanIgnoreReturnValue
  private boolean add(
      Watchers matching,
      Account.Id accountId,
      ProjectWatchKey key,
      Set<NotifyConfig.NotifyType> watchedTypes,
      NotifyConfig.NotifyType type) {
    logger.atFine().log("Checking project watch %s of account %s", key, accountId);

    IdentifiedUser user = args.identifiedUserFactory.create(accountId);
    try {
      if (filterMatch(user, key.filter())) {
        // If we are set to notify on this type, add the user.
        // Otherwise, still return true to stop notifications for this user.
        if (watchedTypes.contains(type)) {
          matching.bcc.accounts.add(accountId);
        }
        logger.atFine().log("Added account %s as watcher", accountId);
        return true;
      }
      logger.atFine().log("The filter did not match for account %s; skip notification", accountId);
    } catch (QueryParseException e) {
      // Ignore broken filter expressions.
      logger.atInfo().log(
          "Account %s has invalid filter in project watch %s: %s", accountId, key, e.getMessage());
    }
    return false;
  }

  private boolean filterMatch(CurrentUser user, String filter) throws QueryParseException {
    WatcherChangeQueryBuilder qb;
    Predicate<ChangeData> p = null;

    if (user == null) {
      qb = WatcherChangeQueryBuilder.asUser(args.queryBuilder.get(), args.anonymousUser.get());
    } else {
      qb = WatcherChangeQueryBuilder.asUser(args.queryBuilder.get(), user);
      p = qb.isVisible();
    }

    if (filter != null) {
      Predicate<ChangeData> filterPredicate = qb.parse(filter);
      if (p == null) {
        p = filterPredicate;
      } else {
        p = Predicate.and(filterPredicate, p);
      }
    }
    return p == null || p.asMatchable().match(changeData);
  }

  public static class WatcherChangeQueryBuilder extends ChangeQueryBuilder {
    public WatcherChangeQueryBuilder(Arguments args) {
      super(args);
    }

    public static WatcherChangeQueryBuilder asUser(ChangeQueryBuilder other, CurrentUser user) {
      return new WatcherChangeQueryBuilder(other.getArgs().asUser(user));
    }

    @Override
    protected Predicate<ChangeData> defaultField(String query) throws QueryParseException {
      if (query.startsWith("refs/")) {
        return ref(query);
      }

      // Adapt the capacity of this list when adding more default predicates.
      List<Predicate<ChangeData>> predicates = Lists.newArrayListWithCapacity(11);
      predicates.add(file(query));
      try {
        predicates.add(label(query));
      } catch (StorageException | IOException | ConfigInvalidException | QueryParseException e) {
        // Skip.
      }
      predicates.add(commit(query));
      predicates.add(message(query));
      predicates.add(comment(query));
      predicates.add(projects(query));
      predicates.add(ref(query));
      predicates.add(branch(query));
      predicates.add(topic(query));
      // Adapt the capacity of the "predicates" list when adding more default
      // predicates.
      return Predicate.or(predicates);
    }

    @Override
    public Predicate<ChangeData> is(String value) throws QueryParseException {
      if ("watched".equalsIgnoreCase(value)) {
        // project watches cannot use "is:watched" as this would trigger an endless loop in
        // IsWatchedByPredicate
        throw new QueryParseException(
            String.format("Operator 'is:watched' cannot be used in project watches."));
      }
      return super.is(value);
    }
  }
}
