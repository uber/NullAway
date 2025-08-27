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

public class AstubxGeneratorCLI {
  // For JSON parsing
  public static record TypeParam(String name, List<String> bounds) {}

  public static record MethodJson(
      String name, boolean nullMarked, boolean nullUnmarked, List<TypeParam> typeParams) {}

  public static record ClassJson(
      String name,
      String type,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParam> typeParams,
      List<MethodJson> methods) {}

  public static void main(String[] args) {
    String jsonDirPath = args[0];
    String astubxDirPath = args[1];

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

    //    ObjectMapper mapper = new ObjectMapper();
    Gson gson = new Gson();
    Type parsedType = new TypeToken<Map<String, List<ClassJson>>>() {}.getType();

    // for each JSON file
    for (File jsonFile : jsonFiles) {
      String name = jsonFile.getName();
      String baseName = name.substring(0, name.length() - ".json".length());
      File outputFile = new File(astubxDirPath, baseName + ".astubx");

      //      System.out.println("Processing: " + jsonFile.getAbsolutePath());

      // parse JSON file
      Map<String, List<ClassJson>> parsed;
      try {
        String jsonContent = Files.readString(jsonFile.toPath());
        parsed = gson.fromJson(jsonContent, parsedType);
        //        parsed =
        //            mapper.readValue(Files.newInputStream(jsonFile.toPath()), new
        // TypeReference<>() {});
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
          if (clazz.nullMarked()) {
            nullMarkedClasses.add(clazz.name());
          }

          for (MethodJson method : clazz.methods()) {
            String signature = method.name();
            Set<String> methodAnns = new LinkedHashSet<>();
            Map<Integer, ImmutableSet<String>> argAnnotation = new LinkedHashMap<>();

            // get the arguments
            String argsOnly = signature.replaceAll(".*\\((.*)\\)", "$1").trim();
            String[] arguments =
                argsOnly.isEmpty()
                    ? new String[0]
                    : argsOnly.split(
                        ",(?=(?:[^<]*<[^>]*>)*[^>]*$)"); // split using comma but not the commas
            // separating the generics

            for (int i = 0; i < arguments.length; i++) {
              String arg = arguments[i].trim();
              if (arg.indexOf('<') != -1) {
                arg = arg.substring(0, arg.indexOf('<'));
              }
              if (arg.contains("Nullable")) {
                argAnnotation.put(i, ImmutableSet.of("Nullable"));
              }
            }
            methodRecords.put(
                signature,
                MethodAnnotationsRecord.create(
                    ImmutableSet.copyOf(methodAnns), ImmutableMap.copyOf(argAnnotation)));
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
      //      System.out.println("Wrote output to " + outputFile.getAbsolutePath());
    }
  }
}
