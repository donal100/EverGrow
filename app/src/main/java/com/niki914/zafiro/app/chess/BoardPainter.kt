package com.niki914.zafiro.app.chess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.niki914.zafiro.app.R

/**
 * Shared board painter used by the widget and the simulation view: chess.com palette,
 * lichess "staunty" pieces (vector drawables), file/rank coordinates and a last-move
 * highlight. Colours were sampled from a chess.com screenshot.
 */
internal object BoardPainter {

    const val LIGHT_SQUARE = "#EDEDD1"
    const val DARK_SQUARE = "#769656"
    const val LAST_MOVE = "#BBCA46"
    private const val COORD_ON_LIGHT = "#8E8C7C"
    private const val COORD_ON_DARK = "#EFEFD2"

    private val pieceCache = HashMap<Char, Drawable>()

    fun emptyBoard(): CharArray = CharArray(64) { ' ' }

    fun parseFen(fen: String): CharArray {
        val out = CharArray(64) { ' ' }
        var rank = 7
        var file = 0
        for (ch in fen.substringBefore(' ')) {
            when {
                ch == '/' -> {
                    rank--
                    file = 0
                }
                ch in '1'..'8' -> file += ch - '0'
                else -> {
                    if (rank in 0..7 && file in 0..7) out[rank * 8 + file] = ch
                    file++
                }
            }
        }
        return out
    }

    /** "e4" -> index, index 0 = a1. */
    fun sq(name: String): Int = (name[1] - '1') * 8 + (name[0] - 'a')

    fun squareOf(index: Int): String {
        if (index !in 0..63) return "a1"
        return "" + ('a' + index % 8) + ('1' + index / 8)
    }

    private fun pieceRes(pc: Char): Int = when (pc) {
        'K' -> R.drawable.piece_wk
        'Q' -> R.drawable.piece_wq
        'R' -> R.drawable.piece_wr
        'B' -> R.drawable.piece_wb
        'N' -> R.drawable.piece_wn
        'P' -> R.drawable.piece_wp
        'k' -> R.drawable.piece_bk
        'q' -> R.drawable.piece_bq
        'r' -> R.drawable.piece_br
        'b' -> R.drawable.piece_bb
        'n' -> R.drawable.piece_bn
        else -> R.drawable.piece_bp
    }

    private fun pieceDrawable(context: Context, pc: Char): Drawable? {
        pieceCache[pc]?.let { return it }
        val d = try {
            context.getDrawable(pieceRes(pc))
        } catch (t: Throwable) {
            null
        } ?: return null
        pieceCache[pc] = d
        return d
    }

    /**
     * Draws the whole board, filling a square of [size] pixels starting at (originX, originY).
     * [selected] gets a ring so the simulation shows what is picked up.
     */
    fun draw(
        context: Context,
        canvas: Canvas,
        board: CharArray,
        flip: Boolean,
        size: Float,
        originX: Float = 0f,
        originY: Float = 0f,
        from: Int? = null,
        to: Int? = null,
        selected: Int? = null,
        showCoords: Boolean = true,
    ) {
        val cell = size / 8f
        val paint = Paint()
        val light = Color.parseColor(LIGHT_SQUARE)
        val dark = Color.parseColor(DARK_SQUARE)
        val highlight = Color.parseColor(LAST_MOVE)

        val coords = Paint().apply {
            isAntiAlias = true
            textSize = cell * 0.17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        for (rank in 0..7) for (file in 0..7) {
            val col = if (flip) 7 - file else file
            val row = if (flip) rank else 7 - rank
            val left = originX + col * cell
            val top = originY + row * cell
            val isDark = (file + rank) % 2 == 0
            paint.color = if (isDark) dark else light
            canvas.drawRect(left, top, left + cell, top + cell, paint)

            val idx = rank * 8 + file
            if (idx == from || idx == to) {
                paint.color = highlight
                canvas.drawRect(left, top, left + cell, top + cell, paint)
            }

            if (showCoords && rank == 0) {
                coords.color = Color.parseColor(if (isDark) COORD_ON_DARK else COORD_ON_LIGHT)
                coords.textAlign = Paint.Align.RIGHT
                canvas.drawText(('a' + file).toString(), left + cell * 0.92f, top + cell * 0.94f, coords)
            }
            if (showCoords && file == 0) {
                coords.color = Color.parseColor(if (isDark) COORD_ON_DARK else COORD_ON_LIGHT)
                coords.textAlign = Paint.Align.LEFT
                canvas.drawText((rank + 1).toString(), left + cell * 0.08f, top + cell * 0.24f, coords)
            }

            val pc = board[idx]
            if (pc == ' ') continue
            val d = pieceDrawable(context, pc) ?: continue
            d.setBounds(left.toInt(), top.toInt(), (left + cell).toInt(), (top + cell).toInt())
            d.draw(canvas)

            if (idx == selected) {
                val ring = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = cell * 0.07f
                    color = Color.argb(200, 20, 20, 20)
                    isAntiAlias = true
                }
                canvas.drawRect(left + cell * 0.05f, top + cell * 0.05f,
                    left + cell * 0.95f, top + cell * 0.95f, ring)
            }
        }
    }
}
