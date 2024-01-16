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
package com.uber.modelexample;

import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.EmptyLibraryModels;
import com.uber.nullaway.LibraryModels;

@AutoService(LibraryModels.class)
public class ExampleLibraryModels extends EmptyLibraryModels {
  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.<MethodRef, Integer>builder()
        .put(methodRef("org.utilities.StringUtils", "isEmptyOrNull(java.lang.CharSequence)"), 0)
        .build();
  }
}
