/*
 * Copyright (c) 2022 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.fixserialization;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import com.uber.nullaway.fixserialization.out.FieldInitializationInfo;
import com.uber.nullaway.fixserialization.out.SuggestedNullableFixInfo;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

/**
 * Serializer class where all generated files in Fix Serialization package is created through APIs
 * of this class.
 */
public class Serializer {
  /** Path to write errors. */
  private final Path errorOutputPath;

  /** Path to write suggested fix metadata. */
  private final Path suggestedFixesOutputPath;

  /** Path to write suggested fix metadata. */
  private final Path fieldInitializationOutputPath;

  /**
   * Adapter used to serialize outputs. This adapter is capable of serializing outputs according to
   * the requested serilization version and maintaining backward compatibility with previous
   * versions of NullAway.
   */
  private final SerializationAdapter serializationAdapter;

  public Serializer(FixSerializationConfig config, SerializationAdapter serializationAdapter) {
    String outputDirectory = config.outputDirectory;
    this.errorOutputPath = Paths.get(outputDirectory, "errors.tsv");
    this.suggestedFixesOutputPath = Paths.get(outputDirectory, "fixes.tsv");
    this.fieldInitializationOutputPath = Paths.get(outputDirectory, "field_init.tsv");
    this.serializationAdapter = serializationAdapter;
    serializeVersion(outputDirectory);
    initializeOutputFiles(config);
  }

  /**
   * Appends the string representation of the {@link SuggestedNullableFixInfo}.
   *
   * @param suggestedNullableFixInfo SuggestedFixInfo object.
   * @param enclosing Flag to control if enclosing method and class should be included.
   */
  public void serializeSuggestedFixInfo(
      SuggestedNullableFixInfo suggestedNullableFixInfo, boolean enclosing) {
    if (enclosing) {
      suggestedNullableFixInfo.initEnclosing();
    }
    appendToFile(
        suggestedNullableFixInfo.tabSeparatedToString(serializationAdapter),
        suggestedFixesOutputPath);
  }

  /**
   * Appends the string representation of the {@link ErrorMessage}.
   *
   * @param errorInfo ErrorMessage object.
   */
  public void serializeErrorInfo(ErrorInfo errorInfo) {
    errorInfo.initEnclosing();
    appendToFile(serializationAdapter.serializeError(errorInfo), errorOutputPath);
  }

  public void serializeFieldInitializationInfo(FieldInitializationInfo info) {
    appendToFile(info.tabSeparatedToString(serializationAdapter), fieldInitializationOutputPath);
  }

  /** Cleared the content of the file if exists and writes the header in the first line. */
  private void initializeFile(Path path, String header) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new RuntimeException("Could not clear file at: " + path, e);
    }
    try (OutputStream os = new FileOutputStream(path.toFile())) {
      header += "\n";
      os.write(header.getBytes(Charset.defaultCharset()), 0, header.length());
      os.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not finish resetting File at Path: " + path, e);
    }
  }

  /**
   * Returns the serialization version.
   *
   * @return The serialization version.
   */
  public int getSerializationVersion() {
    return serializationAdapter.getSerializationVersion();
  }

  /**
   * Serializes the using {@link SerializationAdapter} version as {@code string} in
   * <b>serialization_version.txt</b> file under root output directory for all serialized outputs.
   *
   * @param outputDirectory Path to root directory for all serialized outputs.
   */
  private void serializeVersion(@Nullable String outputDirectory) {
    Path versionOutputPath = Paths.get(outputDirectory).resolve("serialization_version.txt");
    try (Writer fileWriter =
        Files.newBufferedWriter(versionOutputPath.toFile().toPath(), Charset.defaultCharset())) {
      fileWriter.write(Integer.toString(serializationAdapter.getSerializationVersion()));
    } catch (IOException exception) {
      throw new RuntimeException("Could not serialize output version", exception);
    }
  }

  /** Initializes every file which will be re-generated in the new run of NullAway. */
  private void initializeOutputFiles(FixSerializationConfig config) {
    try {
      Files.createDirectories(Paths.get(config.outputDirectory));
      if (config.suggestEnabled) {
        initializeFile(suggestedFixesOutputPath, SuggestedNullableFixInfo.header());
      }
      if (config.fieldInitInfoEnabled) {
        initializeFile(fieldInitializationOutputPath, FieldInitializationInfo.header());
      }
      initializeFile(errorOutputPath, serializationAdapter.getErrorsOutputFileHeader());
    } catch (IOException e) {
      throw new RuntimeException("Could not finish resetting serializer", e);
    }
  }

  private void appendToFile(String row, Path path) {
    // Since there is no method available in API of either javac or errorprone to inform NullAway
    // that the analysis is finished, we cannot open a single stream and flush it within a finalize
    // method. Must open and close a new stream everytime we are appending a new line to a file.
    if (row == null || row.equals("")) {
      return;
    }
    row = row + "\n";
    try (OutputStream os = new FileOutputStream(path.toFile(), true)) {
      os.write(row.getBytes(Charset.defaultCharset()), 0, row.length());
      os.flush();
    } catch (IOException e) {
      throw new RuntimeException("Error happened for writing at file: " + path, e);
    }
  }

  /**
   * Converts the given uri to the real path. Note, in NullAway CI tests, source files exists in
   * memory and there is no real path leading to those files. Instead, we just serialize the path
   * from uri as the full paths are not checked in tests.
   *
   * @param uri Given uri.
   * @return Real path for the give uri.
   */
  @Nullable
  public static Path pathToSourceFileFromURI(@Nullable URI uri) {
    if (uri == null) {
      return null;
    }
    if ("jimfs".equals(uri.getScheme())) {
      // In NullAway unit tests, files are stored in memory and have this scheme.
      return Paths.get(uri);
    }
    if (!"file".equals(uri.getScheme())) {
      return null;
    }
    Path path = Paths.get(uri);
    try {
      return path.toRealPath();
    } catch (IOException e) {
      // In this case, we still would like to continue the serialization instead of returning null
      // and not serializing anything.
      return path;
    }
  }

  /**
   * Serializes the given {@link Symbol} to a string.
   *
   * @param symbol The symbol to serialize.
   * @param adapter adapter used to serialize symbols.
   * @return The serialized symbol.
   */
  public static String serializeSymbol(@Nullable Symbol symbol, SerializationAdapter adapter) {
    if (symbol == null) {
      return "null";
    }
    switch (symbol.getKind()) {
      case FIELD:
      case PARAMETER:
        return symbol.name.toString();
      case METHOD:
      case CONSTRUCTOR:
        return adapter.serializeMethodSignature((Symbol.MethodSymbol) symbol);
      default:
        return symbol.flatName().toString();
    }
  }
}
