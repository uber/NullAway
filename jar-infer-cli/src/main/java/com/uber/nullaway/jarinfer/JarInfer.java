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
import org.apache.commons.cli.*;

/*
 * CLI interface for running the jarinfer analysis {@link DefinitelyDerefedParamsDriver}
 *
 */
public class JarInfer {
  private static final String appName = JarInfer.class.getName();

  public static void main(String[] args) {
    Options options = new Options();
    HelpFormatter hf = new HelpFormatter();
    hf.setWidth(100);
    options.addOption(
        Option.builder("j")
            .argName("jar_path")
            .longOpt("jar")
            .hasArg()
            .required()
            .desc("path to target jar file")
            .build());
    options.addOption(
        Option.builder("p")
            .argName("pkg_name")
            .longOpt("pkg")
            .hasArg()
            .required()
            .desc("qualified package name")
            .build());
    options.addOption(
        Option.builder("o")
            .argName("out_path")
            .longOpt("out")
            .hasArg()
            .required()
            .desc("path to processed jar file")
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
      String jarPath = line.getOptionValue('j');
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
