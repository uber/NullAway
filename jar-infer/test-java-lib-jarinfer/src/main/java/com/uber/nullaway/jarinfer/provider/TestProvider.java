package com.uber.nullaway.jarinfer.provider;

import com.google.auto.service.AutoService;
import com.uber.nullaway.jarinfer.JarInferStubxProvider;
import java.util.Collections;
import java.util.List;

@AutoService(JarInferStubxProvider.class)
public class TestProvider implements JarInferStubxProvider {
  @Override
  public List<String> pathsToStubxFiles() {
    return Collections.singletonList("jarinfer.astubx");
  }
}
