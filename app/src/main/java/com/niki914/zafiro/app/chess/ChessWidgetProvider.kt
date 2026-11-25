package com.niki914.zafiro.app.chess

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.RemoteViews
import com.niki914.zafiro.app.R
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Home-screen chess board driven by the agent.
 *
 * The agent writes filesDir/chess/line.json:
 *   { "fen": "...", "eval": -0.34, "flip": true,
 *     "steps": [ { "uci": "e5d3", "san": "Nd3", "label": "52...Nd3", "eval": -0.39 }, ... ] }
 *
 * The widget renders the position and steps through the engine line on tap.
 */
class ChessWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_NEXT && action != ACTION_PREV && action != ACTION_RESET) return
        try {
            val data = Store.load(context)
            val total = data?.optJSONArray("steps")?.length() ?: 0
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChessWidgetProvider::class.java))
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            for (id in ids) {
                val cur = prefs.getInt(KEY_PREFIX + id, 0)
                val next = when (action) {
                    ACTION_NEXT -> (cur + 1).coerceAtMost(total)
                    ACTION_PREV -> (cur - 1).coerceAtLeast(0)
                    else -> 0
                }
                prefs.edit().putInt(KEY_PREFIX + id, next).apply()
                render(context, mgr, id)
            }
        } catch (t: Throwable) {
            // never let a widget crash the app process
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_chess)
            val data = Store.load(context)
            val total = data?.optJSONArray("steps")?.length() ?: 0
            val index = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PREFIX + id, 0).coerceIn(0, total)

            views.setOnClickPendingIntent(R.id.chess_next, pending(context, ACTION_NEXT, 1))
            views.setOnClickPendingIntent(R.id.chess_prev, pending(context, ACTION_PREV, 2))
            views.setOnClickPendingIntent(R.id.chess_reset, pending(context, ACTION_RESET, 3))
            views.setOnClickPendingIntent(R.id.chess_board, pending(context, ACTION_NEXT, 1))

            if (data == null) {
                views.setImageViewBitmap(R.id.chess_board, boardBitmap(emptyBoard(), true, null, null))
                views.setTextViewText(R.id.chess_label, "Chess · no line yet")
                views.setTextViewText(R.id.chess_eval, "—")
                mgr.updateAppWidget(id, views)
                return
            }

            val fen = data.optString("fen")
            val flip = data.optBoolean("flip", fen.split(" ").getOrNull(1) != "w")
            var board = parseFen(fen)
            var lastFrom: String? = null
            var lastTo: String? = null
            val steps = data.optJSONArray("steps")
            for (i in 0 until index) {
                val uci = steps?.optJSONObject(i)?.optString("uci") ?: break
                applyMove(board, uci)
                lastFrom = uci.substring(0, 2)
                lastTo = uci.substring(2, 4)
            }

            val label = when {
                index == 0 -> "Start · tap to step (0/$total)"
                else -> {
                    val st = steps?.optJSONObject(index - 1)
                    "${st?.optString("label") ?: ""} · $index/$total"
                }
            }
            val eval = when {
                index == 0 -> data.optDouble("eval", Double.NaN)
                else -> steps?.optJSONObject(index - 1)?.optDouble("eval", Double.NaN) ?: Double.NaN
            }
            views.setImageViewBitmap(R.id.chess_board, boardBitmap(board, flip, lastFrom, lastTo))
            views.setTextViewText(R.id.chess_label, label)
            views.setTextViewText(R.id.chess_eval, if (eval.isNaN()) "—" else (if (eval > 0) "+" else "") + String.format("%.2f", eval))
            mgr.updateAppWidget(id, views)
        } catch (t: Throwable) {
            // keep the launcher happy even if the json is malformed
        }
    }

    private fun pending(context: Context, action: String, code: Int): PendingIntent {
        val intent = Intent(context, ChessWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_NEXT = "com.niki914.zafiro.app.chess.NEXT"
        const val ACTION_PREV = "com.niki914.zafiro.app.chess.PREV"
        const val ACTION_RESET = "com.niki914.zafiro.app.chess.RESET"
        private const val PREFS = "chess_widget"
        private const val KEY_PREFIX = "index_"

        private fun emptyBoard(): CharArray = CharArray(64) { ' ' }

        private fun parseFen(fen: String): CharArray {
            val out = CharArray(64) { ' ' }
            var rank = 7
            var file = 0
            for (ch in fen.substringBefore(' ')) {
                when {
                    ch == '/' -> { rank--; file = 0 }
                    ch in '1'..'8' -> file += ch - '0'
                    else -> {
                        if (rank in 0..7 && file in 0..7) out[rank * 8 + file] = ch
                        file++
                    }
                }
            }
            return out
        }

        private fun sq(name: String): Int = (name[1] - '1') * 8 + (name[0] - 'a')

        private fun applyMove(b: CharArray, uci: String) {
            if (uci.length < 4) return
            val from = sq(uci.substring(0, 2))
            val to = sq(uci.substring(2, 4))
            if (from !in 0..63 || to !in 0..63) return
            var pc = b[from]
            if (pc == ' ') return
            val isPawn = pc == 'P' || pc == 'p'
            if (isPawn && b[to] == ' ' && uci[0] != uci[2]) {
                val cap = if (pc == 'P') to - 8 else to + 8
                if (cap in 0..63) b[cap] = ' '
            }
            b[from] = ' '
            if (uci.length >= 5) pc = if (pc.isUpperCase()) uci[4].uppercaseChar() else uci[4].lowercaseChar()
            b[to] = pc
            if (pc.lowercaseChar() == 'k' && abs(uci[0] - uci[2]) == 2) {
                val r = uci[1]
                val rook = if (pc == 'K') 'R' else 'r'
                if (uci[2] > uci[0]) { b[sq("h$r")] = ' '; b[sq("f$r")] = rook }
                else { b[sq("a$r")] = ' '; b[sq("d$r")] = rook }
            }
        }

        private fun boardBitmap(b: CharArray, flip: Boolean, lastFrom: String?, lastTo: String?): Bitmap {
            val size = 768
            val cell = size / 8f
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val dark = Paint().apply { color = Color.parseColor("#769656") }
            val light = Paint().apply { color = Color.parseColor("#EEEED2") }
            val ring = Paint().apply {
                color = Color.parseColor("#F6F669")
                style = Paint.Style.STROKE
                strokeWidth = cell * 0.10f
            }
            val fill = Paint().apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = cell * 0.66f
                isAntiAlias = true
            }
            val stroke = Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = cell * 0.055f
            }
            val hiFrom = lastFrom?.let { sq(it) }
            val hiTo = lastTo?.let { sq(it) }

            for (rank in 0..7) for (file in 0..7) {
                val col = if (flip) 7 - file else file
                val row = if (flip) rank else 7 - rank
                val left = col * cell
                val top = row * cell
                val isDark = (file + rank) % 2 == 0
                c.drawRect(left, top, left + cell, top + cell, if (isDark) dark else light)
                val idx = rank * 8 + file
                if (idx == hiFrom || idx == hiTo) {
                    c.drawRect(left + cell * 0.05f, top + cell * 0.05f,
                        left + cell * 0.95f, top + cell * 0.95f, ring)
                }
                val pc = b[idx]
                if (pc == ' ') continue
                val glyph = if (pc.isUpperCase()) pc.toString() else pc.uppercaseChar().toString()
                val cx = left + cell / 2f
                val cy = top + cell / 2f - (fill.ascent() + fill.descent()) / 2f
                if (pc.isUpperCase()) {
                    stroke.color = Color.parseColor("#222222")
                    fill.color = Color.WHITE
                } else {
                    stroke.color = Color.WHITE
                    fill.color = Color.parseColor("#1B1B1B")
                }
                c.drawText(glyph, cx, cy, stroke)
                c.drawText(glyph, cx, cy, fill)
            }
            return bmp
        }
    }
}

internal object Store {
    fun file(ctx: Context): File = File(File(ctx.filesDir, "chess"), "line.json")

    fun load(ctx: Context): JSONObject? = try {
        val f = file(ctx)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (t: Throwable) {
        null
    }
}
