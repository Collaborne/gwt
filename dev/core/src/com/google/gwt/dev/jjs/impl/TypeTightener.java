/*
 * Copyright 2007 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.jjs.impl;

import com.google.gwt.dev.jjs.ast.CanBeAbstract;
import com.google.gwt.dev.jjs.ast.Context;
import com.google.gwt.dev.jjs.ast.JBinaryOperation;
import com.google.gwt.dev.jjs.ast.JBinaryOperator;
import com.google.gwt.dev.jjs.ast.JCastOperation;
import com.google.gwt.dev.jjs.ast.JClassType;
import com.google.gwt.dev.jjs.ast.JDeclarationStatement;
import com.google.gwt.dev.jjs.ast.JExpression;
import com.google.gwt.dev.jjs.ast.JField;
import com.google.gwt.dev.jjs.ast.JFieldRef;
import com.google.gwt.dev.jjs.ast.JInstanceOf;
import com.google.gwt.dev.jjs.ast.JInterfaceType;
import com.google.gwt.dev.jjs.ast.JLocal;
import com.google.gwt.dev.jjs.ast.JLocalRef;
import com.google.gwt.dev.jjs.ast.JMethod;
import com.google.gwt.dev.jjs.ast.JMethodCall;
import com.google.gwt.dev.jjs.ast.JModVisitor;
import com.google.gwt.dev.jjs.ast.JNullLiteral;
import com.google.gwt.dev.jjs.ast.JNullType;
import com.google.gwt.dev.jjs.ast.JParameter;
import com.google.gwt.dev.jjs.ast.JParameterRef;
import com.google.gwt.dev.jjs.ast.JProgram;
import com.google.gwt.dev.jjs.ast.JReferenceType;
import com.google.gwt.dev.jjs.ast.JReturnStatement;
import com.google.gwt.dev.jjs.ast.JTryStatement;
import com.google.gwt.dev.jjs.ast.JType;
import com.google.gwt.dev.jjs.ast.JTypeOracle;
import com.google.gwt.dev.jjs.ast.JVariable;
import com.google.gwt.dev.jjs.ast.JVariableRef;
import com.google.gwt.dev.jjs.ast.JVisitor;
import com.google.gwt.dev.jjs.ast.js.JsniFieldRef;
import com.google.gwt.dev.jjs.ast.js.JsniMethodRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The purpose of this pass is to record "type flow" information and then use
 * the information to infer places where "tighter" (that is, more specific)
 * types can be inferred for locals, fields, parameters, and method return
 * types. We also optimize dynamic casts and instanceof operations.
 * 
 * Type flow occurs automatically in most JExpressions. But locals, fields,
 * parameters, and method return types serve as "way points" where type
 * information is fixed based on the declared type. Type tightening can be done
 * by analyzing the types "flowing" into each way point, and then updating the
 * declared type of the way point to be a more specific type than it had before.
 * 
 * Oddly, it's quite possible to tighten a variable to the Null type, which
 * means either the variable was never assigned, or it was only ever assigned
 * null. This is great for two reasons:
 * 
 * 1) Once a variable has been tightened to null, it will no longer impact the
 * variables that depend on it.
 * 
 * 2) It creates some very interesting opportunities to optimize later, since we
 * know statically that the value of the variable is always null.
 * 
 * Open issue: we don't handle recursion where a method passes (some of) its own
 * args to itself or returns its own call result. With our naive analysis, we
 * can't figure out that tightening might occur.
 * 
 * Type flow is not supported for primitive types, only reference types.
 */
public class TypeTightener {
  /*
   * TODO(later): handle recursion, self-assignment, arrays, method tightening
   * on invocations from within JSNI blocks
   */

  /**
   * Replaces dangling null references with dummy calls.
   */
  public class FixDanglingRefsVisitor extends JModVisitor {

    @Override
    public void endVisit(JFieldRef x, Context ctx) {
      JExpression instance = x.getInstance();
      boolean isStatic = x.getField().isStatic();
      if (isStatic && instance != null) {
        // this doesn't really belong here, but while we're here let's remove
        // non-side-effect qualifiers to statics
        if (!instance.hasSideEffects()) {
          JFieldRef fieldRef = new JFieldRef(program, x.getSourceInfo(), null,
              x.getField(), x.getEnclosingType());
          ctx.replaceMe(fieldRef);
        }
      } else if (!isStatic && instance.getType() == typeNull) {
        if (!instance.hasSideEffects()) {
          instance = program.getLiteralNull();
        }
        JFieldRef fieldRef = new JFieldRef(program, x.getSourceInfo(),
            instance, program.getNullField(), null);
        ctx.replaceMe(fieldRef);
      }
    }

    @Override
    public void endVisit(JMethodCall x, Context ctx) {
      JExpression instance = x.getInstance();
      JMethod method = x.getTarget();
      boolean isStatic = method.isStatic();
      boolean isStaticImpl = program.isStaticImpl(method);
      if (isStatic && !isStaticImpl && instance != null) {
        // this doesn't really belong here, but while we're here let's remove
        // non-side-effect qualifiers to statics
        if (!instance.hasSideEffects()) {
          JMethodCall newCall = new JMethodCall(program, x.getSourceInfo(),
              null, x.getTarget());
          newCall.getArgs().addAll(x.getArgs());
          ctx.replaceMe(newCall);
        }
      } else if (!isStatic && instance.getType() == typeNull) {
        // bind null instance calls to the null method
        if (!instance.hasSideEffects()) {
          instance = program.getLiteralNull();
        }
        JMethodCall newCall = new JMethodCall(program, x.getSourceInfo(),
            instance, program.getNullMethod());
        ctx.replaceMe(newCall);
      } else if (isStaticImpl && method.params.size() > 0
          && method.params.get(0).isThis() && x.getArgs().size() > 0
          && x.getArgs().get(0).getType() == typeNull) {
        // bind null instance calls to the null method for static impls
        instance = x.getArgs().get(0);
        if (!instance.hasSideEffects()) {
          instance = program.getLiteralNull();
        }
        JMethodCall newCall = new JMethodCall(program, x.getSourceInfo(),
            instance, program.getNullMethod());
        ctx.replaceMe(newCall);
      }
    }
  }

  /**
   * Record "type flow" information. Variables receive type flow via assignment.
   * As a special case, Parameters also receive type flow based on the types of
   * arguments used when calling the containing method (think of this as a kind
   * of assignment). Method return types receive type flow from their contained
   * return statements, plus the return type of any methods that
   * override/implement them.
   * 
   * Note that we only have to run this pass ONCE to record the relationships,
   * because type tightening never changes any relationships, only the types of
   * the things related. In my original implementation, I had naively mapped
   * nodes onto sets of JReferenceType directly, which meant I had to rerun this
   * visitor each time.
   */
  public class RecordVisitor extends JVisitor {

    private JMethod currentMethod;

    @Override
    public void endVisit(JBinaryOperation x, Context ctx) {
      if (x.isAssignment() && (x.getType() instanceof JReferenceType)) {
        JExpression lhs = x.getLhs();
        if (lhs instanceof JVariableRef) {
          addAssignment(((JVariableRef) lhs).getTarget(), x.getRhs());
        }
      }
    }

    @Override
    public void endVisit(JClassType x, Context ctx) {
      if (program.typeOracle.isInstantiatedType(x)) {
        for (JClassType cur = x; cur != null; cur = cur.extnds) {
          addImplementor(cur, x);
          addInterfacesImplementorRecursive(cur, x);
        }
      }
    }

    @Override
    public void endVisit(JDeclarationStatement x, Context ctx) {
      JExpression initializer = x.getInitializer();
      if (initializer != null) {
        addAssignment(x.getVariableRef().getTarget(), initializer);
      }
    }

    @Override
    public void endVisit(JField x, Context ctx) {
      if (x.getLiteralInitializer() != null) {
        // TODO: do I still need this?
        addAssignment(x, x.getLiteralInitializer());
      }
      currentMethod = null;
    }

    @Override
    public void endVisit(JMethod x, Context ctx) {
      if (program.typeOracle.isInstantiatedType(x.getEnclosingType())) {
        for (JMethod method : program.typeOracle.getAllOverrides(x)) {
          addOverrider(method, x);
        }
      }
      currentMethod = null;
    }

    @Override
    public void endVisit(JMethodCall x, Context ctx) {
      // All of the params in the target method are considered to be assigned by
      // the arguments from the caller
      Iterator<JExpression> argIt = x.getArgs().iterator();
      ArrayList<JParameter> params = x.getTarget().params;
      for (int i = 0; i < params.size(); ++i) {
        JParameter param = params.get(i);
        JExpression arg = argIt.next();
        if (param.getType() instanceof JReferenceType) {
          addAssignment(param, arg);
        }
      }
    }

    @Override
    public void endVisit(JReturnStatement x, Context ctx) {
      if (currentMethod.getType() instanceof JReferenceType) {
        addReturn(currentMethod, x.getExpr());
      }
    }

    @Override
    public void endVisit(JsniFieldRef x, Context ctx) {
      if (x.isLvalue()) {
        // If this happens in JSNI, we can't make any type-tightening
        // assumptions. Fake an assignment-to-self to prevent tightening.
        addAssignment(x.getTarget(), x);
      }
    }

    @Override
    public void endVisit(JsniMethodRef x, Context ctx) {
      // If this happens in JSNI, we can't make any type-tightening assumptions
      // Fake an assignment-to-self on all args to prevent tightening
      JMethod method = x.getTarget();
      for (JParameter param : method.params) {
        addAssignment(param, new JParameterRef(program, null, param));
      }
    }

    @Override
    public void endVisit(JTryStatement x, Context ctx) {
      // Never tighten args to catch blocks
      // Fake an assignment-to-self to prevent tightening
      for (JLocalRef arg : x.getCatchArgs()) {
        addAssignment(arg.getTarget(), arg);
      }
    }

    /**
     * Merge param call args across overriders/implementors. We can't tighten a
     * param type in an overriding method if the declaring method is looser.
     */
    @Override
    public boolean visit(JMethod x, Context ctx) {
      currentMethod = x;

      if (!x.isStatic()) {
        /*
         * Add an assignment to each parameter from that same parameter in every
         * method this method overrides.
         */
        Set<JMethod> overrides = program.typeOracle.getAllOverrides(x);
        if (overrides.isEmpty()) {
          return true;
        }
        for (int j = 0, c = x.params.size(); j < c; ++j) {
          JParameter param = x.params.get(j);
          Set<JParameter> set = paramUpRefs.get(param);
          if (set == null) {
            set = new HashSet<JParameter>();
            paramUpRefs.put(param, set);
          }
          for (JMethod baseMethod : overrides) {
            JParameter baseParam = baseMethod.params.get(j);
            set.add(baseParam);
          }
        }
      } else if (program.isStaticImpl(x)) {
        /*
         * Special case: also add upRefs from a staticImpl's params to the
         * params of the instance method it is implementing. Most of the time,
         * this would happen naturally since the instance method delegates to
         * the static. However, in cases where the static has been inlined into
         * the instance method, future optimization could tighten an instance
         * call into a static call at some other call site, and fail to inline.
         * If we allowed a staticImpl param to be tighter than its instance
         * param, badness would ensue.
         */
        JMethod staticImplFor = program.staticImplFor(x);
        if (staticImplFor == null
            || !staticImplFor.getEnclosingType().methods.contains(staticImplFor)) {
          // The instance method has already been pruned.
          return true;
        }
        assert (x.params.size() == staticImplFor.params.size() + 1);
        for (int j = 0, c = x.params.size(); j < c; ++j) {
          JParameter param = x.params.get(j);
          Set<JParameter> set = paramUpRefs.get(param);
          if (set == null) {
            set = new HashSet<JParameter>();
            paramUpRefs.put(param, set);
          }
          if (j == 0) {
            // Fake an assignment-to-self to prevent tightening; consider this
            // an implicit assignment from a this reference of the looser type.
            assert (param.isThis());
            set.add(param);
          } else {
            JParameter baseParam = staticImplFor.params.get(j - 1);
            set.add(baseParam);
          }
        }
      }

      return true;
    }

    private void addAssignment(JVariable target, JExpression rhs) {
      add(target, rhs, assignments);
    }

    private void addImplementor(JReferenceType target, JClassType implementor) {
      add(target, implementor, implementors);
    }

    private void addInterfacesImplementorRecursive(JReferenceType target,
        JClassType implementor) {
      for (JInterfaceType implment : target.implments) {
        addImplementor(implment, implementor);
        addInterfacesImplementorRecursive(implment, implementor);
      }
    }

    private void addOverrider(JMethod target, JMethod overrider) {
      add(target, overrider, overriders);
    }

    private void addReturn(JMethod target, JExpression expr) {
      add(target, expr, returns);
    }
  }

  /**
   * Wherever possible, use the type flow information recorded by RecordVisitor
   * to change the declared type of a field, local, parameter, or method to a
   * more specific type.
   * 
   * Also optimize dynamic casts and instanceof operations where possible.
   */
  public class TightenTypesVisitor extends JModVisitor {

    /**
     * <code>true</code> if this visitor has changed the AST apart from calls
     * to Context.
     */
    private boolean myDidChange = false;

    public boolean didChange() {
      return myDidChange || super.didChange();
    }

    public void endVisit(JCastOperation x, Context ctx) {
      JType argType = x.getExpr().getType();
      if (!(x.getCastType() instanceof JReferenceType)
          || !(argType instanceof JReferenceType)) {
        return;
      }

      JReferenceType toType = (JReferenceType) x.getCastType();
      JReferenceType fromType = (JReferenceType) argType;

      boolean triviallyTrue = false;
      boolean triviallyFalse = false;

      JTypeOracle typeOracle = program.typeOracle;
      if (typeOracle.canTriviallyCast(fromType, toType)) {
        triviallyTrue = true;
      } else if (!typeOracle.isInstantiatedType(toType)) {
        triviallyFalse = true;
      } else if (!typeOracle.canTheoreticallyCast(fromType, toType)) {
        triviallyFalse = true;
      }

      if (triviallyTrue) {
        // remove the cast operation
        ctx.replaceMe(x.getExpr());
      } else if (triviallyFalse) {
        // replace with a magic NULL cast
        JCastOperation newOp = new JCastOperation(program, x.getSourceInfo(),
            program.getTypeNull(), x.getExpr());
        ctx.replaceMe(newOp);
      } else {
        // If possible, try to use a narrower cast
        JClassType concreteType = getSingleConcreteType(toType);
        if (concreteType != null) {
          JCastOperation newOp = new JCastOperation(program, x.getSourceInfo(),
              concreteType, x.getExpr());
          ctx.replaceMe(newOp);
        }
      }
    }

    @Override
    public void endVisit(JField x, Context ctx) {
      if (!x.isVolatile()) {
        tighten(x);
      }
    }

    @Override
    public void endVisit(JInstanceOf x, Context ctx) {
      JType argType = x.getExpr().getType();
      if (!(argType instanceof JReferenceType)) {
        // TODO: is this even possible? Replace with assert maybe.
        return;
      }

      JReferenceType toType = x.getTestType();
      JReferenceType fromType = (JReferenceType) argType;

      boolean triviallyTrue = false;
      boolean triviallyFalse = false;

      JTypeOracle typeOracle = program.typeOracle;
      if (fromType == program.getTypeNull()) {
        // null is never instanceof anything
        triviallyFalse = true;
      } else if (typeOracle.canTriviallyCast(fromType, toType)) {
        triviallyTrue = true;
      } else if (!typeOracle.isInstantiatedType(toType)) {
        triviallyFalse = true;
      } else if (!typeOracle.canTheoreticallyCast(fromType, toType)) {
        triviallyFalse = true;
      }

      if (triviallyTrue) {
        // replace with a simple null test
        JNullLiteral nullLit = program.getLiteralNull();
        JBinaryOperation neq = new JBinaryOperation(program, x.getSourceInfo(),
            program.getTypePrimitiveBoolean(), JBinaryOperator.NEQ,
            x.getExpr(), nullLit);
        ctx.replaceMe(neq);
      } else if (triviallyFalse) {
        // replace with a false literal
        ctx.replaceMe(program.getLiteralBoolean(false));
      } else {
        // If possible, try to use a narrower cast
        JClassType concreteType = getSingleConcreteType(toType);
        if (concreteType != null) {
          JInstanceOf newOp = new JInstanceOf(program, x.getSourceInfo(),
              concreteType, x.getExpr());
          ctx.replaceMe(newOp);
        }
      }
    }

    @Override
    public void endVisit(JLocal x, Context ctx) {
      tighten(x);
    }

    /**
     * Tighten based on return types and overrides.
     */
    @Override
    public void endVisit(JMethod x, Context ctx) {
      if (!(x.getType() instanceof JReferenceType)) {
        return;
      }
      JReferenceType refType = (JReferenceType) x.getType();

      if (refType == typeNull) {
        return;
      }

      // tighten based on non-instantiability
      if (!program.typeOracle.isInstantiatedType(refType)) {
        x.setType(typeNull);
        myDidChange = true;
        return;
      }

      JClassType concreteType = getSingleConcreteType(x.getType());
      if (concreteType != null) {
        x.setType(concreteType);
        myDidChange = true;
      }

      /*
       * The only information that we can infer about native methods is if they
       * are declared to return a leaf type.
       */
      if (x.isNative()) {
        return;
      }

      // tighten based on both returned types and possible overrides
      List<JReferenceType> typeList = new ArrayList<JReferenceType>();

      /*
       * Always assume at least one null assignment; if there really aren't any
       * other assignments, then this variable will get the null type. If there
       * are, it won't hurt anything because null type will always lose.
       */
      typeList.add(typeNull);

      Set<JExpression> myReturns = returns.get(x);
      if (myReturns != null) {
        for (JExpression expr : myReturns) {
          typeList.add((JReferenceType) expr.getType());
        }
      }
      Set<JMethod> myOverriders = overriders.get(x);
      if (myOverriders != null) {
        for (JMethod method : myOverriders) {
          typeList.add((JReferenceType) method.getType());
        }
      }

      JReferenceType resultType = program.generalizeTypes(typeList);
      resultType = program.strongerType(refType, resultType);
      if (refType != resultType) {
        x.setType(resultType);
        myDidChange = true;
      }
    }

    /**
     * Tighten the target method from the abstract base method to the final
     * implementation.
     */
    @Override
    public void endVisit(JMethodCall x, Context ctx) {
      JMethod target = x.getTarget();
      JMethod concreteMethod = getSingleConcreteMethod(target);
      if (concreteMethod != null) {
        JMethodCall newCall = new JMethodCall(program, x.getSourceInfo(),
            x.getInstance(), concreteMethod);
        newCall.getArgs().addAll(x.getArgs());
        ctx.replaceMe(newCall);
        target = concreteMethod;
        x = newCall;
      }

      if (x.canBePolymorphic()) {
        // See if we can remove virtualization from this call.
        JExpression instance = x.getInstance();
        assert (instance != null);
        JReferenceType instanceType = (JReferenceType) instance.getType();
        Set<JMethod> myOverriders = overriders.get(target);
        if (myOverriders != null) {
          for (JMethod override : myOverriders) {
            JReferenceType overrideType = override.getEnclosingType();
            if (program.typeOracle.canTheoreticallyCast(instanceType,
                overrideType)) {
              // This call is truly polymorphic.
              // TODO: composite types! :)
              return;
            }
          }
        }
        x.setCannotBePolymorphic();
        didChange = true;
      }
    }

    @Override
    public void endVisit(JParameter x, Context ctx) {
      tighten(x);
    }

    @Override
    public boolean visit(JClassType x, Context ctx) {
      // don't mess with classes used in code gen
      if (program.codeGenTypes.contains(x)) {
        return false;
      }
      return true;
    }

    public boolean visit(JMethod x, Context ctx) {
      /*
       * Explicitly NOT visiting native methods since we can't infer further
       * type information.
       */
      return !x.isNative();
    }

    /**
     * Find a replacement method. If the original method is abstract, this will
     * return the leaf, final implementation of the method. If the method is
     * already concrete, but enclosed by an abstract type, the overriding method
     * from the leaf concrete type will be returned.
     */
    private JMethod getSingleConcreteMethod(JMethod method) {
      if (getSingleConcreteType(method.getEnclosingType()) != null) {
        return getSingleConcrete(method, overriders);
      } else {
        return null;
      }
    }

    /**
     * Given an abstract type, return the single concrete implementation of that
     * type.
     */
    private JClassType getSingleConcreteType(JType type) {
      if (type instanceof JReferenceType) {
        JReferenceType refType = (JReferenceType) type;
        if (refType.isAbstract()) {
          JClassType singleConcrete = getSingleConcrete((JReferenceType) type,
              implementors);
          assert (singleConcrete == null || program.typeOracle.isInstantiatedType(singleConcrete));
          return singleConcrete;
        }
      }
      return null;
    }

    /**
     * Tighten based on assignment, and for parameters, callArgs as well.
     */
    private void tighten(JVariable x) {
      if (!(x.getType() instanceof JReferenceType)) {
        return;
      }
      JReferenceType refType = (JReferenceType) x.getType();

      if (refType == typeNull) {
        return;
      }

      // tighten based on non-instantiability
      if (!program.typeOracle.isInstantiatedType(refType)) {
        x.setType(typeNull);
        myDidChange = true;
        return;
      }

      // tighten based on leaf types
      JClassType leafType = getSingleConcreteType(refType);
      if (leafType != null) {
        x.setType(leafType);
        myDidChange = true;
        return;
      }

      // tighten based on assignment
      List<JReferenceType> typeList = new ArrayList<JReferenceType>();

      /*
       * For non-parameters, always assume at least one null assignment; if
       * there really aren't any other assignments, then this variable will get
       * the null type. If there are, it won't hurt anything because null type
       * will always lose.
       * 
       * For parameters, don't perform any tightening if we can't find any
       * actual assignments. The method should eventually get pruned.
       */
      if (!(x instanceof JParameter)) {
        typeList.add(typeNull);
      }

      Set<JExpression> myAssignments = assignments.get(x);
      if (myAssignments != null) {
        for (JExpression expr : myAssignments) {
          JType type = expr.getType();
          if (!(type instanceof JReferenceType)) {
            return; // something fishy is going on, just abort
          }
          typeList.add((JReferenceType) type);
        }
      }

      if (x instanceof JParameter) {
        Set<JParameter> myParams = paramUpRefs.get(x);
        if (myParams != null) {
          for (JParameter param : myParams) {
            typeList.add((JReferenceType) param.getType());
          }
        }
      }

      if (typeList.isEmpty()) {
        return;
      }

      JReferenceType resultType = program.generalizeTypes(typeList);
      resultType = program.strongerType(refType, resultType);
      if (refType != resultType) {
        x.setType(resultType);
        myDidChange = true;
      }
    }
  }

  public static boolean exec(JProgram program) {
    return new TypeTightener(program).execImpl();
  }

  private static <T, V> void add(T target, V value, Map<T, Set<V>> map) {
    Set<V> set = map.get(target);
    if (set == null) {
      set = new HashSet<V>();
      map.put(target, set);
    }
    set.add(value);
  }

  /**
   * Find exactly one concrete element for a key in a Map of Sets. If there are
   * none or more than one concrete element, return <code>null</code>.
   */
  private static <B, T extends CanBeAbstract> T getSingleConcrete(B x,
      Map<? super B, Set<T>> map) {

    Set<T> set = map.get(x);
    // No set, then no concrete version
    if (set == null) {
      return null;
    }

    T toReturn = null;
    for (T elt : set) {
      if (elt.isAbstract()) {
        continue;
      }

      // If we already have previously seen a concrete element, fail
      if (toReturn != null) {
        return null;
      } else {
        toReturn = elt;
      }
    }

    return toReturn;
  }

  private final Map<JVariable, Set<JExpression>> assignments = new IdentityHashMap<JVariable, Set<JExpression>>();
  private final Map<JReferenceType, Set<JClassType>> implementors = new IdentityHashMap<JReferenceType, Set<JClassType>>();
  private final Map<JMethod, Set<JMethod>> overriders = new IdentityHashMap<JMethod, Set<JMethod>>();
  private final Map<JParameter, Set<JParameter>> paramUpRefs = new IdentityHashMap<JParameter, Set<JParameter>>();
  private final JProgram program;
  private final Map<JMethod, Set<JExpression>> returns = new IdentityHashMap<JMethod, Set<JExpression>>();

  private final JNullType typeNull;

  private TypeTightener(JProgram program) {
    this.program = program;
    typeNull = program.getTypeNull();
  }

  private boolean execImpl() {
    RecordVisitor recorder = new RecordVisitor();
    recorder.accept(program);

    /*
     * We must iterate multiple times because each way point we tighten creates
     * more opportunities to do additional tightening for the things that depend
     * on it.
     */
    boolean madeChanges = false;
    while (true) {
      TightenTypesVisitor tightener = new TightenTypesVisitor();
      tightener.accept(program);
      if (!tightener.didChange()) {
        break;
      }
      madeChanges = true;

      FixDanglingRefsVisitor fixer = new FixDanglingRefsVisitor();
      fixer.accept(program);
    }
    return madeChanges;
  }
}
