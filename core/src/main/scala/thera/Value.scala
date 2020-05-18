package thera

import scala.jdk.CollectionConverters._
import com.amihaiemil.eoyaml._

/**
 * A value is data you can refer to in a template.
 */
sealed trait Value

case class Str(value: String) extends Value {
  def +(that: Str) = Str(value + that.value)
}

object Str {
  def empty = Str("")
}

case class Arr(value: List[Value]) extends Value
case class Function(f: List[Value] => Str) extends Value with Function1[List[Value], Value] {
  def apply(x: List[Value]): Str = f(x)
}

object Function {
  def function[R1 <: Value](f: (R1) => Str) = Function {
    case (r1: R1 @unchecked) :: Nil => f(r1)
    case x => throw new RuntimeException(s"Argument list $x is inapplicable to 1-ary function")
  }

  def function[R1 <: Value, R2 <: Value](f: (R1, R2) => Str) = Function {
    case (r1: R1 @unchecked) :: (r2: R2 @unchecked) :: Nil => f(r1, r2)
    case x => throw new RuntimeException(s"Argument list $x is inapplicable to 2-ary function")
  }

  def function[R1 <: Value, R2 <: Value, R3 <: Value](f: (R1, R2, R3) => Str) = Function {
    case (r1: R1 @unchecked) :: (r2: R2 @unchecked) :: (r3: R3 @unchecked) :: Nil => f(r1, r2, r3)
    case x => throw new RuntimeException(s"Argument list $x is inapplicable to 3-ary function")
  }
}

/**
 * A tree of values. The values are indexable by their path.
 */
trait ValueHierarchy extends Value {
  protected def resolvePath(path: List[String]): Value

  /**
   * Looks up a value by name in this value hierarchy. Note that
   * this value hierarchy may contain other value hierarchies.
   * Given a path p = n1.n2.<...>.nx, the resolution goes according to the
   * following rules:
   *
   * - Empty path never resolves to a Value
   * - If `p` resolves to a Value, return that value
   * - Otherwise, try to find a longest subpath `sp` of `p`, starting
   *   from `n1`, such that
   *   - `sp` resolves to a ValueHierarchy `h`, and
   *   - `p - sp` (that is, `p` with `sp` dropped), resolves to a Value in `h`

   * @return a resolved Value or null if the value does not exist.
   */
  final def get(path: List[String]): Value =
    if (path.isEmpty) null
    else resolvePath(path) match {
      case null =>
        @annotation.tailrec def resolveFromIntermediaryValueHierarchy(subpathLength: Int): Value = {
          val (subpath, leftover) = (path.take(subpathLength), path.drop(subpathLength))
          if (subpath.isEmpty) null
          else resolvePath(subpath) match {
            case ctx: ValueHierarchy => ctx.get(leftover) match {
              case null => resolveFromIntermediaryValueHierarchy(subpathLength - 1)
              case x => x
            }
            case _ => resolveFromIntermediaryValueHierarchy(subpathLength - 1)
          }
        }
        resolveFromIntermediaryValueHierarchy(path.length)
      case x => x
    }

  final def apply(path: List[String]): Value = get(path) match {
    case null => throw new RuntimeException(s"Value not found: ${path.mkString(".")}")
    case x => x
  }

  /**
   * Creates a combined ValueHierarchy out of `this` and `other` ValueHierarchy.
   * In case of a name conflict, `other` has precedence during value resolution.
   * A name conflict is defined as a path that resolves to a Value in both hierarchies.
   */
  def +(other: ValueHierarchy): ValueHierarchy = ValueHierarchy { name =>
    other.get(name) match {
      case null => this.get(name)
      case x => x
    }
  }

  override def toString = "<valuehierarchy>"
}

object ValueHierarchy {
  def apply(f: List[String] => Value): ValueHierarchy = new ValueHierarchy {
    protected def resolvePath(name: List[String]): Value = f(name)
  }

  def map(m: Map[List[String], Value]) = ValueHierarchy { path =>
    m.get(path).orNull
  }

  def names(m: Map[String, Value]): ValueHierarchy =
    map(m.map { case (k, v) => List(k) -> v })

  def names(ns: (String, Value)*): ValueHierarchy =
    names(ns.toMap)

  def yaml(src: String): ValueHierarchy = {
    def valueFromNode(node: YamlNode): Value = {
      @annotation.tailrec
      def searchInMapping(path: List[String], m: YamlMapping): Value =
        path match {
          case Nil => null
          case name :: Nil => valueFromNode(m.value(name))
          case name :: rest => m.value(name) match {
            case x: YamlMapping => searchInMapping(rest, x)
            case _ => null
          }
        }

      node match {
        case x: Scalar => Str(x.value)
        case x: YamlSequence => Arr(x.values.asScala.toList.map(valueFromNode))
        case x: YamlMapping => ValueHierarchy { path => searchInMapping(path, x) }
        case null => null
      }
    }

    val mapping = Yaml.createYamlInput(src).readYamlMapping()
    valueFromNode(mapping).asInstanceOf[ValueHierarchy]
  }

  def empty: ValueHierarchy = ValueHierarchy { _ => null }
}
