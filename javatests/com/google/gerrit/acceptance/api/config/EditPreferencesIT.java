// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.acceptance.api.config;

import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.PreferencesAssertionUtil.assertPrefs;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.extensions.client.EditPreferencesInfo;
import org.junit.Test;

@NoHttpd
public class EditPreferencesIT extends AbstractDaemonTest {

  @Test
  public void getEditPreferences() throws Exception {
    EditPreferencesInfo result = gApi.config().server().getDefaultEditPreferences();
    assertPrefs(result, EditPreferencesInfo.defaults());
  }

  @Test
  public void setEditPreferences() throws Exception {
    int newLineLength = EditPreferencesInfo.defaults().lineLength + 10;
    EditPreferencesInfo update = new EditPreferencesInfo();
    update.lineLength = newLineLength;
    EditPreferencesInfo result = gApi.config().server().setDefaultEditPreferences(update);
    assertWithMessage("lineLength").that(result.lineLength).isEqualTo(newLineLength);

    result = gApi.config().server().getDefaultEditPreferences();
    EditPreferencesInfo expected = EditPreferencesInfo.defaults();
    expected.lineLength = newLineLength;
    assertPrefs(result, expected);
  }
}
