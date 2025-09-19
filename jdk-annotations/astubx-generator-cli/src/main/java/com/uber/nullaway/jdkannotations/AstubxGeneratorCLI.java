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
      System.err.println("JSON Directory does not exist or is not a directory.");
      System.exit(1);
    }

    File[] jsonFiles = jsonDir.listFiles((dir, name) -> name.endsWith(".json"));
    if (jsonFiles == null || jsonFiles.length == 0) {
      System.err.println("No JSON files found.");
      System.exit(1);
    }

    Gson gson = new Gson();
    Type parsedType = new TypeToken<Map<String, List<ClassJson>>>() {}.getType();

    // for each JSON file
    //    for (File jsonFile : jsonFiles) {
    File jsonFile = jsonFiles[0];
    //      String name = jsonFile.getName();
    //      String baseName = name.substring(0, name.length() - ".json".length());
    File outputFile = new File(astubxDirPath, "output.astubx");

    //      System.out.println("Processing: " + jsonFile.getAbsolutePath());

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
    //    Set<String> nullMarkedClassTypes = new LinkedHashSet<>();
    Map<String, Set<Integer>> nullableUpperBounds = new LinkedHashMap<>(); // not used

    for (Map.Entry<String, List<ClassJson>> entry : parsed.entrySet()) {
      // for each class
      for (ClassJson clazz : entry.getValue()) {
        // remove type parameters from the class type
        String className = clazz.type();
        if (className.indexOf('<') != -1) {
          className = className.substring(0, className.indexOf('<'));
        }
        boolean nullMarked = clazz.nullMarked();
        if (clazz.nullMarked()) {
          nullMarkedClasses.add(className);
          //          nullMarkedClassTypes.add(clazz.type());
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
            nullableUpperBounds.put(className, upperBoundIndex);
          }
        }

        for (MethodJson method : clazz.methods()) {
          String methodName = method.name();
          String returnType = method.returnType();
          ImmutableSet<String> returnTypeNullness = ImmutableSet.of();
          if (returnType.indexOf(" ") != -1) {
            //            System.err.println("nullable return");
            returnType = returnType.replace("@org.jspecify.annotations.Nullable ", "");
            returnType = returnType.replace(" ", "");
            returnTypeNullness = ImmutableSet.of("Nullable");
          }
          String signature = className + ":" + returnType + " ";
          signature += methodName.substring(0, methodName.indexOf('(') + 1);
          if (methodName.substring(0, methodName.length() - 2).equals(clazz.name())) {
            continue;
          }
          //            System.err.println("methodName: " + methodName);
          //            System.err.println("signature: " + signature);
          Map<Integer, ImmutableSet<String>> argAnnotation = new LinkedHashMap<>();

          // get the arguments
          String argsOnly = methodName.replaceAll(".*\\((.*)\\)", "$1").trim();
          //            System.out.println("argsOnly: " + argsOnly);
          // split using comma but not the commas separating the generics
          String[] arguments =
              argsOnly.isEmpty() ? new String[0] : argsOnly.split(",(?=(?:[^<]*<[^>]*>)*[^>]*$)");

          String nullableParameters = "";
          for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i].trim();
            //              System.err.println("arg:" + arg);
            if (arg.indexOf('<') != -1) {
              arg = arg.substring(0, arg.indexOf('<'));
              //                System.err.println("< trimmed" + arg);
            }
            if (arg.contains("Nullable")) {
              argAnnotation.put(i, ImmutableSet.of("Nullable"));
              //                arg = arg.substring(arg.indexOf(" ")).trim();
              arg = arg.replace("@org.jspecify.annotations.Nullable ", "");
              arg = arg.replace(" ", "");
              nullableParameters += arg;
              //                System.err.println("Contains nullable parameter: " +
              // nullableParameters);
            }
            signature += arg + ", ";
          }
          if (argAnnotation == null) {}
          if (!nullableParameters.equals("")) {
            methodName = className + ":" + nullableParameters + " " + methodName;
          }
          //            System.err.println(">> " + methodName);
          if (arguments.length == 0) {
            signature += ")";
          } else {
            signature = signature.substring(0, signature.length() - 2) + ")";
          }
          if (nullMarked && (!returnTypeNullness.isEmpty() || !nullableParameters.isEmpty())) {
            methodRecords.put(
                signature,
                MethodAnnotationsRecord.create(
                    returnTypeNullness, ImmutableMap.copyOf(argAnnotation)));
          }
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
    //      System.out.println("Wrote output to " + outputFile.getAbsolutePath());
    //    }
  }
}
