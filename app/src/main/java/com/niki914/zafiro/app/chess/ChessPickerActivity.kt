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
    private var explainView: TextView? = null
    private var lastPayload: org.json.JSONObject? = null
    private var explainBusy = false
    private var simMe = "white"

    /** Stateless simulation: the position travels with every call. */
    private var simFen = ""

    /** Positions the simulation passed through, so Back can walk them. */
    private val simHistory = ArrayList<String>()

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

    private fun fmtLoss(v: Double): String =
        if (v.isNaN()) "" else " (" + String.format("%.1f", v) + "% lost)"

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
        simHistory.clear()
        simMe = state.optString("me", "white")
        simFen = state.optString("fen")
        val flip = simMe == "black"

        val bv = ChessBoardView(this).apply {
            this.flip = flip
            turn = state.optString("turn", simMe)
            board = BoardPainter.parseFen(state.optString("fen"))
            onMove = { from, to -> playMove(from, to, this) }
        }
        boardView = bv

        root.setPadding(dp(6), dp(6), dp(6), dp(4))
        root.addView(bv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // square it off from the real content width, so it can never overflow the dialog
        bv.post {
            val w = bv.width
            if (w > 0) {
                val side = min(w, (resources.displayMetrics.heightPixels * 0.62f).toInt())
                bv.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                bv.requestLayout()
            }
        }

        status = label("", 13f, "#ECEAE6")
        root.addView(status)
        explainView = label("", 13f, "#BFD3A6")
        root.addView(explainView)
        setStatus(
            "Your move \u00b7 " + fmtOdds(state.optDouble("odds", Double.NaN)) + " odds \u00b7 best " +
                    state.optString("best") + " \u00b7 simulation only, nothing is played on chess.com"
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(button("\u25c0 Back") { backMove() })
        bar.addView(button("\u25b6 Next") { nextReply() })
        root.addView(bar)

        val bar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar2.addView(button("\ud83e\udd16 Why") { explainCurrent() })
        bar2.addView(button("\u21bb Widget") { analyseForWidget(game) })
        bar2.addView(button("\u21ba Reset") {
            simHistory.clear()
            openSim(game)
        })
        bar2.addView(button("\u2715 Close") { finish() })
        root.addView(bar2)
    }

    private fun playMove(from: String, to: String, bv: ChessBoardView) {
        if (busy) return
        busy = true
        bv.enabledTaps = false
        explainView?.text = ""
        setStatus("Checking " + from + "\u2192" + to + "\u2026")
        Thread {
            val outcome = runCatching { py("chess_pick.sim_move('" + simFen + "','" + from + "','" + to + "','" + simMe + "')") }
            ui.post {
                busy = false
                bv.enabledTaps = true
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    bv.board = BoardPainter.parseFen(simFen)
                    setStatus("Not played: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                val nextFen = r.optString("fen", r.optString("reply_fen"))
                if (nextFen.isNotBlank() && nextFen != simFen) {
                    simHistory.add(simFen)
                    simFen = nextFen
                }
                bv.board = BoardPainter.parseFen(simFen)
                bv.turn = r.optString("turn", simMe)
                bv.from = BoardPainter.sq(from)
                bv.to = BoardPainter.sq(to)
                val san = r.optString("san")
                val mark = r.optString("mark")
                val labelText = r.optString("label")
                val over = r.optBoolean("checkmate") || r.optBoolean("stalemate")
                // the opponent only moves when Next is tapped
                bv.enabledTaps = false
                val tail = when {
                    r.optBoolean("checkmate") -> " \u00b7 checkmate"
                    r.optBoolean("stalemate") -> " \u00b7 stalemate"
                    else -> " \u00b7 tap Next for the engine"
                }
                setStatus(
                    san + mark + " \u00b7 " + labelText + fmtLoss(r.optDouble("loss_pct", Double.NaN)) + " \u00b7 " +
                            fmtOdds(r.optDouble("odds_before", Double.NaN)) + " \u2192 " +
                            fmtOdds(r.optDouble("odds_after", Double.NaN)) + tail
                )
                if (over) bv.enabledTaps = false
                lastPayload = org.json.JSONObject().apply {
                    put("fen_before", fenArg)
                    put("move", san)
                    put("best", r.optString("best_san", r.optString("best")))
                    put("label", labelText)
                    put("mark", mark)
                    if (!lossPct.isNaN()) put("loss_pct", lossPct)
                    put("odds_before", r.optDouble("odds_before", Double.NaN))
                    put("odds_after", r.optDouble("odds_after", Double.NaN))
                    put("side", simMe)
                }
                if (labelText == "inaccuracy" || labelText == "mistake" || labelText == "blunder") {
                    explainCurrent()
                }
            }
        }.start()
    }

    /** Plays the engine move for the opponent — on demand, never automatically. */
    private fun nextReply() {
        if (busy) return
        val bv = boardView ?: return
        if (simFen.isBlank()) {
            setStatus("Tap Reset to start again")
            return
        }
        busy = true
        bv.enabledTaps = false
        setStatus("Engine thinking\u2026")
        Thread {
            val outcome = runCatching { py("chess_pick.sim_play('" + simFen + "','" + simMe + "')") }
            ui.post {
                busy = false
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    setStatus("Not played: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    bv.enabledTaps = true
                    return@post
                }
                val nextFen = r.optString("fen")
                if (nextFen.isNotBlank() && nextFen != simFen) {
                    simHistory.add(simFen)
                    simFen = nextFen
                }
                bv.board = BoardPainter.parseFen(simFen)
                bv.turn = r.optString("turn", simMe)
                val uci = r.optString("uci")
                if (uci.length >= 4) {
                    bv.from = BoardPainter.sq(uci.substring(0, 2))
                    bv.to = BoardPainter.sq(uci.substring(2, 4))
                }
                val over = r.optBoolean("checkmate") || r.optBoolean("stalemate")
                // taps come back only when the move left it to the user
                bv.enabledTaps = !over && r.optString("turn") == simMe
                val tail = when {
                    r.optBoolean("checkmate") -> " \u00b7 checkmate"
                    r.optBoolean("stalemate") -> " \u00b7 stalemate"
                    else -> ""
                }
                val who = if (r.optBoolean("mine")) "engine played " + r.optString("san") + " for you"
                else "engine: " + r.optString("san")
                setStatus(who + " \u00b7 " + fmtOdds(r.optDouble("odds", Double.NaN)) + " odds" + tail)
            }
        }.start()
    }

    /** Undo one step of the simulation. */
    private fun backMove() {
        val bv = boardView ?: return
        if (busy) return
        if (simHistory.isEmpty()) {
            setStatus("Nothing to undo")
            return
        }
        simFen = simHistory.removeAt(simHistory.size - 1)
        bv.board = BoardPainter.parseFen(simFen)
        bv.from = null
        bv.to = null
        val turnField = simFen.split(" ").getOrNull(1) ?: "w"
        bv.turn = if (turnField == "w") "white" else "black"
        bv.enabledTaps = bv.turn == simMe
        setStatus(if (bv.enabledTaps) "Back \u00b7 your move" else "Back \u00b7 engine to move \u00b7 tap Next")
    }

    /** Asks the coach model why the engine rated the last move that way. */
    private fun explainCurrent() {
        val payload = lastPayload
        if (payload == null) {
            setStatus("Make a move first")
            return
        }
        if (explainBusy) return
        explainBusy = true
        explainView?.text = "coach thinking\u2026"
        val b64 = android.util.Base64.encodeToString(
            payload.toString().toByteArray(), android.util.Base64.NO_WRAP
        )
        Thread {
            val outcome = runCatching { py("chess_pick.explain('" + b64 + "')") }
            ui.post {
                explainBusy = false
                val r = outcome.getOrNull()
                explainView?.text = if (r == null || !r.optBoolean("ok")) {
                    "coach: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?")
                } else {
                    "coach (" + r.optString("model") + "): " + r.optString("text")
                }
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

    /** Retries once: the :python worker can be mid-restart when a tap lands. */
    private fun py(expr: String): JSONObject {
        var last: Throwable? = null
        for (attempt in 0 until 2) {
            try {
                return pyOnce(expr)
            } catch (t: Throwable) {
                last = t
                if (attempt == 0) {
                    try {
                        Thread.sleep(700)
                        runBlocking { PyRuntime.warmUp() }
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        throw last ?: IllegalStateException("python failed")
    }

    private fun pyOnce(expr: String): JSONObject {
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
