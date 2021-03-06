package thera

/**
 * A template is a mixture of text, variables and calls to other templates.
 * The variables and names of other templates are resolved against a runtime
 * Context.
 *
 * An evaluation of a Template is a process of resolving all its
 * variables and calling all the functions in it. If a template
 * does not have any arguments, it is evaluated to Str. If it does,
 * it is evaluated to a Function. It is then possible to pass the
 * arguments to that Function and thus finish the evaluation of the
 * Template.
 *
 * A Template is evaluated against a context which is a ValueHierarchy.
 * The context contains the variables and functions that the Template
 * refers to. The rules for evaluating a template against a context `ctxInit`
 * are as follows:
 *
 * - The variables predefined in the template form a context `templateContext`.
 *   The variable references in the template are resolved against a
 *   final context `ctx` = `ctxInit + templateContext`. @see `ValueHierarchy.+`.
 * - The template body consists of a combination of plain text, variable references
 *   and function calls.
 * - Variable references
 *   - Are resolved against `ctx` to Str.
 *   - If they are arguments to  a function call,
 *     they may resolve to any other Value.
 *   - An unresolved variable reference is an error.
 *   - If the unresolved variable is an argument to the Template,
 *     the Variable stays unresolved without an error.
 * - A function call is resolved as follows:
 *   - The name is resolved to a Function against `ctx`
 *   - The arguments, each of which can be either a List[Node]
 *     or a Lambda, are resolved as specified in these rules, recursively.
 *   - The function is called with the resolved arguments and the
 *     resulting Str is substituted in place of the function call.
 * - If the template does not have any arguments, its body must consist
 *   only of Str nodes after resolution is done. These nodes are concatenated
 *   to form a single result Str node.
 * - If the template has arguments, the result of the template
 *   evaluation is a Function that takes the corresponding arguments and computes
 *   the resulting Str.
 *
 * @param argNames – the names of the arguments to this template.
 *                   Upon evaluation, the argument values are bound to
 *                   the given names.
 * @param templateContext – constant variables defined in the template.
 * @param body – the body of the template. Can refer to the variables and
 *               templates defined in predefinedVars and bound to argNames.
 */
case class Template(argNames: List[String], context: ValueHierarchy, body: Body) {
  def mkString(implicit ctx: ValueHierarchy =
    ValueHierarchy.empty): String =
    evaluate(ctx) match {
      case Right(str) => str
      case Left(_) => throw new RuntimeException(
        s"Expected string, found function")
    }

  def mkFunction(implicit ctx: ValueHierarchy =
    ValueHierarchy.empty): List[Value] => String =
    evaluate(ctx) match {
      case Left(f) => f
      case Right(_) => throw new RuntimeException(
        s"Expected function, found string")
    }

  def mkValue(implicit ctx: ValueHierarchy =
    ValueHierarchy.empty): Value =
    evaluate(ctx) match {
      case Left(f) => Function { args => Str(f(args)) }
      case Right(str) => Str(str)
    }


  private def evaluate(ctxInit: ValueHierarchy =
      ValueHierarchy.empty): Either[List[Value] => String, String] = {
    var ctx = predef.context + ctxInit + this.context
    if (this.argNames.nonEmpty) Left( { argValues =>
      ctx = ctx + ValueHierarchy.names(this.argNames.zip(argValues).toMap)
      evaluateBody(this.body)(ctx)
    })
    else Right(evaluateBody(this.body)(ctx))
  }

  private def evaluateBody(body: Body)(implicit ctx: ValueHierarchy): String = {
    val evaluatedValues: List[Value] = body.nodes.map(evaluateNode(_, inFunctionCall = false))
    nodesToString(evaluatedValues)
  }

  private def evaluateNode(node: Node, inFunctionCall: Boolean)(
      implicit ctx: ValueHierarchy): Value = node match {
    case Text(str) => Str(str)
    case Variable(path) => ctx(path) match {
      case x: Str => x
      case x if !inFunctionCall => throw new RuntimeException(
        s"Variables outside function calls can only resolve to text. " +
        s"Variable ${path.mkString(".")} was resolved to $x")
      case x => x
    }
    case Call(path, argsNodes) =>
      val f: Function = ctx(path) match {
        case x: Function => x
        case x => throw new RuntimeException(
          s"Expected function at path ${path.mkString(".")}, got: $x")
      }
      val args: List[Value] = argsNodes.map {
        case Lambda(argNames, body) =>
          Function { argValues =>
            val ctx2 = ctx + ValueHierarchy.names(argNames.zip(argValues).toMap)
            Str(evaluateBody(body)(ctx2))
          }
        case b: Body =>
          b.nodes.map(evaluateNode(_, inFunctionCall = true)) match {
            case x :: Nil => x
            case xs => Str(nodesToString(xs))
          }
      }
      f(args)
  }

  private def nodesToString(ns: List[Value]): String =
    ns.foldLeft("") {
      case (accum, Str(str)) => accum + str
      case (_, x) => throw new RuntimeException(
        s"Error evaluating template: non-text value $x encountered. " +
        s"Full evaluation:\n$ns")
    }
}

sealed trait Node
case class Text(value: String) extends Node
/**
 * A call to a template located at a given path and with provided arguments.
 *
 * @param path – the path to the template to be called.
 * @param args – these nodes will be bound to the argument names of the
 *               template being called.
 */
case class Call(path: List[String], args: List[CallArg]) extends Node
case class Variable(path: List[String]) extends Node

sealed trait CallArg
case class Body(nodes: List[Node]) extends CallArg
case class Lambda(argNames: List[String], body: Body) extends CallArg
