/*
 * Copyright 2014 Google Inc.
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

package com.google.template.soy.sharedpasses;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.sharedpasses.FindIndirectParamsVisitor.IndirectParamsInfo;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.CallParamValueNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoySyntaxExceptionUtils;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.soytree.TemplateRegistry.DelegateTemplateDivision;
import com.google.template.soy.soytree.defn.HeaderParam;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.aggregate.UnionType;
import com.google.template.soy.types.primitive.NullType;
import com.google.template.soy.types.primitive.SanitizedType;
import com.google.template.soy.types.proto.SoyProtoType;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Visitor for checking that calling arguments match the declared parameter types
 * of the called template. Throws a SoySyntaxException if any problems are found.
 * Note that we don't check for missing parameters, that check is done by
 * CheckCallsVisitor.
 *
 * Note: This pass requires that the ResolveExpressionTypesVisitor has already
 * been run.
 *
 * @author Talin
 */
public class CheckCallingParamTypesVisitor extends AbstractSoyNodeVisitor<Void> {

  /** Registry of all templates in the Soy tree. */
  private TemplateRegistry templateRegistry;

  /** The current template being checked. */
  private TemplateNode template;

  /** Map of all template parameters, both explicit and implicit, organized by template. */
  private final Map<TemplateNode, TemplateParamTypes> paramTypesMap = Maps.newHashMap();

  /**
   * {@inheritDoc}
   * @throws SoySyntaxException If the arguments in a call operation are incompatible
   *    with the declared types of the callee's parameters.
   */
  @Override protected void visitSoyFileSetNode(SoyFileSetNode node) {

    // Build templateRegistry.
    templateRegistry = new TemplateRegistry(node);
    visitChildren(node);
    templateRegistry = null;
  }

  @Override protected void visitCallNode(CallNode node) {
    TemplateNode callee;
    if (node instanceof CallBasicNode) {
      callee = templateRegistry.getBasicTemplate(((CallBasicNode) node).getCalleeName());
      if (callee != null) {
        checkCallParamTypes(node, callee);
      }
    } else {
      Set<DelegateTemplateDivision> divisions =
          templateRegistry.getDelTemplateDivisionsForAllVariants(
              ((CallDelegateNode) node).getDelCalleeName());
      if (divisions != null) {
        for (DelegateTemplateDivision division : divisions) {
          for (TemplateDelegateNode delTemplate :
              division.delPackageNameToDelTemplateMap.values()) {
            checkCallParamTypes(node, delTemplate);
          }
        }
      }
    }

    visitChildren(node);
  }


  @Override protected void visitSoyNode(SoyNode node) {
    if (node instanceof ParentSoyNode<?>) {
      visitChildren((ParentSoyNode<?>) node);
    }
  }

  @Override protected void visitTemplateNode(TemplateNode node) {
    template = node;
    visitChildren(node);
    template = null;
  }

  private void checkCallParamTypes(CallNode call, TemplateNode callee) {
    TemplateParamTypes calleeParamTypes = getTemplateParamTypes(callee);
    Set<String> explicitParams = Sets.newHashSet();
    for (CallParamNode callerParam : call.getChildren()) {
      SoyType argType = null;
      if (callerParam.getKind() == SoyNode.Kind.CALL_PARAM_VALUE_NODE) {
        ExprNode expr = ((CallParamValueNode) callerParam).getValueExprUnion().getExpr();
        if (expr != null) {
          argType = expr.getType();
        }
      } else if (callerParam.getKind() == SoyNode.Kind.CALL_PARAM_CONTENT_NODE) {
        argType = SanitizedType.getTypeForContentKind(
            ((CallParamContentNode) callerParam).getContentKind());
      }
      if (argType == null) {
        continue;
      }
      // The types of the parameters. If this is an explicitly declared parameter,
      // then this collection will have only one member; If it's an implicit
      // parameter then this may contain multiple types. Note we don't use
      // a union here because the rules are a bit different than the normal rules
      // for assigning union types.
      // It's possible that the set may be empty, because all of the callees
      // are external. In that case there's nothing we can do, so we don't
      // report anything.
      Collection<SoyType> declaredParamTypes = calleeParamTypes.params.get(callerParam.getKey());
      for (SoyType formalType : declaredParamTypes) {
        checkArgumentAgainstParamType(
            call, callerParam.getKey(), argType, formalType,
            calleeParamTypes.isIndirect(callerParam.getKey()));
      }

      explicitParams.add(callerParam.getKey());
    }

    if (call.isPassingData()) {
      if (call.isPassingAllData() && template.getParams() != null) {
        // Check indirect params that are passed via data="all".
        // We only need to check explicit params of calling template here.
        for (TemplateParam callerParam : template.getParams()) {
          if (!(callerParam instanceof HeaderParam)) {
            continue;
          }
          String paramName = callerParam.name();

          // The parameter is explicitly overridden with another value, which we
          // already checked.
          if (explicitParams.contains(paramName)) {
            continue;
          }

          Collection<SoyType> declaredParamTypes = calleeParamTypes.params.get(paramName);
          for (SoyType formalType : declaredParamTypes) {
            checkArgumentAgainstParamType(
                call, paramName, callerParam.type(), formalType,
                calleeParamTypes.isIndirect(paramName));
          }
        }
      } else {
        // TODO: Check if the fields of the type of data arg can be assigned to the params.
        // This is possible for some types, and never allowed for other types.
      }
    }
  }


  /**
   * Check that the argument passed to the template is compatible with the template
   * parameter type.
   * @param call The call node.
   * @param paramName the name of the parameter.
   * @param argType The type of the value being passed.
   * @param formalType The type of the parameter.
   * @param isIndirect Whether the parameter is an indirect parameter.
   */
  private void checkArgumentAgainstParamType(
      CallNode call, String paramName, SoyType argType, SoyType formalType, boolean isIndirect) {
    if (formalType.getKind() == SoyType.Kind.UNKNOWN ||
        formalType.getKind() == SoyType.Kind.ANY) {
      // Special rules for unknown / any
      if (argType instanceof SoyProtoType) {
        reportProtoArgumentTypeMismatch(call, paramName, formalType, argType);
      }
    } else if (argType.getKind() == SoyType.Kind.UNKNOWN) {
      // Special rules for unknown / any
      //
      // This check disabled: We now allow maps created from protos to be passed
      // to a function accepting a proto, this makes migration easier.
      // (See GenJsCodeVisitor.genParamTypeChecks).
      // TODO(user): Re-enable at some future date?
      // if (formalType instanceof SoyProtoType) {
      //   reportProtoArgumentTypeMismatch(call, paramName, formalType, argType);
      // }
    } else {
      if (!formalType.isAssignableFrom(argType)) {
        if (isIndirect &&
            argType.getKind() == SoyType.Kind.UNION &&
            ((UnionType) argType).isNullable()) {
          if (UnionType.of(formalType, NullType.getInstance()).isAssignableFrom(argType)) {
            // Special case for indirect params: Allow a nullable type to be assigned
            // to a non-nullable type if the non-nullable type is an indirect parameter type.
            // The reason is because without flow analysis, we can't know whether or not
            // there are if-statements preventing null from being passed as an indirect
            // param, so we assume all indirect params are optional.
            return;
          }
        }
        reportArgumentTypeMismatch(call, paramName, formalType, argType);
      }
    }
  }


  private void reportArgumentTypeMismatch(
      SoyNode node, String paramName, SoyType paramType, SoyType argType) {
    throw SoySyntaxExceptionUtils.createWithNode(
        "Argument type mismatch: cannot call template parameter '" + paramName +
        "' with type '" + paramType + "' with value of type '" + argType + "'", node);
  }


  private void reportProtoArgumentTypeMismatch(
      SoyNode node, String paramName, SoyType paramType, SoyType argType) {
    throw SoySyntaxExceptionUtils.createWithNode(
        "Argument type mismatch: cannot mix protobuf / non-protobuf types " +
        "when calling template parameter '" + paramName + "' with type '" + paramType +
        "' with value of type '" + argType + "'", node);
  }


  /**
   * Get the parameter types for a callee.
   * @param node The template being called.
   * @return The set of template parameters, both explicit and implicit.
   */
  private TemplateParamTypes getTemplateParamTypes(TemplateNode node) {
    TemplateParamTypes paramTypes = paramTypesMap.get(node);
    if (paramTypes == null) {
      paramTypes = new TemplateParamTypes();

      // Store all of the explicitly declared param types
      if (node.getParams() != null) {
        for (TemplateParam param : node.getParams()) {
          Preconditions.checkNotNull(param.type());
          paramTypes.params.put(param.name(), param.type());
        }
      }

      // Store indirect params where there's no conflict with explicit params.
      // Note that we don't check here whether the explicit type and the implicit
      // types are in agreement - that will be done when it's this template's
      // turn to be analyzed as a caller.
      IndirectParamsInfo ipi = (new FindIndirectParamsVisitor(templateRegistry)).exec(node);
      for (String indirectParamName: ipi.indirectParamTypes.keySet()) {
        if (paramTypes.params.containsKey(indirectParamName)) {
          continue;
        }
        paramTypes.params.putAll(indirectParamName, ipi.indirectParamTypes.get(indirectParamName));
        paramTypes.indirectParamNames.add(indirectParamName);
      }

      // Save the param types map
      paramTypesMap.put(node, paramTypes);
    }
    return paramTypes;
  }


  private static class TemplateParamTypes {
    public final Multimap<String, SoyType> params = HashMultimap.create();
    public final Set<String> indirectParamNames = Sets.newHashSet();

    public boolean isIndirect(String paramName) {
      return indirectParamNames.contains(paramName);
    }
  }
}
