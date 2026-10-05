// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.term;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.List;

/**
 * Draws a {@link TerminalBuffer} and turns touches and key events into bytes.
 *
 * Rendering is a fixed-pitch grid drawn with one Paint per attribute combination;
 * the palette is the usual sixteen colours, mapped to values that stay readable on
 * a dark background. Text arrives through an {@link InputConnection} so soft
 * keyboards, autocorrect-free composition and IME delete-key repeats all behave the
 * way a text field does, plus {@link #onKeyDown} for hardware keys.
 *
 * Scrolling back is a view concern: the buffer never rewrites history, it only
 * appends to its scrollback list, so the view can draw older rows above the live
 * screen without disturbing the session.
 */
public class TerminalView extends View {

    /** Where typed bytes go. */
    public interface Session {
        void onInput(byte[] data);

        void onResize(int rows, int cols);

        /**
         * The session should deliver a signal to its foreground job.
         *
         * @param signalNumber a POSIX signal number (SIGINT for Ctrl-C)
         */
        void onSignal(int signalNumber);
    }

    private static final int[] PALETTE = {
            // normal
            0xFF3B4048, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            // bright
            0xFF5C6370, 0xFFFF7B86, 0xFFB6E08A, 0xFFFFD68A,
            0xFF82C7FF, 0xFFDCA0F0, 0xFF7FD6E0, 0xFFFFFFFF,
    };
    private static final int DEFAULT_FG = 0xFFD7DBE0;
    private static final int DEFAULT_BG = 0xFF15171C;
    private static final int CURSOR_COLOR = 0xFF4D6BFE;

    private TerminalBuffer buffer;
    private Session session;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Paint cursorPaint = new Paint();

    private float cellWidth;
    private float cellHeight;
    private float baselineOffset;
    private int visibleRows;
    private int visibleCols;

    /** 0 = live screen at the bottom; positive = scrolled back by that many rows. */
    private int scrollOffset;
    private boolean ctrlArmed;
    private boolean altArmed;

    public TerminalView(Context context) {
        this(context, null);
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(DEFAULT_BG);

        Typeface mono = Typeface.MONOSPACE;
        textPaint.setTypeface(mono);
        textPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 13f);
        textPaint.setColor(DEFAULT_FG);
        cursorPaint.setColor(CURSOR_COLOR);
    }

    public void attach(TerminalBuffer buffer, Session session) {
        this.buffer = buffer;
        this.session = session;
        buffer.setListener(new TerminalBuffer.Listener() {
            @Override
            public void onScreenChanged() {
                postInvalidateOnAnimation();
            }

            @Override
            public void onBell() {
                // A quiet device is preferable to a beep on every tab completion.
            }
        });
        requestLayout();
        invalidate();
    }

    /** Called by the activity after the session starts, so sh knows the real size. */
    public void reportSize() {
        if (session != null && visibleRows > 0 && visibleCols > 0) {
            session.onResize(visibleRows, visibleCols);
        }
    }

    /** True when the control key is armed for the next character typed. */
    public boolean isCtrlArmed() {
        return ctrlArmed;
    }

    public void toggleCtrl() {
        ctrlArmed = !ctrlArmed;
        invalidate();
    }

    /** Alt is a modifier, so its state comes from the physical key, not a toggle. */
    public void setAlt(boolean armed) {
        altArmed = armed;
    }

    public void scrollToBottom() {
        scrollOffset = 0;
        postInvalidateOnAnimation();
    }

    // ---- measurement ------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeGrid(w, h);
    }

    private void recomputeGrid(int width, int height) {
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        // A monospace cell is as wide as 'M'; measured rather than assumed so any
        // monospace face the user has selected still lines up.
        cellWidth = textPaint.measureText("M");
        if (cellWidth <= 0) {
            cellWidth = textPaint.getTextSize() * 0.6f;
        }
        cellHeight = metrics.descent - metrics.ascent;
        baselineOffset = -metrics.ascent;

        int cols = Math.max(1, (int) (width / cellWidth));
        int rows = Math.max(1, (int) (height / cellHeight));
        // A little padding so glyphs are not clipped at the edges.
        int paddedCols = Math.max(1, (int) ((width - cellWidth * 0.5f) / cellWidth));
        if (paddedCols < cols) {
            cols = paddedCols;
        }
        visibleCols = cols;
        visibleRows = rows;

        if (buffer != null) {
            buffer.resize(rows, cols);
        }
        reportSize();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Fill whatever the parent offers; the grid adapts.
        setMeasuredDimension(
                resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    // ---- drawing ----------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (buffer == null) {
            return;
        }
        canvas.drawColor(DEFAULT_BG);

        List<TerminalBuffer.Cell[]> history = buffer.scrollback();
        int historySize = history.size();

        for (int row = 0; row < visibleRows; row++) {
            // Row index in the concatenation of scrollback + live screen.
            int logical = historySize - scrollOffset + row;
            TerminalBuffer.Cell[] line;
            if (logical < 0) {
                line = null;
            } else if (logical < historySize) {
                line = history.get(logical);
            } else {
                line = buffer.row(logical - historySize);
            }
            if (line != null) {
                drawRow(canvas, row, line);
            }
        }

        drawCursor(canvas, historySize);
    }

    private void drawRow(Canvas canvas, int row, TerminalBuffer.Cell[] line) {
        float y = row * cellHeight + baselineOffset;
        int count = Math.min(line.length, visibleCols);
        for (int col = 0; col < count; col++) {
            TerminalBuffer.Cell cell = line[col];
            if (cell.ch == ' ' && cell.bg < 0 && !cell.inverse) {
                continue;
            }
            float x = col * cellWidth;
            int fg = cell.fg < 0 ? DEFAULT_FG : PALETTE[cell.fg % PALETTE.length];
            int bg = cell.bg < 0 ? DEFAULT_BG : PALETTE[cell.bg % PALETTE.length];
            if (cell.inverse) {
                int swap = fg;
                fg = bg;
                bg = swap;
            }
            if (bg != DEFAULT_BG) {
                backgroundPaint.setColor(bg);
                canvas.drawRect(x, row * cellHeight, x + cellWidth, (row + 1) * cellHeight,
                        backgroundPaint);
            }
            textPaint.setColor(fg);
            textPaint.setFakeBoldText(cell.bold);
            textPaint.setUnderlineText(cell.underline);
            canvas.drawText(String.valueOf(cell.ch), x, y, textPaint);
        }
        textPaint.setFakeBoldText(false);
        textPaint.setUnderlineText(false);
    }

    private void drawCursor(Canvas canvas, int historySize) {
        if (!buffer.cursorVisible() || scrollOffset != 0) {
            return;
        }
        int row = buffer.cursorRow();
        int col = buffer.cursorCol();
        if (row < 0 || row >= visibleRows || col < 0 || col >= visibleCols) {
            return;
        }
        float x = col * cellWidth;
        float top = row * cellHeight;
        backgroundPaint.setColor(CURSOR_COLOR);
        canvas.drawRect(x, top, x + cellWidth, top + cellHeight, backgroundPaint);
        TerminalBuffer.Cell[] line = buffer.row(row);
        if (line != null && col < line.length && line[col].ch != ' ') {
            textPaint.setColor(DEFAULT_BG);
            canvas.drawText(String.valueOf(line[col].ch), x, top + baselineOffset, textPaint);
        }
    }

    // ---- touch ------------------------------------------------------------------

    private float lastTouchY;
    private boolean dragging;
    private long lastTapTime;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = event.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (buffer == null) {
                    return true;
                }
                float delta = event.getY() - lastTouchY;
                if (Math.abs(delta) > cellHeight * 0.5f) {
                    dragging = true;
                    int rows = (int) (delta / cellHeight);
                    if (rows != 0) {
                        List<TerminalBuffer.Cell[]> history = buffer.scrollback();
                        int max = history.size();
                        scrollOffset = Math.max(0, Math.min(max, scrollOffset + rows));
                        lastTouchY = event.getY();
                        postInvalidateOnAnimation();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapTime < 400) {
                        scrollToBottom();
                    }
                    lastTapTime = now;
                    requestFocus();
                    showKeyboard();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    public void showKeyboard() {
        android.view.inputmethod.InputMethodManager manager =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hideKeyboard() {
        android.view.inputmethod.InputMethodManager manager =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ---- text input -------------------------------------------------------------

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new TerminalInputConnection(this, true);
    }

    /** Sends a run of characters, honouring the armed Ctrl and Alt modifiers. */
    void sendText(CharSequence text) {
        if (session == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ctrlArmed) {
                // Ctrl-A..Ctrl-Z and a few punctuation cases, as a terminal expects.
                int code = controlCode(ch);
                if (code > 0) {
                    session.onInput(new byte[] { (byte) code });
                }
                continue;
            }
            byte[] encoded;
            try {
                encoded = String.valueOf(ch).getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                encoded = new byte[] { (byte) ch };
            }
            if (altArmed) {
                // Alt is an ESC prefix.
                byte[] withEsc = new byte[encoded.length + 1];
                withEsc[0] = 0x1B;
                System.arraycopy(encoded, 0, withEsc, 1, encoded.length);
                session.onInput(withEsc);
            } else {
                session.onInput(encoded);
            }
        }
        if (ctrlArmed) {
            ctrlArmed = false;
            postInvalidateOnAnimation();
        }
        if (altArmed) {
            altArmed = false;
            postInvalidateOnAnimation();
        }
    }

    private static int controlCode(char ch) {
        if (ch >= 'a' && ch <= 'z') {
            return ch - 'a' + 1;
        }
        if (ch >= 'A' && ch <= 'Z') {
            return ch - 'A' + 1;
        }
        switch (ch) {
            case ' ':
            case '@':
                return 0;
            case '[':
                return 27;
            case '\\':
                return 28;
            case ']':
                return 29;
            case '^':
                return 30;
            case '_':
                return 31;
            default:
                return -1;
        }
    }

    /** Sends one of the escape sequences a shell expects for a named key. */
    public void sendKey(String name) {
        if (session == null) {
            return;
        }
        boolean application = buffer != null && buffer.applicationCursorKeys();
        String sequence;
        if ("up".equals(name)) {
            sequence = application ? "\u001BOA" : "\u001B[A";
        } else if ("down".equals(name)) {
            sequence = application ? "\u001BOB" : "\u001B[B";
        } else if ("right".equals(name)) {
            sequence = application ? "\u001BOC" : "\u001B[C";
        } else if ("left".equals(name)) {
            sequence = application ? "\u001BOD" : "\u001B[D";
        } else if ("home".equals(name)) {
            sequence = "\u001B[H";
        } else if ("end".equals(name)) {
            sequence = "\u001B[F";
        } else if ("esc".equals(name)) {
            sequence = "\u001B";
        } else if ("tab".equals(name)) {
            sequence = "\t";
        } else if ("enter".equals(name)) {
            sequence = "\r";
        } else if ("backspace".equals(name)) {
            sequence = "\u007F";
        } else if ("interrupt".equals(name)) {
            session.onSignal(2);   // SIGINT
            return;
        } else if ("eof".equals(name)) {
            // Ctrl-D is a byte, not a signal: the line discipline turns it into EOF.
            session.onInput(new byte[] { 0x04 });
            return;
        } else {
            return;
        }
        try {
            session.onInput(sequence.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always present on Android.
        }
    }

    /** Hardware keys and the volume keys, which phone keyboards otherwise swallow. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        setAlt(event.isAltPressed());
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                sendKey("enter");
                return true;
            case KeyEvent.KEYCODE_DEL:
                sendKey("backspace");
                return true;
            case KeyEvent.KEYCODE_TAB:
                sendKey("tab");
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                sendKey("esc");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.isAltPressed()) {
                    sendKey("up");
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.isAltPressed()) {
                    sendKey("down");
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (event.isAltPressed()) {
                    sendKey("left");
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.isAltPressed()) {
                    sendKey("right");
                    return true;
                }
                return false;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
