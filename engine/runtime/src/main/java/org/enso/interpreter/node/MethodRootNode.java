package org.enso.interpreter.node;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;
import java.util.function.Supplier;
import org.enso.compiler.context.LocalScope;
import org.enso.compiler.core.CompilerError;
import org.enso.interpreter.EnsoLanguage;
import org.enso.interpreter.runtime.EnsoContext;
import org.enso.interpreter.runtime.callable.function.Function;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.data.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.error.PanicException;
import org.enso.interpreter.runtime.scope.ModuleScope;

@ReportPolymorphism
@NodeInfo(shortName = "Method", description = "A root node for Enso methods.")
public class MethodRootNode extends ClosureRootNode {
  private static final ExpressionNode[] NO_STATEMENTS = new ExpressionNode[0];

  private final Type type;
  private final String methodName;

  private MethodRootNode(
      EnsoLanguage language,
      LocalScope localScope,
      ModuleScope moduleScope,
      ExpressionNode body,
      SourceSection section,
      Type type,
      String methodName) {
    super(
        language,
        localScope,
        moduleScope,
        body,
        section,
        shortName(type.getName(), methodName),
        null,
        false);
    this.type = type;
    this.methodName = methodName;
  }

  private static String shortName(String atomName, String methodName) {
    return atomName + "." + methodName;
  }

  /**
   * Creates an instance of this node.
   *
   * @param language the language identifier
   * @param localScope a description of the local scope
   * @param moduleScope a description of the module scope
   * @param body the program provider to be executed
   * @param section a mapping from {@code provider} to the program source
   * @param type the type this method is defined for
   * @param methodName the name of this method
   * @return a node representing the specified closure
   */
  public static MethodRootNode build(
      EnsoLanguage language,
      LocalScope localScope,
      ModuleScope moduleScope,
      Supplier<ExpressionNode> body,
      SourceSection section,
      Type type,
      String methodName) {
    return build(
        language, localScope, moduleScope, new LazyBodyNode(body), section, type, methodName);
  }

  public static MethodRootNode build(
      EnsoLanguage language,
      LocalScope localScope,
      ModuleScope moduleScope,
      ExpressionNode body,
      SourceSection section,
      Type type,
      String methodName) {
    return new MethodRootNode(language, localScope, moduleScope, body, section, type, methodName);
  }

  /**
   * Builds root node for {@link AtomConstructor#getConstructorFunction() constructor function} of
   * the provided {@code constructor}.
   *
   * @param language the language identifier
   * @param localScope a description of the local scope
   * @param moduleScope a description of the module scope
   * @param body the program provider to be executed
   * @param section a mapping from {@code provider} to the program source
   * @param constructor constructor specifying type and name
   * @return a node representing the specified closure
   * @see #constructorFor(Function)
   */
  public static MethodRootNode buildConstructor(
      EnsoLanguage language,
      LocalScope localScope,
      ModuleScope moduleScope,
      ExpressionNode body,
      SourceSection section,
      AtomConstructor constructor) {
    return new Constructor(language, localScope, moduleScope, body, section, constructor);
  }

  /**
   * Finds constructor for given {@link AtomConstructor#getConstructorFunction() constructor
   * function}.
   *
   * @param fn the function
   * @return constructor or {@code null}
   */
  public static AtomConstructor constructorFor(Function fn) {
    if (fn.getCallTarget().getRootNode() instanceof MethodRootNode node) {
      return node instanceof Constructor consNode ? consNode.constructor : null;
    } else {
      return null;
    }
  }

  public static MethodRootNode buildOperator(
      EnsoLanguage language,
      LocalScope localScope,
      ModuleScope moduleScope,
      Supplier<ExpressionNode> readLeft,
      Supplier<ExpressionNode> readRight,
      Supplier<ExpressionNode> body,
      SourceSection section,
      Type type,
      String methodName) {
    Supplier<ExpressionNode> supplyWholeBody =
        () -> {
          var readLeftNode = readLeft.get();
          var readRightNode = readRight.get();
          var exprNode = body.get();
          var operatorNode = new BinaryOperatorNode(readLeftNode, readRightNode, exprNode);
          return operatorNode;
        };
    return build(language, localScope, moduleScope, supplyWholeBody, section, type, methodName);
  }

  /**
   * Computes the fully qualified name of this method.
   *
   * <p>The name has a form of [method's module]::[qualified type name]::[method name].
   *
   * @return the qualified name of this method.
   */
  @Override
  public String getQualifiedName() {
    return getModuleScope().getModule().getName().toString()
        + "::"
        + type.getQualifiedName().toString()
        + "::"
        + methodName;
  }

  /**
   * @return the constructor this method was defined for
   */
  public Type getType() {
    return type;
  }

  /**
   * @return the method name
   */
  public String getMethodName() {
    return methodName;
  }

  @Override
  public Node deepCopy() {
    LazyBodyNode.replaceLazyNode(getBody());
    return super.deepCopy();
  }

  @Override
  public Node copy() {
    LazyBodyNode.replaceLazyNode(getBody());
    return super.copy();
  }

  private static class LazyBodyNode extends ExpressionNode {
    private final Supplier<ExpressionNode> provider;

    LazyBodyNode(Supplier<ExpressionNode> body) {
      this.provider = body;
    }

    static void replaceLazyNode(Node n) {
      if (n instanceof LazyBodyNode lazy) {
        lazy.replaceItself();
      }
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
      ExpressionNode newNode = replaceItself();
      return newNode.executeGeneric(frame);
    }

    @CompilerDirectives.TruffleBoundary
    final ExpressionNode replaceItself() {
      try {
        ExpressionNode newNode = replace(provider.get());
        notifyInserted(newNode);
        return newNode;
      } catch (CompilerError abnormalException) {
        var ctx = EnsoContext.get(this);
        var msg = Text.create(abnormalException.getMessage());
        var load = ctx.getBuiltins().error().makeCompileError(msg);
        throw new PanicException(load, this);
      }
    }
  }

  @Override
  public boolean isSubjectToInstrumentation() {
    return true;
  }

  private static final class Constructor extends MethodRootNode {
    private final AtomConstructor constructor;

    Constructor(
        EnsoLanguage language,
        LocalScope localScope,
        ModuleScope moduleScope,
        ExpressionNode body,
        SourceSection section,
        AtomConstructor constructor) {
      super(
          language,
          localScope,
          moduleScope,
          body,
          section,
          constructor.getType(),
          constructor.getName());
      this.constructor = constructor;
    }
  }
}
