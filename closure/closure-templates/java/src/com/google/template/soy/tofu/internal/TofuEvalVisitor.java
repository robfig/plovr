/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.tofu.internal;

import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.SoyValueHelper;
import com.google.template.soy.shared.restricted.SoyJavaFunction;
import com.google.template.soy.sharedpasses.render.EvalVisitor;

import java.util.Deque;
import java.util.Map;

import javax.annotation.Nullable;


/**
 * Version of {@code EvalVisitor} for the Tofu backend.
 *
 * <p>For deprecated function implementations, uses {@code SoyTofuFunction}s instead of
 * {@code SoyJavaRuntimeFunction}s. (For new functions that implement {@code SoyJavaFunction}, there
 * is no difference.)
 *
 * @author Kai Huang
 */
// TODO: Attempt to remove this class.
class TofuEvalVisitor extends EvalVisitor {


  /**
   * @param valueHelper Instance of SoyValueHelper to use.
   * @param soyJavaFunctionsMap Map of all SoyJavaFunctions (name to function).
   * @param data The current template data.
   * @param ijData The current injected data.
   * @param env The current environment.
   */
  protected TofuEvalVisitor(
      SoyValueHelper valueHelper, @Nullable Map<String, SoyJavaFunction> soyJavaFunctionsMap,
      SoyRecord data, @Nullable SoyRecord ijData, Deque<Map<String, SoyValue>> env) {

    super(valueHelper, soyJavaFunctionsMap, data, ijData, env);
  }

}
