/*
 * Copyright (C) 2017. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.myapplication;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import org.utilities.StringUtils;

/** Sample activity. */
@SuppressWarnings("UnusedVariable") // This is sample code
public class MainActivity extends AppCompatActivity {
  @NonNull private Object mOnCreateInitialiedField;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    mOnCreateInitialiedField = new Object();
    // uncomment to show that NullAway is actually running
    // Object x = null;
    // x.hashCode();
  }

  static int checkModel(@Nullable String s) {
    if (!StringUtils.isEmptyOrNull(s)) {
      return s.hashCode();
    }
    return 0;
  }
}
