/*
 * Copyright 2013 The Closure Compiler Authors.
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

package com.google.javascript.jscomp;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.newtypes.JSType;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;

import java.util.Collection;
import java.util.List;

/**
 * Unit tests for {@link NewTypeInference}.
 *
 * @author blickly@google.com (Ben Lickly)
 * @author dimvar@google.com (Dimitris Vardoulakis)
 */
public class NewTypeInferenceTest extends CompilerTypeTestCase {

  private NewTypeInference parseAndTypeCheck(String externs, String js) {
    setUp();
    compiler.init(
        Lists.newArrayList(SourceFile.fromCode("[externs]", externs)),
        Lists.newArrayList(SourceFile.fromCode("[testcode]", js)),
        compiler.getOptions());

    Node externsRoot =
        compiler.getInput(new InputId("[externs]")).getAstRoot(compiler);
    Node astRoot =
        compiler.getInput(new InputId("[testcode]")).getAstRoot(compiler);

    assertEquals("parsing error: " +
        Joiner.on(", ").join(compiler.getErrors()),
        0, compiler.getErrorCount());
    assertEquals("parsing warning: " +
        Joiner.on(", ").join(compiler.getWarnings()),
        0, compiler.getWarningCount());

    GlobalTypeInfo symbolTable = new GlobalTypeInfo(compiler);
    symbolTable.process(externsRoot, astRoot);
    compiler.setSymbolTable(symbolTable);
    NewTypeInference typeInf = new NewTypeInference(compiler);
    typeInf.process(externsRoot, astRoot);
    return typeInf;
  }

  private void checkNoWarnings(String js) {
    NewTypeInference typeInf = parseAndTypeCheck("", js);
    Collection<JSError> warnings = typeInf.getWarnings();
    assertEquals("Expected no warning, but found: " + warnings + "\n",
        0, warnings.size());
  }

  private NewTypeInference typeCheck(String js, DiagnosticType warningKind) {
    return typeCheck("", js, warningKind);
  }

  private NewTypeInference typeCheck(
      String externs, String js, DiagnosticType warningKind) {
    Preconditions.checkNotNull(warningKind);
    return typeCheck(externs, js, ImmutableList.of(warningKind));
  }

  private NewTypeInference typeCheck(
      String js, List<DiagnosticType> warningKinds) {
    return typeCheck("", js, warningKinds);
  }

  private NewTypeInference typeCheck(
      String externs, String js, List<DiagnosticType> warningKinds) {
    Preconditions.checkNotNull(warningKinds);
    NewTypeInference typeInf = parseAndTypeCheck(externs, js);
    Collection<JSError> warnings = typeInf.getWarnings();
    String errorMessage =
        "Expected warning of type:\n" +
        "================================================================\n" +
        warningKinds +
        "================================================================\n" +
        "but found:\n" +
        "----------------------------------------------------------------\n" +
        warnings + "\n" +
        "----------------------------------------------------------------\n";
    assertEquals(errorMessage + "For warnings",
        warningKinds.size(), warnings.size());
    for (JSError warning : warnings) {
      assertTrue("Wrong warning type\n" + errorMessage,
          warningKinds.contains(warning.getType()));
    }
    return typeInf;
  }

  // Only for tests where there is a single top-level function in the program
  private void inferFirstFormalType(String js, JSType expected) {
    NewTypeInference typeInf = parseAndTypeCheck("", js);
    Collection<JSError> warnings = typeInf.getWarnings();
    if (warnings.size() > 0) {
      fail("Expected no warnings, but found: " + warnings);
    }
    assertEquals(expected, typeInf.getFormalType(0));
  }

  // Only for tests where there is a single top-level function in the program
  private void inferReturnType(String js, JSType expected) {
    NewTypeInference typeInf = parseAndTypeCheck("", js);
    Collection<JSError> warnings = typeInf.getWarnings();
    if (warnings.size() > 0) {
      fail("Expected no warnings, but found: " + warnings);
    }
    assertEquals(expected, typeInf.getReturnType());
  }

  private void checkInference(String js, String varName, JSType expected) {
    NewTypeInference typeInf = parseAndTypeCheck("", js);
    Collection<JSError> warnings = typeInf.getWarnings();
    if (warnings.size() > 0) {
      fail("Expected no warnings, but found: " + warnings);
    }
    assertEquals(expected, typeInf.getFinalType(varName));
  }

  private void checkInferenceWithWarning(
      String js, DiagnosticType warningKind, String varName, JSType expected) {
    NewTypeInference typeInf = typeCheck(js, warningKind);
    assertEquals(expected, typeInf.getFinalType(varName));
  }

  private void checkDeclaredType(String js, String varName, JSType expected) {
    NewTypeInference typeInf = parseAndTypeCheck("", js);
    assertEquals(expected, typeInf.getDeclaredType(varName));
  }

  public void testUnusedVariable() {
    checkInference("var x;", "x", JSType.UNDEFINED);
    checkInference("var x = void 0;", "x", JSType.UNDEFINED);
    checkInference("var x = 5;", "x", JSType.NUMBER);
  }

  public void testExterns() {
    typeCheck(
        "/** @constructor */ function Array(){}",
        "/** @param {Array} x */ function f(x) {}; f(5);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testThisInFunctionJsdoc() {
    typeCheck(
        "/** @constructor */ function Foo(){};\n" +
        "/** @type {number} */ Foo.prototype.n;",
        "/** @type {function(this:Foo)} */ function f() { this.n = 'str' };",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @type {function(this:gibberish)} */ function foo() {}",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);
  }

  public void testNewInFunctionJsdoc() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function h(/** function(new:Foo,...[number]):number */ f) {\n" +
        "  (new f()) - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testInvalidThisReference() {
    typeCheck("this.x = 5;", CheckGlobalThis.GLOBAL_THIS);
    typeCheck("function f(x){}; f(this);", CheckGlobalThis.GLOBAL_THIS);
  }

  public void testSuperClassWithUndeclaredProps() {
    checkNoWarnings(
        "/** @constructor */ function Error() {};\n" +
        "Error.prototype.sourceURL;\n" +
        "/** @constructor @extends {Error} */ function SyntaxError() {}");
  }

  public void testInheritMethodFromParent() {
    typeCheck(
        "/** @constructor */ function Foo() {};\n" +
        "/** @param {string} x */ Foo.prototype.method = function(x) {};\n" +
        "/** @constructor @extends {Foo} */ function Bar() {};\n" +
        "(new Bar).method(4)",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testSubClassWithUndeclaredProps() {
    checkNoWarnings(
        "/** @constructor */ function Super() {};\n" +
        "/** @type {string} */ Super.prototype.str;\n" +
        "/** @constructor @extends {Super} */ function Sub() {};\n" +
        "Sub.prototype.str;");
  }

  public void testUseBeforeDeclaration() {
    typeCheck("x; var x;", VariableReferenceCheck.UNDECLARED_REFERENCE);

    typeCheck("x = 7; var x;", VariableReferenceCheck.UNDECLARED_REFERENCE);

    checkInferenceWithWarning(
        "var /** undefined */ y = x; var x;",
        VariableReferenceCheck.UNDECLARED_REFERENCE,
        "x", JSType.UNDEFINED);

    checkNoWarnings(
        "function f() { return 9; }\n" +
        "var x = f();\n" +
        "x - 7;");
  }

  public void testUseWithoutDeclaration() {
    typeCheck("x;", VarCheck.UNDEFINED_VAR_ERROR);
    typeCheck("x = 7;", VarCheck.UNDEFINED_VAR_ERROR);
    typeCheck("var y = x;", VarCheck.UNDEFINED_VAR_ERROR);
  }

  public void testVarRedeclaration() {
    typeCheck("function f(x) { var x; }",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("var f; function f() {}",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("function f() {}; var f;",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("function f() {}; function f() {};",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("var f = function g() {}; function f() {};",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("var f = function g() {}; var f = function h() {};",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    checkNoWarnings("var g = function f() {}; var h = function f() {};");
  }

  public void testDeclaredVariables() {
    checkInference("/** @type {string} */ var x;", "x", JSType.UNDEFINED);

    checkInference("/** @type {string} */ var x = 'str';", "x", JSType.STRING);

    checkInference("/** @type {string} */ var x; x = 'str';",
        "x", JSType.STRING);

    typeCheck("var /** null */ obj = 5;", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck("var /** ?number */ n = true;", NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testEmptyBlockPropagation() {
    checkInference("var x = 5; { }", "x", JSType.NUMBER);
  }

  public void testSimplePropagation() {
    checkInference("var x; x = 1;", "x", JSType.NUMBER);
    checkInference("var x; x = 1; x++;", "x", JSType.NUMBER);
    checkInference("var x; x = 'string';", "x", JSType.STRING);
    checkInference("var x; x = 'string'; 23;", "x", JSType.STRING);
    checkInference("var x; x = null;", "x", JSType.NULL);
    checkInference("var x; x = true;", "x", JSType.TRUE_TYPE);
    checkInference("var x; x = false;", "x", JSType.FALSE_TYPE);
    checkInference("var x; x = undefined;", "x", JSType.UNDEFINED);
  }

  public void testConditionalBranch() {
    checkInference("var x; if (true) { x = 1; } else { x = 'str'; }",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference("var x = 5; if (true) { x = 'str'; }",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference("var x = 5; if (true) { } else { }", "x", JSType.NUMBER);

    checkInference("var x = 5; if (true) { } else { x = 'str'; }",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference(
        "var x, y;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else if (true) {\n" +
        "  x = null;\n" +
        "}",
        "x", JSType.join(
            JSType.NUMBER, JSType.join(JSType.NULL, JSType.UNDEFINED)));
  }

  public void testNameInferred() {
    checkInference("var x = 5; var y = x", "y", JSType.NUMBER);
  }

  public void testForLoopInference() {
    checkInference(
        "var x = 5;\n" +
        "for (;true;) {\n" +
        "  x = 'str';\n" +
        "}",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference(
        "var x = 5;" +
        "while (true) {" +
        "  x = 'str';" +
        "}",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference(
        "while (true) {" +
        "  var x = 'str';" +
        "}",
        "x", JSType.join(JSType.STRING, JSType.UNDEFINED));

    checkInference(
        "for (var x = 5; x < 10; x++) {\n" +
        "}",
        "x", JSType.NUMBER);
  }

  public void testConditionalSpecialization() {
    checkInference(
        "var x, y = 5;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else {\n" +
        "  x = 'str';\n" +
        "}\n" +
        "if (x === 5) {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    checkInference(
        "var x, y = 5;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x !== null) {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    checkInference(
        "var x, y;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x === null) {\n" +
        "  y = 5;" +
        "} else {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    checkNoWarnings(
        "var numOrNull = true ? null : 1\n" +
        "if (null === numOrNull) { var /** null */ n = numOrNull; }");
  }

  public void testUnspecializedStrictComparisons() {
    typeCheck(
        "var /** number */ n = (1 === 2);",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testAndOrConditionalSpecialization() {
    checkInference(
        "var x, y = 5;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else if (true) {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x !== null && x !== undefined) {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    checkInference(
        "var x, y;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else if (true) {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x === null || x === void 0) {\n" +
        "  y = 5;\n" +
        "} else {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    checkInference(
        "var x, y = 5;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else if (true) {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x === null || x === undefined) {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.join(
            JSType.NUMBER, JSType.join(JSType.NULL, JSType.UNDEFINED)));

    checkInference(
        "var x, y;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else if (true) {\n" +
        "  x = null;\n" +
        "}\n" +
        "if (x !== null && x !== undefined) {\n" +
        "  y = 5;\n" +
        "} else {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.join(
            JSType.NUMBER, JSType.join(JSType.NULL, JSType.UNDEFINED)));

    checkInference(
        "var x, y = 5;\n" +
        "if (true) {\n" +
        "  x = 5;\n" +
        "} else {\n" +
        "  x = 'str';\n" +
        "}\n" +
        "if (x === 7 || x === 8) {\n" +
        "  y = x;\n" +
        "}",
        "y", JSType.NUMBER);

    typeCheck(
        "/** @constructor */ function C(){}\n" +
        "var obj = new C;\n" +
        "if (obj || false) { 123, obj.asdf; }",
        TypeCheck.INEXISTENT_PROPERTY);

    checkNoWarnings(
        "function f(/** (number|string) */ x) {\n" +
        "  (typeof x === 'number') && (x - 5);\n" +
        "}");

    checkNoWarnings(
        "function f(/** (number|string|null) */ x) {\n" +
        "  (x && (typeof x === 'number')) && (x - 5);\n" +
        "}");

    typeCheck(
        "function f(/** (number|string|null) */ x) {\n" +
        "  (x && (typeof x === 'string')) && (x - 5);\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(/** (number|string|null) */ x) {\n" +
        "  typeof x === 'string' && x;\n" +
        "  x < 'asdf';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testLoopConditionSpecialization() {
    checkInference(
        "var x = true ? null : 'str';\n" +
        "while (x !== null) {\n" +
        "}",
        "x", JSType.NULL);

    checkInference(
        "var x = true ? null : 'str';\n" +
        "for (;x !== null;) {\n" +
        "}",
        "x", JSType.NULL);

    checkInference(
        "for (var x = true ? null : 'str'; x === null;) {\n" +
        "}",
        "x", JSType.STRING);

    checkInference(
        "var x;\n" +
        "for (x = true ? null : 'str'; x === null;) {\n" +
        "}",
        "x", JSType.STRING);

    checkInference(
        "var x = true ? null : 'str';\n" +
        "do {} while (x === null);",
        "x", JSType.STRING);
  }

  public void testHook() {
    checkInference("var x = true ? 5 : 'str'",
        "x", JSType.join(JSType.NUMBER, JSType.STRING));

    checkInference(
        "var x = true ? 5 : 'str';\n" +
        "var y = (x === 5) ? x : 6;",
        "y", JSType.NUMBER);

  }

  public void testVarDecls() {
    checkDeclaredType("/** @type {number} */ var x;", "x", JSType.NUMBER);

    checkDeclaredType(
        "var /** number */ x, /** string */ y;", "x", JSType.NUMBER);

    checkDeclaredType(
        "var /** number */ x, /** string */ y;", "y", JSType.STRING);

    checkInference(
        "var x, y; " +
        "if (true) {" +
        "  y = 5;" +
        "}",
        "y", JSType.join(JSType.NUMBER, JSType.UNDEFINED));

    typeCheck("/** @type {number} */ var x, y;", TypeCheck.MULTIPLE_VAR_DEF);

    typeCheck("/** @type {number} */ var /** number */ x;",
        GlobalTypeInfo.DUPLICATE_JSDOC);

    typeCheck("var /** number */ x = 5, /** string */ y = 6;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck("var /** number */ x = 'str', /** string */ y = 'str2';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testBadInitialization() {
    typeCheck("/** @type {string} */ var s = 123;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testBadAssignment() {
    typeCheck("/** @type {string} */ var s; s = 123;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testBadArithmetic() {
    typeCheck("123 - 'str';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("123 * 'str';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("123 / 'str';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("123 % 'str';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("var y = 123; var x = 'str'; var z = x - y;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("var y = 123; var x; var z = x - y;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("+true;", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("true + 5;", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("5 + true;", NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testSpecialization() {
    checkInference("var x; x === 5;", "x", JSType.UNDEFINED);

    checkInference(
        "var x = 5, y;" +
        "if (true) {\n" +
        "  y = 5;\n" +
        "} else {\n" +
        "  y = 'str';\n" +
        "}\n" +
        "x < y;",
        "y", JSType.NUMBER);
  }

  public void testTypeAfterIF() {
    // We don't warn here; see comment in analyzeExprFwd, Token.NAME.
    checkNoWarnings("var x = true ? 1 : 'str'; x - 1;");
  }

  public void testSimpleBwdPropagation() {
    inferFirstFormalType("function f(x) { x - 5; }", JSType.NUMBER);

    inferFirstFormalType("function f(x) { x++; }", JSType.NUMBER);

    inferFirstFormalType("function f(y) { var x = y; x - 5; }", JSType.NUMBER);

    inferFirstFormalType("function f(y) { var x; x = y; x - 5; }",
        JSType.NUMBER);

    inferFirstFormalType("function f(x) { x + 5; }",
        JSType.join(JSType.NUMBER, JSType.STRING));
  }

  public void testSimpleReturn() {
    inferReturnType("function f(x) {}", JSType.UNDEFINED);

    inferReturnType("function f(x) { return; }", JSType.UNDEFINED);

    inferReturnType("function f(x) { return 123; }", JSType.NUMBER);

    inferReturnType(
        "function f(x) { if (x) {return 123;} else {return 'asdf';} }",
        JSType.join(JSType.NUMBER, JSType.STRING));

    inferReturnType(
        "function f(x) { if (x) {return 123;} }",
        JSType.join(JSType.NUMBER, JSType.UNDEFINED));

    inferReturnType(
        "function f(x) { var y = x; y - 5; return x; }", JSType.NUMBER);
  }

  public void testComparisons() {
    checkNoWarnings(
        "1 < 0; 'a' < 'b'; true < false; null < null; undefined < undefined;");

    checkNoWarnings(
        "/** @param {{ p1: ?, p2: ? }} x */ function f(x) { x.p1 < x.p2; }");

    checkNoWarnings("function f(x, y) { x < y; }");

    checkNoWarnings("var x = 1; var y = true ? 1 : 'str'; x < y;");

    typeCheck("var x = 'str'; var y = 1; x < y;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    inferReturnType(
        "function f(x) {\n" +
        "  var y = 1;\n" +
        "  x < y;\n" +
        "  return x;\n" +
        "}",
        JSType.NUMBER);

    checkNoWarnings(
        "function f(x) {\n" +
        "  var y = x, z = 7;\n" +
        "  y < z;\n" +
        "}");
  }

  public void testFunctionJsdoc() {
    inferReturnType(
        "/** @return {number} */\n" +
        "function f() { return 1; }",
        JSType.NUMBER);

    inferReturnType(
        "/** @param {number} n */\n" +
        "function f(n) { return n; }",
        JSType.NUMBER);

    checkNoWarnings(
        "/** @param {number} n */\n" +
        "function f(n) { n < 5; }");

    typeCheck(
        "/** @param {string} n */\n" +
        "function f(n) { n < 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @return {string} */\n" +
        "function f() { return 1; }",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    typeCheck(
        "/** @return {string} */\n" +
        "function f() { return; }",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    inferFirstFormalType(
        "/** @return {string} */\n" +
        "function f(s) { return s; }",
        JSType.STRING);

    typeCheck(
        "/** @return {number} */\n" +
        "function f() {}",
        CheckMissingReturn.MISSING_RETURN_STATEMENT);

    typeCheck(
        "/** @return {(undefined|number)} */\n" +
        "function f() { if (true) { return 'str'; } }",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    typeCheck(
        "/** @param {function(number)} fun */\n" +
        "function f(fun) {}\n" +
        "f(function (/** string */ s) {});",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {number} n */ function f(/** number */ n) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    checkNoWarnings("/** @constructor */ var Foo = function() {}; new Foo;");

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @param {number} x */ Foo.prototype.method = function(x) {};\n" +
        "(new Foo).method('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "Foo.prototype.method = /** @param {number} x */ function(x) {};\n" +
        "(new Foo).method('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "Foo.prototype.method = function(/** number */ x) {};\n" +
        "(new Foo).method('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("/** @type {function(number)} */ function f(x) {}; f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("/** @type {number} */ function f() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @type {function():number} */\n" +
        "function /** number */ f() { return 1; }",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    checkNoWarnings(
        "function f(/** function(number) */ fnum, floose, cond) {\n" +
        "  var y;\n" +
        "  if (cond) {\n" +
        "    y = fnum;\n" +
        "  } else {\n" +
        "    floose();\n" +
        "    y = floose;\n" +
        "  }\n" +
        "  return y;\n" +
        "}");

    typeCheck(
        "/** @param {function(): *} x */ function g(x) {}\n" +
        "/** @param {function(number): string} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "var x = {}; x.a = function(/** string */ x) {}; x.a(123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings("/** @param {function(...)} x */ function f(x) {}");

    checkNoWarnings(
        "/**\n" +
        " * @interface\n" +
        " */\n" +
        "function A() {};\n" +
        "/** @return {number} */\n" +
        "A.prototype.foo = function() {};");

    typeCheck(
        "/** @param {number} x */ function f(y) {}",
        GlobalTypeInfo.INEXISTENT_PARAM);
  }

  public void testRecordWithoutTypesJsdoc() {
    typeCheck(
        "function f(/** {a, b} */ x) {}\n" +
        "f({c: 123});",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testBackwardForwardPathologicalCase() {
    inferFirstFormalType("function f(x) { var y = 5; y < x; }", JSType.NUMBER);
  }

  public void testTopInitialization() {
    checkNoWarnings("function f(x) { var y = x; y < 5; }");

    checkNoWarnings("function f(x) { x < 5; }");

    typeCheck(
        "function f(x) { x - 5; x < 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  var y = x; y - 5; y < 'str';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testMultipleFunctions() {
    typeCheck("function g() {};\n function f(x) { var x; };",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck("function f(x) { var x; };\n function g() {};",
        VariableReferenceCheck.REDECLARED_VARIABLE);
  }

  public void testSimpleCalls() {
    typeCheck("function f() {}; f(5);", TypeCheck.WRONG_ARGUMENT_COUNT);

    typeCheck("function f(x) { x-5; }; f();",
        TypeCheck.WRONG_ARGUMENT_COUNT);

    typeCheck(
        "/** @return {number} */ function f() { return 1; }\n" +
        "var /** string */ s = f();",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck("function f(/** number */ x) {}; f(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** boolean */ x) {}\n" +
        "function g() { f(123); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** void */ x) {}\n" +
        "function g() { f(123); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** boolean */ x) {}\n" +
        "function g(x) {\n" +
        "  var /** string */ s = x;\n" +
        "  f(x < 7);\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(/** number */ x) {}\n" +
        "function g(x, y) {\n" +
        "  y < x;\n" +
        "  f(x);\n" +
        "  var /** string */ s = y;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testObjectType() {
    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "function takesObj(/** Object */ x) {}\n" +
        "takesObj(new Foo);");

    checkNoWarnings(
        "function takesObj(/** Object */ x) {}\n" +
        "takesObj(null);");

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function /** Object */ returnsObj() { return {}; }\n" +
        "function takesFoo(/** Foo */ x) {}\n" +
        "takesFoo(returnsObj());",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testCallsWithComplexOperator() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "function fun(cond, /** Foo */ f, /** Bar */ g) {\n" +
        "  (cond ? f : g)();\n" +
        "}",
        TypeCheck.NOT_CALLABLE);
  }

  public void testDeferredChecks() {
    typeCheck(
        "function f() { return 'str'; }\n" +
        "function g() { f() - 5; }",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "function f(x) { x - 5; }\n" +
        "f(5 < 6);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x, y) { x - y; }\n" +
        "f(5);",
        TypeCheck.WRONG_ARGUMENT_COUNT);

    typeCheck(
        "function f() { return 'str'; }\n" +
        "function g() { var x = f(); x - 7; }",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "function f(/** number */ x, y) { return x-y; }\n" +
        "f(5, 'str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @return {number} */ function f(x) { return x; }\n" +
        "f('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** number */ x) { return x; }\n" +
        "function g(x) {\n" +
        "  var /** string */ s = f(x);\n" +
        "};",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "function f() { new Foo('asdf'); }\n" +
        "/** @constructor */ function Foo(x) { x - 5; }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/** @constructor */\n" +
        "function Arr() {}\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {...T} var_args\n" +
        " */\n" +
        "Arr.prototype.push = function(var_args) {};\n" +
        "function f(x) {}\n" +
        "var renameByParts = function(parts) {\n" +
        "  var mapped = new Arr();\n" +
        "  mapped.push(f(parts));\n" +
        "};");

    // Here we don't want a deferred check and an INVALID_INFERRED_RETURN_TYPE
    // warning b/c the return type is declared.
    typeCheck(
        "/** @return {string} */ function foo(){ return 'str'; }\n" +
        "function g() { foo() - 123; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f() {" +
        " function x() {};\n" +
        " function g() { x(1); }" +
        " g();" +
        "}",
        TypeCheck.WRONG_ARGUMENT_COUNT);
  }

  public void testFunctionsInsideFunctions() {
    typeCheck(
        "(function() {\n" +
        "  function f() {}; f(5);\n" +
        "})();", TypeCheck.WRONG_ARGUMENT_COUNT);

    typeCheck(
        "(function() {\n" +
        "  function f() { return 'str'; }\n" +
        "  function g() { f() - 5; }\n" +
        "})();",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "var /** number */ x;\n" +
        "function f() { x = 'str'; }",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "var x;\n" +
        "function f() { x - 5; x < 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testCrossScopeWarnings() {
    typeCheck(
        "function f() {\n" +
        "  x < 'str';\n" +
        "}" +
        "var x = 5;\n" +
        "f()",
        NewTypeInference.CROSS_SCOPE_GOTCHA);

    checkNoWarnings(
        "function g(x) {\n" +
        "  function f() { x < 'str'; z < 'str'; x = 5; }\n" +
        "  var z = x;\n" +
        "  f();\n" +
        "  x - 5;\n" +
        "  z < 'str';\n" +
        "}");

    // TODO(dimvar): we can't do this yet; requires more info in the summary
    // checkNoWarnings(
    //     "/** @constructor */\n" +
    //     "function Foo() {\n" +
    //     "  /** @type{?Object} */ this.prop = null;\n" +
    //     "}\n" +
    //     "Foo.prototype.initProp = function() { this.prop = {}; };\n" +
    //     "var obj = new Foo();\n" +
    //     "if (obj.prop == null) {\n" +
    //     "  obj.initProp();\n" +
    //     "  obj.prop.a = 123;\n" +
    //     "}");
  }

  public void testTrickyUnknownBehavior() {
    checkNoWarnings(
        "function f(/** function() */ x, cond) {\n" +
        "  var y = cond ? x() : 5;\n" +
        "  y < 'str';\n" +
        "}");

    checkNoWarnings(
        "/** @param {function() : ?} x */ function f(x, cond) {\n" +
        "  var y = cond ? x() : 5;\n" +
        "  y < 'str';\n" +
        "}");

    checkNoWarnings(
        "function f(/** function() */ x) {\n" +
        "  x() < 'str';\n" +
        "}");

    typeCheck(
        "function g() { return {}; }\n" +
        "function f() {\n" +
        "  var /** ? */ x = g();\n" +
        "  return x.y;\n" +
        "}", NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    checkNoWarnings(
        "function g() { return {}; }\n" +
        "function f() {\n" +
        "  var /** ? */ x = g()\n" +
        "  x.y = 5;\n" +
        "}");

    checkNoWarnings(
        "function g(x) { return x; }\n" +
        "function f(z) {\n" +
        "  var /** ? */ x = g(z);\n" +
        "  x.y2 = 123;\n" + // specializing to a loose object here
        "  return x.y1 - 5;\n" +
        "}");
  }

  public void testDeclaredFunctionTypesInFormals() {
    typeCheck(
        "function f(/** function():number */ x) {\n" +
        "  var /** string */ s = x();\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(/** function(number) */ x) {\n" +
        "  x(true);\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function g(x, y, /** function(number) */ f) {\n" +
        "  y < x;\n" +
        "  f(x);\n" +
        "  var /** string */ s = y;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  var y = x(); y - 5; y < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @param {function():?} x */ function f(x) {\n" +
        "  var y = x(); y - 5; y < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(/** ? */ x) { x < 'asdf'; x - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @param {function(number): string} x */ function g(x) {}\n" +
        "/** @param {function(number): string} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}");

    checkNoWarnings(
        "/** @param {function(number): *} x */ function g(x) {}\n" +
        "/** @param {function(*): string} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}");

    typeCheck(
        "/** @param {function(*): string} x */ function g(x) {}\n" +
        "/** @param {function(number): string} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {function(number): string} x */ function g(x) {}\n" +
        "/** @param {function(number): *} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testSpecializedFunctions() {
    typeCheck(
        "function f(/** function(string) : number */ x) {\n" +
        "  if (x('str') === 5) {\n" +
        "    x(5);\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** function(string) : string */ x) {\n" +
        "  if (x('str') === 5) {\n" +
        "    x(5);\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** function(string) */ x, y) {\n" +
        "  y(1);\n" +
        "  if (x === y) {\n" +
        "    x(5);\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (x === null) {\n" +
        "    return 5;\n" +
        "  } else {\n" +
        "    return x - 43;\n" +
        "  }\n" +
        "}\n" +
        "f('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testLooseConstructors() {
    checkNoWarnings(
        "function f(ctor) {\n" +
        "  new ctor(1);\n" +
        "}");

    typeCheck(
        "function f(ctor) {\n" +
        "  new ctor(1);\n" +
        "}\n" +
        "/** @constructor */ function Foo(/** string */ y) {}\n" +
        "f(Foo);", NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testLooseFunctions() {
    checkNoWarnings(
        "function f(x) {\n" +
        "  x(1);\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  x(1);\n" +
        "}\n" +
        "function g(/** string */ y) {}\n" +
        "f(g);", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(x) {\n" +
        "  x(1);\n" +
        "}\n" +
        "function g(/** number */ y) {}\n" +
        "f(g);");

    checkNoWarnings(
        "function f(x) {\n" +
        "  x(1);\n" +
        "}\n" +
        "function g(/** (number|string) */ y) {}\n" +
        "f(g);");

    typeCheck(
        "function f(x) {\n" +
        "  5 - x(1);\n" +
        "}\n" +
        "/** @return {string} */\n" +
        "function g(/** number */ y) { return ''; }\n" +
        "f(g);", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(x) {\n" +
        "  5 - x(1);\n" +
        "}\n" +
        "/** @return {(number|string)} */\n" +
        "function g(/** number */ y) { return 5; }\n" +
        "f(g);");

    checkNoWarnings(
        "function f(x, y) {\n" +
        "  x(5);\n" +
        "  y(5);\n" +
        "  return x(y);\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  x();\n" +
        "  return x;\n" +
        "}\n" +
        "function g() {}\n" +
        "function h() { f(g) - 5; }",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "function f(x, cond) {\n" +
        "  x();\n" +
        "  return cond ? 5 : x;\n" +
        "}\n" +
        "function g() {}\n" +
        "function h() { f(g, true) - 5; }",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);
    // A loose function is a loose subtype of a non-loose function.
    // Traditional function subtyping would warn here.
    checkNoWarnings(
        "function f(x) {\n" +
        "  x(5);\n" +
        "  return x;\n" +
        "}\n" +
        "function g(x) {}\n" +
        "function h() {\n" +
        "  var /** function((number|string)) */ fun = f(g);\n" +
        "}");

    typeCheck(
        "function g(/** string */ x) {}\n" +
        "function f(x, y) {\n" +
        "  y - 5;\n" +
        "  x(y);\n" +
        "  y + y;\n" +
        "}" +
        "f(g, 5)", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @return {string} */\n" +
        "function g(/** number */ x) { return 'str'; }\n" +
        "/** @return {number} */\n" +
        "function f(x) {\n" +
        "  var y = 5;\n" +
        "  var z = x(y);\n" +
        "  return z;\n" +
        "}" +
        "f(g)", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/** @return {number} */\n" +
        "function g(/** number */ y) { return 6; }\n" +
        "function f(x, cond) {\n" +
        "  if (cond) {\n" +
        "    5 - x(1);\n" +
        "  } else {\n" +
        "    x('str') < 'str';\n" +
        "  }\n" +
        "}\n" +
        "f(g, true)\n");

    checkNoWarnings(
        "function f(g, cond) {\n" +
        "  if (cond) {\n" +
        "    g(5, cond);\n" +
        "  }\n" +
        "}");
  }

  public void testBackwardForwardPathologicalCase2() {
    typeCheck(
        "function f(/** number */ x, /** string */ y, z) {\n" +
        "  var w = z;\n" +
        "  x < z;\n" +
        "  w < y;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testNotCallable() {
    typeCheck(
        "/** @param {number} x */ function f(x) {\n" +
        "  x(7);\n" +
        "}", TypeCheck.NOT_CALLABLE);
  }

  public void testSimpleLocallyDefinedFunction() {
    typeCheck(
        "function f() { return 'str'; }\n" +
        "var x = f();\n" +
        "x - 7;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f() { return 'str'; }\n" +
        "f() - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "(function() {\n" +
        "  function f() { return 'str'; }\n" +
        "  f() - 5;\n" +
        "})();",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "(function() {\n" +
        "  function f() { return 'str'; }\n" +
        "  f() - 5;\n" +
        "})();",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testIdentityFunction() {
    checkNoWarnings(
        "function f(x) { return x; }\n" +
        "5 - f(1);");
  }

  public void testReturnTypeInferred() {
    typeCheck(
        "function f() {\n" +
        "  var x = g();\n" +
        "  var /** string */ s = x;\n" +
        "  x - 5;\n" +
        "};\n" +
        "function g() { return 'str'};",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testGetpropOnNonObjects() {
    typeCheck("(1).foo;", NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    typeCheck(
        "var /** number */ n;\n" +
        "n.foo;", NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    typeCheck(
        "var x = {}; x.foo.bar = 1;", TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "var /** number */ n;\n" +
        "n.foo = 5;", NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    checkNoWarnings(
        "/** @param {*} x */\n" +
        "function f(x) {\n" +
        "  if (x.prop) {\n" +
        "    var /** { prop: ? } */ y = x;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "/** @param {number} x */\n" +
        "function f(x) {\n" +
        "  if (x.prop) {}\n" +
        "}",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    typeCheck("null[123];", NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    checkNoWarnings(
        "function f(/** Object */ x) { if (x[123]) { return 1; } }");

    typeCheck(
        "function f(/** number */ x) { if (x[123]) { return 1; } }",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);
  }

  public void testNonexistentProperty() {
    typeCheck(
        "/** @param {{ a: number }} obj */\n" +
        "function f(obj) {\n" +
        "  123, obj.b;\n" +
        "  obj.b = 'str';\n" +
        "}",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck("({}).p < 'asdf';", TypeCheck.INEXISTENT_PROPERTY);

    checkNoWarnings("(/** @type {?} */ (null)).prop - 123;");

    checkNoWarnings("(/** @type {?} */ (null)).prop += 123;");
  }

  public void testDontInferBottom() {
    typeCheck(
        // Ensure we don't infer bottom for x here
        "function f(x) { var /** string */ s; (s = x) - 5; } f(9);",
        ImmutableList.of(
            NewTypeInference.INVALID_OPERAND_TYPE,
            NewTypeInference.MISTYPED_ASSIGN_RHS));
  }

  public void testAssignToInvalidObject() {
    typeCheck(
        "n.foo = 5; var n;",
        ImmutableList.of(
            VariableReferenceCheck.UNDECLARED_REFERENCE,
            NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT));
  }

  public void testAssignmentDoesntFlowWrongInit() {
    typeCheck(
        "var /** number */ n;\n" +
        "n = 'typo';\n" +
        "n - 5;", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @param {{ n: number }} x */ function f(x) {\n" +
        "  x.n = 'typo';\n" +
        "  x.n - 5;\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testPossiblyNonexistentProperties() {
    checkNoWarnings(
        "/** @param {{ n: number }} x */ function f(x) {\n" +
        "  if (x.p) {\n" +
        "    return x.p;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "/** @param {{ p : string }} x */ function reqHasPropP(x){}\n" +
        "/** @param {{ n: number }} x */ function f(x, cond) {\n" +
        "  if (cond) {\n" +
        "    x.p = 'str';\n" +
        "  }\n" +
        "  reqHasPropP(x);\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {{ n: number }} x */ function f(x, cond) {\n" +
        "  if (cond) { x.p = 'str'; }\n" +
        "  if (x.p) {\n" +
        "    x.p - 5;\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(/** { n : number } */ x) {\n" +
        "  x.s = 'str';\n" +
        "  return x.inexistentProp;\n" +
        "}",
        TypeCheck.INEXISTENT_PROPERTY);
  }

  public void testDeclaredRecordTypes() {
    checkNoWarnings(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  return x.p - 3;\n" +
        "}");

    typeCheck(
        "/** @param {{ p: string }} x */ function f(x) {\n" +
        "  return x.p - 3;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @param {{ 'p': string }} x */ function f(x) {\n" +
        "  return x.p - 3;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  return x.q;\n" +
        "}",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "/** @param {{ p: string }} obj */ function f(obj, x, y) {\n" +
        "  x < y;\n" +
        "  x - 5;\n" +
        "  obj.p < y;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.p = 3;\n" +
        "}");

    typeCheck(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.p = 'str';\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.q = 'str';\n" +
        "}");

    checkNoWarnings(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.q = 'str';\n" +
        "}\n" +
        "/** @param {{ p: number }} x */ function g(x) {\n" +
        "  f(x);\n" +
        "}");

    typeCheck(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.q = 'str';\n" +
        "  return x.q;\n" +
        "}\n" +
        "/** @param {{ p: number }} x */ function g(x) {\n" +
        "  f(x) - 5;\n" +
        "}", NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    checkNoWarnings(
        "/** @param {{ p: number }} x */ function f(x) {\n" +
        "  x.q = 'str';\n" +
        "  x.q = 7;\n" +
        "}");

    typeCheck(
        "function f(/** { prop: number} */ obj) {\n" +
        "  obj.prop = 'asdf';\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(/** { prop: number} */ obj, cond) {\n" +
        "  if (cond) { obj.prop = 123; } else { obj.prop = 234; }\n" +
        "  obj.prop = 'asdf';\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "function f(/** {p: number} */ x, /** {p: (number|null)} */ y) {\n" +
        "  var z;\n" +
        "  if (true) { z = x; } else { z = y; }\n" +
        "}");

    typeCheck(
        "var /** { a: number } */ obj1 = { a: 321};\n" +
        "var /** { a: number, b: number } */ obj2 = obj1;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testSimpleObjectLiterals() {
    checkNoWarnings(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj) {\n" +
        "  obj = { p: 123 };\n" +
        "}");

    typeCheck(
        "/** @param {{ p: number, p2: string }} obj */\n" +
        "function f(obj) {\n" +
        "  obj = { p: 123 };\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj) {\n" +
        "  obj = { p: 'str' };\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "var obj;\n" +
        "obj = { p: 123 };\n" +
        "obj.p < 'str';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj, x) {\n" +
        "  obj = { p: x };\n" +
        "  x < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj, x) {\n" +
        "  obj = { p: 123, q: x };\n" +
        "  obj.q - 5;\n" +
        "  x < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);
    // An example of how record types can hide away the extra properties and
    // allow type misuse.
    checkNoWarnings(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj) {\n" +
        "  obj.q = 123;\n" +
        "}\n" +
        "/** @param {{ p: number, q: string }} obj */\n" +
        "function g(obj) { f(obj); }");

    checkNoWarnings(
        "/** @param {{ p: number }} obj */\n" +
        "function f(obj) {}\n" +
        "var obj = {p: 5};\n" +
        "if (true) {\n" +
        "  obj.q = 123;\n" +
        "}\n" +
        "f(obj);");

    typeCheck(
        "function f(/** number */ n) {}; f({});",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testInferPreciseTypeWithDeclaredUnknown() {
    typeCheck("var /** ? */ x = 'str'; x - 123;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testSimpleLooseObjects() {
    checkNoWarnings("function f(obj) { obj.x = 1; obj.x - 5; }");

    typeCheck(
        "function f(obj) { obj.x = 'str'; obj.x - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(obj) {\n" +
        "  var /** number */ x = obj.p;\n" +
        "  obj.p < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(obj) {\n" +
        "  var /** @type {{ p: number }} */ x = obj;\n" +
        "  obj.p < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(obj) {\n" +
        "  obj.x = 1;\n" +
        "  return obj.x;\n" +
        "}\n" +
        "f({x: 'str'});");

    typeCheck(
        "function f(obj) {\n" +
        "  obj.x - 1;\n" +
        "}\n" +
        "f({x: 'str'});", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(obj, cond) {\n" +
        "  if (cond) {\n" +
        "    obj.x = 'str';\n" +
        "  }\n" +
        "  obj.x - 5;\n" +
        "}");

    typeCheck(
        "function f(obj) {\n" +
        "  obj.x - 1;\n" +
        "  return obj;\n" +
        "}\n" +
        "var /** string */ s = (f({x: 5})).x;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }


  public void testNestedLooseObjects() {
    checkNoWarnings(
        "function f(obj) {\n" +
        "  obj.a.b = 123;\n" +
        "}");

    typeCheck(
        "function f(obj) {\n" +
        "  obj.a.b = 123;\n" +
        "  obj.a.b < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(obj, cond) {\n" +
        "  (cond ? obj : obj).x - 1;\n" +
        "  return obj.x;\n" +
        "}\n" +
        "f({x: 'str'}, true);", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(obj) {\n" +
        "  obj.a.b - 123;\n" +
        "}\n" +
        "f({a: {b: 'str'}})", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(obj) {\n" +
        "  obj.a.b = 123;\n" +
        "}\n" +
        "f({a: {b: 'str'}})");

    typeCheck(
        "function f(obj) {\n" +
        "  var o;\n" +
        "  (o = obj).x - 1;\n" +
        "  return o.x;\n" +
        "}\n" +
        "f({x: 'str'});", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(obj) {\n" +
        "  ({x: obj.foo}).x - 1;\n" +
        "}\n" +
        "f({foo: 'str'});", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  ({p: x++}).p = 'str';\n" +
        "}\n" +
        "f('str');", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  ({p: 'str'}).p = x++;\n" +
        "}\n" +
        "f('str');", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x, y, z) {\n" +
        "  ({p: (y = x++), q: 'str'}).p = z = y;\n" +
        "  z < 'str';\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testLooseObjectSubtyping() {
    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @constructor */\n" +
        "function Bar() {}\n" +
        "function f(obj) { obj.prop - 5; }\n" +
        "var /** !Foo */ x = new Foo;\n" +
        "f(x);\n" +
        "var /** !Bar */ y = x;",
        ImmutableList.of(
            NewTypeInference.INVALID_ARGUMENT_TYPE,
            NewTypeInference.MISTYPED_ASSIGN_RHS));

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function f(obj) { obj.prop - 5; }\n" +
        "f(new Foo);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {string} */ this.prop = 'str'; }\n" +
        "function f(obj) { obj.prop - 5; }\n" +
        "f(new Foo);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() { /** @type {number} */ this.prop = 1; }\n" +
        "function g(obj) { var /** string */ s = obj.prop; return obj; }\n" +
        "var /** !Foo */ x = g({ prop: '' });",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    // Infer obj.a as loose, don't warn at the call to f.
    checkNoWarnings(
        "function f(obj) { obj.a.num - 5; }\n" +
        "function g(obj) {\n" +
        "  obj.a.str < 'str';\n" +
        "  f(obj);\n" +
        "}");
  }

  public void testUnionOfRecords() {
    // The previous type inference doesn't warn because it keeps records
    // separate in unions.
    // We treat {x:number}|{y:number} as {x:number=, y:number=}
    typeCheck(
        "/** @param {({x:number}|{y:number})} obj */\n" +
        "function f(obj) {}\n" +
        "f({x: 5, y: 'asdf'});",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testUnionOfFunctionAndNumber() {
    checkNoWarnings("var x = function(/** number */ y){};");

    typeCheck("var x = function(/** number */ y){}; var x = 5",
        VariableReferenceCheck.REDECLARED_VARIABLE);

    typeCheck(
        "var x = function(/** number */ y){}; x('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "var x = true ? function(/** number */ y){} : 5; x('str');",
        ImmutableList.of(
            TypeCheck.NOT_CALLABLE, NewTypeInference.INVALID_ARGUMENT_TYPE));
  }


  public void testFoo() {
    typeCheck(
        "/** @constructor */ function Foo() {}; Foo();",
        TypeCheck.CONSTRUCTOR_NOT_CALLABLE);

    typeCheck(
        "function Foo() {}; new Foo();",
        TypeCheck.NOT_A_CONSTRUCTOR);

    checkNoWarnings(
        "/** @constructor */ function Foo() {};\n" +
        "function reqFoo(/** Foo */ f) {};\n" +
        "reqFoo(new Foo());");

    typeCheck(
        "/** @constructor */ function Foo() {};\n" +
        "/** @constructor */ function Bar() {};\n" +
        "function reqFoo(/** Foo */ f) {};\n" +
        "reqFoo(new Bar());",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {};\n" +
        "function reqFoo(/** Foo */ f) {};\n" +
        "function g() {\n" +
        "  /** @constructor */ function Foo() {};\n" +
        "  reqFoo(new Foo());\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @param {number} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "/** @param {!Foo} x */\n" +
        "function f(x) { x.method('asdf'); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testComma() {
    typeCheck(
        "var x; var /** string */ s = (x = 1, x);",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  var y = x;\n" +
        "  y < (123, 'asdf');\n" +
        "}\n" +
        "f(123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testTypeof() {
    typeCheck("(typeof 'asdf') < 123;", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  var y = x;\n" +
        "  y < (typeof 123);\n" +
        "}\n" +
        "f(123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'string') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x == 'string') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if ('string' === typeof x) {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'number') {\n" +
        "    x < 'asdf';\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'boolean') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'undefined') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'function') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @param {*} x */\n" +
        "function f(x) {\n" +
        "  if (typeof x === 'function') {\n" +
        "    x();\n" +
        "  }\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x === 'object') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(x) {\n" +
        "  if (!(typeof x == 'number')) {\n" +
        "    x < 'asdf';\n" +
        "  }\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  if (!(typeof x == 'number')) {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x !== 'number') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (typeof x == 'number') {} else { x - 5; }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(/** (number|string) */ x) {\n" +
        "  if (typeof x !== 'string') {\n" +
        "    x - 5;\n" +
        "  }\n" +
        "}");

    checkNoWarnings(
        "function f() {" +
        "  return (typeof 123 == 'number' ||" +
        "    typeof 123 == 'string' ||" +
        "    typeof 123 == 'boolean' ||" +
        "    typeof 123 == 'undefined' ||" +
        "    typeof 123 == 'function' ||" +
        "    typeof 123 == 'object' ||" +
        "    typeof 123 == 'unknown');" +
        "}");

    typeCheck(
        "function f(){ if (typeof 123 == 'numbr') return 321; }",
        TypeValidator.UNKNOWN_TYPEOF_VALUE);
  }

  public void testAssignWithOp() {
    typeCheck(
        "function f(x) {\n" +
        "  var y = x, z = 0;\n" +
        "  y < (z -= 123);\n" +
        "}\n" +
        "f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  var y = x, z = { prop: 0 };\n" +
        "  y < (z.prop -= 123);\n" +
        "}\n" +
        "f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  var z = { prop: 0 };\n" +
        "  x < z.prop;\n" +
        "  z.prop -= 123;\n" +
        "}\n" +
        "f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("var x = 0; x *= 'asdf';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "var /** string */ x = 'asdf'; x *= 123;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "var x; x *= 123;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testClassConstructor() {
    checkNoWarnings(
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};\n" +
        "(new Foo()).n - 5;");

    typeCheck(
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};\n" +
        "(new Foo()).n = 'str';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n;\n" +
        "};\n" +
        "(new Foo()).n = 'str';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f() { (new Foo()).n = 'str'; }\n" +
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f() { var x = new Foo(); x.n = 'str'; }\n" +
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "function f() { var x = new Foo(); return x.n - 5; }\n" +
        "/** @constructor */ function Foo() {\n" +
        "  this.n = 5;\n" +
        "};");

    typeCheck(
        "function f() { var x = new Foo(); x.s = 'str'; x.s < x.n; }\n" +
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {\n" +
        "  /** @type {number} */ this.n = 5;\n" +
        "};\n" +
        "function reqFoo(/** Foo */ x) {};\n" +
        "reqFoo({ n : 20 });",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f() { var x = new Foo(); x.n - 5; x.n < 'str'; }\n" +
        "/** @constructor */ function Foo() {\n" +
        "  this.n = 5;\n" +
        "};", NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testPropertyDeclarations() {
    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  /** @type {number} */ this.x = 'abc';\n" +
        "  /** @type {string} */ this.x = 'def';\n" +
        "}",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  /** @type {number} */ this.x = 5;\n" +
        "  /** @type {number} */ this.x = 7;\n" +
        "}",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  this.x = 5;\n" +
        "  /** @type {number} */ this.x = 7;\n" +
        "}\n" +
        "function g() { (new Foo()).x < 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  /** @type {number} */ this.x = 7;\n" +
        "  this.x = 5;\n" +
        "}\n" +
        "function g() { (new Foo()).x < 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  /** @type {number} */ this.x = 7;\n" +
        "  this.x < 'str';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {\n" +
        "  /** @type {?} */ this.x = 1;\n" +
        "  /** @type {?} */ this.x = 1;\n" +
        "}", GlobalTypeInfo.REDECLARED_PROPERTY);
  }

  public void testPrototypePropertyAssignments() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {string} */ Foo.prototype.x = 'str';\n" +
        "function g() { (new Foo()).x - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "Foo.prototype.x = 'str';\n" +
        "function g() { var f = new Foo(); f.x - 5; f.x < 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {function(string)} s */\n" +
        "Foo.prototype.bar = function(s) {};\n" +
        "function g() { (new Foo()).bar(5); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {};\n" +
        "Foo.prototype.bar = function(s) {\n" +
        "  /** @type {string} */ this.x = 'str';\n" +
        "};\n" +
        "(new Foo()).x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "(function() { Foo.prototype.prop = 123; })();",
        GlobalTypeInfo.CTOR_IN_DIFFERENT_SCOPE);

    typeCheck(
        "/** @constructor */ function F() {}" +
        "F.prototype.bar = function() {};" +
        "F.prototype.bar = function() {};",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    typeCheck(
        "/** @constructor */ function F() {}" +
        "/** @return {void} */ F.prototype.bar = function() {};" +
        "F.prototype.bar = function() {};",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    checkNoWarnings(
        "/** @constructor */ function C(){}\n" +
        "C.prototype.foo = {};\n" +
        "C.prototype.method = function() { this.foo.bar = 123; }");
    // TODO(dimvar): I think we can fix the next one with better deferred checks
    // for prototype methods. Look into it.
    // typeCheck(
    //     "/** @constructor */ function Foo() {};\n" +
    //     "Foo.prototype.bar = function(s) { s < 'asdf'; };\n" +
    //     "function g() { (new Foo()).bar(5); }",
    //     NewTypeInference.INVALID_ARGUMENT_TYPE);
    // TODO(blickly): Add fancier JSDoc annotation finding to jstypecreator
    // typeCheck(
    //     "/** @constructor */ function Foo() {};\n" +
    //     "/** @param {string} s */ Foo.prototype.bar = function(s) {};\n" +
    //     "function g() { (new Foo()).bar(5); }",
    //     NewTypeInference.INVALID_ARGUMENT_TYPE);
    // typeCheck(
    //     "/** @constructor */ function Foo() {};\n" +
    //     "Foo.prototype.bar = function(/** string */ s) {};\n" +
    //     "function g() { (new Foo()).bar(5); }",
    //     NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f() {}\n" +
        "function g() { f.prototype.prop = 123; }");
  }

  public void testAssignmentsToPrototype() {
    // TODO(dimvar): the 1st should pass, the 2nd we may stop catching
    // if we decide to not check these assignments at all.

    // checkNoWarnings(
    //     "/** @constructor */\n" +
    //     "function Foo() {}\n" +
    //     "/** @constructor @extends {Foo} */\n" +
    //     "function Bar() {}\n" +
    //     "Bar.prototype = new Foo;\n" +
    //     "Bar.prototype.method1 = function() {};");

    // typeCheck(
    //     "/**\n" +
    //     " * @constructor\n" +
    //     " * @struct\n" +
    //     " */\n" +
    //     "function Bar() {}\n" +
    //     "Bar.prototype = {};\n",
    //     TypeCheck.CONFLICTING_SHAPE_TYPE);
  }

  public void testConflictingPropertyDefinitions() {
    typeCheck(
        "/** @constructor */ function Foo() { this.x = 'str1'; };\n" +
        "/** @type {string} */ Foo.prototype.x = 'str2';\n" +
        "(new Foo).x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {string} */ Foo.prototype.x = 'str1';\n" +
        "Foo.prototype.x = 'str2';\n" +
        "(new Foo).x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "Foo.prototype.x = 'str2';\n" +
        "/** @type {string} */ Foo.prototype.x = 'str1';\n" +
        "(new Foo).x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {string} */ this.x = 'str1'; };\n" +
        "Foo.prototype.x = 'str2';\n" +
        "(new Foo).x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() { this.x = 5; };\n" +
        "/** @type {string} */ Foo.prototype.x = 'str';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {string} */ this.x = 'str1'; };\n" +
        "Foo.prototype.x = 5;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {string} */ this.x = 'str'; };\n" +
        "/** @type {number} */ Foo.prototype.x = 'str';",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.prototype.x = 1;\n" +
        "/** @type {number} */ Foo.prototype.x = 2;",
        GlobalTypeInfo.REDECLARED_PROPERTY);
  }

  public void testPrototypeAliasing() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "Foo.prototype.x = 'str';\n" +
        "var fp = Foo.prototype;\n" +
        "fp.x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testInstanceof() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function takesFoos(/** Foo */ afoo) {}\n" +
        "function f(/** (number|Foo) */ x) {\n" +
        "  takesFoos(x);\n" +
        "  if (x instanceof Foo) { takesFoos(x); }\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("({} instanceof function(){});",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "(123 instanceof Foo);",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function takesFoos(/** Foo */ afoo) {}\n" +
        "function f(/** boolean */ cond, /** (number|Foo) */ x) {\n" +
        "  if (x instanceof (cond || Foo)) { takesFoos(x); }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "function f(/** (number|!Foo) */ x) {\n" +
        "  if (x instanceof Foo) {} else { x - 5; }\n" +
        "}");

    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "function takesFoos(/** Foo */ afoo) {}\n" +
        "function f(/** Foo */ x) {\n" +
        "  if (x instanceof Bar) {} else { takesFoos(x); }\n" +
        "}");

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "function takesFoos(/** Foo */ afoo) {}\n" +
        "/** @param {*} x */ function f(x) {\n" +
        "  takesFoos(x);\n" +
        "  if (x instanceof Foo) { takesFoos(x); }\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "var x = new Foo();\n" +
        "x.bar = 'asdf';\n" +
        "if (x instanceof Foo) { x.bar - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) { if (x instanceof UndefinedClass) {} }",
        VarCheck.UNDEFINED_VAR_ERROR);

    typeCheck(
        "/** @constructor */ function Foo() { this.prop = 123; }\n" +
        "function f(x) { x = 123; if (x instanceof Foo) { x.prop; } }",
        ImmutableList.of(
            NewTypeInference.INVALID_OPERAND_TYPE,
            NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT));
  }

  public void testFunctionWithProps() {
    typeCheck(
        "function f() {}\n" +
        "f.x = 'asdf';\n" +
        "f.x - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testConstructorProperties() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.n = 1\n" +
        "/** @type {number} */ Foo.n = 1",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    checkNoWarnings(
        "function g() { Foo.bar - 5; }\n" +
        "/** @constructor */ function Foo() {}\n" +
        "Foo.bar = 42;");

    typeCheck(
        "function g() { Foo.bar - 5; }\n" +
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {string} */ Foo.bar = 'str';",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function g() { return (new Foo).bar; }\n" +
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {string} */ Foo.bar = 'str';",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {string} */ Foo.prop = 'asdf';\n" +
        "var x = Foo;\n" +
        "x.prop - 5;", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function g() { Foo.prototype.baz = (new Foo).bar + Foo.bar; }\n" +
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.prototype.bar = 5\n" +
        "/** @type {string} */ Foo.bar = 'str';",
        GlobalTypeInfo.CTOR_IN_DIFFERENT_SCOPE);

    // TODO(dimvar): warn about redeclared property
    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.n = 1;\n" +
        "Foo.n = 1;");

    // TODO(dimvar): warn about redeclared property
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.n;\n" +
        "Foo.n = '';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testTypeTighteningHeuristic() {
    typeCheck(
        "/** @param {*} x */ function f(x) { var /** ? */ y = x; x - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(/** ? */ x) {\n" +
        "  if (!(typeof x == 'number')) {\n" +
        "    x < 'asdf';\n" +
        "  }\n" +
        "}");

    checkNoWarnings(
        "function f(/** { prop: ? } */ x) {\n" +
        "  var /** (number|string) */ y = x.prop;\n" +
        "  x.prop < 5;\n" +
        "}");
  }

  public void testDeclaredPropertyIndirectly() {
    typeCheck(
        "function f(/** { n: number } */ obj) {\n" +
        "  var o2 = obj;\n" +
        "  o2.n = 'asdf';\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testNonRequiredArguments() {
    checkNoWarnings(
        "function f(f1, /** function(string=) */ f2, cond) {\n" +
        "  var y;\n" +
        "  if (cond) {\n" +
        "    f1();" +
        "    y = f1;\n" +
        "  } else {\n" +
        "    y = f2;\n" +
        "  }\n" +
        "  return y;\n" +
        "}");

    typeCheck(
        "/** @param {function(number=)} fnum */\n" +
        "function f(fnum) {\n" +
        "  fnum(); fnum('asdf');\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {function(... [number])} fnum */\n" +
        "function f(fnum) {\n" +
        "  fnum(); fnum(1, 2, 3, 'asdf');\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {function(number=, number)} g */\n" +
        "function f(g) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @param {number=} x */\n" +
        "function f(x) {}\n" +
        "f(); f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {number=} x */\n" +
        "function f(x) {}\n" +
        "f(1, 2);",
        TypeCheck.WRONG_ARGUMENT_COUNT);

    typeCheck("/** @type {function()} */ function f(x) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck("/** @type {function(number)} */ function f() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck("/** @type {function(number)} */ function f(/** number */ x) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/**\n" +
        " * @param {number=} x\n" +
        " * @param {number} y\n" +
        " */\n" +
        "function f(x, y) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @type {function(number=)} */ function f(x) {}\n" +
        "f(); f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("/** @type {function(number=, number)} */ function f(x, y) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "function /** number */ f() { return 'asdf'; }",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    typeCheck(
        "/** @return {number} */ function /** number */ f() { return 1; }",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @type {function(): number} */\n" +
        "function /** number */ f() { return 1; }",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @type {function(... [number])} */ function f() {}" +
        "f(); f(1, 2, 3); f(1, 2, 'asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {...number} var_args */ function f(var_args) {}\n" +
        "f(); f(1, 2, 3); f(1, 2, 'asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck("/** @type {function(... [number])} */ function f(x) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/**\n" +
        " * @param {...number} var_args\n" +
        " * @param {number=} x\n" +
        " */\n" +
        "function f(var_args, x) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @type {function(number=, ...[number])} */\n" +
        "function f(x) {}\n" +
        "f('asdf');", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** function(number=) */ fnum," +
        "  /** function(string=) */ fstr, cond) {\n" +
        "  var y;\n" +
        "  if (cond) {\n" +
        "    y = fnum;\n" +
        "  } else {\n" +
        "    y = fstr;\n" +
        "  }\n" +
        "  y();\n" +
        "  y(123);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** function(... [number]) */ fnum," +
        "  /** function(... [string]) */ fstr, cond) {\n" +
        "  var y;\n" +
        "  if (cond) {\n" +
        "    y = fnum;\n" +
        "  } else {\n" +
        "    y = fstr;\n" +
        "  }\n" +
        "  y();\n" +
        "  y(123);\n" +
        "}", NewTypeInference.CALL_FUNCTION_WITH_BOTTOM_FORMAL);

    typeCheck(
        "function f(\n" +
        "  /** function() */ f1, /** function(string=) */ f2, cond) {\n" +
        "  var y;\n" +
        "  if (cond) {\n" +
        "    y = f1;\n" +
        "  } else {\n" +
        "    y = f2;\n" +
        "  }\n" +
        "  y(123);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @param {function(string): *} x */ function g(x) {}\n" +
        "/** @param {function(... [number]): string} x */ function f(x) {\n" +
        "  g(x);\n" +
        "}", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @param {number=} x\n" +
        " * @param {number=} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "f(undefined, 123);\n" +
        "f('str')",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(/** function(...) */ fun) {}\n" +
        "f(function() {});");

    // The restarg formal doesn't have to be called var_args.
    // It shouldn't be used in the body of the function.
    typeCheck(
        "/** @param {...number} x */ function f(x) { x - 5; }",
        VarCheck.UNDEFINED_VAR_ERROR);

    typeCheck(
        "/** @param {number=} x */ function f(x) { x - 5; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @param {number=} x */ function f(x) { if (x) { x - 5; } }");
  }

  public void testInferredOptionalFormals() {
    checkNoWarnings("function f(x) {} f();");

    checkNoWarnings("function f(/** number */ x, y) { x-5; } f(123);");

    typeCheck(
        "function f(x) {\n" +
        "  if (x !== undefined) {\n" +
        "    return x-5;\n" +
        "  } else {\n" +
        "    return 0;\n" +
        "  }\n" +
        "}\n" +
        "f() - 1;\n" +
        "f('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @return {function(number=)} */\n" +
        "function f() {\n" +
        "  return function(x) {};\n" +
        "}\n" +
        "f()();\n" +
        "f()('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testSimpleClassInheritance() {
    checkNoWarnings(
        "/** @constructor */\n" +
        "function Parent() {}\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {}\n" +
        "Child.prototype = new Parent();");

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {\n" +
        "  /** @type {string} */ this.prop = 'asdf';\n" +
        "}\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {}\n" +
        "Child.prototype = new Parent();\n" +
        "(new Child()).prop - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {\n" +
        "  /** @type {string} */ this.prop = 'asdf';\n" +
        "}\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {}\n" +
        "Child.prototype = new Parent();\n" +
        "(new Child()).prop = 5;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {}\n" +
        "/** @type {string} */ Parent.prototype.prop = 'asdf';\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {}\n" +
        "Child.prototype = new Parent();\n" +
        "(new Child()).prop - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {}\n" +
        "/** @type {string} */ Parent.prototype.prop = 'asdf';\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {\n" +
        "  /** @type {number} */ this.prop = 5;\n" +
        "}\n" +
        "Child.prototype = new Parent();",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {}\n" +
        "/** @type {string} */ Parent.prototype.prop = 'asdf';\n" +
        "/** @constructor @extends{Parent} */\n" +
        "function Child() {}\n" +
        "Child.prototype = new Parent();\n" +
        "/** @type {number} */ Child.prototype.prop = 5;",
        ImmutableList.of(
            GlobalTypeInfo.INVALID_PROP_OVERRIDE,
            NewTypeInference.MISTYPED_ASSIGN_RHS));

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() {}\n" +
        "/** @extends {Parent} */ function Child() {}",
        GlobalTypeInfo.EXTENDS_NOT_ON_CTOR_OR_INTERF);

    typeCheck(
        "/** @constructor @extends{number} */ function Foo() {}",
        GlobalTypeInfo.EXTENDS_NON_OBJECT);

    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @implements {string}\n" +
        " */\n" +
        "function Foo() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/**\n" +
        " * @interface\n" +
        " * @extends {number}\n" +
        " */\n" +
        "function Foo() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);
  }

  public void testInheritanceSubtyping() {
    checkNoWarnings(
        "/** @constructor */ function Parent() {}\n" +
        "/** @constructor @extends{Parent} */ function Child() {}\n" +
        "(function(/** Parent */ x) {})(new Child);");

    typeCheck(
        "/** @constructor */ function Parent() {}\n" +
        "/** @constructor @extends{Parent} */ function Child() {}\n" +
        "(function(/** Child */ x) {})(new Parent);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Parent() {}\n" +
        "/** @constructor @extends{Parent} */ function Child() {}\n" +
        "/** @constructor */\n" +
        "function Foo() { /** @type {Parent} */ this.x = new Child(); }\n" +
        "/** @type {Child} */ Foo.prototype.y = new Parent();",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testRecordtypeSubtyping() {
    // TODO(dimvar): fix
    // checkNoWarnings(
    //     "/** @interface */ function I() {}\n" +
    //     "/** @type {number} */ I.prototype.prop;\n" +
    //     "function f(/** !I */ x) {" +
    //     "  var /** { prop: number} */ y = x;" +
    //     "}");
  }

  public void testWarnAboutOverridesNotVisibleDuringGlobalTypeInfo() {
    typeCheck(
        "/** @constructor @extends {Parent} */ function Child() {}\n" +
        "/** @type {string} */ Child.prototype.y = 'str';\n" +
        "/** @constructor */ function Grandparent() {}\n" +
        "/** @type {number} */ Grandparent.prototype.y = 9;\n" +
        "/** @constructor @extends {Grandparent} */ function Parent() {}",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);
  }

  public void testInvalidMethodPropertyOverride() {
    typeCheck(
        "/** @interface */ function Parent() {}\n" +
        "/** @type {number} */ Parent.prototype.y = 9;\n" +
        "/** @constructor @implements {Parent} */ function Child() {}\n" +
        "/** @param {string} x */ Child.prototype.y = function(x) {};",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/** @interface */ function Parent() {}\n" +
        "/** @param {string} x */ Parent.prototype.y = function(x) {};\n" +
        "/** @constructor @implements {Parent} */ function Child() {}\n" +
        "/** @type {number} */ Child.prototype.y = 9;",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/** @constructor */ function Parent() {}\n" +
        "/** @type {number} */ Parent.prototype.y = 9;\n" +
        "/** @constructor @extends {Parent} */ function Child() {}\n" +
        "/** @param {string} x */ Child.prototype.y = function(x) {};",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/** @constructor */ function Parent() {}\n" +
        "/** @param {string} x */ Parent.prototype.y = function(x) {};\n" +
        "/** @constructor @extends {Parent} */ function Child() {}\n" +
        "/** @type {number} */ Child.prototype.y = 9;",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    // TODO(dimvar): fix
    // typeCheck(
    //     "/** @constructor */\n" +
    //     "function Foo() {}\n" +
    //     "Foo.prototype.f = function(/** number */ x, /** number */ y) {};\n" +
    //     "/** @constructor @extends {Foo} */\n" +
    //     "function Bar() {}\n" +
    //     "/** @override */\n" +
    //     "Bar.prototype.f = function(x) {};",
    //     GlobalTypeInfo.INVALID_PROP_OVERRIDE);
  }

  public void testMultipleObjects() {
    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "/** @param {(Foo|Bar)} x */ function reqFooBar(x) {}\n" +
        "function f(cond) {\n" +
        "  reqFooBar(cond ? new Foo : new Bar);\n" +
        "}");

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "/** @param {Foo} x */ function reqFoo(x) {}\n" +
        "function f(cond) {\n" +
        "  reqFoo(cond ? new Foo : new Bar);\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "/** @param {(Foo|Bar)} x */ function g(x) {\n" +
        "  if (x instanceof Foo) {\n" +
        "    var /** Foo */ y = x;\n" +
        "  } else {\n" +
        "    var /** Bar */ z = x;\n" +
        "  }\n" +
        "  var /** Foo */ w = x;\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {string} */ this.s = 'str'; }\n" +
        "/** @param {(!Foo|{n:number, s:string})} x */ function g(x) {\n" +
        "  if (x instanceof Foo) {\n" +
        "  } else {\n" +
        "    x.s - 5;\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.prototype.n = 5;\n" +
        "/** @param {{n : number}} x */ function reqRecord(x) {}\n" +
        "function f() {\n" +
        "  reqRecord(new Foo);\n" +
        "}");

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @type {number} */ Foo.prototype.n = 5;\n" +
        "/** @param {{n : string}} x */ function reqRecord(x) {}\n" +
        "function f() {\n" +
        "  reqRecord(new Foo);\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @param {{n : number}|!Foo} x */\n" +
        "function f(x) {\n" +
        "  x.n - 5;\n" +
        "}",
        NewTypeInference.POSSIBLY_INEXISTENT_PROPERTY);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @param {{n : number}|!Foo} x */\n" +
        "function f(x) {\n" +
        "  x.abc - 5;\n" +
        "}",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "/** @param {!Bar|!Foo} x */\n" +
        "function f(x) {\n" +
        "  x.abc = 'str';\n" +
        "  if (x instanceof Foo) {\n" +
        "    x.abc - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testMultipleFunctionsInUnion() {
    checkNoWarnings(
        "/** @param {function():string | function():number} x\n" +
        "  * @return {string|number} */\n" +
        "function f(x) {\n" +
        "  return x();\n" +
        "}");

    typeCheck(
        "/** @param {function(string)|function(number)} x\n" +
        "  * @param {string|number} y */\n" +
        "function f(x, y) {\n" +
        "  x(y);\n" +
        "}",
        NewTypeInference.CALL_FUNCTION_WITH_BOTTOM_FORMAL);
    // typeCheck(
    //     // Right now we treat the parameter as undeclared. This could change.
    //     "/** @type {(function(string)|function(number))} */\n" +
    //     "function f(x) {\n" +
    //     "  x = 'str'; x = 7; x = null; x = true;\n" +
    //     "  x - 5;\n" +
    //     "}",
    //     NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testPrototypeOnNonCtorFunction() {
    checkNoWarnings("function Foo() {}; Foo.prototype.y = 5;");
  }

  public void testInvalidTypeReference() {
    typeCheck(
        "/** @type {gibberish} */ var x;",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "/** @param {gibberish} x */ function f(x){};",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "function f(/** gibberish */ x){};",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "/** @returns {gibberish} */\n" +
        "function f(x) { return x; };",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "/** @interface @extends {gibberish} */ function Foo(){};",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "/** @constructor @implements {gibberish} */ function Foo(){};",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);

    typeCheck(
        "/** @constructor @extends {gibberish} */ function Foo() {};",
        GlobalTypeInfo.UNRECOGNIZED_TYPE_NAME);
  }

  public void testCircularDependencies() {
    checkNoWarnings(
        "/** @constructor @extends {Bar}*/ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}");

    checkNoWarnings(
        "/** @param {Foo} x */ function f(x) {}\n" +
        "/** @constructor */ function Foo() {}");

    typeCheck(
        "f(new Bar)\n" +
        "/** @param {Foo} x */ function f(x) {}\n" +
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/** @constructor @param {Foo} x */ function Bar(x) {}\n" +
        "/** @constructor @param {Bar} x */ function Foo(x) {}\n" +
        "new Bar(new Foo(null));");

    typeCheck(
        "/** @constructor @param {Foo} x */ function Bar(x) {}\n" +
        "/** @constructor @param {Bar} x */ function Foo(x) {}\n" +
        "new Bar(new Foo(undefined));",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor @extends {Bar} */ function Foo() {}\n" +
        "/** @constructor @extends {Foo} */ function Bar() {}",
        GlobalTypeInfo.INHERITANCE_CYCLE);

    typeCheck(
        "/** @interface @extends {Bar} */ function Foo() {}\n" +
        "/** @interface @extends {Foo} */ function Bar() {}",
        GlobalTypeInfo.INHERITANCE_CYCLE);

    typeCheck(
        "/** @constructor @extends {Foo} */ function Foo() {}",
        GlobalTypeInfo.INHERITANCE_CYCLE);
  }

  public void testInterfacesWithBody() {
    typeCheck(
        "/** @interface */ function I() { var x = 123; }",
        GlobalTypeInfo.INTERFACE_WITH_A_BODY);
  }

  public void testInterfaceSingleInheritance() {
    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @type {string} */ I.prototype.prop;\n" +
        "/** @constructor @implements{I} */ function C() {}",
        TypeValidator.INTERFACE_METHOD_NOT_IMPLEMENTED);

    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x) {};\n" +
        "/** @constructor @implements{I} */ function C() {}",
        TypeValidator.INTERFACE_METHOD_NOT_IMPLEMENTED);

    typeCheck(
        "/** @interface */ function IParent() {}\n" +
        "/** @type {number} */ IParent.prototype.prop;\n" +
        "/** @interface @extends{IParent} */ function IChild() {}\n" +
        "/** @constructor @implements{IChild} */\n" +
        "function C() { this.prop = 5; }\n" +
        "(new C).prop < 'adsf';",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @interface */ function IParent() {}\n" +
        "/** @type {number} */ IParent.prototype.prop;\n" +
        "/** @interface @extends{IParent} */ function IChild() {}\n" +
        "/** @constructor @implements{IChild} */\n" +
        "function C() { this.prop = 'str'; }",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "/** @constructor */\n" +
        "function Parent() { /** @type {number} */ this.prop = 123; }\n" +
        "/** @constructor @extends {Parent} */ function Child() {}\n" +
        "(new Child).prop = 321;");

    typeCheck(
        "/** @constructor */\n" +
        "function Parent() { /** @type {number} */ this.prop = 123; }\n" +
        "/** @constructor @extends {Parent} */ function Child() {}\n" +
        "(new Child).prop = 'str';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x, y) {};\n" +
        "/** @constructor @implements{I} */ function C() {}\n" +
        "/** @param {string} y */\n" +
        "C.prototype.method = function(x, y) {};\n" +
        "(new C).method(5, 6);", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x, y) {};\n" +
        "/** @constructor @implements{I} */ function C() {}\n" +
        "/** @param {string} y */\n" +
        "C.prototype.method = function(x, y) {};\n" +
        "(new C).method('asdf', 'fgr');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x) {};\n" +
        "/** @constructor @implements{I} */ function C() {}\n" +
        "C.prototype.method = function(x) {};\n" +
        "(new C).method('asdf');", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */ function I1() {}\n" +
        "/** @param {number} x */ I1.prototype.method = function(x, y) {};\n" +
        "/** @interface */ function I2() {}\n" +
        "/** @param {string} y */ I2.prototype.method = function(x, y) {};\n" +
        "/** @constructor @implements{I1} @implements{I2} */ function C(){}\n" +
        "C.prototype.method = function(x, y) {};\n" +
        "(new C).method('asdf', 'fgr');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */ function I1() {}\n" +
        "/** @param {number} x */ I1.prototype.method = function(x, y) {};\n" +
        "/** @interface */ function I2() {}\n" +
        "/** @param {string} y */ I2.prototype.method = function(x, y) {};\n" +
        "/** @constructor @implements{I1} @implements{I2} */ function C(){}\n" +
        "C.prototype.method = function(x, y) {};\n" +
        "(new C).method(1, 2);", NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/** @interface */ function I1() {}\n" +
        "/** @param {number} x */ I1.prototype.method = function(x) {};\n" +
        "/** @interface */ function I2() {}\n" +
        "/** @param {string} x */ I2.prototype.method = function(x) {};\n" +
        "/** @constructor @implements{I1} @implements{I2} */ function C(){}\n" +
        // Type of C.method is @param {(string|number)}
        "C.prototype.method = function(x) {};");

    typeCheck(
        "/** @interface */ function I1() {}\n" +
        "/** @param {number} x */ I1.prototype.method = function(x) {};\n" +
        "/** @interface */ function I2() {}\n" +
        "/** @param {string} x */ I2.prototype.method = function(x) {};\n" +
        "/** @constructor @implements{I1} @implements{I2} */ function C(){}\n" +
        // Type of C.method is @param {(string|number)}
        "C.prototype.method = function(x) {};\n" +
        "(new C).method(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */ function I() {}\n" +
        "/** @param {number} x */ I.prototype.method = function(x) {};\n" +
        "/** @constructor */ function S() {}\n" +
        "/** @param {string} x */ S.prototype.method = function(x) {};\n" +
        "/** @constructor @implements{I} @extends{S} */ function C(){}\n" +
        // Type of C.method is @param {(string|number)}
        "C.prototype.method = function(x) {};\n" +
        "(new C).method(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testInterfaceArgument() {
    typeCheck(
        "/** @interface */\n" +
        "function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x) {};\n" +
        "/** @param {!I} x */\n" +
        "function foo(x) { x.method('asdf'); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface */\n" +
        "function IParent() {}\n" +
        "/** @param {number} x */\n" +
        "IParent.prototype.method = function(x) {};\n" +
        "/** @interface @extends {IParent} */\n" +
        "function IChild() {}\n" +
        "/** @param {!IChild} x */\n" +
        "function foo(x) { x.method('asdf'); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testExtendedInterfacePropertiesCompatibility1() {
    typeCheck(
        "/** @interface */function Int0() {};" +
        "/** @interface */function Int1() {};" +
        "/** @type {number} */" +
        "Int0.prototype.foo;" +
        "/** @type {string} */" +
        "Int1.prototype.foo;" +
        "/** @interface \n @extends {Int0} \n @extends {Int1} */" +
        "function Int2() {};",
        TypeCheck.INCOMPATIBLE_EXTENDED_PROPERTY_TYPE);
  }

  public void testTwoLevelExtendedInterface() {
    typeCheck(
        "/** @interface */function Int0() {};" +
        "/** @type {function()} */" +
        "Int0.prototype.foo;" +
        "/** @interface @extends {Int0} */function Int1() {};" +
        "/** @constructor \n @implements {Int1} */" +
        "function Ctor() {};",
        TypeValidator.INTERFACE_METHOD_NOT_IMPLEMENTED);
  }

  public void testConstructorExtensions() {
    typeCheck(
        "/** @constructor */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x) {};\n" +
        "/** @constructor @extends{I} */ function C() {}\n" +
        "C.prototype.method = function(x) {};\n" +
        "(new C).method('asdf');", NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function I() {}\n" +
        "/** @param {number} x */\n" +
        "I.prototype.method = function(x, y) {};\n" +
        "/** @constructor @extends{I} */ function C() {}\n" +
        "/** @param {string} y */\n" +
        "C.prototype.method = function(x, y) {};\n" +
        "(new C).method('asdf', 'fgr');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testInterfaceAndConstructorInvalidConstructions() {
    typeCheck(
        "/** @constructor @extends {Bar} */\n" +
        "function Foo() {}\n" +
        "/** @interface */\n" +
        "function Bar() {}",
        TypeCheck.CONFLICTING_EXTENDED_TYPE);

    typeCheck(
        "/** @constructor @implements {Bar} */\n" +
        "function Foo() {}\n" +
        "/** @constructor */\n" +
        "function Bar() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @interface @implements {Foo} */\n" +
        "function Bar() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @interface @extends {Foo} */\n" +
        "function Bar() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);
  }

  public void testNot() {
    typeCheck(
        "/** @constructor */ function Foo() {}\n" +
        "/** @constructor */\n" +
        "function Bar() { /** @type {string} */ this.prop = 'asdf'; }\n" +
        "function f(/** (!Foo|!Bar) */ obj) {\n" +
        "  if (!(obj instanceof Foo)) {\n" +
        "    obj.prop - 5;\n" +
        "  }\n" +
        "}", NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(cond) {\n" +
        "  var x = cond ? null : 123;\n" +
        "  if (!(x === null)) { x - 5; }\n" +
        "}");

    typeCheck(
        "/** @constructor */ function Foo(){ this.prop = 123; }\n" +
        "function f(/** Foo */ obj) {\n" +
        "  if (!obj) { obj.prop; }\n" +
        "}", NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);
  }

  public void testNullability() {
    checkNoWarnings(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @param {Foo} x */\n" +
        "function f(x) {}\n" +
        "f(new Foo);");

    typeCheck(
        "/** @constructor */ function Foo(){ this.prop = 123; }\n" +
        "function f(/** Foo */ obj) { obj.prop; }",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);

    typeCheck(
        "/** @interface */\n" +
        "function I() {}\n" +
        "I.prototype.method = function() {};\n" +
        "/** @param {I} x */\n" +
        "function foo(x) { x.method(); }",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);
  }

  public void testGetElem() {
    typeCheck(
        "/** @constructor */\n" +
        "function C(){ /** @type {number} */ this.prop = 1; }\n" +
        "(new C)['prop'] < 'asdf';",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x, y) {\n" +
        "  x < y;\n" +
        "  ({})[y - 5];\n" +
        "  x = 'asdf';\n" +
        "}\n" +
        "f('asdf', 123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x, y) {\n" +
        "  ({})[y - 5];\n" +
        "  x < y;\n" +
        "}\n" +
        "f('asdf', 123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  x['prop'] = 'str';\n" +
        "  return x['prop'] - 5;\n" +
        "}\n" +
        "f({});", NewTypeInference.INVALID_OPERAND_TYPE);

    // TODO(blickly): The fact that this has no warnings is somewhat unpleasant.
    checkNoWarnings(
        "function f(x) {\n" +
        "  x['prop'] = 7;\n" +
        "  var p = 'prop';\n" +
        "  x[p] = 'str';\n" +
        "  return x['prop'] - 5;\n" +
        "}\n" +
        "f({});");
  }

  public void testNamespaces() {
    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @constructor */ ns.C = function() {};\n" +
        "ns.C();",
        TypeCheck.CONSTRUCTOR_NOT_CALLABLE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @param {number} x */ ns.f = function(x) {};\n" +
        "ns.f('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @constructor */ ns.C = function(){}\n" +
        "ns.C.prototype.method = function(/** string */ x){};\n" +
        "(new ns.C).method(5);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @const */ ns.ns2 = {};\n" +
        "/** @constructor */ ns.ns2.C = function() {};\n" +
        "ns.ns2.C();",
        TypeCheck.CONSTRUCTOR_NOT_CALLABLE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @const */ ns.ns2 = {};\n" +
        "/** @constructor */ ns.ns2.C = function() {};\n" +
        "ns.ns2.C.prototype.method = function(/** string */ x){};\n" +
        "(new ns.ns2.C).method(11);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function C1(){}\n" +
        "/** @constructor */ C1.C2 = function(){}\n" +
        "C1.C2.prototype.method = function(/** string */ x){};\n" +
        "(new C1.C2).method(1);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @constructor */ function C1(){};\n" +
        "/** @constructor */ C1.prototype.C2 = function(){};\n" +
        "(new C1).C2();",
        TypeCheck.CONSTRUCTOR_NOT_CALLABLE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @type {number} */ ns.N = 5;\n" +
        "ns.N();",
        TypeCheck.NOT_CALLABLE);

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @type {number} */ ns.foo = 123;\n" +
        "/** @type {string} */ ns.foo = '';",
        ImmutableList.of(
            GlobalTypeInfo.REDECLARED_PROPERTY,
            NewTypeInference.MISTYPED_ASSIGN_RHS));

    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @type {number} */ ns.foo;\n" +
        "/** @type {string} */ ns.foo;",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    // We warn for duplicate declarations even if they are the same type.
    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @type {number} */ ns.foo;\n" +
        "/** @type {number} */ ns.foo;",
        GlobalTypeInfo.REDECLARED_PROPERTY);

    // Without the @const, we don't consider it a namespace and don't warn.
    checkNoWarnings(
        "var ns = {};\n" +
        "/** @type {number} */ ns.foo = 123;\n" +
        "/** @type {string} */ ns.foo = '';");

    // TODO(dimvar): warn about redeclared property
    typeCheck(
        "/** @const */ var ns = {};\n" +
        "ns.x = 5;\n" +
        "/** @type {string} */\n" +
        "ns.x = 'str';",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testThrow() {
    checkNoWarnings("throw 123;");

    checkNoWarnings("var msg = 'hello'; throw msg;");

    checkNoWarnings(
        "function f(cond, x, y) {\n" +
        "  if (cond) {\n" +
        "    x < y;\n" +
        "    throw 123;\n" +
        "  } else {\n" +
        "    x < 2;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "throw (1 - 'asdf');",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testQnameInJsdoc() {
    typeCheck(
        "/** @const */ var ns = {};\n" +
        "/** @constructor */ ns.C = function() {};\n" +
        "/** @param {!ns.C} x */ function f(x) {\n" +
        "  123, x.prop;\n" +
        "}", TypeCheck.INEXISTENT_PROPERTY);
  }

  public void testIncrementDecrements() {
    checkNoWarnings(
        "/** @const */ var ns = { x : 5 };\n" +
        "ns.x++; ++ns.x; ns.x--; --ns.x");

    typeCheck(
        "function f(ns) { --ns.x; }; f({x : 'str'})",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testAndOr() {
    checkNoWarnings(
        "function f(x, y, z) { return x || y && z;}");

    typeCheck(
        "function f(/** number */ x, /** string */ y) {\n" +
        "  var /** number */ n = x || y;\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(/** number */ x, /** string */ y) {\n" +
        "  var /** number */ n = y || x;\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testNonStringComparisons() {
    checkNoWarnings(
        "function f(x) {\n" +
        "  if (null == x) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}");

    checkNoWarnings(
        "function f(x) {\n" +
        "  if (x == null) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  if (null == x) {\n" +
        "    var /** null */ y = x;\n" +
        "    var /** undefined */ z = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (5 == x) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (x == 5) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (null == x) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (x == null) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "function f(x) {\n" +
        "  if (null != x) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}");

    checkNoWarnings(
        "function f(x) {\n" +
        "  if (x != null) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "function f(x) {\n" +
        "  if (5 != x) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (x != 5) {\n" +
        "  } else {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (null != x) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  if (x != null) {\n" +
        "    var /** (null|undefined) */ y = x;\n" +
        "  }\n" +
        "}", NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testAnalyzeLoopsBwd() {
    checkNoWarnings("for(;;);");

    typeCheck(
        "function f(x) {\n" +
        "  for (; x - 5 > 0; ) {}\n" +
        "  x = undefined;\n" +
        "}\n" +
        "f(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  while (x - 5 > 0) {}\n" +
        "  x = undefined;\n" +
        "}\n" +
        "f(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  if (x - 5 > 0) {}\n" +
        "  x = undefined;\n" +
        "}\n" +
        "f(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  do {} while (x - 5 > 0);\n" +
        "  x = undefined;\n" +
        "}\n" +
        "f(true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testDontLoosenNominalTypes() {
    checkNoWarnings(
        "/** @constructor */ function Foo() { this.prop = 123; }\n" +
        "function f(x) { if (x instanceof Foo) { var y = x.prop; } }");

    checkNoWarnings(
        "/** @constructor */ function Foo() { this.prop = 123; }\n" +
        "/** @constructor */ function Bar() { this.prop = 123; }\n" +
        "function f(cond, x) {\n" +
        "  x = cond ? new Foo : new Bar;\n" +
        "  var y = x.prop;\n" +
        "}");
  }

  public void testFunctionsWithAbnormalExit() {
    checkNoWarnings("function f(x) { x = 1; throw x; }");

    // TODO(dimvar): to fix these, we must collect all THROWs w/out an out-edge
    // and use the envs from them in the summary calculation. (Rare case.)

    // typeCheck(
    //     "function f(x) {\n" +
    //     "  var y = 1;\n" +
    //     "  x < y;\n" +
    //     "  throw 123;\n" +
    //     "}\n" +
    //     "f('asdf');",
    //     NewTypeInference.INVALID_ARGUMENT_TYPE);
    // typeCheck(
    //     "function f(x, cond) {\n" +
    //     "  if (cond) {\n" +
    //     "    var y = 1;\n" +
    //     "    x < y;\n" +
    //     "    throw 123;\n" +
    //     "  }\n" +
    //     "}\n" +
    //     "f('asdf', 'whatever');",
    //     NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testAssignAdd() {
    // Without a type annotation, we can't find the type error here.
    typeCheck(
        "function f(x, y) {\n" +
        "  x < y;\n" +
        "  var /** number */ z = 5;\n" +
        "  z += y;\n" +
        "}\n" +
        "f('asdf', 5);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function f(x, y) {\n" +
        "  x < y;\n" +
        "  var z = 5;\n" +
        "  z += y;\n" +
        "}\n" +
        "f('asdf', 5);");

    typeCheck(
        "var s = 'asdf'; (s += 'asdf') - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings("var s = 'asdf'; s += 5;");

    typeCheck(
        "var b = true; b += 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "var n = 123; n += 'asdf';", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "var s = 'asdf'; s += true;", NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testSwitch() {
    checkNoWarnings(
        "switch (1) { case 1: break; case 2: break; default: break; }");

    typeCheck(
        "switch (1) {\n" +
        "  case 1:\n" +
        "    1 - 'asdf';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "switch (1) {\n" +
        "  default:\n" +
        "    1 - 'asdf';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "switch (1 - 'asdf') {\n" +
        "  case 1:\n" +
        "    break;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "switch (1) {\n" +
        "  case (1 - 'asdf'):\n" +
        "    break;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "function f(/** Foo */ x) {\n" +
        "  switch (x) {\n" +
        "    case null:\n" +
        "      break;\n" +
        "    default:\n" +
        "      var /** !Foo */ y = x;\n" +
        "  }\n" +
        "}");

    checkNoWarnings(
        "function f(x) {\n" +
        "  switch (x) {\n" +
        "    case 123:\n" +
        "      x - 5;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "function f(/** Foo */ x) {\n" +
        "  switch (x) {\n" +
        "    case null:\n" +
        "    default:\n" +
        "      var /** !Foo */ y = x;\n" +
        "  }\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(x) {\n" +
        "  switch (x) {\n" +
        "    case null:\n" +
        "      x - 5;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x) {\n" +
        "  switch (x) {\n" +
        "    case null:\n" +
        "      var /** undefined */ y = x;\n" +
        "  }\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    // Tests for fall-through
    typeCheck(
        "function f(x) {\n" +
        "  switch (x) {\n" +
        "    case 1: x - 5;\n" +
        "    case 'asdf': x < 123; x < 'asdf'; break;\n" +
        "  }\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function f(x) {\n" +
        "  switch (x) {\n" +
        "    case 1: x - 5;\n" +
        "    case 'asdf': break;\n" +
        "  }\n" +
        "}");

    typeCheck(
        "function g(/** number */ x) { return 5; }\n" +
        "function f() {\n" +
        "  switch (3) { case g('asdf'): return 123; }\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testForIn() {
    checkNoWarnings(
        "function f(/** string */ y) {\n" +
        "  for (var x in { a: 1, b: 2 }) { y = x; }\n" +
        "  x = 234;\n" +
        "  return 123;\n" +
        "}");

    typeCheck(
        "function f(y) {\n" +
        "  var z = x + 234;\n" +
        "  for (var x in { a: 1, b: 2 }) {}\n" +
        "  return 123;\n" +
        "}",
        ImmutableList.of(
            VariableReferenceCheck.UNDECLARED_REFERENCE,
            NewTypeInference.INVALID_OPERAND_TYPE));

    typeCheck(
        "function f(/** number */ y) {\n" +
        "  for (var x in { a: 1, b: 2 }) { y = x; }\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "for (var x in 123) ;",
        NewTypeInference.FORIN_EXPECTS_OBJECT);

    typeCheck(
        "function f(/** undefined */ y) {\n" +
        "  var x;\n" +
        "  for (x in { a: 1, b: 2 }) { y = x; }\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testTryCatch() {
    typeCheck(
        "try { e; } catch (e) {}",
        VariableReferenceCheck.UNDECLARED_REFERENCE);

    typeCheck(
        "e; try {} catch (e) {}",
        VariableReferenceCheck.UNDECLARED_REFERENCE);

    checkNoWarnings("try {} catch (e) { e; }");
    // If the CFG can see that the TRY won't throw, it doesn't go to the catch.
    checkNoWarnings("try {} catch (e) { 1 - 'asdf'; }");

    typeCheck(
        "try { throw 123; } catch (e) { 1 - 'asdf'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "try { throw 123; } catch (e) {} finally { 1 - 'asdf'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);
    // The next tests should fail when we model local scopes properly.
    checkNoWarnings("try {} catch (e) {} e;");

    typeCheck(
        "var /** string */ e = 'asdf'; try {} catch (e) {} e - 5;",
        VariableReferenceCheck.REDECLARED_VARIABLE);
  }

  public void testIn() {
    typeCheck("(true in {});", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck("('asdf' in 123);", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "var /** number */ n = ('asdf' in {});",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "function f(/** { a: number } */ obj) {\n" +
        "  if ('p' in obj) {\n" +
        "    return obj.p;\n" +
        "  }\n" +
        "}\n" +
        "f({ a: 123 });");

    typeCheck(
        "function f(/** { a: number } */ obj) {\n" +
        "  if (!('p' in obj)) {\n" +
        "    return obj.p;\n" +
        "  }\n" +
        "}",
        TypeCheck.INEXISTENT_PROPERTY);
  }

  public void testDelprop() {
    checkNoWarnings("delete ({ prop: 123 }).prop;");

    typeCheck(
        "var /** number */ x = delete ({ prop: 123 }).prop;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
    // We don't detect the missing property
    checkNoWarnings("var obj = { a: 1, b: 2 }; delete obj.a; obj.a;");
  }

  public void testArrayLit() {
    typeCheck("[1, 2, 3 - 'asdf']", NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(x, y) {\n" +
        "  x < y;\n" +
        "  [y - 5];\n" +
        "}\n" +
        "f('asdf', 123);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testArrayAccesses() {
    typeCheck(
        "/** @constructor */ function Array(){}",
        "var a = [1,2,3]; a['str'];",
        NewTypeInference.NON_NUMERIC_ARRAY_INDEX);

    typeCheck(
        "/** @constructor */ function Array(){}",
        "function f(/** !Array */ arr, i) {\n" +
        "  arr[i];\n" +
        "  i = 'str';\n" +
        "}\n" +
        "f([1, 2, 3], 'str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testRegExpLit() {
    checkNoWarnings("/abc/");
  }

  public void testDifficultLvalues() {
    checkNoWarnings(
        "function f() { return {}; }\n" +
        "f().x = 123;");

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {number} */ this.a = 123; }\n" +
        "/** @return {!Foo} */\n" +
        "function retFoo() { return new Foo(); }\n" +
        "function f(cond) {\n" +
        "  (retFoo()).a = 'asdf';\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "(new Foo).x += 123;",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() { /** @type {number} */ this.a = 123; }\n" +
        "function f(cond, /** !Foo */ foo1) {\n" +
        "  var /** { a: number } */ x = { a: 321 };\n" +
        "  (cond ? foo1 : x).a = 'asdf';\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "function f(obj) { obj[1 - 'str'] = 3; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function f(/** number */ n, pname) { n[pname] = 3; }",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);
  }

  public void testGenericsJsdocParsing() {
    checkNoWarnings("/** @template T\n@param {T} x */ function f(x) {}");

    checkNoWarnings(
        "/** @template T\n @param {T} x\n @return {T} */\n" +
        "function f(x) { return x; };");

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @param {T} x\n" +
        " * @extends {Bar.<T>} // error, Bar is not templatized \n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @constructor */\n" +
        "function Bar() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {Foo.<number, string>} x */\n" +
        "function f(x) {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);
  }

  public void testPolymorphicFunctionInstantiation() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "id('str') - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {T} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "f(123, 'asdf');",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {(T|null)} x\n" +
        " * @return {(T|number)}\n" +
        " */\n" +
        "function f(x) { return x === null ? 123 : x; }\n" +
        "/** @return {(null|undefined)} */ function g() { return null; }\n" +
        "var /** (number|undefined) */ y = f(g());");

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {(T|number)} x\n" +
        " */\n" +
        "function f(x) {}\n" +
        "/** @return {*} */ function g() { return 1; }\n" +
        "f(g());",
        NewTypeInference.FAILED_TO_UNIFY);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "/** @return {*} */ function g() { return 1; }\n" +
        "id(g()) - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T, U\n" +
        " * @param {T} x\n" +
        " * @param {U} y\n" +
        " * @return {U}\n" +
        " */\n" +
        "function f(x, y) { return y; }\n" +
        "f(10, 'asdf') - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "function g(x) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f(x, 5);\n" +
        "  x = 'asdf';\n" +
        "}\n" +
        "g('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "function g(/** ? */ x) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {(T|number)} x\n" +
        "   */\n" +
        "  function f(x) {}\n" +
        "  f(x)\n" +
        "}");
    // TODO(blickly): Catching the INVALID_ARUGMENT_TYPE here requires
    // return-type unification.
    checkNoWarnings(
        "function g(x) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @return {T}\n" +
        "   */\n" +
        "  function f(x) { return x; }\n" +
        "  f(x) - 5;\n" +
        "  x = 'asdf';\n" +
        "}\n" +
        "g('asdf');");
    // Empty instantiations
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {(T|number)} x\n" +
        " */\n" +
        "function f(x) {}\n" +
        "f(123);",
        NewTypeInference.FAILED_TO_UNIFY);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {(T|null)} x\n" +
        " * @param {(T|number)} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "f(null, 'str');",
        NewTypeInference.FAILED_TO_UNIFY);

    typeCheck(
        "/** @constructor */ function Foo(){};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {(T|Foo)} x\n" +
        " * @param {(T|number)} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "f(new Foo(), 'str');",
        NewTypeInference.FAILED_TO_UNIFY);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {function(T):T} f\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function apply(f, x) { return f(x); }\n" +
        "/** @type {string} */" +
        "var out;" +
        "var result = apply(function(x){ out = x; return x; }, 0);",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);
  }

  public void testUnification() {
    typeCheck(
        "/** @constructor */ function Foo(){};\n" +
        "/** @constructor */ function Bar(){};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "var /** Bar */ x = id(new Foo);",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "id({}) - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "var /** (number|string) */ x = id('str');");

    typeCheck(
        "function f(/** * */ a, /** string */ b) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f(a, b);\n" +
        "}",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);

    checkNoWarnings(
        "function f(/** string */ b) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f({p:5, r:'str'}, {p:20, r:b});\n" +
        "}");

    typeCheck(
        "function f(/** string */ b) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f({r:'str'}, {p:20, r:b});\n" +
        "}",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);

    typeCheck(
        "function g(x) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  var /** boolean */ y = true;\n" +
        "  f(x, y);\n" +
        "}\n" +
        "g('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {number} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "f(123, 'asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @template T\n" +
        " */\n" +
        "function Foo() {}\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {Foo.<T>} x\n" +
        " */\n" +
        "function takesFoo(x) {}\n" +
        "takesFoo(undefined);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testUnifyObjects() {
    checkNoWarnings(
        "function f(b) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f({p:5, r:'str'}, {p:20, r:b});\n" +
        "}");

    checkNoWarnings(
        "function f(b) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f({p:20, r:b}, {p:5, r:'str'});\n" +
        "}");

    typeCheck(
        "function g(x) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  f({prop: x}, {prop: 5});\n" +
        "}\n" +
        "g('asdf');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function g(x, cond) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  var y = cond ? {prop: 'str'} : {prop: 5};\n" +
        "  f({prop: x}, y);\n" +
        "}\n" +
        "g({}, true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function g(x, cond) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {T} x\n" +
        "   * @param {T} y\n" +
        "   */\n" +
        "  function f(x, y) {}\n" +
        "  /** @type {{prop : (string | number)}} */\n" +
        "  var y = cond ? {prop: 'str'} : {prop: 5};\n" +
        "  f({prop: x}, y);\n" +
        "}\n" +
        "g({}, true);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {{a: number, b: T}} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function f(x) { return x.b; }\n" +
        "f({a: 1, b: 'asdf'}) - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @return {T}\n" +
        " */\n" +
        "function f(x) { return x.b; }\n" +
        "f({b: 'asdf'}) - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testInstantiationInsideObjectTypes() {
    typeCheck(
        "/**\n" +
        " * @template U\n" +
        " * @param {U} y\n" +
        " */\n" +
        "function g(y) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {{a: U, b: T}} x\n" +
        "   * @return {T}\n" +
        "   */\n" +
        "  function f(x) { return x.b; }\n" +
        "  f({a: y, b: 'asdf'}) - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template U\n" +
        " * @param {U} y\n" +
        " */\n" +
        "function g(y) {\n" +
        "  /**\n" +
        "   * @template T\n" +
        "   * @param {{b: T}} x\n" +
        "   * @return {T}\n" +
        "   */\n" +
        "  function f(x) { return x.b; }\n" +
        "  f({b: y}) - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testInstantiateInsideFunctionTypes() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {function(T):T} fun\n" +
        " */\n" +
        "function f(x, fun) {}\n" +
        "function g(x) { return x - 5; }\n" +
        "f('asdf', g);",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {function(T):number} fun\n" +
        " */\n" +
        "function f(fun) {}\n" +
        "function g(x) { return 'asdf'; }\n" +
        "f(g);",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {function(T=)} fun\n" +
        " */\n" +
        "function f(fun) {}\n" +
        "/** @param{string=} x */ function g(x) {}\n" +
        "f(g);");

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {function(... [T])} fun\n" +
        " */\n" +
        "function f(fun) {}\n" +
        "/** @param {...number} var_args */ function g(var_args) {}\n" +
        "f(g);");
  }

  public void testPolymorphicFuncallsFromDifferentScope() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function id(x) { return x; }\n" +
        "function g() {\n" +
        "  id('asdf') - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {number} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "function g() {\n" +
        "  f('asdf', 'asdf');\n" +
        "}",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {T} y\n" +
        " */\n" +
        "function f(x, y) {}\n" +
        "function g() {\n" +
        "  f(123, 'asdf');\n" +
        "}",
        NewTypeInference.NOT_UNIQUE_INSTANTIATION);
  }

  public void testOpacityOfTypeParameters() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function f(x) {\n" +
        "  x - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {{ a: T }} x\n" +
        " */\n" +
        "function f(x) {\n" +
        "  x.a - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @param {function(T):T} fun\n" +
        " */\n" +
        "function f(x, fun) {\n" +
        "  fun(x) - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function f(x) {\n" +
        "  return 5;\n" +
        "}",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function f(x) {\n" +
        "  var /** ? */ y = x;\n" +
        "}");

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {(T|number)}\n" +
        " */\n" +
        "function f(x) {\n" +
        "  var y;\n" +
        "  if (1 < 2) {\n" +
        "    y = x;\n" +
        "  } else {\n" +
        "    y = 123;\n" +
        "  }\n" +
        "  return y;\n" +
        "}");

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {(T|number)}\n" +
        " */\n" +
        "function f(x) {\n" +
        "  var y;\n" +
        "  if (1 < 2) {\n" +
        "    y = x;\n" +
        "  } else {\n" +
        "    y = 123;\n" +
        "  }\n" +
        "  return y;\n" +
        "}\n" +
        "f(123) - 5;");

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @return {(T|number)}\n" +
        " */\n" +
        "function f(x) {\n" +
        "  var y;\n" +
        "  if (1 < 2) {\n" +
        "    y = x;\n" +
        "  } else {\n" +
        "    y = 123;\n" +
        "  }\n" +
        "  return y;\n" +
        "}\n" +
        "var /** (number|boolean) */ z = f('asdf');",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function f(x) {\n" +
        "  var /** T */ y = x;\n" +
        "  y - 5;\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T, U\n" +
        " * @param {T} x\n" +
        " * @param {U} y\n" +
        " */\n" +
        "function f(x, y) {\n" +
        "  x = y;\n" +
        "}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testGenericClassInstantiation() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {T} y */\n" +
        "Foo.prototype.bar = function(y) {}\n" +
        "new Foo('str').bar(5)",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @type {function(T)} y */\n" +
        "Foo.prototype.bar = function(y) {};\n" +
        "new Foo('str').bar(5)",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) { /** @type {T} */ this.x = x; }\n" +
        "/** @return {T} */\n" +
        "Foo.prototype.bar = function() { return this.x; };\n" +
        "new Foo('str').bar() - 5",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) { /** @type {T} */ this.x = x; }\n" +
        "/** @type {function() : T} */\n" +
        "Foo.prototype.bar = function() { return this.x; };\n" +
        "new Foo('str').bar() - 5",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @type {function(this:Foo.<T>, T)} */\n" +
        "Foo.prototype.bar = function(x) { this.x = x; };\n" +
        "new Foo('str').bar(5)",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {!Foo.<number>} x */\n" +
        "function f(x) {}\n" +
        "f(new Foo(7));");

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {Foo.<number>} x */\n" +
        "function f(x) {}\n" +
        "f(new Foo('str'));",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {T} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "/** @param {!Foo.<number>} x */\n" +
        "function f(x) { x.method('asdf'); }",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @template T\n" +
        " */\n" +
        "function Foo() {}\n" +
        "/** @param {T} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "var /** @type {Foo} */ foo = null;\n" +
        "foo.method('asdf');",
        NewTypeInference.PROPERTY_ACCESS_ON_NONOBJECT);
  }

  public void testLooserCheckingForInferredProperties() {
    checkNoWarnings(
        "/** @constructor */\n" +
        "function Foo(x) { this.prop = x; }\n" +
        "function f(/** !Foo */ obj) {\n" +
        "  obj.prop = true ? 1 : 'asdf';\n" +
        "  obj.prop - 5;\n" +
        "}");

    checkNoWarnings(
        "/** @constructor */\n" +
        "function Foo(x) { this.prop = x; }\n" +
        "function f(/** !Foo */ obj) {\n" +
        "  if (!(typeof obj.prop == 'number')) {\n" +
        "    obj.prop < 'asdf';\n" +
        "  }\n" +
        "}");

    typeCheck(
        "/** @constructor */\n" +
        "function Foo(x) { this.prop = x; }\n" +
        "function f(/** !Foo */ obj) {\n" +
        "  obj.prop = true ? 1 : 'asdf';\n" +
        "  obj.prop - 5;\n" +
        "  obj.prop < 'asdf';\n" +
        "}",
        NewTypeInference.INVALID_OPERAND_TYPE);

    checkNoWarnings(
        "function /** string */ f(/** ?number */ x) {\n" +
        "  var o = { prop: 'str' };\n" +
        "  if (x) {\n" +
        "    o.prop = x;\n" +
        "  }\n" +
        "  return o.prop;\n" +
        "}");
  }

  public void testInheritanceWithGenerics() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/** @constructor @implements {I.<number>} */\n" +
        "function Foo() {}\n" +
        "Foo.prototype.bar = function(x) {};\n" +
        "(new Foo).bar('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/** @constructor @implements {I.<number>} */\n" +
        "function Foo() {}\n" +
        "/** @override */\n" +
        "Foo.prototype.bar = function(x) {};\n" +
        "new Foo().bar('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/**\n" +
        " * @template U\n" +
        " * @constructor\n" +
        " * @implements {I.<U>}\n" +
        " * @param {U} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "Foo.prototype.bar = function(x) {};{}\n" +
        "new Foo(5).bar('str');",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/** @constructor @implements {I.<number>} */\n" +
        "function Foo() {}\n" +
        "Foo.prototype.bar = function(x) {};\n" +
        "/** @param {I.<string>} x */ function f(x) {};\n" +
        "f(new Foo());",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/** @constructor @implements {I.<number>} */\n" +
        "function Foo() {}\n" +
        "/** @param {string} x */\n" +
        "Foo.prototype.bar = function(x) {};",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/** @param {T} x */\n" +
        "I.prototype.bar = function(x) {};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " * @constructor @implements {I.<number>}\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {T} x */\n" +
        "Foo.prototype.bar = function(x) {};",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " */\n" +
        "function Foo() {}\n" +
        "/** @param {T} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @extends {Foo.<T>}\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Bar(x) {}\n" +
        "/** @param {number} x */\n" +
        "Bar.prototype.method = function(x) {};",
        GlobalTypeInfo.INVALID_PROP_OVERRIDE);

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " */\n" +
        "function High() {}\n" +
        "/** @param {Low.<T>} x */\n" +
        "High.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @extends {High.<T>}\n" +
        " */\n" +
        "function Low() {}");

    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " */\n" +
        "function High() {}\n" +
        "/** @param {Low.<number>} x */\n" +
        "High.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @extends {High.<T>}\n" +
        " */\n" +
        "function Low() {}");

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " */\n" +
        "function High() {}\n" +
        "/** @param {Low.<T>} x */ // error, low is not templatized\n" +
        "High.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @constructor\n" +
        " * @extends {High.<number>}\n" +
        " */\n" +
        "function Low() {}",
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    // BAD INHERITANCE, WE DON'T HAVE A WARNING TYPE FOR THIS
    // TODO(dimvar): fix
    checkNoWarnings(
        "/**\n" +
        " * @template T\n" +
        " * @interface\n" +
        " */\n" +
        "function I(x) {}\n" +
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @implements {I.<T>}\n" +
        " * @extends {Bar}\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/**\n" +
        " * @constructor\n" +
        " * @implements {I.<number>}\n" +
        " */\n" +
        "function Bar(x) {}");
  }

  public void testDifficultClassGenericsInstantiation() {
    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {Bar.<T>} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Bar(x) {}\n" +
        "/** @param {Foo.<T>} x */\n" +
        "Bar.prototype.method = function(x) {};\n" +
        "(new Foo(123)).method(new Bar('asdf'));",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/**\n" +
        " * @template T\n" +
        " * @constructor\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/** @param {Foo.<Foo.<T>>} x */\n" +
        "Foo.prototype.method = function(x) {};\n" +
        "(new Foo(123)).method(new Foo(new Foo('asdf')));",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "/** @interface\n @template T */function A() {};" +
        "/** @return {T} */A.prototype.foo = function() {};" +
        "/** @interface\n @template U\n @extends {A.<U>} */function B() {};" +
        "/** @constructor\n @implements {B.<string>} */function C() {};" +
        "/** @return {string}\n @override */\n" +
        "C.prototype.foo = function() { return 123; };",
        NewTypeInference.RETURN_NONDECLARED_TYPE);

    // Polymorphic method on a generic class.
    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @template T\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/**\n" +
        " * @template U\n" +
        " * @param {U} x\n" +
        " * @return {U}\n" +
        " */\n" +
        "Foo.prototype.method = function(x) { return x; };\n" +
        "(new Foo(123)).method('asdf') - 5;",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testNominalTypeUnification() {
    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @template T, U\n" +
        " * @param {T} x\n" +
        " */\n" +
        "function Foo(x) {}\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {!Foo.<T>} x\n" +
        " */\n" +
        "function fn(x) {}\n" +
        "fn(new Foo('asdf'));",
        // {!Foo.<T>} is instantiating only the 1st template var of Foo
        RhinoErrorReporter.BAD_JSDOC_ANNOTATION);

    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @template S, T\n" +
        " * @param {S} x\n" +
        " */\n" +
        "function Foo(x) {\n" +
        "  /** @type {S} */ this.prop = x;\n" +
        "}\n" +
        "/**\n" +
        " * @template T\n" +
        " * @param {!Foo.<T>} x\n" +
        " * @return {T}\n" +
        " */\n" +
        "function fn(x) { return x.prop; }\n" +
        "fn(new Foo('asdf')) - 5;",
        ImmutableList.of(
            // {!Foo.<T>} is instantiating only the 1st template var of Foo
            RhinoErrorReporter.BAD_JSDOC_ANNOTATION,
            NewTypeInference.INVALID_OPERAND_TYPE));
  }

  public void testCasts() {
    typeCheck(
        "(/** @type {number} */ ('asdf'));",
        TypeValidator.INVALID_CAST);

    checkNoWarnings(
        "function f(/** (number|string) */ x) {\n" +
        "  var y = /** @type {number} */ (x);\n" +
        "}");

    checkNoWarnings("(/** @type {(number|string)} */ (1));");

    checkNoWarnings("(/** @type {number} */ (/** @type {?} */ ('asdf')))");
  }

  public void testOverride() {
    typeCheck(
        "/** @interface */\n" +
        "function Intf() {}\n" +
        "/** @param {(number|string)} x */\n" +
        "Intf.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @constructor\n" +
        " * @implements {Intf}\n" +
        " */\n" +
        "function C() {}\n" +
        "/** @override */\n" +
        "C.prototype.method = function (x) { x - 1; };\n" +
        "(new C).method('asdf');",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @interface */\n" +
        "function Intf() {}\n" +
        "/** @param {(number|string)} x */\n" +
        "Intf.prototype.method = function(x) {};\n" +
        "/**\n" +
        " * @constructor\n" +
        " * @implements {Intf}\n" +
        " */\n" +
        "function C() {}\n" +
        "/** @inheritDoc */\n" +
        "C.prototype.method = function (x) { x - 1; };\n" +
        "(new C).method('asdf');",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @override */\n" +
        "Foo.prototype.method = function() {};",
        TypeCheck.UNKNOWN_OVERRIDE);

    typeCheck(
        "/** @constructor */\n" +
        "function Foo() {}\n" +
        "/** @inheritDoc */\n" +
        "Foo.prototype.method = function() {};",
        TypeCheck.UNKNOWN_OVERRIDE);
  }

  public void testFunctionConstructor() {
    checkNoWarnings(
        "/** @type {Function} */ function topFun() {}\n" +
        "topFun(1);");

    checkNoWarnings(
        "/** @type {Function} */ function topFun(x) { return x - 5; }");

    checkNoWarnings(
        "function f(/** Function */ fun) {}\n" +
        "f(function g(x) { return x - 5; });");

    checkNoWarnings(
        "function f(/** !Function */ fun) { return new fun(1, 2); }");
  }

  public void testConditionalExBranch() {
    checkNoWarnings(
        "function g() { throw 1; }\n" +
        "function f() {\n" +
        "  try {\n" +
        "    if (g()) {}\n" +
        "  } catch (e) {}\n" +
        "};");
  }

  public void testGetpropDoesntCrash() {
    checkNoWarnings(
        "/** @constructor */ function Obj(){}\n" +
        "/** @constructor */ var Foo = function() {\n" +
        "    /** @private {Obj} */ this.obj;\n" +
        "};\n" +
        "Foo.prototype.update = function() {\n" +
        "    if (!this.obj.size) {}\n" +
        "};");
  }

  public void testMeetOfLooseObjAndNamedDoesntCrash() {
    checkNoWarnings(
        "/** @constructor */ function Foo(){ this.prop = 5; }\n" +
        "/** @constructor */ function Bar(){}\n" +
        "/** @param {function(!Foo)} func */\n" +
        "Bar.prototype.forEach = function(func) {\n" +
        "  this.forEach(function(looseObj) { looseObj.prop; });\n" +
        "};");
  }

  public void testAccessVarargsDoesntCrash() {
    // TODO(blickly): Support arguments so we only get one warning
    typeCheck(
        "/** @param {...} var_args */\n" +
        "function f(var_args) { return true ? var_args : arguments; }",
        ImmutableList.of(
            VarCheck.UNDEFINED_VAR_ERROR,
            VarCheck.UNDEFINED_VAR_ERROR));
  }

  public void testDeclaredMethodWithoutScope() {
    checkNoWarnings(
        "/** @interface */ function Foo(){}\n" +
        "/** @type {function(number)} */ Foo.prototype.bar;\n" +
        "/** @constructor @implements {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(x){}");

    checkNoWarnings(
        "/** @type {!Function} */\n" +
        "var g = function() { throw 0; };\n" +
        "/** @constructor */ function Foo(){}\n" +
        "/** @type {function(number)} */ Foo.prototype.bar = g;\n" +
        "/** @constructor @extends {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(x){}");

    checkNoWarnings(
        "/** @param {string} s */\n" +
        "var reqString = function(s) {};\n" +
        "/** @constructor */ function Foo(){}\n" +
        "/** @type {function(string)} */ Foo.prototype.bar = reqString;\n" +
        "/** @constructor @extends {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(x){}");

    typeCheck(
        "/** @param {string} s */\n" +
        "var reqString = function(s) {};\n" +
        "/** @constructor */ function Foo(){}\n" +
        "/** @type {function(number)} */ Foo.prototype.bar = reqString;\n" +
        "/** @constructor @extends {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(x){}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "/** @constructor */ function Foo(){}\n" +
        "/** @type {Function} */ Foo.prototype.bar = null;\n" +
        "/** @constructor @extends {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(){}");

    typeCheck(
        "/** @constructor */ function Foo(){}\n" +
        "/** @type {!Function} */ Foo.prototype.bar = null;\n" +
        "/** @constructor @extends {Foo} */ function Bar(){}\n" +
        "Bar.prototype.bar = function(){}",
        NewTypeInference.MISTYPED_ASSIGN_RHS);
  }

  public void testPropNamesWithDot() {
    checkNoWarnings("var x = { '.': 1, ';': 2, '/': 3, '{': 4, '}': 5 }");

    checkNoWarnings(
        "function f(/** { foo : { bar : string } } */ x) {\n" +
        "  x['foo.bar'] = 5;\n" +
        "}");

    typeCheck(
        "var x = { '.' : 'str' };\n" +
        "x['.'] - 5",
        NewTypeInference.INVALID_OPERAND_TYPE);
  }

  public void testObjLitDeclaredProps() {
    typeCheck(
        "({ /** @type {string} */ prop: 123 });",
        NewTypeInference.INVALID_OBJLIT_PROPERTY_TYPE);

    typeCheck(
        "var lit = { /** @type {string} */ prop: 'str' };\n" +
        "lit.prop = 123;",
        NewTypeInference.MISTYPED_ASSIGN_RHS);

    checkNoWarnings(
        "var lit = { /** @type {(number|string)} */ prop: 'str' };\n" +
        "var /** string */ s = lit.prop;");
  }

  public void testCallArgumentsChecked() {
    typeCheck(
        "3(1 - 'str');",
        ImmutableList.of(
            TypeCheck.NOT_CALLABLE,
            NewTypeInference.INVALID_OPERAND_TYPE));

  }

  public void testRecursiveFunctions() {
    typeCheck(
        "function foo(){ foo() - 123; return 'str'; }",
        NewTypeInference.INVALID_INFERRED_RETURN_TYPE);

    typeCheck(
        "/** @return {string} */ function foo(){ foo() - 123; return 'str'; }",
        NewTypeInference.INVALID_OPERAND_TYPE);

    typeCheck(
        "/** @return {number} */\n" +
        "var f = function rec() { return rec; };",
        NewTypeInference.RETURN_NONDECLARED_TYPE);
  }

  public void testStructPropAccess() {
    checkNoWarnings(
        "/** @constructor @struct */ function Foo() { this.prop = 123; }\n" +
        "(new Foo).prop;");

    typeCheck(
        "/** @constructor @struct */ function Foo() { this.prop = 123; }\n" +
        "(new Foo)['prop'];",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @interface */ function Foo() {}\n" +
        "/** @type {number} */ Foo.prototype.prop;\n" +
        "function f(/** !Foo */ x) { x['prop']; }",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @struct */ function Foo() {\n" +
        "  this.prop = 123;\n" +
        "  this['prop'] - 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @struct */ function Foo() { this.prop = 123; }\n" +
        "(new Foo)['prop'] = 123;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @struct */ function Foo() { this.prop = 123; }\n" +
        "function f(pname) { (new Foo)[pname] = 123; }",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @struct */ function Foo() { this.prop = {}; }\n" +
        "(new Foo)['prop'].newprop = 123;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @struct */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "function f(cond) {\n" +
        "  var x;\n" +
        "  if (cond) {\n" +
        "    x = new Foo;\n" +
        "  }\n" +
        "  else {\n" +
        "    x = new Bar;\n" +
        "  }\n" +
        "  x['prop'] = 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "(/** @struct */ { 'prop' : 1 });",
        TypeCheck.ILLEGAL_OBJLIT_KEY);

    typeCheck(
        "var lit = /** @struct */ { prop : 1 }; lit['prop'];",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "function f(cond) {\n" +
        "  var x;\n" +
        "  if (cond) {\n" +
        "    x = /** @struct */ { a: 1 };\n" +
        "  }\n" +
        "  else {\n" +
        "    x = /** @struct */ { a: 2 };\n" +
        "  }\n" +
        "  x['a'] = 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "function f(cond) {\n" +
        "  var x;\n" +
        "  if (cond) {\n" +
        "    x = /** @struct */ { a: 1 };\n" +
        "  }\n" +
        "  else {\n" +
        "    x = {};\n" +
        "  }\n" +
        "  x['random' + 'propname'] = 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);
  }

  public void testDictPropAccess() {
    checkNoWarnings(
        "/** @constructor @dict */ function Foo() { this['prop'] = 123; }\n" +
        "(new Foo)['prop'];");

    typeCheck(
        "/** @constructor @dict */ function Foo() { this['prop'] = 123; }\n" +
        "(new Foo).prop;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @dict */ function Foo() {\n" +
        "  this['prop'] = 123;\n" +
        "  this.prop - 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @dict */ function Foo() { this['prop'] = 123; }\n" +
        "(new Foo).prop = 123;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @dict */ function Foo() { this['prop'] = {}; }\n" +
        "(new Foo).prop.newprop = 123;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "/** @constructor @dict */ function Foo() {}\n" +
        "/** @constructor */ function Bar() {}\n" +
        "function f(cond) {\n" +
        "  var x;\n" +
        "  if (cond) {\n" +
        "    x = new Foo;\n" +
        "  }\n" +
        "  else {\n" +
        "    x = new Bar;\n" +
        "  }\n" +
        "  x.prop = 123;\n" +
        "}",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "(/** @dict */ { prop : 1 });",
        TypeCheck.ILLEGAL_OBJLIT_KEY);

    typeCheck(
        "var lit = /** @dict */ { 'prop' : 1 }; lit.prop;",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);

    typeCheck(
        "(/** @dict */ {}).toString();",
        TypeValidator.ILLEGAL_PROPERTY_ACCESS);
  }

  public void testStructWithIn() {
    typeCheck(
        "('prop' in /** @struct */ {});",
        TypeCheck.IN_USED_WITH_STRUCT);

    typeCheck(
        "for (var x in /** @struct */ {});",
        TypeCheck.IN_USED_WITH_STRUCT);
  }

  public void testStructDictSubtyping() {
    checkNoWarnings(
        "var lit = { a: 1 }; lit.a - 2; lit['a'] + 5;");

    typeCheck(
        "/** @constructor @struct */ function Foo() {}\n" +
        "/** @constructor @dict */ function Bar() {}\n" +
        "function f(/** Foo */ x) {}\n" +
        "f(/** @dict */ {});",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(/** { a : number } */ x) {}\n" +
        "f(/** @dict */ { 'a' : 5 });",
        NewTypeInference.INVALID_ARGUMENT_TYPE);
  }

  public void testInferStructDictFormal() {
    typeCheck(
        "function f(obj) {\n" +
        "  return obj.prop;\n" +
        "}\n" +
        "f(/** @dict */ { 'prop': 123 });",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    typeCheck(
        "function f(obj) {\n" +
        "  return obj['prop'];\n" +
        "}\n" +
        "f(/** @struct */ { prop: 123 });",
        NewTypeInference.INVALID_ARGUMENT_TYPE);

    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "function f(obj) { obj['prop']; return obj; }\n" +
        "var /** !Foo */ x = f({ prop: 123 });");
  }

  public void testStructDictInheritance() {
    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "/** @constructor @struct @extends {Foo} */\n" +
        "function Bar() {}");

    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "/** @constructor @unrestricted @extends {Foo} */\n" +
        "function Bar() {}");

    checkNoWarnings(
        "/** @constructor @dict */\n" +
        "function Foo() {}\n" +
        "/** @constructor @dict @extends {Foo} */\n" +
        "function Bar() {}");

    typeCheck(
        "/** @constructor @unrestricted */\n" +
        "function Foo() {}\n" +
        "/** @constructor @struct @extends {Foo} */\n" +
        "function Bar() {}",
        TypeCheck.CONFLICTING_SHAPE_TYPE);

    typeCheck(
        "/** @constructor @unrestricted */\n" +
        "function Foo() {}\n" +
        "/** @constructor @dict @extends {Foo} */\n" +
        "function Bar() {}",
        TypeCheck.CONFLICTING_SHAPE_TYPE);

    typeCheck(
        "/** @interface */\n" +
        "function Foo() {}\n" +
        "/** @constructor @dict @implements {Foo} */\n" +
        "function Bar() {}",
        GlobalTypeInfo.DICT_IMPLEMENTS_INTERF);
  }

  public void testStructPropCreation() {
    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() { this.prop = 1; }\n" +
        "(new Foo).prop = 2;");

    typeCheck(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "Foo.prototype.method = function() { this.prop = 1; };\n" +
        "(new Foo).prop = 2;",
        ImmutableList.of(
            TypeCheck.ILLEGAL_PROPERTY_CREATION,
            TypeCheck.ILLEGAL_PROPERTY_CREATION));

    typeCheck(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "(new Foo).prop += 2;",
        TypeCheck.INEXISTENT_PROPERTY);

    typeCheck(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "Foo.prototype.method = function() { this.prop = 1; };\n" +
        "(new Foo).prop++;",
        ImmutableList.of(
            TypeCheck.ILLEGAL_PROPERTY_CREATION,
            TypeCheck.INEXISTENT_PROPERTY));

    typeCheck(
        "(/** @struct */ { prop: 1 }).prop2 = 123;",
        TypeCheck.ILLEGAL_PROPERTY_CREATION);

    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "/** @constructor @struct @extends {Foo} */\n" +
        "function Bar() {}\n" +
        "Bar.prototype.prop = 123;");

    typeCheck(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "/** @constructor @struct @extends {Foo} */\n" +
        "function Bar() {}\n" +
        "Bar.prototype.prop = 123;\n" +
        "(new Foo).prop = 234;",
        TypeCheck.ILLEGAL_PROPERTY_CREATION);

    typeCheck(
        "/**\n" +
        " * @constructor\n" +
        " * @struct\n" +
        " */\n" +
        "function Foo() {\n" +
        "  var t = this;\n" +
        "  t.x = 123;\n" +
        "}",
        TypeCheck.ILLEGAL_PROPERTY_CREATION);

    checkNoWarnings(
        "/**\n" +
        " * @constructor\n" +
        " * @struct\n" +
        " */\n" +
        "function Foo() {}\n" +
        "Foo.someprop = 123;");

    // TODO(dimvar): the current type inf also doesn't catch this.
    // Consider warning when the prop is not an "own" prop.
    checkNoWarnings(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "Foo.prototype.bar = 123;\n" +
        "(new Foo).bar = 123;");

    checkNoWarnings(
        "function f(obj) { obj.prop = 123; }\n" +
        "f(/** @struct */ {});");

    typeCheck(
        "/** @constructor @struct */\n" +
        "function Foo() {}\n" +
        "function f(obj) { obj.prop - 5; return obj; }\n" +
        "var s = (1 < 2) ? new Foo : f({ prop: 123 });\n" +
        "s.newprop = 123;",
        TypeCheck.ILLEGAL_PROPERTY_CREATION);
  }

  public void testMisplacedStructDictAnnotation() {
    typeCheck(
        "/** @struct */ function Struct1() {}",
        GlobalTypeInfo.CONSTRUCTOR_REQUIRED);
    typeCheck(
        "/** @dict */ function Dict() {}",
        GlobalTypeInfo.CONSTRUCTOR_REQUIRED);
  }

  public void testGlobalVariableInJoin() {
    typeCheck(
        "function f() { true ? globalVariable : 123; }",
        VarCheck.UNDEFINED_VAR_ERROR);
  }
}