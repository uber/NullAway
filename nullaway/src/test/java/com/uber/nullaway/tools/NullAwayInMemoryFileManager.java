package com.uber.nullaway.tools;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.ErrorProneInMemoryFileManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.tools.StandardLocation;

public class NullAwayInMemoryFileManager extends ErrorProneInMemoryFileManager {

  public NullAwayInMemoryFileManager(Class<?> clazz) {
    super(clazz);
  }

  void createAndInstallTempFolderForOutput() {
    Path tempDirectory;
    try {
      tempDirectory =
          Files.createTempDirectory(fileSystem().getRootDirectories().iterator().next(), "");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Arrays.stream(StandardLocation.values())
        .filter(StandardLocation::isOutputLocation)
        .forEach(
            outputLocation -> {
              try {
                setLocationFromPaths(outputLocation, ImmutableList.of(tempDirectory));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }
}
