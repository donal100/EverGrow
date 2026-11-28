package com.niki914.zafiro.app.chess

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlin.math.roundToInt

/**
 * One screen for everything chess.
 *
 * Header: ◀ ▶ switch between the daily games where it is the user's move, ⟳ refetches the
 * list, tapping the title shows the list. Middle: the board of the selected game, tap a
 * piece and a destination to try a move — a simulation, nothing is sent to chess.com.
 * Back / Next step through the simulation (Next plays for whoever is to move, the user's
 * side included), Why asks the coach about the last move whoever played it, Widget runs
 * the full analysis and repaints the home-screen widget.
 */
class ChessPickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var holder: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var coachView: TextView
    private lateinit var askInput: EditText
    private val ui = Handler(Looper.getMainLooper())

    private var busy = false
    private var games: List<Game> = emptyList()
    private var index = 0
    private var showingList = false
    private var boardView: ChessBoardView? = null
    private var lastPayload: JSONObject? = null
    private var explainBusy = false
    private var simMe = "white"
    private var simFen = ""
    private val sims = HashMap<String, SimState>()
    /** Opponents seen so far — needed to find games that are waiting on them. */
    private val known = LinkedHashSet<String>()

    private data class Game(
        val id: String,
        val opponent: String,
        val colour: String,
        val plies: Int,
        val last: String,
        val waiting: Boolean = false,
    )

    private class SimState(
        var fen: String,
        val history: MutableList<String> = ArrayList(),
        /** "you played e4", "opponent played Bf6", "engine replied Nh4" */
        val moves: MutableList<String> = ArrayList(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor(BG)))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            setPadding(dp(6), dp(6), dp(6), dp(4))
        }
        setContentView(root)
        buildSkeleton()
        if (restoreState()) {
            headerTitle.text = headerForIndex()
            currentGame()?.let { openSim(it) }
            refreshGamesQuietly()
        } else {
            reloadGames()
        }
    }

    // ------------------------------------------------------------------ ui --

    private fun buildSkeleton() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("\u25c0") { switchGame(-1) })
        headerTitle = label("Loading\u2026", 15f, TEXT).apply {
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            gravity = Gravity.CENTER
            setOnClickListener { toggleList() }
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("\u25b6") { switchGame(1) })
        header.addView(button("\u27f3") { reloadGames() })
        root.addView(header)

        holder = FrameLayout(this)
        root.addView(holder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        statusView = label("", 13f, TEXT)
        root.addView(statusView)
        coachView = label("", 13f, COACH).apply {
            maxLines = 8
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(coachView)

        val askRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        askInput = EditText(this).apply {
            hint = "Ask about this position\u2026"
            setSingleLine(true)
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(MUTED))
            textSize = 14f
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        askRow.addView(askInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        askRow.addView(button("\u27a4") { sendQuestion() })
        root.addView(askRow)

        val bar1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar1.addView(button("\u25c0 Back") { backMove() })
        bar1.addView(button("\u25b6 Next") { nextReply() })
        bar1.addView(button("\ud83e\udd16 Why") { explainCurrent() })
        root.addView(bar1)

        val bar2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar2.addView(button("\u2328 FEN") { fenDialog() })
        bar2.addView(button("\u21bb Widget") { currentGame()?.let { analyseForWidget(it) } })
        bar2.addView(button("\u21ba Reset") { currentGame()?.let { resetSim(it) } })
        bar2.addView(button("\u2715 Close") { finish() })
        root.addView(bar2)
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    private fun label(text: String, size: Float, color: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor(TEXT))
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onClick() }
        }

    private fun setStatus(text: String) {
        statusView.text = text
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtOdds(v: Double): String =
        if (v.isNaN()) "\u2014" else v.roundToInt().toString() + "%"

    private fun fmtLoss(v: Double): String =
        if (v.isNaN()) "" else " (" + String.format("%.1f", v) + "% lost)"

    private fun turnOf(fen: String): String =
        if ((fen.split(" ").getOrNull(1) ?: "w") == "w") "white" else "black"

    // ---------------------------------------------------------- persistence --

    private val stateFile: File get() = File(File(filesDir, "chess"), "picker_state.json")

    /** Everything needed to come back exactly where the user left off. */
    private fun saveState() {
        try {
            val o = JSONObject()
            o.put("index", index)
            o.put("simMe", simMe)
            o.put("fen", simFen)
            o.put("status", statusView.text.toString())
            o.put("coach", coachView.text.toString())
            val arr = org.json.JSONArray()
            for (g in games) arr.put(JSONObject().apply {
                put("id", g.id)
                put("opponent", g.opponent)
                put("colour", g.colour)
                put("plies", g.plies)
                put("last", g.last)
                put("waiting", g.waiting)
            })
            o.put("games", arr)
            o.put("known", org.json.JSONArray(known.toList()))
            val simsObj = JSONObject()
            for ((id, st) in sims) simsObj.put(id, JSONObject().apply {
                put("fen", st.fen)
                val h = org.json.JSONArray()
                for (f in st.history) h.put(f)
                put("history", h)
                val mv = org.json.JSONArray()
                for (m in st.moves) mv.put(m)
                put("moves", mv)
            })
            o.put("sims", simsObj)
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(o.toString())
        } catch (_: Throwable) {
        }
    }

    private fun restoreState(): Boolean {
        return try {
            val f = stateFile
            if (!f.exists()) return false
            val o = JSONObject(f.readText())
            val kn = o.optJSONArray("known")
            if (kn != null) for (i in 0 until kn.length()) known.add(kn.optString(i))
            val arr = o.optJSONArray("games") ?: return false
            val list = ArrayList<Game>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                list.add(
                    Game(
                        g.optString("id"), g.optString("opponent"), g.optString("colour"),
                        g.optInt("plies"), g.optString("last"), g.optBoolean("waiting", false)
                    )
                )
            }
            if (list.isEmpty()) return false
            games = list
            index = o.optInt("index", 0).coerceIn(0, games.size - 1)
            simMe = o.optString("simMe", games[index].colour)
            simFen = o.optString("fen", "")
            val simsObj = o.optJSONObject("sims") ?: JSONObject()
            sims.clear()
            for (id in simsObj.keys()) {
                val s = simsObj.optJSONObject(id) ?: continue
                val hist = ArrayList<String>()
                val h = s.optJSONArray("history")
                if (h != null) for (i in 0 until h.length()) hist.add(h.optString(i))
                val log = ArrayList<String>()
                val mv = s.optJSONArray("moves")
                if (mv != null) for (i in 0 until mv.length()) log.add(mv.optString(i))
                sims[id] = SimState(s.optString("fen"), hist, log)
            }
            statusView.text = "" // rebuilt by openSim: never come back to an old error
            coachView.text = o.optString("coach", "")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun headerForIndex(): String {
        val g = games.getOrNull(index) ?: return "No games"
        return "vs " + g.opponent + " \u00b7 " + g.colour + " \u00b7 " + (index + 1) + "/" + games.size
    }

    // --------------------------------------------------------------- games --

    private fun currentGame(): Game? = games.getOrNull(index)

    private fun reloadGames() {
        setStatus("Looking for your games\u2026")
        coachView.text = ""
        Thread {
            val loaded: List<Game>? = try {
                idsToMove().mapNotNull { id -> runCatching { details(id) }.getOrNull() }
            } catch (t: Throwable) {
                null
            }
            ui.post {
                when {
                    loaded == null -> setStatus("Couldn't reach chess.com")
                    loaded.isEmpty() -> {
                        games = emptyList()
                        headerTitle.text = "No games waiting on you"
                        holder.removeAllViews()
                        boardView = null
                        setStatus("Nothing to play \u2014 \u27f3 retries")
                    }
                    else -> {
                        known.addAll(loaded.map { it.opponent })
                        val keep = games.firstOrNull { it.id == ADHOC }
                        games = if (keep != null) listOf(keep) + loaded else loaded
                        if (index >= games.size) index = 0
                        openSim(games[index])
                        fetchWaiting()
                    }
                }
            }
        }.start()
    }

    /** Refetches the game list without touching the simulation in progress. */
    private fun refreshGamesQuietly() {
        Thread {
            val loaded: List<Game>? = try {
                idsToMove().mapNotNull { id -> runCatching { details(id) }.getOrNull() }
            } catch (t: Throwable) {
                null
            }
            if (loaded == null || loaded.isEmpty()) return@Thread
            ui.post {
                val current = games.getOrNull(index)
                val keep = games.firstOrNull { it.id == ADHOC }
                val base = if (keep != null) listOf(keep) + loaded.filter { it.id != ADHOC } else loaded
                // a game waiting on the opponent is not in the to-move list: keep it on screen
                games = if (current != null && base.none { it.id == current.id }) base + current else base
                known.addAll(loaded.map { it.opponent })
                val same = games.indexOfFirst { it.id == current?.id }
                index = if (same >= 0) same else 0
                if (!showingList) headerTitle.text = headerForIndex()
                saveState()
                // games where the opponent has the move are only visible through their feeds
                fetchWaiting()
            }
        }.start()
    }

    /** Games where it is the opponent's move: found through their own to-move feeds. */
    private fun fetchWaiting() {
        val names = known.toList()
        if (names.isEmpty()) return
        Thread {
            val outcome = runCatching {
                val b64 = android.util.Base64.encodeToString(
                    org.json.JSONArray(names).toString().toByteArray(), android.util.Base64.NO_WRAP
                )
                py("chess_pick.waiting('" + b64 + "')")
            }
            val r = outcome.getOrNull() ?: return@Thread
            val arr = r.optJSONArray("games") ?: return@Thread
            val extra = ArrayList<Game>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                extra.add(
                    Game(
                        g.optString("id"), g.optString("opponent"), g.optString("colour"),
                        g.optInt("plies"), g.optString("last"), true
                    )
                )
            }
            if (extra.isEmpty()) return@Thread
            ui.post {
                val have = games.map { it.id }.toSet()
                val merged = games + extra.filter { it.id !in have }
                if (merged.size != games.size) {
                    known.addAll(extra.map { it.opponent })
                    games = merged
                    if (showingList) renderList()
                    if (games.isNotEmpty() && !showingList) headerTitle.text = headerForIndex()
                    saveState()
                }
            }
        }.start()
    }

    private fun switchGame(delta: Int) {
        if (games.isEmpty()) return
        index = (index + delta + games.size) % games.size
        showingList = false
        // a different game starts from the real position, never from a cached simulation
        sims.remove(games[index].id)
        openSim(games[index])
        saveState()
    }

    private fun toggleList() {
        if (games.isEmpty()) return
        showingList = !showingList
        if (showingList) renderList() else currentGame()?.let { openSim(it) }
    }

    private fun renderList() {
        holder.removeAllViews()
        boardView = null
        // the status line belongs to the board, not to the game list
        statusView.visibility = View.GONE
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        column.addView(label("Your move \u00b7 " + games.size + " \u00b7 tap to switch", 13f, "#9FD08A"))
        for ((i, g) in games.withIndex()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val suffix = if (g.waiting) " \u00b7 their move" else ""
            if (g.id == ADHOC) {
                box.addView(label("Ad-hoc \u00b7 you are " + g.colour, 16f, TEXT))
                box.addView(label("your own position \u00b7 tap to open", 12f, MUTED))
            } else {
                box.addView(label(g.opponent + " \u00b7 you are " + g.colour + suffix, 16f, TEXT))
                box.addView(label("ply " + g.plies + " \u00b7 last " + g.last, 12f, MUTED))
            }
            box.setOnClickListener {
                if (games.getOrNull(index)?.id != g.id) sims.remove(g.id)
                index = i
                showingList = false
                openSim(g)
            }
            column.addView(box)
            column.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#332A2A2A"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        val adhoc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        adhoc.addView(label("\uff0b Ad-hoc position", 16f, "#9FD08A"))
        adhoc.addView(label("paste a FEN \u00b7 photo read \u00b7 puzzle", 12f, MUTED))
        adhoc.setOnClickListener { fenDialog() }
        column.addView(adhoc)

        holder.addView(ScrollView(this).apply { addView(column) })
        headerTitle.text = "Games \u00b7 tap to go back"
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
        if (game.id == ADHOC) {
            val cached = sims[ADHOC]
            if (cached != null) {
                simMe = game.colour
                simFen = cached.fen
                headerTitle.text = "Ad-hoc \u00b7 " + game.colour
                showBoard()
                setStatus("Position \u00b7 taps are simulation only")
                saveState()
                return
            }
        }
        headerTitle.text = "vs " + game.opponent + " \u00b7 " + game.colour +
                " \u00b7 " + (index + 1) + "/" + games.size
        coachView.text = ""
        lastPayload = null

        val cached = sims[game.id]
        if (cached != null) {
            simMe = game.colour
            simFen = cached.fen
            showBoard()
            setStatus("Your move \u00b7 simulation only \u00b7 " + headerForIndex())
            saveState()
            return
        }

        setStatus("Opening " + game.opponent + "\u2026")
        busy = true
        Thread {
            val outcome = runCatching { py("chess_pick.sim_start('" + game.id + "')") }
            ui.post {
                busy = false
                val st = outcome.getOrNull()
                if (st == null || !st.optBoolean("ok")) {
                    setStatus("Couldn't open " + game.opponent + ": " +
                            (st?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                simMe = st.optString("me", game.colour)
                simFen = st.optString("fen")
                sims[game.id] = SimState(simFen)
                showBoard()
                setStatus("Your move \u00b7 " + fmtOdds(st.optDouble("odds", Double.NaN)) +
                        " odds \u00b7 best " + st.optString("best"))
                saveState()
            }
        }.start()
    }

    /** Kept for the cached case: the odds come back with every rated move instead. */
    private fun oddsFromBoard(): Double = Double.NaN

    private fun showBoard() {
        statusView.visibility = View.VISIBLE
        val bv: ChessBoardView = boardView ?: run {
            val fresh = ChessBoardView(this)
            fresh.onMove = { from, to -> playMove(from, to, fresh) }
            holder.removeAllViews()
            holder.addView(fresh, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
            boardView = fresh
            fresh
        }
        bv.flip = simMe == "black"
        bv.board = BoardPainter.parseFen(simFen)
        bv.turn = turnOf(simFen)
        bv.enabledTaps = true
        bv.from = null
        bv.to = null
        bv.invalidate()
    }

    private fun resetSim(game: Game) {
        sims.remove(game.id)
        boardView = null
        openSim(game)
    }

    private fun playMove(from: String, to: String, bv: ChessBoardView) {
        val game = currentGame() ?: return
        if (busy) return
        val fenArg = simFen
        busy = true
        bv.enabledTaps = false
        coachView.text = ""
        setStatus("Checking " + from + "\u2192" + to + "\u2026")
        Thread {
            val outcome = runCatching {
                py("chess_pick.sim_move('" + fenArg + "','" + from + "','" + to + "','" + simMe + "')")
            }
            ui.post {
                busy = false
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    bv.enabledTaps = true
                    setStatus("Not played: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                val nextFen = r.optString("fen", r.optString("reply_fen"))
                if (nextFen.isNotBlank() && nextFen != fenArg) {
                    sims[game.id]?.history?.add(fenArg)
                    simFen = nextFen
                    sims[game.id]?.fen = nextFen
                }
                bv.flip = simMe == "black"
                bv.board = BoardPainter.parseFen(simFen)
                bv.turn = r.optString("turn", simMe)
                bv.from = BoardPainter.sq(from)
                bv.to = BoardPainter.sq(to)
                val over = r.optBoolean("checkmate") || r.optBoolean("stalemate")
                // either colour's move is fine from here: the board follows the side to move
                bv.enabledTaps = !over
                val san = r.optString("san")
                val mark = r.optString("mark")
                val labelText = r.optString("label")
                val lossPct = r.optDouble("loss_pct", Double.NaN)
                val tail = when {
                    r.optBoolean("checkmate") -> " \u00b7 checkmate"
                    r.optBoolean("stalemate") -> " \u00b7 stalemate"
                    else -> " \u00b7 move the other side, or tap Next for the engine"
                }
                setStatus(
                    san + mark + " \u00b7 " + labelText + fmtLoss(lossPct) + " \u00b7 " +
                            fmtOdds(r.optDouble("odds_before", Double.NaN)) + " \u2192 " +
                            fmtOdds(r.optDouble("odds_after", Double.NaN)) + tail
                )
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
                    put("mover", "you")
                }
                sims[game.id]?.moves?.add(
                    (if (turnOf(fenArg) == simMe) "you played " else "opponent played ") + san
                )
                saveState()
            }
        }.start()
    }

    private fun nextReply() {
        val game = currentGame() ?: return
        val bv = boardView ?: return
        if (busy) return
        if (simFen.isBlank()) {
            setStatus("Nothing to play")
            return
        }
        busy = true
        bv.enabledTaps = false
        coachView.text = ""
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
                val fenBefore = simFen
                val nextFen = r.optString("fen")
                if (nextFen.isNotBlank() && nextFen != fenBefore) {
                    sims[game.id]?.history?.add(fenBefore)
                    simFen = nextFen
                    sims[game.id]?.fen = nextFen
                }
                bv.flip = simMe == "black"
                bv.board = BoardPainter.parseFen(simFen)
                bv.turn = r.optString("turn", simMe)
                val uci = r.optString("uci")
                if (uci.length >= 4) {
                    bv.from = BoardPainter.sq(uci.substring(0, 2))
                    bv.to = BoardPainter.sq(uci.substring(2, 4))
                }
                val over = r.optBoolean("checkmate") || r.optBoolean("stalemate")
                bv.enabledTaps = !over
                val tail = when {
                    r.optBoolean("checkmate") -> " \u00b7 checkmate"
                    r.optBoolean("stalemate") -> " \u00b7 stalemate"
                    else -> ""
                }
                val who = (if (r.optBoolean("mine")) "engine played " + r.optString("san") + " for you"
                else "engine: " + r.optString("san"))
                setStatus(who + " \u00b7 " + fmtOdds(r.optDouble("odds", Double.NaN)) + " odds" + tail)
                lastPayload = org.json.JSONObject().apply {
                    put("fen_before", fenBefore)
                    put("move", r.optString("san"))
                    put("best", r.optString("best_san", r.optString("san")))
                    put("label", if (r.optBoolean("mine")) "engine move" else "engine reply")
                    put("mark", "")
                    put("loss_pct", 0)
                    put("odds_before", r.optDouble("odds_before", Double.NaN))
                    put("odds_after", r.optDouble("odds", Double.NaN))
                    put("side", simMe)
                    put("mover", "engine")
                }
                sims[game.id]?.moves?.add(
                    if (r.optBoolean("mine")) "engine played " + r.optString("san") + " for you"
                    else "engine replied " + r.optString("san")
                )
                saveState()
            }
        }.start()
    }

    private fun backMove() {
        val game = currentGame() ?: return
        val bv = boardView ?: return
        if (busy) return
        val history = sims[game.id]?.history
        if (history.isNullOrEmpty()) {
            setStatus("Nothing to undo")
            return
        }
        simFen = history.removeAt(history.size - 1)
        sims[game.id]?.moves?.let { if (it.isNotEmpty()) it.removeAt(it.size - 1) }
        sims[game.id]?.fen = simFen
        coachView.text = ""
        lastPayload = null
        showBoard()
        setStatus(if (turnOf(simFen) == simMe) "Back \u00b7 your move" else "Back \u00b7 engine to move \u00b7 tap Next")
        saveState()
    }

    /** Free-form question about the position on the board. */
    private fun sendQuestion() {
        val question = askInput.text.toString().trim()
        if (question.isEmpty()) return
        if (simFen.isBlank()) {
            setStatus("Open a game first")
            return
        }
        if (busy) return
        busy = true
        askInput.setText("")
        coachView.text = "coach thinking\u2026"
        val payload = org.json.JSONObject().apply {
            put("fen", simFen)
            put("side", simMe)
            put("question", question)
            val lp = lastPayload
            if (lp != null && !lp.isNull("odds_after")) {
                val odds = lp.optDouble("odds_after", Double.NaN)
                if (!odds.isNaN()) put("odds", odds)
            }
            if (lp != null && lp.has("best")) put("best", lp.optString("best"))
            val log = currentGame()?.let { sims[it.id]?.moves } ?: emptyList<String>()
            if (log.isNotEmpty()) put("last_move", log.last())
            if (log.size > 1) put("recent", log.takeLast(4).joinToString(", "))
        }
        Thread {
            val b64 = android.util.Base64.encodeToString(
                payload.toString().toByteArray(), android.util.Base64.NO_WRAP
            )
            val outcome = runCatching { py("chess_pick.ask('" + b64 + "')") }
            ui.post {
                busy = false
                val r = outcome.getOrNull()
                coachView.text = if (r == null || !r.optBoolean("ok")) {
                    "coach: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?")
                } else {
                    "you: " + question + "\ncoach (" + r.optString("model") + "): " + r.optString("text")
                }
                saveState()
            }
        }.start()
    }

    // ------------------------------------------------------------- ad-hoc --

    /** Any position: paste a FEN (a photo read, a puzzle, a what-if) and analyse it. */
    private fun fenDialog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        val clipText = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString().trim()
        } else ""

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val input = EditText(this).apply {
            hint = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
            minLines = 2
            setSingleLine(false)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(MUTED))
            if (looksLikeFen(clipText)) setText(clipText)
        }
        val blackSide = android.widget.CheckBox(this).apply {
            text = "I play Black"
            setTextColor(Color.parseColor(TEXT))
        }
        val paste = button("\u2398 Paste from clipboard") {
            if (clipText.isNotEmpty()) {
                input.setText(clipText)
                input.setSelection(clipText.length)
            } else {
                setStatus("Clipboard is empty")
            }
        }
        box.addView(input)
        box.addView(paste)
        box.addView(blackSide)

        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Ad-hoc position")
            .setView(box)
            .setPositiveButton("Load", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fen = input.text.toString().trim()
                if (!looksLikeFen(fen)) {
                    // keep the dialog open: the text is still there to fix
                    input.error = "Needs 8 rows, like 8/8/8/8/8/8/8/8 w - - 0 1"
                } else {
                    setStatus("")
                    dlg.dismiss()
                    loadFen(fen, if (blackSide.isChecked) "black" else "white")
                }
            }
        }
        dlg.show()
    }

    /** Cheap client-side gate so a stray string never reaches the engine as a position. */
    private fun looksLikeFen(text: String): Boolean {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size < 2) return false
        val rows = parts[0].split("/")
        if (rows.size != 8) return false
        if (!rows.all { it.matches(Regex("[rnbqkpRNBQKP1-8]+")) }) return false
        return parts[1] == "w" || parts[1] == "b"
    }

    private fun loadFen(fen: String, me: String) {
        if (fen.isBlank()) {
            setStatus("Paste a FEN first")
            return
        }
        busy = true
        setStatus("Loading position\u2026")
        coachView.text = ""
        lastPayload = null
        val payload = org.json.JSONObject().apply {
            put("fen", fen)
            put("me", me)
            put("label", "position")
        }
        Thread {
            val b64 = android.util.Base64.encodeToString(
                payload.toString().toByteArray(), android.util.Base64.NO_WRAP
            )
            val outcome = runCatching { py("chess_pick.adopt('" + b64 + "')") }
            ui.post {
                busy = false
                val r = outcome.getOrNull()
                if (r == null || !r.optBoolean("ok")) {
                    setStatus("Not loaded: " + (r?.optString("error") ?: outcome.exceptionOrNull()?.message ?: "?"))
                    return@post
                }
                val game = Game(ADHOC, "Ad-hoc", me, 0, "\u2014")
                games = listOf(game) + games.filter { it.id != ADHOC }
                index = 0
                simMe = me
                simFen = r.optString("fen", fen)
                sims[ADHOC] = SimState(simFen)
                showBoard()
                headerTitle.text = "Ad-hoc \u00b7 " + me
                setStatus(
                    "Position \u00b7 " + fmtOdds(r.optDouble("odds", Double.NaN)) + " odds \u00b7 best " +
                            r.optString("best") + " \u00b7 " + r.optString("source")
                )
                ChessWidgetProvider.refresh(this)
                saveState()
            }
        }.start()
    }

    // --------------------------------------------------------------- coach --

    private fun explainCurrent() {
        val payload = lastPayload
        if (payload == null) {
            setStatus("Make a move first \u2014 Why explains the last one")
            return
        }
        if (explainBusy) return
        explainBusy = true
        coachView.text = "coach thinking\u2026"
        val b64 = android.util.Base64.encodeToString(
            payload.toString().toByteArray(), android.util.Base64.NO_WRAP
        )
        Thread {
            val outcome = runCatching { py("chess_pick.explain('" + b64 + "')") }
            ui.post {
                explainBusy = false
                val r = outcome.getOrNull()
                coachView.text = if (r == null || !r.optBoolean("ok")) {
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
                    Toast.LENGTH_SHORT
                ).show()
                setStatus("Widget updated \u00b7 " + r.optString("source") + " " +
                        String.format("%+.2f", r.optDouble("eval", 0.0)))
            }
        }.start()
    }

    // --------------------------------------------------------------- python --

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
            append("import sys, json\n")
            append("for p in ('").append(work).append("', '").append(pylibs).append("'):\n")
            append("    sys.path.insert(0, p)\n")
            append("import chess_pick\n")
            // no redirect_stdout: it replaces the worker stream and runtime.py then
            // dies on sys.stdout.unbind(); the RESULT marker is parsed by prefix anyway
            append("_res = ").append(expr).append("\n")
            append("print('RESULT' + json.dumps(_res, ensure_ascii=False))\n")
        }
        val out = runBlocking { PyRuntime.exec(code, CALL_TIMEOUT_MS) }.output
        val line = out.lineSequence().lastOrNull { it.startsWith("RESULT") }
            ?: throw IllegalStateException(out.trim().takeLast(200).ifBlank { "no output from the python worker" })
        return JSONObject(line.substring("RESULT".length))
    }

    private companion object {
        /** The ad-hoc position is a row in the list like any game, so it survives a refresh. */
        const val ADHOC = "adhoc"
        const val ME = "ConfirmableMotorcycle"
        const val CALL_TIMEOUT_MS = 120_000L
        const val SQUARES = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?"
        const val BG = "#1B1B1B"
        const val TEXT = "#ECEAE6"
        const val MUTED = "#8A8782"
        const val COACH = "#BFD3A6"
    }
}
