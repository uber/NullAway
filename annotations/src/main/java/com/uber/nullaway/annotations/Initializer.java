/*
 * Copyright (c) 2023 Uber Technologies, Inc.
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

package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation used to mark a method as an initializer.
 *
 * <p>During initialization checking (see <a
 * href=https://github.com/uber/NullAway/wiki/Error-Messages#initializer-method-does-not-guarantee-nonnull-field-is-initialized--nonnull-field--not-initialized>NullAway
 * Wiki</a>), NullAway considers a method marked with any annotation with simple name
 * {@code @Initializer} to denote an initializer method. Initializer methods are assumed by NullAway
 * to always be called before any other method of the class that is not a constructor or called from
 * a constructor. This means a non-null field is considered to be properly initialized if it's set
 * by such an initializer method. By design, NullAway doesn't check for such initialization, since
 * an important use case of initializer methods is documenting methods used by annotation processors
 * or external frameworks as part of object set up (e.g. {@code android.app.Activity.onCreate} or
 * {@code javax.annotation.processing.Processor.init}). Note that there are other ways of defining
 * initializer methods from external libraries (i.e. library models), and that a method overriding
 * an initializer method is always considered an initializer method (again, for the sake of
 * framework events such as {@code onCreate}).
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface Initializer {}
