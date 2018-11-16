package thera

import fastparse._, NoWhitespace._, Parsed.{ Failure, Success }
import io.circe.{ Json, yaml }

import ast._

object parser extends HeaderParser with BodyParser with BodyUtilParser with UtilParser {
  val t = token
  def module[_: P]: P[Function] = (header.? ~ node() ~ End).map {
    case (Some((args, h)), t) => Function(args, h         , t)
    case (None           , t) => Function(Nil , Json.obj(), t)
  }
}

trait HeaderParser { this: parser.type =>
  def header[_: P]: P[(List[String], Json)] =
    (wsnl(t.tripleDash) ~/ moduleArgs.? ~/ lines ~ wsnl(t.tripleDash)).flatMap {
      case (args, Nil  ) => Pass(args.getOrElse(Nil) -> Json.obj())
      case (args, lines) =>
        yaml.parser.parse(lines.mkString("\n")).fold(
          error => Fail
        , json  => Pass(args.getOrElse(Nil) -> json))
    }

  def lines[_: P]: P[Seq[String]] = t.line.!.rep(min = 0, sep = t.nl)

  def moduleArgs[_: P]: P[List[String]] =
    (wsnl("[") ~/ t.name.!.rep(sep = wsnl(",")) ~ wsnl("]")).map(_.toList)
}

trait BodyParser { this: parser.type =>
  def node[_: P](specialChars: String = ""): P[Node] =
    leaf((specialChars ++ t.defaultSpecialChars).distinct).rep(1).map(_.toList).map {
      case n :: Nil => n
      case ns       => Leafs(ns)
    }

  def leaf[_: P](specialChars: String): P[Leaf] =
    expr | text(specialChars)
  
  def text[_: P](specialChars: String): P[Text] =
    textOne(specialChars).rep(1).map { texts => texts.foldLeft(Text("")) { (accum, t) =>
      Text(accum.value + t.value) } }

  def expr[_: P]: P[Leaf] = "${" ~/ exprBody ~ "}"

  def exprBody[_: P]: P[Leaf] = call | variable

  def function[_: P]: P[Function] = ("${" ~ args ~ wsnl("=>") ~/ wsnl0Esc ~ node() ~ "}")
    .map { case (args, body) => Function(args, Json.obj(), body) }

  def call[_: P]: P[Call] = (wsnl(path) ~ ":" ~/ wsnl0Esc ~
    (function | node(",")).rep(min = 0, sep = "," ~ wsnl0Esc))
      .map { case (path, args) => Call(path, args.toList) }

  def variable[_: P]: P[Variable] = wsnl(path).map(Variable(_))
}

trait BodyUtilParser { this: parser.type =>
  def textOne[_: P](specialChars: String): P[Text] = (
    oneOf(specialChars.toList.map {c => () => LiteralStr(s"\\$c").!.map(_.tail.head.toString)})
  | CharsWhile(c => !specialChars.contains(c)).! ).map(Text)

  def path[_: P]: P[List[String]] = t.name.!.rep(min = 1, sep = wsnl(".")).map(_.toList)

  def arg [_: P]: P[     String ] = wsnl(t.name.!)
  def args[_: P]: P[List[String]] = arg.rep(min = 1, sep = ",").map(_.toList)
}

trait UtilParser { this: parser.type =>
  def ws[_: P, A](that: => P[A]): P[A] = t.ws0 ~ that ~ t.ws0
  def oneOf[_: P, A](that: Seq[() => P[A]]): P[A] =
    that.foldLeft(Fail: P[A]) { (accum, next) => accum | next() }

  def wsnl[_: P, A](that: => P[A]): P[A] = t.wsnl0 ~ that ~ t.wsnl0

  def wsnl0Esc[_: P] = t.wsnl0 ~ ("\\" ~ &(t.wsnl1)).? 
}

object token {
  def tripleDash[_: P] = P("---")

  def line[_: P] = !tripleDash ~ CharsWhile(_ != '\n')

  def nl1[_: P] = "\n"
  def nl [_: P] = nl1.rep(1)
  def nl0[_: P] = nl1.rep(0)

  def ws1[_: P] = CharIn(" \t")
  def ws [_: P] = ws1.rep(1)
  def ws0[_: P] = ws1.rep(0)

  def wsnl1[_: P] = ws1 | nl1
  def wsnl [_: P] = wsnl1.rep(1)
  def wsnl0[_: P] = wsnl1.rep(0)

  def name[_: P] = CharIn("a-zA-Z0-9\\-_").rep(1)

  val defaultSpecialChars = "${}\\"
}

object ParserTest extends App {
  import parser._
  import better.files._, better.files.File._, java.io.{ File => JFile }

  val toParse = List("html-template")

  toParse.map(name => file"example/$name.html").foreach { file =>
    println(s"=== Parsing $file ===")
    parse(file.contentAsString, module(_)) match {
      case Success(result, pos) => println(result)
      case f: Failure => println(f)
    }
    println()
  }
//   println(parse("""
// ---
// ---""".tail, header(_)))
}