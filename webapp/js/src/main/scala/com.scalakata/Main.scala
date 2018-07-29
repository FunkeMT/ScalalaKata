package com.scalakata

import org.denigma.codemirror._
import org.scalajs.dom
import org.scalajs.dom.raw.HTMLTextAreaElement
import org.scalajs.dom.ext.Ajax
import scalajs.concurrent.JSExecutionContext.Implicits.queue
import scalajs.js
import scalajs.js.annotation.JSExport
import scalatags.JsDom.all._

@JSExport
object Main {
  @JSExport
  def main(): Unit = {
    val isMac = dom.window.navigator.userAgent.contains("Mac")

    dom.document.body.className =
      if (isMac) "mac"
      else "pc"

    val ctrl = if(isMac) "Cmd" else "Ctrl"
    val ctrlS = if(isMac) "⌘" else "Ctrl"

    val params = EditorConfig.
      mode("simplemode").
      autofocus(true).
      lineNumbers(false).
      lineWrapping(false).
      tabSize(2).
      indentWithTabs(false).
      theme("solarized dark").
      smartIndent(true).
      keyMap("sublime").
      scrollPastEnd(true).
      scrollbarStyle("simple").
      extraKeys(js.Dictionary(
        "Tab"          -> "specialTab",
        s"$ctrl-l"     -> null,
        s"$ctrl-Space" -> "autocomplete",
         "."           -> "autocompleteDot",
        s"$ctrl-."     -> "typeAt",
        s"$ctrl-Enter" -> "run",
        // s"$ctrl-,"     -> "config", // TODO: edit configs
        "F1"           -> "help",
        "F2"           -> "solarizedToggle",
        "F7"           -> "share"
      )).
      autoCloseBrackets(true).
      matchBrackets(true).
      showCursorWhenSelecting(true).
      autofocus(true).
      highlightSelectionMatches(js.Dictionary(
        "showToken" -> js.Dynamic.global.RegExp("\\w")
      ))

    val themeButton = dom.document.getElementById("theme")
    val stateButton = dom.document.getElementById("state")
    val shareButton = dom.document.getElementById("share")
    val stopButton = dom.document.getElementById("stop")

    CodeMirror.commands.run = Rendering.runDSL _
    //CodeMirror.commands.typeAt = Hint.typeAt _
    CodeMirror.commands.autocomplete = Hint.autocompleteDsl _
    //CodeMirror.commands.autocompleteDot = Hint.autocompleteDot _
    CodeMirror.commands.help = (editor: Editor) ⇒ {
      editor.getDoc().setValue(Util.wrap("help"))
      Rendering.run(editor)
    }
    CodeMirror.commands.specialTab = (editor: Editor) ⇒ {
      if (editor.somethingSelected()) editor.indentSelection("add");
      else editor.execCommand("insertSoftTab");
    }
    CodeMirror.commands.solarizedToggle = (editor: Editor) ⇒ {
      val isDark = editor.getOption("theme").asInstanceOf[String] == "solarized dark"
      val theme =
        if(isDark) "solarized light"
        else "solarized dark"

      val icon =
        if(isDark) "moon"
        else "sun"
      themeButton.setAttribute("data-glyph", icon)
      editor.setOption("theme", theme)
    }
    CodeMirror.commands.share = (editor: Editor) ⇒ {
      GitHub.share(editor.getDoc().getValue())
        .recover { case t ⇒ s"Failed to share your code: ${t.getMessage}" }
        .foreach { gistId ⇒
          val sharedDiv = dom.document.getElementById("shared")
          sharedDiv.setAttribute("style", "display: block")

          val loc = dom.window.location
          val baseUrl = loc.protocol + "//" + loc.host
          val scalaKataLink = s"$baseUrl/gist/$gistId"
          val gitHubLink = s"https://gist.github.com/anonymous/$gistId"

          val close = i(`class` := "oi", "data-glyph".attr := "circle-x").render

          close.addEventListener("click", (e: dom.Event) ⇒
            sharedDiv.setAttribute("style", "display: none")
          )

          while (sharedDiv.firstChild != null) {
            sharedDiv.removeChild(sharedDiv.firstChild)
          }
          sharedDiv.appendChild(div(
            "Shared as:", a(href := scalaKataLink, target := "_blank")(scalaKataLink),
            "(", a(href := gitHubLink, target := "_blank")("GitHub"), ")",
            close
          ).render)
        }
    }

    dom.document.getElementById("scalakata") match {
      case el:HTMLTextAreaElement ⇒ {
        val editor = CodeMirror.fromTextArea(el, params)
        val doc = editor.getDoc()
        editor.focus()
        Rendering.resetCursor(doc)
        themeButton.addEventListener("click", (e: dom.Event) ⇒ CodeMirror.commands.solarizedToggle(editor))
        shareButton.addEventListener("click", (e: dom.Event) ⇒ CodeMirror.commands.share(editor))
        dom.document.getElementById("help").addEventListener("click", (e: dom.Event) ⇒ CodeMirror.commands.help(editor))
        stateButton.setAttribute("title", s"run ($ctrlS + Enter)")
        stateButton.addEventListener("click", (e: dom.Event) ⇒ {
          if(Rendering.toclear) {
            Rendering.clear(doc)
            Rendering.toclear = false
          } else {
            // Resume AudioContext
            // see: https://goo.gl/7K7WLu
            Scalala.audioContext.resume()

            // Call ScalalaDSL Evaluation
            Rendering.runDSL(editor)
          }
        })
        stopButton.addEventListener("click", (e: dom.Event) => Scalala.stop())

        val path = dom.window.location.pathname
        if(path != "/") {
          if(path.startsWith("/room/")) {
            Collaborative(editor)
          } else if(path.startsWith("/gist/")) {
            val gistId = dom.window.location.pathname.drop("/gist/".length)
            GitHub.fetch(gistId)
              .recover { case _ ⇒ Util.wrap(s"// Failed to load gist") }
              .foreach { content ⇒
                doc.setValue(content)
                Rendering.run(editor)
              }
          } else {
            Ajax.get(s"/assets/$path").onSuccess{ case xhr ⇒
              doc.setValue(xhr.responseText)
              Rendering.run(editor)
            }
          }
        } else {
          val storage = dom.window.localStorage.getItem(Rendering.localStorageKey)
          if(storage != null) {
            doc.setValue(storage)
            Rendering.run(editor)
          }
          else {
            doc.setValue(
              """musician piano_1
                |  instrument Piano
                |  plays -a,e,d,d/2,c/2,d,d/2,e/2,d/4,c/4,-a/4,-g/4
                |
                |musician piano_2
                |  instrument Piano
                |  plays chord(--a,--c.sharp,-e)
                |
                |musician piano_3
                |  instrument Piano
                |  plays --f
                |
                |musician piano_4
                |  instrument Piano
                |  plays -a,c,+a.dot
                |
                |
                |play with tempo 70
                |  piano_1 at 1,
                |  piano_1 at 105,
                |  piano_2 at 1,
                |  piano_3 at 105,
                |  piano_4 at 224
              """.stripMargin)
            //CodeMirror.commands.help(editor)
            ()
          }
        }
      }
      case _ ⇒ dom.console.error("cannot find text area for the code!")
    }

    Scalala.loadSoundfonts()
  }
}
