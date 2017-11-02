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

import static com.uber.nullaway.LibraryModels.MemberName.member;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.LibraryModels;

@AutoService(LibraryModels.class)
public class ExampleLibraryModels implements LibraryModels {

  @Override
  public ImmutableSetMultimap<MemberName, Integer> failIfNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MemberName, Integer> nonNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MemberName, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.<MemberName, Integer>builder()
        .put(member("org.utilities.StringUtils", "isEmptyOrNull"), 0)
        .build();
  }

  @Override
  public ImmutableSet<MemberName> nullableReturns() {
    return ImmutableSet.of();
  }
}
