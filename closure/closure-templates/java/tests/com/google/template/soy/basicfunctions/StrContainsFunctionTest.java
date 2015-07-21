/*
 * Copyright 2012 Google Inc.
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
import com.google.template.soy.data.restricted.BooleanData;
import com.google.template.soy.data.restricted.StringData;
import com.google.template.soy.exprtree.Operator;
import com.google.template.soy.jssrc.restricted.JsExpr;

import junit.framework.TestCase;


/**
 * Unit tests for {@link StrContainsFunction}.
 *
 * @author Felix Arends
 */
public class StrContainsFunctionTest extends TestCase {


  public void testComputeForJava_containsString() {
    SoyValue arg0 = StringData.forValue("foobarfoo");
    SoyValue arg1 = StringData.forValue("bar");

    StrContainsFunction f = new StrContainsFunction();
    assertEquals(BooleanData.TRUE, f.computeForJava(ImmutableList.of(arg0, arg1)));
  }


  public void testComputeForJava_containsSanitizedContent() {
    SoyValue arg0 = ordainAsSafe("foobarfoo", ContentKind.TEXT);
    SoyValue arg1 = ordainAsSafe("bar", ContentKind.TEXT);

    StrContainsFunction f = new StrContainsFunction();
    assertEquals(BooleanData.TRUE, f.computeForJava(ImmutableList.of(arg0, arg1)));
  }


  public void testComputeForJava_doesNotContainString() {
    SoyValue arg0 = StringData.forValue("foobarfoo");
    SoyValue arg1 = StringData.forValue("baz");

    StrContainsFunction f = new StrContainsFunction();
    assertEquals(BooleanData.FALSE, f.computeForJava(ImmutableList.of(arg0, arg1)));
  }


  public void testComputeForJava_doesNotContainSanitizedContent() {
    SoyValue arg0 = ordainAsSafe("foobarfoo", ContentKind.TEXT);
    SoyValue arg1 = ordainAsSafe("baz", ContentKind.TEXT);

    StrContainsFunction f = new StrContainsFunction();
    assertEquals(BooleanData.FALSE, f.computeForJava(ImmutableList.of(arg0, arg1)));
  }


  public void testComputeForJsSrc_lowPrecedenceArg() {
    StrContainsFunction f = new StrContainsFunction();
    JsExpr arg0 = new JsExpr("'foo' + 'bar'", Operator.PLUS.getPrecedence());
    JsExpr arg1 = new JsExpr("'ba' + 'r'", Operator.PLUS.getPrecedence());
    assertEquals(
        new JsExpr("('' + ('foo' + 'bar')).indexOf('' + ('ba' + 'r')) != -1",
            Operator.NOT_EQUAL.getPrecedence()),
        f.computeForJsSrc(ImmutableList.of(arg0, arg1)));
  }


  public void testComputeForJsSrc_maxPrecedenceArgs() {

    StrContainsFunction f = new StrContainsFunction();
    JsExpr arg0 = new JsExpr("'foobar'", Integer.MAX_VALUE);
    JsExpr arg1 = new JsExpr("'bar'", Integer.MAX_VALUE);
    assertEquals(
        new JsExpr("('foobar').indexOf('bar') != -1",
            Operator.NOT_EQUAL.getPrecedence()),
        f.computeForJsSrc(ImmutableList.of(arg0, arg1)));
  }

}
