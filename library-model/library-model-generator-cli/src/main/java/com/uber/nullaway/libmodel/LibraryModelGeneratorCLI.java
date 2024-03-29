/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

package com.uber.nullaway.libmodel;

/**
 * A CLI tool for invoking the process for {@link LibraryModelGenerator} which generates astubx
 * file(s) from a directory containing annotated source code to be used as external library models.
 */
public class LibraryModelGeneratorCLI {
  /**
   * This is the main method of the cli tool. It parses the source files within a specified
   * directory, obtains meaningful Nullability annotation information and writes it into an astubx
   * file.
   *
   * @param args Command line arguments for the directory containing source files and the output
   *     directory.
   */
  public static void main(String[] args) {
    if (args.length != 2) {
      System.out.println(
          "Incorrect number of command line arguments. Required arguments:  <inputSourceDirectory> <outputDirectory>");
      return;
    }
    LibraryModelGenerator libraryModelGenerator = new LibraryModelGenerator();
    libraryModelGenerator.generateAstubxForLibraryModels(args[0], args[1]);
  }
}
