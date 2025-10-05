package com.uber.nullaway.jdkannotations;

public class AstubxGeneratorCLI {
  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Invalid number of arguments. Required: <inputPath> <outputPath>");
      System.exit(2);
    }
    AstubxGenerator.generateAstubx(args[0], args[1]);
  }
}
