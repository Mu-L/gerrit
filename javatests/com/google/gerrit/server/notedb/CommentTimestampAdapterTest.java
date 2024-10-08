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

package com.google.gerrit.server.notedb;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Comment;
import com.google.gerrit.entities.HumanComment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CommentTimestampAdapterTest {
  /** Arbitrary time outside of a DST transition, as an ISO instant. */
  private static final String NON_DST_STR = "2017-02-07T10:20:30.123Z";

  /** Arbitrary time outside of a DST transition, as a reasonable Java 11 representation. */
  private static final ZonedDateTime NON_DST = ZonedDateTime.parse(NON_DST_STR);

  /** {@link #NON_DST_STR} truncated to seconds. */
  private static final String NON_DST_STR_TRUNC = "2017-02-07T10:20:30Z";

  /** Arbitrary time outside of a DST transition, as an unreasonable Timestamp representation. */
  private static final Timestamp NON_DST_TS = Timestamp.from(NON_DST.toInstant());

  /** {@link #NON_DST_TS} truncated to seconds. */
  private static final Timestamp NON_DST_TS_TRUNC =
      Timestamp.from(ZonedDateTime.parse(NON_DST_STR_TRUNC).toInstant());

  /**
   * Real live ms since epoch timestamp of a comment that was posted during the PDT to PST
   * transition in November 2013.
   */
  private static final long MID_DST_MS = 1383466224175L;

  private TimeZone systemTimeZone;
  private Gson legacyGson;
  private Gson gson;

  @Before
  public void setUp() {
    systemTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));

    // Match ChangeNoteUtil#gson as of 4e1f02db913d91f2988f559048e513e6093a1bce
    legacyGson = new GsonBuilder().setPrettyPrinting().create();
    gson = ChangeNoteJson.newGson();
  }

  @After
  public void tearDown() {
    TimeZone.setDefault(systemTimeZone);
  }

  private String normalizeWhitespaces(String input) {
    // There is a known difference between different JDK versions. Common Locale Data Repository
    // (CLDR) version 42 replaces ASCII spaces (U+0020) with NNBSP (Narrow No-Break Space, U+202F)
    // in some places within the date time. The change was made in CLDR 42, which  JDK 20 and newer
    // use: https://bugs.openjdk.org/browse/JDK-8284840.
    // For test purposes, we will make whitespaces consistent, so they can be compared directly with
    // an expected output in all JDK version.
    return input.replace("\u202F", " ");
  }

  @Test
  public void legacyAdapterViaZonedDateTime() {
    assertThat(normalizeWhitespaces(legacyGson.toJson(NON_DST_TS)))
        .isEqualTo("\"Feb 7, 2017, 2:20:30 AM\"");
  }

  @Test
  public void legacyAdapterCanParseOutputOfNewAdapter() {
    String instantJson = gson.toJson(NON_DST_TS);
    assertThat(instantJson).isEqualTo('"' + NON_DST_STR_TRUNC + '"');
    Timestamp result = legacyGson.fromJson(instantJson, Timestamp.class);
    assertThat(result).isEqualTo(NON_DST_TS_TRUNC);
  }

  @Test
  public void newAdapterCanParseOutputOfLegacyAdapter() {
    String legacyJson = legacyGson.toJson(NON_DST_TS);
    assertThat(normalizeWhitespaces(legacyJson)).isEqualTo("\"Feb 7, 2017, 2:20:30 AM\"");
    assertThat(gson.fromJson(legacyJson, Timestamp.class))
        .isEqualTo(new Timestamp(NON_DST_TS.getTime() / 1000 * 1000));
  }

  @Test
  public void newAdapterCanParseOutputOfLegacyAdapterFromOldJDK() {
    // The old JDK8 formatted the date time without a comma after the year.
    String legacyJson = "\"Feb 7, 2017 2:20:30 AM\"";
    assertThat(gson.fromJson(legacyJson, Timestamp.class))
        .isEqualTo(new Timestamp(NON_DST_TS.getTime() / 1000 * 1000));
  }

  @Test
  public void fixedFallbackFormatCanParseOutputOfLegacyAdapter() {
    assertThat(CommentTimestampAdapter.parseDateTimeWithFixedFormat("Feb 7, 2017 2:20:30 AM"))
        .isEqualTo(Timestamp.from(ZonedDateTime.parse("2017-02-07T10:20:30Z").toInstant()));
    assertThat(CommentTimestampAdapter.parseDateTimeWithFixedFormat("Feb 17, 2017 10:20:30 AM"))
        .isEqualTo(Timestamp.from(ZonedDateTime.parse("2017-02-17T18:20:30Z").toInstant()));
    assertThat(CommentTimestampAdapter.parseDateTimeWithFixedFormat("Feb 17, 2017 02:20:30 PM"))
        .isEqualTo(Timestamp.from(ZonedDateTime.parse("2017-02-17T22:20:30Z").toInstant()));
    assertThat(CommentTimestampAdapter.parseDateTimeWithFixedFormat("Feb 07, 2017 10:20:30 PM"))
        .isEqualTo(Timestamp.from(ZonedDateTime.parse("2017-02-08T06:20:30Z").toInstant()));
  }

  @Test
  public void newAdapterDisagreesWithLegacyAdapterDuringDstTransition() {
    String duringJson = legacyGson.toJson(new Timestamp(MID_DST_MS));
    Timestamp duringTs = legacyGson.fromJson(duringJson, Timestamp.class);

    // This is unfortunate, but it's just documenting the current behavior, there is no real good
    // solution here. The goal is that all these changes will be rebuilt with proper UTC instant
    // strings shortly after the new adapter is live.
    Timestamp newDuringTs = gson.fromJson(duringJson, Timestamp.class);
    assertThat(newDuringTs.toString()).isEqualTo(duringTs.toString());
    assertThat(newDuringTs).isNotEqualTo(duringTs);
  }

  @Test
  public void newAdapterRoundTrip() {
    String json = gson.toJson(NON_DST_TS);
    // Round-trip lossily truncates ms, but that's ok.
    assertThat(json).isEqualTo('"' + NON_DST_STR_TRUNC + '"');
    assertThat(gson.fromJson(json, Timestamp.class)).isEqualTo(NON_DST_TS_TRUNC);
  }

  @Test
  public void nullSafety() {
    assertThat(gson.toJson(null, Timestamp.class)).isEqualTo("null");
    assertThat(gson.fromJson("null", Timestamp.class)).isNull();
  }

  @Test
  public void newAdapterRoundTripOfWholeComment() {
    Comment c =
        new HumanComment(
            new Comment.Key("uuid", "filename", 1),
            Account.id(100),
            NON_DST_TS.toInstant(),
            (short) 0,
            "message",
            "serverId",
            false);
    c.lineNbr = 1;
    c.setCommitId(ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));

    String json = gson.toJson(c);
    assertThat(json).contains("\"writtenOn\": \"" + NON_DST_STR_TRUNC + "\",");

    Comment result = gson.fromJson(json, HumanComment.class);
    // Round-trip lossily truncates ms, but that's ok.
    assertThat(result.writtenOn).isEqualTo(NON_DST_TS_TRUNC);
    result.writtenOn = NON_DST_TS;
    assertThat(result).isEqualTo(c);
  }
}
