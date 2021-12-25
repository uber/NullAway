package com.uber.nullaway.fixserialization;

import com.google.errorprone.VisitorState;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import com.uber.nullaway.fixserialization.out.SeperatedValueDisplay;
import com.uber.nullaway.fixserialization.out.SuggestedFixInfo;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Writer {
  public final Path ERROR;
  public final Path SUGGEST_FIX;
  public final String DELIMITER = "$*$";

  public Writer(FixSerializationConfig config) {
    String outputDirectory = config.outputDirectory;
    this.ERROR = Paths.get(outputDirectory, "errors.csv");
    this.SUGGEST_FIX = Paths.get(outputDirectory, "fixes.csv");
    reset(config);
  }

  public void saveFix(SuggestedFixInfo suggestedFixInfo) {
    appendToFile(suggestedFixInfo, SUGGEST_FIX);
  }

  public void saveErrorNode(ErrorMessage errorMessage, VisitorState state, boolean deep) {
    ErrorInfo error = new ErrorInfo(errorMessage);
    if (deep) {
      error.findEnclosing(state, errorMessage);
    }
    appendToFile(error, ERROR);
  }

  private void resetFile(Path path, String header) {
    try {
      Files.deleteIfExists(path);
      OutputStream os = new FileOutputStream(path.toFile());
      header += "\n";
      os.write(header.getBytes(Charset.defaultCharset()), 0, header.length());
      os.flush();
      os.close();
    } catch (IOException e) {
      throw new RuntimeException(
          "Could not finish resetting File at Path: " + path + ", Exception: " + e);
    }
  }

  private void reset(FixSerializationConfig config) {
    try {
      Files.createDirectories(Paths.get(config.outputDirectory));
      if (config.suggestEnabled) {
        resetFile(SUGGEST_FIX, SuggestedFixInfo.header(DELIMITER));
      }
      if (config.logErrorEnabled) {
        resetFile(ERROR, ErrorInfo.header(DELIMITER));
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not finish resetting writer: " + e);
    }
  }

  private void appendToFile(SeperatedValueDisplay value, Path path) {
    OutputStream os;
    String display = value.display(DELIMITER);
    if (display == null || display.equals("")) {
      return;
    }
    display = display.replaceAll("\\R+", " ").replaceAll("\t", "") + "\n";
    try {
      os = new FileOutputStream(path.toFile(), true);
      os.write(display.getBytes(Charset.defaultCharset()), 0, display.length());
      os.flush();
      os.close();
    } catch (Exception e) {
      System.err.println("Error happened for writing at file: " + path);
    }
  }
}
