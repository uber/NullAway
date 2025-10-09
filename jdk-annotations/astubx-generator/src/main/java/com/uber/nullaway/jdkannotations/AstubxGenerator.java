package com.uber.nullaway.jdkannotations;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.ClassInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.MethodInfo;
import com.uber.nullaway.javacplugin.NullnessAnnotationSerializer.TypeParamInfo;
import com.uber.nullaway.libmodel.MethodAnnotationsRecord;
import com.uber.nullaway.libmodel.StubxWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class utilizes jdk-javac-plugin module to generate JSON files from Java source files. Using
 * the JSON files, it generates astubx files that contains the required annotation information that
 * NullAway needs.
 */
public class AstubxGenerator {

  public record AstubxData(
      ImmutableMap<String, String> importedAnnotations,
      Map<String, Set<String>> packageAnnotations,
      Map<String, Set<String>> typeAnnotations,
      Map<String, MethodAnnotationsRecord> methodRecords,
      Map<String, Set<Integer>> nullableUpperBounds,
      Set<String> nullMarkedClasses) {}

  public static void generateAstubx(String jsonDirPath, String astubxDirPath) {
    AstubxData astubxData = getAstubxData(jsonDirPath);
    writeToAstubxFile(
        astubxDirPath,
        astubxData.importedAnnotations,
        astubxData.packageAnnotations,
        astubxData.typeAnnotations,
        astubxData.methodRecords,
        astubxData.nullMarkedClasses,
        astubxData.nullableUpperBounds);
  }

  public static AstubxData getAstubxData(String jsonDirPath) {
    Map<String, List<ClassInfo>> parsed = parseJson(jsonDirPath);

    ImmutableMap<String, String> importedAnnotations =
        ImmutableMap.of(
            "NonNull", "org.jspecify.annotations.NonNull",
            "Nullable", "org.jspecify.annotations.Nullable");
    // There is no package-info.java files in jspecify/jdk that were @NullMarked so package
    // information support is skipped in jdk-javac-plugin
    Map<String, Set<String>> packageAnnotations = new HashMap<>();
    Map<String, Set<String>> typeAnnotations = new HashMap<>();
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();
    Set<String> nullMarkedClasses = new LinkedHashSet<>();
    Map<String, Set<Integer>> nullableUpperBounds = new LinkedHashMap<>();

    for (Map.Entry<String, List<ClassInfo>> entry : parsed.entrySet()) {
      // for each class
      for (ClassInfo clazz : entry.getValue()) {
        // get fully qualified class name
        String fullyQualifiedClassName = clazz.type();
        if (fullyQualifiedClassName.indexOf('<') != -1) {
          fullyQualifiedClassName =
              fullyQualifiedClassName.substring(0, fullyQualifiedClassName.indexOf('<'));
        }
        if (clazz.nullMarked()) {
          nullMarkedClasses.add(fullyQualifiedClassName);
        }

        // check upperbounds of type parameters
        Set<Integer> upperBoundIndex = new LinkedHashSet<>();
        for (int idx = 0; idx < clazz.typeParams().size(); idx++) {
          TypeParamInfo typeParam = clazz.typeParams().get(idx);
          for (String bound : typeParam.bounds()) {
            if (bound.contains("@org.jspecify.annotations.Nullable")
                || bound.contains("@Nullable")) {
              upperBoundIndex.add(idx);
            }
          }
        }
        if (!upperBoundIndex.isEmpty()) {
          nullableUpperBounds.put(fullyQualifiedClassName, upperBoundIndex);
        }
        // get methodRecords
        getMethodRecords(clazz, fullyQualifiedClassName, methodRecords);
      }
    }
    // return result as modelData for testing
    AstubxData modelData =
        new AstubxData(
            importedAnnotations,
            packageAnnotations,
            typeAnnotations,
            methodRecords,
            nullableUpperBounds,
            nullMarkedClasses);
    return modelData;
  }

  public static void writeToAstubxFile(
      String astubxDirPath,
      ImmutableMap<String, String> importedAnnotations,
      Map<String, Set<String>> packageAnnotations,
      Map<String, Set<String>> typeAnnotations,
      Map<String, MethodAnnotationsRecord> methodRecords,
      Set<String> nullMarkedClasses,
      Map<String, Set<Integer>> nullableUpperBounds) {
    // check if the astubx file directory exists
    try {
      Files.createDirectories(Paths.get(astubxDirPath));
    } catch (IOException e) {
      System.err.println("Failed to create directory: " + astubxDirPath);
      throw new RuntimeException(e);
    }
    File outputFile = new File(astubxDirPath, "output.astubx");
    try (DataOutputStream out = new DataOutputStream(new FileOutputStream(outputFile))) {
      StubxWriter.write(
          out,
          importedAnnotations,
          packageAnnotations,
          typeAnnotations,
          methodRecords,
          nullMarkedClasses,
          nullableUpperBounds);
    } catch (IOException e) {
      System.err.println("Error writing JSON file: " + outputFile.getAbsolutePath());
      throw new RuntimeException(e);
    }
  }

  /**
   * This method parses the JSON files generated by the jdk-javac-plugin, and returns the
   * information as a Map from module name to information for classes in that module.
   *
   * @param jsonDirPath The path to the JSON files.
   * @return A Map from module name to information for classes in that module.
   */
  private static Map<String, List<ClassInfo>> parseJson(String jsonDirPath) {
    // get parsed JSON file
    File jsonDir = new File(jsonDirPath);

    if (!jsonDir.exists() || !jsonDir.isDirectory()) {
      throw new IllegalArgumentException(
          "JSON directory does not exist or is not a directory: " + jsonDirPath);
    }

    File[] jsonFiles = jsonDir.listFiles((dir, name) -> name.endsWith(".json"));
    if (jsonFiles == null || jsonFiles.length == 0) {
      throw new IllegalStateException("No JSON files found in: " + jsonDirPath);
    }

    Gson gson = new Gson();
    Type parsedType = new TypeToken<Map<String, List<ClassInfo>>>() {}.getType();

    // parse JSON file
    Map<String, List<ClassInfo>> parsed = new HashMap<>();
    for (File jsonFile : jsonFiles) {
      try {
        String jsonContent = Files.readString(jsonFile.toPath());
        parsed.putAll(gson.fromJson(jsonContent, parsedType));
      } catch (IOException e) {
        System.err.println("Error reading JSON file: " + jsonFile.getAbsolutePath());
        throw new RuntimeException(e);
      }
    }

    return parsed;
  }

  private static void getMethodRecords(
      ClassInfo clazz,
      String fullyQualifiedClassName,
      Map<String, MethodAnnotationsRecord> methodRecords) {
    for (MethodInfo method : clazz.methods()) {
      String methodName = method.name();
      // get return type nullness
      String returnType = method.returnType();
      ImmutableSet<String> returnTypeNullness = ImmutableSet.of();
      // check if return type has Nullable annotation
      if (returnType.contains("@org.jspecify.annotations.Nullable")
          || returnType.contains("Nullable")) {
        returnType = returnType.replace("@org.jspecify.annotations.Nullable ", "");
        returnType = returnType.replace(" ", ""); // remove whitespace in Array types
        returnTypeNullness = ImmutableSet.of("Nullable");
      }
      String signatureForMethodRecords = fullyQualifiedClassName + ":" + returnType + " ";
      signatureForMethodRecords += methodName.substring(0, methodName.indexOf('(') + 1);
      Map<Integer, ImmutableSet<String>> argAnnotation = new LinkedHashMap<>();

      // get the arguments
      String argsOnly = methodName.replaceAll(".*\\((.*)\\)", "$1").trim();
      // Split using comma but leave the commas that are inside any angle brackets(to leave
      // generics) or is a comma that divides annotations. After finding a comma, it checks two
      // conditions; 1) if the comma is followed by a '@' character, it skips the comma 2) it looks
      // at the rest of the string and if any angle brackets are not paired, skips the comma
      String[] arguments =
          argsOnly.isEmpty() ? new String[0] : argsOnly.split(",(?!@)(?=(?:[^<]*<[^>]*>)*[^>]*$)");

      for (int i = 0; i < arguments.length; i++) {
        String arg = arguments[i].trim();
        // remove generics on arguments
        if (arg.indexOf('<') != -1) {
          arg = arg.substring(0, arg.indexOf('<'));
        }
        // remove annotations
        if (arg.contains("@")) {
          String[] args = arg.split(" ");
          arg = "";
          for (String argument : args) {
            if (argument.contains("@")) {
              if (argument.contains("Nullable")) {
                argAnnotation.put(i, ImmutableSet.of("Nullable"));
              }
              arg += argument.substring(0, argument.indexOf('@'));
            } else {
              arg += argument;
            }
          }
        } else {
          // remove any spaces in Array types
          arg = arg.replace(" ", "");
        }
        signatureForMethodRecords += arg + ", ";
      }
      if (arguments.length == 0) {
        signatureForMethodRecords += ")";
      } else {
        signatureForMethodRecords =
            signatureForMethodRecords.substring(0, signatureForMethodRecords.length() - 2) + ")";
      }
      methodRecords.put(
          signatureForMethodRecords,
          MethodAnnotationsRecord.create(returnTypeNullness, ImmutableMap.copyOf(argAnnotation)));
    }
  }
}
