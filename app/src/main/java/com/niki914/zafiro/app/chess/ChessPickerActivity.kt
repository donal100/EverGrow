package com.niki914.zafiro.app.chess

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Picker behind the chess widget.
 *
 * Lists only the daily games where it is the user's move (chess.com publishes
 * nothing but ids there, so each game's opponent/colour/last move comes from
 * the per-game callback), and on tap runs exactly the analysis the agent runs —
 * chess_pick.py in the :python worker — then repaints the widget. No LLM, no
 * screenshot, no typing.
 */
class ChessPickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        showMessage("Looking for your games\u2026")
        Thread { loadGames() }.start()
    }

    // ---------------------------------------------------------------- ui --

    private fun clear() = root.removeAllViews()

    private fun label(text: String, size: Float, color: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun showMessage(text: String) {
        ui.post {
            clear()
            root.addView(label(text, 15f, "#ECEAE6"))
            root.addView(label("Tap to close", 12f, "#8A8782"))
            root.setOnClickListener { finish() }
        }
    }

    private fun row(main: String, sub: String, onClick: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        box.addView(label(main, 16f, "#ECEAE6"))
        box.addView(label(sub, 12f, "#8A8782"))
        box.setOnClickListener { onClick() }
        root.addView(box)
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#332A2A2A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- games --

    private data class Game(
        val id: String,
        val opponent: String,
        val colour: String,
        val plies: Int,
        val last: String,
    )

    private fun loadGames() {
        val games: List<Game>? = try {
            idsToMove().mapNotNull { id -> runCatching { details(id) }.getOrNull() }
        } catch (t: Throwable) {
            null
        }
        if (games == null) {
            showMessage("Couldn't reach chess.com")
            return
        }
        if (games.isEmpty()) {
            showMessage("No games waiting on you")
            return
        }
        ui.post {
            clear()
            root.addView(label("Your move \u00b7 " + games.size, 13f, "#9FD08A"))
            for (g in games) {
                row(g.opponent + " \u00b7 you are " + g.colour, "ply " + g.plies + " \u00b7 last " + g.last) {
                    analyse(g.id)
                }
            }
            root.addView(label("Tap outside to close", 11f, "#8A8782").apply {
                setOnClickListener { finish() }
            })
        }
    }

    private fun idsToMove(): List<String> {
        val body = http("https://api.chess.com/pub/player/" + ME + "/games/to-move")
        val arr = JSONObject(body).optJSONArray("games") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val url = arr.optJSONObject(i)?.optString("url").orEmpty()
            val id = url.substringAfterLast("/")
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    private fun details(id: String): Game {
        val game = JSONObject(http("https://www.chess.com/callback/daily/game/" + id)).getJSONObject("game")
        val h = game.getJSONObject("pgnHeaders")
        val white = h.optString("White")
        val black = h.optString("Black")
        val meWhite = white.equals(ME, true)
        return Game(
            id = id,
            opponent = if (meWhite) black else white,
            colour = if (meWhite) "white" else "black",
            plies = game.optInt("plyCount"),
            last = squares(game.optString("lastMove")),
        )
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Evergrow)")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun squares(code: String): String =
        if (code.length < 2) "\u2014" else sqName(code[0]) + sqName(code[1])

    private fun sqName(ch: Char): String {
        val i = SQUARES.indexOf(ch)
        if (i < 0) return "?"
        return "" + ('a' + i % 8) + ('1' + i / 8)
    }

    // ----------------------------------------------------------- analysis --

    private fun analyse(id: String) {
        ui.post {
            clear()
            root.addView(label("Analysing\u2026", 15f, "#ECEAE6"))
            root.addView(label("game " + id + " \u00b7 tablebase or Stockfish, then the widget", 12f, "#8A8782"))
            root.setOnClickListener { finish() }
        }
        Thread {
            val outcome = runCatching { runAnalysis(id) }
            ui.post {
                val res = outcome.getOrNull()
                if (res == null) {
                    showMessage("Analysis failed: " + (outcome.exceptionOrNull()?.message ?: "unknown"))
                    return@post
                }
                if (!res.optBoolean("ok")) {
                    showMessage("Couldn't analyse: " + res.optString("error", "?"))
                    return@post
                }
                ChessWidgetProvider.refresh(this)
                Toast.makeText(
                    this,
                    res.optString("opponent") + " \u00b7 " + res.optString("source") + " " +
                            String.format("%+.2f", res.optDouble("eval", 0.0)),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }.start()
    }

    /** Runs filesDir/work/chess_pick.py in the :python worker and returns its JSON. */
    private fun runAnalysis(id: String): JSONObject {
        val work = File(filesDir, "work").absolutePath
        val pylibs = File(filesDir, "pylibs").absolutePath
        val code = buildString {
            append("import sys, json, io, contextlib\n")
            append("for p in ('").append(work).append("', '").append(pylibs).append("'):\n")
            append("    sys.path.insert(0, p)\n")
            append("import chess_pick\n")
            append("_sink = io.StringIO()\n")
            append("with contextlib.redirect_stdout(_sink):\n")
            append("    _res = chess_pick.main('analyse', '").append(id).append("')\n")
            append("print('RESULT' + json.dumps(_res, ensure_ascii=False))\n")
        }
        val out = runBlocking { PyRuntime.exec(code, ANALYSIS_TIMEOUT_MS) }.output
        val line = out.lineSequence().lastOrNull { it.startsWith("RESULT") }
            ?: throw IllegalStateException(out.trim().takeLast(200).ifBlank { "no output from the python worker" })
        return JSONObject(line.substring("RESULT".length))
    }

    private companion object {
        const val ME = "ConfirmableMotorcycle"
        const val ANALYSIS_TIMEOUT_MS = 120_000L
        const val SQUARES = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?"
    }
}
