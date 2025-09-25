package com.uber.nullaway.jdkannotations;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
public class AstubxGeneratorCLI {
  // For JSON parsing
  private static record TypeParam(String name, List<String> bounds) {}

  private static record MethodJson(
      String returnType,
      String name,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParam> typeParams) {}

  private static record ClassJson(
      String name,
      String type,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParam> typeParams,
      List<MethodJson> methods) {}

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Invalid number of arguments. Required: <inputPath> <outputPath>");
      System.exit(2);
    }
    generateAstubx(args[0], args[1]);
  }

  public static class AstubxData {
    public final Map<String, MethodAnnotationsRecord> methodRecords;
    public final Map<String, Set<Integer>> nullableUpperBounds;
    public final Set<String> nullMarkedClasses;

    public AstubxData(
        Map<String, MethodAnnotationsRecord> methodRecords,
        Map<String, Set<Integer>> nullableUpperBounds,
        Set<String> nullMarkedClasses) {
      this.methodRecords = methodRecords;
      this.nullableUpperBounds = nullableUpperBounds;
      this.nullMarkedClasses = nullMarkedClasses;
    }

    @Override
    public String toString() {
      return "AstubxData{"
          + "methodRecords="
          + methodRecords
          + ", nullableUpperBounds="
          + nullableUpperBounds
          + ", nullMarkedClasses="
          + nullMarkedClasses
          + '}';
    }
  }

  public static AstubxData generateAstubx(String jsonDirPath, String astubxDirPath) {
    // get parsed JSON file
    Map<String, List<ClassJson>> parsed = parseJson(jsonDirPath);

    // check if the astubx file directory exists
    try {
      Files.createDirectories(Paths.get(astubxDirPath));
    } catch (IOException e) {
      System.err.println("Failed to create directory: " + astubxDirPath + e.getMessage());
    }
    File outputFile = new File(astubxDirPath, "output.astubx");

    ImmutableMap<String, String> importedAnnotations =
        ImmutableMap.of(
            "NonNull", "org.jspecify.annotations.NonNull",
            "Nullable", "org.jspecify.annotations.Nullable");

    Map<String, Set<String>> packageAnnotations = new HashMap<>(); // not used
    Map<String, Set<String>> typeAnnotations = new HashMap<>();
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();
    Set<String> nullMarkedClasses = new LinkedHashSet<>();
    Map<String, Set<Integer>> nullableUpperBounds = new LinkedHashMap<>(); // not used

    for (Map.Entry<String, List<ClassJson>> entry : parsed.entrySet()) {
      // for each class
      for (ClassJson clazz : entry.getValue()) {
        // get fully qualified class name
        String fullyQualifiedClassName = clazz.type();
        if (fullyQualifiedClassName.indexOf('<') != -1) {
          fullyQualifiedClassName =
              fullyQualifiedClassName.substring(0, fullyQualifiedClassName.indexOf('<'));
        }
        // (needed? NullAway checks NullMarkedness with fully qualified name)
        if (clazz.nullMarked()) {
          nullMarkedClasses.add(fullyQualifiedClassName);
        }

        // check upperbounds of type parameters
        for (int idx = 0; idx < clazz.typeParams().size(); idx++) {
          TypeParam typeParam = clazz.typeParams().get(idx);
          Set<Integer> upperBoundIndex = new LinkedHashSet<>();
          for (String bound : typeParam.bounds()) {
            if (bound.matches("@Nullable .*")) {
              upperBoundIndex.add(idx);
            }
          }
          if (!upperBoundIndex.isEmpty()) {
            nullableUpperBounds.put(fullyQualifiedClassName, upperBoundIndex);
          }
        }

        // get methodRecords
        getMethodRecords(clazz, fullyQualifiedClassName, methodRecords);
      }
    }

    // write astubx file
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

    // return result as modelData (for testing)
    AstubxData modelData = new AstubxData(methodRecords, nullableUpperBounds, nullMarkedClasses);
    return modelData;
  }

  private static Map<String, List<ClassJson>> parseJson(String jsonDirPath) {
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
    Type parsedType = new TypeToken<Map<String, List<ClassJson>>>() {}.getType();

    File jsonFile = jsonFiles[0]; // only one JSON file created

    // parse JSON file
    Map<String, List<ClassJson>> parsed;
    try {
      String jsonContent = Files.readString(jsonFile.toPath());
      parsed = gson.fromJson(jsonContent, parsedType);
    } catch (IOException e) {
      System.err.println("Error reading JSON file: " + jsonFile.getAbsolutePath());
      throw new RuntimeException(e);
    }

    return parsed;
  }

  private static void getMethodRecords(
      ClassJson clazz,
      String fullyQualifiedClassName,
      Map<String, MethodAnnotationsRecord> methodRecords) {
    for (MethodJson method : clazz.methods()) {
      String methodName = method.name();
      // skip all constructors
      if (methodName.substring(0, methodName.indexOf('(')).equals(clazz.name())) {
        continue;
      }
      // get return type nullness
      String returnType = method.returnType();
      ImmutableSet<String> returnTypeNullness = ImmutableSet.of();
      // check if return type has Nullable annotation
      if (returnType.contains("@org.jspecify.annotations.Nullable")) {
        returnType = returnType.replace("@org.jspecify.annotations.Nullable ", "");
        returnType = returnType.replace(" ", ""); // remove whitespace in Array types
        returnTypeNullness = ImmutableSet.of("Nullable");
      } else {
        // check upperbound if return type is generic
        for (int idx = 0; idx < clazz.typeParams().size(); idx++) {
          if (returnType.equals(clazz.typeParams().get(idx).name())) {
            returnTypeNullness = ImmutableSet.of("Nullable");
          }
        }
      }
      String signatureForMethodRecords = fullyQualifiedClassName + ":" + returnType + " ";
      signatureForMethodRecords += methodName.substring(0, methodName.indexOf('(') + 1);
      Map<Integer, ImmutableSet<String>> argAnnotation = new LinkedHashMap<>();

      // get the arguments
      String argsOnly = methodName.replaceAll(".*\\((.*)\\)", "$1").trim();
      // split using comma but not the commas separating the generics
      String[] arguments =
          argsOnly.isEmpty() ? new String[0] : argsOnly.split(",(?=(?:[^<]*<[^>]*>)*[^>]*$)");

      for (int i = 0; i < arguments.length; i++) {
        String arg = arguments[i].trim();
        // remove generics on arguments
        if (arg.indexOf('<') != -1) {
          arg = arg.substring(0, arg.indexOf('<'));
        }
        // remove Nullable annotation (Should we think of other annotations?)
        if (arg.contains("Nullable")) {
          argAnnotation.put(i, ImmutableSet.of("Nullable"));
          arg = arg.replace("@org.jspecify.annotations.Nullable ", "");
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
