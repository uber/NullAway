/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.Config;
import com.uber.nullaway.handlers.contract.ContractCheckHandler;
import com.uber.nullaway.handlers.contract.ContractHandler;
import com.uber.nullaway.handlers.contract.fieldcontract.EnsuresNonNullHandler;
import com.uber.nullaway.handlers.contract.fieldcontract.EnsuresNonNullIfHandler;
import com.uber.nullaway.handlers.contract.fieldcontract.RequiresNonNullHandler;
import com.uber.nullaway.handlers.temporary.FluentFutureHandler;

/** Utility static methods for the handlers package. */
public class Handlers {

  private Handlers() {}

  /**
   * Builds the default handler for the checker.
   *
   * @param config NullAway config
   * @return A {@code CompositeHandler} including the standard handlers for the nullness checker.
   */
  public static Handler buildDefault(Config config) {
    ImmutableList.Builder<Handler> handlerListBuilder = ImmutableList.builder();
    MethodNameUtil methodNameUtil = new MethodNameUtil();

    RestrictiveAnnotationHandler restrictiveAnnotationHandler = null;
    if (config.acknowledgeRestrictiveAnnotations()) {
      // This runs before LibraryModelsHandler, so that library models can override third-party
      // bytecode annotations
      restrictiveAnnotationHandler = new RestrictiveAnnotationHandler(config);
      handlerListBuilder.add(restrictiveAnnotationHandler);
    }
    if (config.handleTestAssertionLibraries()) {
      handlerListBuilder.add(new AssertionHandler(methodNameUtil));
    }
    handlerListBuilder.add(new GuavaAssertionsHandler());
    LibraryModelsHandler libraryModelsHandler = new LibraryModelsHandler(config);
    handlerListBuilder.add(libraryModelsHandler);
    handlerListBuilder.add(StreamNullabilityPropagatorFactory.getRxStreamNullabilityPropagator());
    handlerListBuilder.add(StreamNullabilityPropagatorFactory.getJavaStreamNullabilityPropagator());
    handlerListBuilder.add(
        StreamNullabilityPropagatorFactory.fromSpecs(
            libraryModelsHandler.getStreamNullabilitySpecs()));
    handlerListBuilder.add(new ContractHandler(config));
    handlerListBuilder.add(new ApacheThriftIsSetHandler());
    handlerListBuilder.add(new GrpcHandler());
    handlerListBuilder.add(new RequiresNonNullHandler());
    handlerListBuilder.add(new EnsuresNonNullHandler());
    handlerListBuilder.add(new EnsuresNonNullIfHandler());
    handlerListBuilder.add(new SynchronousCallbackHandler());
    if (config.serializationIsActive() && config.getSerializationConfig().fieldInitInfoEnabled) {
      handlerListBuilder.add(
          new FieldInitializationSerializationHandler(config.getSerializationConfig()));
    }
    if (config.checkOptionalEmptiness()) {
      handlerListBuilder.add(new OptionalEmptinessHandler(config, methodNameUtil));
    }
    if (config.checkContracts()) {
      handlerListBuilder.add(new ContractCheckHandler(config));
    }
    handlerListBuilder.add(new LombokHandler(config));
    handlerListBuilder.add(new FluentFutureHandler(config));
    CompositeHandler mainHandler = new CompositeHandler(handlerListBuilder.build());

    // Initialize the handlers that need to be aware of the main handler
    if (restrictiveAnnotationHandler != null) {
      restrictiveAnnotationHandler.initMainHandler(mainHandler);
    }
    libraryModelsHandler.initMainHandler(mainHandler);

    return mainHandler;
  }

  /**
   * Builds an empty handler chain (used for the NullAway dummy empty constructor).
   *
   * @return An empty {@code CompositeHandler}.
   */
  public static Handler buildEmpty() {
    return new CompositeHandler(ImmutableList.of());
  }
}
