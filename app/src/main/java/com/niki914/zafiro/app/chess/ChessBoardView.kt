package com.niki914.zafiro.app.chess

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * Tap-to-move board for the simulation screen. Tap one of your pieces, then a destination;
 * [onMove] gets ("e2", "e4") and the host asks Python whether it is legal and how good it was.
 */
class ChessBoardView(context: Context) : View(context) {

    var board: CharArray = BoardPainter.emptyBoard()
        set(value) {
            field = value
            selection = null
            invalidate()
        }

    var flip: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** "white" or "black" — whose pieces may be picked up. */
    var turn: String = "white"
        set(value) {
            field = value
            selection = null
            invalidate()
        }

    var from: Int? = null
    var to: Int? = null
    var selection: Int? = null
        private set

    var onMove: ((String, String) -> Unit)? = null
    var enabledTaps: Boolean = true

    private fun cell(): Float = min(width, height) / 8f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val side = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        BoardPainter.draw(
            context, canvas, board, flip, min(width, height).toFloat(),
            from = from, to = to, selected = selection,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledTaps) return false
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val c = cell()
        if (c <= 0f) return true
        val colD = (event.x / c).toInt()
        val rowD = (event.y / c).toInt()
        if (colD !in 0..7 || rowD !in 0..7) return true
        val file = if (flip) 7 - colD else colD
        val rank = if (flip) rowD else 7 - rowD
        val idx = rank * 8 + file
        val pc = board[idx]
        val mine = if (turn == "white") pc.isUpperCase() && pc != ' ' else pc.isLowerCase() && pc != ' '

        when {
            selection == null -> if (mine) selection = idx
            selection == idx -> selection = null
            mine -> selection = idx
            else -> {
                val src = selection ?: return true
                selection = null
                from = src
                to = idx
                invalidate()
                onMove?.invoke(BoardPainter.squareOf(src), BoardPainter.squareOf(idx))
                return true
            }
        }
        invalidate()
        return true
    }
}
