package com.scalakata

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.{TimeoutException, Callable, FutureTask, TimeUnit}

import scala.util.control.NonFatal
import scala.concurrent.duration._

import scala.tools.nsc.interactive.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.io.VirtualDirectory
import scala.reflect.internal.util._
import scala.tools.nsc.interactive.Response

import scala.concurrent.duration._

class Compiler(artifacts: String, scalacOptions: Seq[String], security: Boolean, timeout: Duration) {
  def eval(request: EvalRequest): EvalResponse = {
    if (request.code.isEmpty) eval.empty
    else {
      try {
        withTimeout{eval(request.code)}(timeout).getOrElse(eval.empty.copy(timeout = true))
      } catch {
        case NonFatal(e) ⇒ {
          e.printStackTrace
          val pos =
            if(e.getCause != null) {
              e.getCause.getStackTrace().
                find(_.getFileName == "(inline)").
                map(_.getLineNumber)
              // None: not virtual, this is a runtime error in ScalaKata code
            } else {
              Some(e.getStackTrace()(0).getLineNumber)
            }

          eval.empty.copy(runtimeError =
            Some(RuntimeError(e.getCause.toString, pos))
          )
        }
      }
    }
  }

  def autocomplete(request: CompletionRequest): List[CompletionResponse] = {
    def completion(f: (compiler.Position, compiler.Response[List[compiler.Member]]) ⇒ Unit,
                   pos: compiler.Position):
                   List[CompletionResponse] = {

      withResponse[List[compiler.Member]](r ⇒ f(pos, r)).get match {
        case Left(members) ⇒ compiler.ask(() ⇒ {
          members.map(member ⇒
            CompletionResponse(
              name = member.sym.decodedName,
              signature = member.sym.signatureString
            )
          )
        })
        case Right(e) ⇒
          e.printStackTrace
          Nil
      }
    }
    def typeCompletion(pos: compiler.Position) = {
      completion(compiler.askTypeCompletion _, pos)
    }

    def scopeCompletion(pos: compiler.Position) = {
      completion(compiler.askScopeCompletion _, pos)
    }

    // inspired by scala-ide
    // https://github.com/scala-ide/scala-ide/blob/49ab209db4f54bd08824bf91d449df736b206f58/org.scala-ide.sdt.core/src/org/scalaide/core/completion/ScalaCompletions.scala#L179
    askTypeAt(request.code, request.position) { (tree, pos) ⇒ tree match {
      case compiler.New(name) ⇒ typeCompletion(name.pos)
      case compiler.Select(qualifier, _) if qualifier.pos.isDefined && qualifier.pos.isRange ⇒
        typeCompletion(qualifier.pos)
      case compiler.Import(expr, _) ⇒ typeCompletion(expr.pos)
      case compiler.Apply(fun, _) ⇒
        fun match {
          case compiler.Select(qualifier: compiler.New, _) ⇒ typeCompletion(qualifier.pos)
          case compiler.Select(qualifier, _) if qualifier.pos.isDefined && qualifier.pos.isRange ⇒
            typeCompletion(qualifier.pos)
          case _ ⇒ scopeCompletion(fun.pos)
        }
      case _ ⇒ scopeCompletion(pos)
    }}{
      pos ⇒ Some(scopeCompletion(pos))
    }.getOrElse(Nil)
  }

  def typeAt(request: TypeAtRequest): Option[TypeAtResponse] = {
    askTypeAt(request.code, request.position){(tree, _) ⇒ {
      // inspired by ensime
      // https://github.com/ensime/ensime-server/blob/dc0c682854d6210010069c062d9fb8cf3d7707b2/core/src/main/scala/org/ensime/core/RichPresentationCompiler.scala#L333
      val res =
        tree match {
          case compiler.Select(qual, name) ⇒ qual
          case t: compiler.ImplDef if t.impl != null ⇒ t.impl
          case t: compiler.ValOrDefDef if t.tpt != null ⇒ t.tpt
          case t: compiler.ValOrDefDef if t.rhs != null ⇒ t.rhs
          case t ⇒ t
        }
      TypeAtResponse(res.tpe.toString)
    }}{Function.const(None)}
  }

  private def askTypeAt[A]
    (code: String, position: RangePosition)
    (f: (compiler.Tree, compiler.Position) ⇒ A)
    (fb: compiler.Position ⇒ Option[A]): Option[A] = {

    if(code.isEmpty) None
    else {
      val file = reload(code)
      val rpos = compiler.rangePos(file, position.start, position.point, position.end)

      val response = withResponse[compiler.Tree](r ⇒
        compiler.askTypeAt(rpos, r)
      )

      response.get match {
        case Left(tree) ⇒ Some(f(tree, rpos))
        case Right(e) ⇒ e.printStackTrace; fb(rpos)
      }
    }
  }

  private def reload(code: String): BatchSourceFile = {
    val file = new BatchSourceFile("default", code)
    withResponse[Unit](r ⇒ compiler.askReload(List(file), r)).get
    file
  }

  private def withResponse[A](op: Response[A] ⇒ Any): Response[A] = {
    val response = new Response[A]
    op(response)
    response
  }

  private val reporter = new StoreReporter()
  private val settings = new Settings()

  settings.processArguments(scalacOptions.to[List], true)
  settings.bootclasspath.value = artifacts
  settings.classpath.value = artifacts
  settings.Yrangepos.value = true

  private lazy val compiler = new Global(settings, reporter)
  private lazy val eval = new Eval(settings.copy, security)

  private def withTimeout[T](f: ⇒ T)(timeout: Duration): Option[T]= {
    val task = new FutureTask(new Callable[T]() {
      def call = f
    })
    val thread = new Thread( task )
    try {
      thread.start()
      Some(task.get(timeout.toMillis, TimeUnit.MILLISECONDS))
    } catch {
      case e: TimeoutException ⇒ None
    } finally {
      if( thread.isAlive ){
        thread.interrupt()
      }
    }
  }
}