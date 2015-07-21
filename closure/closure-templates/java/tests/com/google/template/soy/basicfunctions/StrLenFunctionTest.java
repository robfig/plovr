/*
 * Copyright 2013 Google Inc.
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

package com.google.template.soy.basicfunctions;

import static com.google.template.soy.data.UnsafeSanitizedContentOrdainer.ordainAsSafe;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.data.SoyValue;
import com.google.template.soy.data.restricted.IntegerData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.restricted.JsExpr;

import junit.framework.TestCase;


/**
 * Unit tests for {@link StrLenFunction}.
 *
 * @author Christian Czekay
 */
public class StrLenFunctionTest extends TestCase {


  public void testComputeForJava_containsString() {
    SoyValue arg0 = StringData.forValue("foobarfoo");

    StrLenFunction f = new StrLenFunction();
    assertEquals(IntegerData.forValue(9), f.computeForJava(ImmutableList.of(arg0)));
  }


  public void testComputeForJava_containsSanitizedContent() {
    SoyValue arg0 = ordainAsSafe("foobarfoo", ContentKind.TEXT);

    StrLenFunction f = new StrLenFunction();
    assertEquals(IntegerData.forValue(9), f.computeForJava(ImmutableList.of(arg0)));
  }


  public void testComputeForJsSrc() {
    StrLenFunction f = new StrLenFunction();
    JsExpr arg0 = new JsExpr("'foo' + 'bar'", Operator.PLUS.getPrecedence());
    assertEquals(
        new JsExpr("('' + ('foo' + 'bar')).length", Integer.MAX_VALUE),
        f.computeForJsSrc(ImmutableList.of(arg0)));
  }

}
