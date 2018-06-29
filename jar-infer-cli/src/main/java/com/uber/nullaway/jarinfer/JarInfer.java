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
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/** CLI interface for running the jarinfer analysis. {@link DefinitelyDerefedParamsDriver} */
public class JarInfer {
  private static final String appName = JarInfer.class.getName();

  /**
   * This is the main method of the cli tool. It parses the arguments, invokes the analysis driver
   * and checks that the output file is written.
   *
   * @param args Command line arguments.
   */
  public static void main(String[] args) {
    Options options = new Options();
    HelpFormatter hf = new HelpFormatter();
    hf.setWidth(100);
    options.addOption(
        Option.builder("i")
            .argName("in_path")
            .longOpt("input-file")
            .hasArg()
            .required()
            .desc("path to target jar/aar file")
            .build());
    options.addOption(
        Option.builder("p")
            .argName("pkg_name")
            .longOpt("package")
            .hasArg()
            .desc("qualified package name")
            .build());
    options.addOption(
        Option.builder("o")
            .argName("out_path")
            .longOpt("output-file")
            .hasArg()
            .required()
            .desc("path to processed jar/aar file")
            .build());
    options.addOption(
        Option.builder("h")
            .argName("help")
            .longOpt("help")
            .desc("print usage information")
            .build());
    try {
      CommandLine line = (new DefaultParser()).parse(options, args);
      if (line.hasOption('h')) {
        hf.printHelp(appName, options, true);
        return;
      }
      String jarPath = line.getOptionValue('i');
      String pkgName = line.getOptionValue('p');
      String outPath = line.getOptionValue('o');
      DefinitelyDerefedParamsDriver defDerefParamDriver = new DefinitelyDerefedParamsDriver();
      defDerefParamDriver.run(jarPath, "L" + pkgName.replaceAll("\\.", "/"), outPath);
      if (!new File(outPath).exists()) {
        System.out.println("Could not write jar file: " + outPath);
      }
    } catch (ParseException pe) {
      hf.printHelp(appName, options, true);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
