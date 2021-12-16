/*
 * Copyright (c) 2021 Uber Technologies, Inc.
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

package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.autofix.Location;
import com.uber.nullaway.autofix.out.Fix;

public class FixHandler extends BaseNoOpHandler {

  private final AutoFixConfig config;

  public FixHandler(AutoFixConfig config) {
    this.config = config;
  }

  @Override
  public void fix(VisitorState state, Symbol target, ErrorMessage errorMessage) {
    Trees trees = Trees.instance(JavacProcessingEnvironment.instance(state.context));
    if (config.canFixElement(trees, target)) {
      Location location = new Location(target);
      if (!config.suggestEnabled) return;
      // todo: remove this condition later, for now we are not supporting anonymous classes
      if (location.isInAnonymousClass()) return;
      Fix fix = buildFix(errorMessage, location);
      if (fix != null) {
        if (config.suggestDeep) {
          fix.findEnclosing(state, errorMessage);
        }
        config.writer.saveFix(fix);
      }
    }
  }

  protected Fix buildFix(ErrorMessage errorMessage, Location location) {
    Fix fix;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
      case WRONG_OVERRIDE_PARAM:
      case PASS_NULLABLE:
      case FIELD_NO_INIT:
      case ASSIGN_FIELD_NULLABLE:
        fix = new Fix();
        fix.location = location;
        fix.annotation = config.annotationFactory.getNullable();
        fix.inject = true;
        break;
      default:
        return null;
    }
    fix.errorMessage = errorMessage;
    return fix;
  }
}
