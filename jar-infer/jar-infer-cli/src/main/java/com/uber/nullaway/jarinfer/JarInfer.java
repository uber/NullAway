/*
 * Copyright (C) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.jarinfer;

import java.io.File;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;

/** CLI interface for running the jarinfer analysis. {@link DefinitelyDerefedParamsDriver} */
public class JarInfer {
  private static final String appName = JarInfer.class.getName();

  /**
   * This is the main method of the cli tool. It parses the arguments, invokes the analysis driver
   * and checks that the output file is written.
   *
   * @param args Command line arguments.
   */
  public static void main(String[] args) throws Exception {
    Options options = new Options();
    TextHelpAppendable helpAppendable = new TextHelpAppendable(System.out);
    helpAppendable.setMaxWidth(100);
    HelpFormatter hf =
        HelpFormatter.builder().setHelpAppendable(helpAppendable).setShowSince(false).get();
    options.addOption(
        Option.builder("i")
            .argName("in_path")
            .longOpt("input-file")
            .hasArg()
            .required()
            .desc("path to target jar/aar file")
            .get());
    options.addOption(
        Option.builder("p")
            .argName("pkg_name")
            .longOpt("package")
            .hasArg()
            .desc("qualified package name")
            .get());
    options.addOption(
        Option.builder("o")
            .argName("out_path")
            .longOpt("output-file")
            .hasArg()
            .required()
            .desc("path to processed jar/aar file")
            .get());
    options.addOption(
        Option.builder("b")
            .argName("annotate_bytecode")
            .longOpt("annotate_bytecode")
            .desc("annotate bytecode")
            .get());
    options.addOption(
        Option.builder("s")
            .argName("strip-jar-signatures")
            .longOpt("strip-jar-signatures")
            .desc("handle signed jars by removing signature information from META-INF/")
            .get());
    options.addOption(
        Option.builder("h").argName("help").longOpt("help").desc("print usage information").get());
    options.addOption(
        Option.builder("d")
            .argName("debug")
            .longOpt("debug")
            .desc("print debug information")
            .get());
    options.addOption(
        Option.builder("v").argName("verbose").longOpt("verbose").desc("set verbosity").get());
    try {
      CommandLine line = new DefaultParser().parse(options, args);
      if (line.hasOption('h')) {
        hf.printHelp(appName, null, options, null, true);
        return;
      }
      String jarPath = line.getOptionValue('i');
      String pkgName = line.getOptionValue('p', "");
      String outPath = line.getOptionValue('o');
      boolean annotateBytecode = line.hasOption('b');
      boolean stripJarSignatures = line.hasOption('s');
      boolean debug = line.hasOption('d');
      boolean verbose = line.hasOption('v');
      if (!pkgName.isEmpty()) {
        pkgName = "L" + pkgName.replaceAll("\\.", "/");
      }
      DefinitelyDerefedParamsDriver driver = new DefinitelyDerefedParamsDriver();
      driver.run(
          jarPath, pkgName, outPath, annotateBytecode, stripJarSignatures, false, debug, verbose);
      if (!new File(outPath).exists()) {
        System.out.println("Could not write jar file: " + outPath);
      }
    } catch (ParseException pe) {
      hf.printHelp(appName, null, options, null, true);
    }
  }
}
