package com.uber.nullaway.handlers.stream;

import com.google.common.collect.ImmutableSet;

public interface StreamMethodRecord {

  ImmutableSet<Integer> argsFromStream();
}
