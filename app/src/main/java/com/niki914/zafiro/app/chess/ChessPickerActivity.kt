package com.niki914.zafiro.app.chess

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything interactive about the chess widget lives here.
 *
 * The list shows only the daily games where it is the user's move (chess.com publishes
 * nothing but ids there, so each game's opponent/colour comes from the per-game callback).
 * Tapping a game opens a *simulation* of that position: tap a piece, tap a destination,
 * and Python rates the move (win odds before/after, best/excellent/…/blunder) while the
 * engine answers as the opponent. Nothing is ever sent to chess.com.
 *
 * "↻ Widget" runs the full analysis and repaints the home-screen widget.
 */
class ChessPickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    private var boardView: ChessBoardView? = null
    private var status: TextView? = null
    private var simMe = "white"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        setContentView(root)
        try {
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        } catch (t: Throwable) {
            // dialog window tweak is cosmetic
        }
        showMessage("Looking for your games\u2026")
        Thread { loadGames() }.start()
    }

    // ------------------------------------------------------------------ ui --

    private fun clear() = root.removeAllViews()

    private fun label(text: String, size: Float, color: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun button(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#ECEAE6"))
            setPadding(dp(10), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }

    private fun showMessage(text: String) {
        boardView = null
        status = null
        ui.post {
            clear()
            root.addView(label(text, 15f, "#ECEAE6"))
            root.addView(label("Tap to close", 12f, "#8A8782"))
            root.setOnClickListener { finish() }
        }
    }

    private fun setStatus(text: String) {
        status?.text = text
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

    private fun fmtOdds(v: Double): String =
        if (v.isNaN()) "\u2014" else v.roundToInt().toString() + "%"

    // --------------------------------------------------------------- games --

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
            boardView = null
            status = null
            root.addView(label("Your move \u00b7 " + games.size, 13f, "#9FD08A"))
            for (g in games) {
                row(g.opponent + " \u00b7 you are " + g.colour, "ply " + g.plies + " \u00b7 last " + g.last) {
                    openSim(g)
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

    // ----------------------------------------------------------- simulation --

    private fun openSim(game: Game) {
        ui.post {
            clear()
            boardView = null
            status = null
            root.addView(label("Opening " + game.opponent + "\u2026", 15f, "#ECEAE6"))
            root.addView(label("pulling the position from chess.com", 12f, "#8A8782"))
        }
        Thread {
            val outcome = runCatching { py("chess_pick.sim_start('" + game.id + "')") }
            ui.post {
                val st = outcome.getOrNull()
                if (st == null || !st.optBoolean("ok")) {
                    showMessage("Couldn't open the game: " +
                            (st?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                showSim(st, game)
            }
        }.start()
    }

    private fun showSim(state: JSONObject, game: Game) {
        clear()
        simMe = state.optString("me", "white")
        val flip = simMe == "black"

        val bv = ChessBoardView(this).apply {
            this.flip = flip
            turn = state.optString("turn", simMe)
            board = BoardPainter.parseFen(state.optString("fen"))
            onMove = { from, to -> playMove(from, to, this) }
        }
        boardView = bv

        val side = min(
            resources.displayMetrics.widthPixels - dp(28),
            (resources.displayMetrics.heightPixels * 0.56f).toInt(),
        )
        root.addView(bv, LinearLayout.LayoutParams(side, side).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        status = label("", 13f, "#ECEAE6")
        root.addView(status)
        setStatus(
            "Your move \u00b7 " + fmtOdds(state.optDouble("odds", Double.NaN)) + " odds \u00b7 best " +
                    state.optString("best") + " \u00b7 simulation only, nothing is played on chess.com"
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(button("\u21bb Widget") { analyseForWidget(game) })
        bar.addView(button("\u21ba Reset") { openSim(game) })
        bar.addView(button("\u2715 Close") { finish() })
        root.addView(bar)
    }

    private fun playMove(from: String, to: String, bv: ChessBoardView) {
        if (busy) return
        busy = true
        bv.enabledTaps = false
        setStatus("Checking " + from + "\u2192" + to + "\u2026")
        Thread {
            val outcome = runCatching { py("chess_pick.sim_move('$from','$to')") }
            ui.post {
                busy = false
                bv.enabledTaps = true
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    bv.board = bv.board
                    setStatus("Not played: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                val replyUci = r.optString("reply_uci")
                bv.board = BoardPainter.parseFen(r.optString("reply_fen", r.optString("fen")))
                bv.turn = simMe
                if (replyUci.length >= 4) {
                    bv.from = BoardPainter.sq(replyUci.substring(0, 2))
                    bv.to = BoardPainter.sq(replyUci.substring(2, 4))
                }
                val san = r.optString("san")
                val mark = r.optString("mark")
                val labelText = r.optString("label")
                val reply = r.optString("reply")
                val tail = if (r.optBoolean("checkmate")) " \u00b7 checkmate"
                else if (r.optBoolean("stalemate")) " \u00b7 stalemate"
                else if (reply.isNotEmpty()) " \u00b7 engine: " + reply
                else ""
                setStatus(
                    san + mark + " \u00b7 " + labelText + " (" + r.optDouble("loss_pct", 0.0) + "% lost) \u00b7 " +
                            fmtOdds(r.optDouble("odds_before", Double.NaN)) + " \u2192 " +
                            fmtOdds(r.optDouble("odds_after", Double.NaN)) + tail
                )
            }
        }.start()
    }

    // -------------------------------------------------------------- widget --

    private fun analyseForWidget(game: Game) {
        if (busy) return
        busy = true
        setStatus("Analysing " + game.opponent + " for the widget\u2026")
        Thread {
            val outcome = runCatching { py("chess_pick.main('analyse','" + game.id + "')") }
            ui.post {
                busy = false
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    setStatus("Widget not updated: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                ChessWidgetProvider.refresh(this)
                Toast.makeText(
                    this,
                    r.optString("opponent") + " \u00b7 " + r.optString("source") + " " +
                            String.format("%+.2f", r.optDouble("eval", 0.0)),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }.start()
    }

    /** Runs a chess_pick expression in the :python worker and returns its JSON. */
    private fun py(expr: String): JSONObject {
        val work = File(filesDir, "work").absolutePath
        val pylibs = File(filesDir, "pylibs").absolutePath
        val code = buildString {
            append("import sys, json, io, contextlib\n")
            append("for p in ('").append(work).append("', '").append(pylibs).append("'):\n")
            append("    sys.path.insert(0, p)\n")
            append("import chess_pick\n")
            append("_sink = io.StringIO()\n")
            append("with contextlib.redirect_stdout(_sink):\n")
            append("    _res = ").append(expr).append("\n")
            append("print('RESULT' + json.dumps(_res, ensure_ascii=False))\n")
        }
        val out = runBlocking { PyRuntime.exec(code, CALL_TIMEOUT_MS) }.output
        val line = out.lineSequence().lastOrNull { it.startsWith("RESULT") }
            ?: throw IllegalStateException(out.trim().takeLast(200).ifBlank { "no output from the python worker" })
        return JSONObject(line.substring("RESULT".length))
    }

    private companion object {
        const val ME = "ConfirmableMotorcycle"
        const val CALL_TIMEOUT_MS = 120_000L
        const val SQUARES = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?"
    }
}
