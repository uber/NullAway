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

  public static class LibraryModelData {
    public final Map<String, MethodAnnotationsRecord> methodRecords;
    public final Map<String, Set<Integer>> nullableUpperBounds;
    public final Set<String> nullMarkedClasses;

    public LibraryModelData(
        Map<String, MethodAnnotationsRecord> methodRecords,
        Map<String, Set<Integer>> nullableUpperBounds,
        Set<String> nullMarkedClasses) {
      this.methodRecords = methodRecords;
      this.nullableUpperBounds = nullableUpperBounds;
      this.nullMarkedClasses = nullMarkedClasses;
    }

    @Override
    public String toString() {
      return "ModelData{"
          + "methodRecords="
          + methodRecords
          + ", nullableUpperBounds="
          + nullableUpperBounds
          + ", nullMarkedClasses="
          + nullMarkedClasses
          + '}';
    }
  }

  public static LibraryModelData generateAstubx(String jsonDirPath, String astubxDirPath) {
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
    // check if the astubx file directory exists
    try {
      Files.createDirectories(Paths.get(astubxDirPath));
    } catch (IOException e) {
      System.err.println("Failed to create directory: " + astubxDirPath + e.getMessage());
    }
    File outputFile = new File(astubxDirPath, "output.astubx");

    // parse JSON file
    Map<String, List<ClassJson>> parsed;
    try {
      String jsonContent = Files.readString(jsonFile.toPath());
      parsed = gson.fromJson(jsonContent, parsedType);
    } catch (IOException e) {
      System.err.println("Error reading JSON file: " + jsonFile.getAbsolutePath());
      throw new RuntimeException(e);
    }

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
        // remove type parameters from the class type
        String fullyQualifiedClassName = clazz.type();
        // remove generics
        if (fullyQualifiedClassName.indexOf('<') != -1) {
          fullyQualifiedClassName =
              fullyQualifiedClassName.substring(0, fullyQualifiedClassName.indexOf('<'));
        }
        if (clazz.nullMarked()) {
          nullMarkedClasses.add(fullyQualifiedClassName);
        }

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

        for (MethodJson method : clazz.methods()) {
          String methodName = method.name();
          String returnType = method.returnType();
          // skip all constructors
          if (methodName.substring(0, methodName.indexOf('(')).equals(clazz.name())) {
            continue;
          }
          ImmutableSet<String> returnTypeNullness = ImmutableSet.of();
          // check Nullable annotation of return type
          if (returnType.indexOf(" ") != -1) {
            returnType = returnType.replace("@org.jspecify.annotations.Nullable ", "");
            returnType = returnType.replace(" ", "");
            returnTypeNullness = ImmutableSet.of("Nullable");
          } else {
            // check upperbound if return type is a generic type
            for (int idx = 0; idx < clazz.typeParams().size(); idx++) {
              if (returnType.equals(clazz.typeParams().get(idx).name())) {
                returnTypeNullness = ImmutableSet.of("Nullable");
              }
            }
          }
          String signature = fullyQualifiedClassName + ":" + returnType + " ";
          signature += methodName.substring(0, methodName.indexOf('(') + 1);
          Map<Integer, ImmutableSet<String>> argAnnotation = new LinkedHashMap<>();

          // get the arguments
          String argsOnly = methodName.replaceAll(".*\\((.*)\\)", "$1").trim();
          // split using comma but not the commas separating the generics
          String[] arguments =
              argsOnly.isEmpty() ? new String[0] : argsOnly.split(",(?=(?:[^<]*<[^>]*>)*[^>]*$)");

          for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i].trim();
            if (arg.indexOf('<') != -1) {
              arg = arg.substring(0, arg.indexOf('<'));
            }
            if (arg.contains("Nullable")) {
              argAnnotation.put(i, ImmutableSet.of("Nullable"));
              arg = arg.replace("@org.jspecify.annotations.Nullable ", "");
              arg = arg.replace(" ", "");
            }
            signature += arg + ", ";
          }
          if (arguments.length == 0) {
            signature += ")";
          } else {
            signature = signature.substring(0, signature.length() - 2) + ")";
          }
          methodRecords.put(
              signature,
              MethodAnnotationsRecord.create(
                  returnTypeNullness, ImmutableMap.copyOf(argAnnotation)));
        }
      }
    }

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
    LibraryModelData modelData =
        new LibraryModelData(methodRecords, nullableUpperBounds, nullMarkedClasses);
    return modelData;
  }
}
