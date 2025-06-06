// Copyright (C) 2023 The Android Open Source Project
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

package com.google.gerrit.server.account.externalids.storage.notedb;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.toOptional;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.config.AuthConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Class to access external IDs.
 *
 * <p>The external IDs are either read from NoteDb or retrieved from the cache.
 */
@Singleton
public class ExternalIdsNoteDbImpl implements ExternalIds {
  private final ExternalIdReader externalIdReader;
  @Nullable private final ExternalIdCacheImpl externalIdCache;
  private final AuthConfig authConfig;
  private final ExternalIdKeyFactory externalIdKeyFactory;

  @Inject
  ExternalIdsNoteDbImpl(
      ExternalIdReader externalIdReader,
      Optional<ExternalIdCacheImpl> externalIdCacheImpl,
      ExternalIdKeyFactory externalIdKeyFactory,
      AuthConfig authConfig) {
    this.externalIdReader = externalIdReader;
    this.externalIdCache =
        externalIdCacheImpl.orElse(
            // Supported case for tests or Google implementation. None of the disabled cache methods
            // should be called from these flows, so it's safe to not assign the var.
            null);
    this.externalIdKeyFactory = externalIdKeyFactory;
    this.authConfig = authConfig;
  }

  @Override
  public ImmutableSet<ExternalId> all() throws IOException, ConfigInvalidException {
    return externalIdReader.all();
  }

  /** Returns all external IDs from the specified revision of the refs/meta/external-ids branch. */
  public ImmutableSet<ExternalId> all(ObjectId rev) throws IOException, ConfigInvalidException {
    return externalIdReader.all(rev);
  }

  @Override
  public Optional<ExternalId> get(ExternalId.Key key) throws IOException {
    ImmutableSet<ExternalId> res = get(ImmutableSet.of(key));
    checkState(res.size() <= 1, "Got multiple matches for external ID [%s]", key);
    return !res.isEmpty() ? res.stream().collect(toOptional()) : Optional.empty();
  }

  @Override
  public ImmutableSet<ExternalId> get(Set<ExternalId.Key> keys) throws IOException {
    ImmutableSet.Builder<ExternalId> res = ImmutableSet.builder();
    for (ExternalId.Key key : keys) {
      Optional<ExternalId> externalId = Optional.empty();
      if (authConfig.isUserNameCaseInsensitiveMigrationMode()) {
        externalId =
            externalIdCache.byKey(externalIdKeyFactory.create(key.scheme(), key.id(), false));
      }
      if (externalId.isEmpty()) {
        externalId = externalIdCache.byKey(key);
      }
      if (externalId.isPresent()) {
        res.add(externalId.get());
      }
    }
    return res.build();
  }

  @Override
  public ImmutableSet<ExternalId> byAccount(Account.Id accountId) throws IOException {
    return externalIdCache.byAccount(accountId);
  }

  @Override
  public ImmutableSet<ExternalId> byAccount(Account.Id accountId, String scheme)
      throws IOException {
    return byAccount(accountId).stream()
        .filter(e -> e.key().isScheme(scheme))
        .collect(toImmutableSet());
  }

  /** Returns the external IDs of the specified account. */
  public ImmutableSet<ExternalId> byAccount(Account.Id accountId, ObjectId rev) throws IOException {
    return externalIdCache.byAccount(accountId, rev);
  }

  @Override
  public ImmutableSetMultimap<Account.Id, ExternalId> allByAccount() throws IOException {
    return externalIdCache.allByAccount();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The external IDs are retrieved from the external ID cache. Each access to the external ID *
   * cache requires reading the SHA1 of the refs/meta/external-ids branch. If external IDs for *
   * multiple emails are needed it is more efficient to use {@link #byEmails(String...)} as this *
   * method reads the SHA1 of the refs/meta/external-ids branch only once (and not once per email).
   *
   * @see #byEmails(String...)
   */
  @Override
  public ImmutableSet<ExternalId> byEmail(String email) throws IOException {
    return externalIdCache.byEmail(email);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The external IDs are retrieved from the external ID cache. Each access to the external ID
   * cache requires reading the SHA1 of the refs/meta/external-ids branch. If external IDs for
   * multiple emails are needed it is more efficient to use this method instead of {@link
   * #byEmail(String)} as this method reads the SHA1 of the refs/meta/external-ids branch only once
   * (and not once per email).
   *
   * @see #byEmail(String)
   */
  @Override
  public ImmutableSetMultimap<String, ExternalId> byEmails(String... emails) throws IOException {
    return externalIdCache.byEmails(emails);
  }
}
