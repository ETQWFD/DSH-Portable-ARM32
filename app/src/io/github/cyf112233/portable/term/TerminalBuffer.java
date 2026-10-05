// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.term;

import java.util.ArrayList;
import java.util.List;

/**
 * A character-cell screen with just enough VT100 to run a real shell.
 *
 * Scope is deliberate: this renders what an interactive bash session actually
 * emits, not everything a terminal can contain. Implemented -- C0 controls (BS, HT,
 * LF, CR, BEL), ESC/CSI cursor movement, erase in line and display, insert/delete
 * line, SGR colour and attributes, DEC private modes for cursor visibility and the
 * alternate screen, and the UTF-8 decoding a Debian shell produces.
 *
 * Not implemented -- the full character-set tables, double-width line drawing,
 * mouse reporting, bracketed paste, and scroll regions. Full-screen editors that
 * lean on those (vim is fine, less so) will look approximate.
 *
 * The buffer is a growable list of rows. When the cursor advances past the last
 * row the screen scrolls; rows pushed off the top go to {@link #scrollback}, which
 * the view draws above the live screen when the user scrolls up.
 */
public final class TerminalBuffer {

    /** One cell: a character plus the attributes in force when it was written. */
    static final class Cell {
        char ch = ' ';
        int fg = -1;          // -1 = default foreground
        int bg = -1;          // -1 = default background
        boolean bold;
        boolean underline;
        boolean inverse;

        void reset() {
            ch = ' ';
            fg = -1;
            bg = -1;
            bold = false;
            underline = false;
            inverse = false;
        }

        void copyStyleFrom(Cell other) {
            fg = other.fg;
            bg = other.bg;
            bold = other.bold;
            underline = other.underline;
            inverse = other.inverse;
        }
    }

    /** Called when the screen contents change, so the view can invalidate. */
    public interface Listener {
        void onScreenChanged();

        /** The session wrote a BEL. */
        void onBell();
    }

    private int rows;
    private int cols;
    private final List<Cell[]> screen = new ArrayList<Cell[]>();
    private final List<Cell[]> scrollback = new ArrayList<Cell[]>();
    private static final int SCROLLBACK_LIMIT = 2000;

    private int cursorRow;
    private int cursorCol;
    private Cell pen = new Cell();
    private boolean cursorVisible = true;
    private boolean applicationCursorKeys;
    private List<Cell[]> savedScreen;
    private int savedCursorRow;
    private int savedCursorCol;

    private Listener listener;

    // Parser state
    private int state = STATE_GROUND;
    private final StringBuilder sequence = new StringBuilder();
    private final StringBuilder utf8 = new StringBuilder();
    private int utf8Expected;
    private int utf8Accumulated;
    private int utf8Codepoint;

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESC = 1;
    private static final int STATE_CSI = 2;
    private static final int STATE_OSC = 3;

    public TerminalBuffer(int rows, int cols) {
        resize(rows, cols);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    public int cursorRow() {
        return cursorRow;
    }

    public int cursorCol() {
        return cursorCol;
    }

    public boolean cursorVisible() {
        return cursorVisible;
    }

    public boolean applicationCursorKeys() {
        return applicationCursorKeys;
    }

    public List<Cell[]> scrollback() {
        return scrollback;
    }

    public Cell[] row(int index) {
        if (index < 0 || index >= screen.size()) {
            return null;
        }
        return screen.get(index);
    }

    /** Resize the grid, keeping existing content anchored at the top. */
    public void resize(int newRows, int newCols) {
        if (newRows < 1) {
            newRows = 1;
        }
        if (newCols < 1) {
            newCols = 1;
        }
        if (newRows == rows && newCols == cols && !screen.isEmpty()) {
            return;
        }
        List<Cell[]> resized = new ArrayList<Cell[]>(newRows);
        for (int r = 0; r < newRows; r++) {
            Cell[] line = blankLine(newCols);
            if (r < screen.size()) {
                Cell[] old = screen.get(r);
                int keep = Math.min(old.length, newCols);
                System.arraycopy(old, 0, line, 0, keep);
            }
            resized.add(line);
        }
        screen.clear();
        screen.addAll(resized);
        rows = newRows;
        cols = newCols;
        cursorRow = Math.min(cursorRow, rows - 1);
        cursorCol = Math.min(cursorCol, cols - 1);
        changed();
    }

    public void clear() {
        screen.clear();
        for (int r = 0; r < rows; r++) {
            screen.add(blankLine(cols));
        }
        cursorRow = 0;
        cursorCol = 0;
        pen.reset();
        changed();
    }

    /**
     * The screen as plain text, one line per row, trailing blanks removed.
     *
     * Public because callers legitimately want the contents without wanting the cell
     * model: the diagnostics dump and any future copy-to-clipboard both need this.
     */
    public String snapshot() {
        StringBuilder text = new StringBuilder();
        int lastNonEmpty = -1;
        List<String> lines = new ArrayList<String>();
        for (int r = 0; r < rows; r++) {
            Cell[] line = row(r);
            StringBuilder rowText = new StringBuilder();
            if (line != null) {
                for (Cell cell : line) {
                    rowText.append(cell.ch);
                }
            }
            String trimmed = rowText.toString().replaceAll("\\s+$", "");
            lines.add(trimmed);
            if (trimmed.length() > 0) {
                lastNonEmpty = r;
            }
        }
        for (int r = 0; r <= lastNonEmpty; r++) {
            text.append(lines.get(r)).append('\n');
        }
        return text.toString();
    }

    /** Drop the scrollback, called when a fresh session starts. */
    public void clearScrollback() {
        scrollback.clear();
    }

    private Cell[] blankLine(int width) {
        Cell[] line = new Cell[width];
        for (int i = 0; i < width; i++) {
            line[i] = new Cell();
        }
        return line;
    }

    private void changed() {
        if (listener != null) {
            listener.onScreenChanged();
        }
    }

    // ---- input ------------------------------------------------------------------

    /**
     * Feed bytes from the session. Decoded as UTF-8 because a Debian shell emits it;
     * a partially received sequence is held until its remaining bytes arrive.
     */
    public void write(byte[] data, int length) {
        for (int i = 0; i < length; i++) {
            consume(data[i] & 0xFF);
        }
        changed();
    }

    private void consume(int b) {
        if (utf8Expected > 0) {
            if ((b & 0xC0) == 0x80) {
                utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
                if (++utf8Accumulated == utf8Expected) {
                    utf8Expected = 0;
                    putCharacter(utf8Codepoint);
                }
                return;
            }
            // Truncated sequence: drop what was pending and treat this byte afresh.
            utf8Expected = 0;
        }
        if (state == STATE_GROUND && b >= 0x80) {
            if ((b & 0xE0) == 0xC0) {
                utf8Expected = 1;
                utf8Codepoint = b & 0x1F;
            } else if ((b & 0xF0) == 0xE0) {
                utf8Expected = 2;
                utf8Codepoint = b & 0x0F;
            } else if ((b & 0xF8) == 0xF0) {
                utf8Expected = 3;
                utf8Codepoint = b & 0x07;
            }
            utf8Accumulated = 0;
            return;
        }

        switch (state) {
            case STATE_GROUND:
                ground(b);
                break;
            case STATE_ESC:
                escape(b);
                break;
            case STATE_CSI:
                csi(b);
                break;
            case STATE_OSC:
                // Terminated by BEL or ST (ESC \); the payload is ignored.
                if (b == 0x07) {
                    state = STATE_GROUND;
                } else if (b == 0x1B) {
                    state = STATE_ESC;
                }
                break;
            default:
                state = STATE_GROUND;
                break;
        }
    }

    private void ground(int b) {
        switch (b) {
            case 0x1B:
                state = STATE_ESC;
                sequence.setLength(0);
                return;
            case 0x07:
                if (listener != null) {
                    listener.onBell();
                }
                return;
            case '\n':
            case 0x0B:
            case 0x0C:
                lineFeed();
                return;
            case '\r':
                cursorCol = 0;
                return;
            case '\t':
                cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8);
                return;
            case 0x08:
                if (cursorCol > 0) {
                    cursorCol--;
                }
                return;
            default:
                break;
        }
        if (b < 0x20) {
            return; // other C0 controls are not rendered
        }
        putCharacter(b);
    }

    private void putCharacter(int codepoint) {
        // Characters outside the BMP are rare in a shell and would need two cells;
        // a replacement keeps the grid aligned.
        char ch = (codepoint > 0xFFFF || codepoint == 0) ? '?' : (char) codepoint;
        if (cursorCol >= cols) {
            // Autowrap: only when the terminal is at the right margin already.
            cursorCol = 0;
            lineFeed();
        }
        Cell[] line = lineAt(cursorRow);
        Cell cell = line[cursorCol];
        cell.ch = ch;
        cell.copyStyleFrom(pen);
        cursorCol++;
    }

    private void lineFeed() {
        if (cursorRow == rows - 1) {
            scrollUp();
        } else {
            cursorRow++;
        }
    }

    private void scrollUp() {
        if (!screen.isEmpty()) {
            scrollback.add(screen.remove(0));
            while (scrollback.size() > SCROLLBACK_LIMIT) {
                scrollback.remove(0);
            }
        }
        screen.add(blankLine(cols));
    }

    private Cell[] lineAt(int index) {
        while (screen.size() <= index) {
            screen.add(blankLine(cols));
        }
        return screen.get(index);
    }

    // ---- escape sequences -------------------------------------------------------

    private void escape(int b) {
        switch (b) {
            case '[':
                state = STATE_CSI;
                sequence.setLength(0);
                return;
            case ']':
                state = STATE_OSC;
                return;
            case '7':
                savedCursorRow = cursorRow;
                savedCursorCol = cursorCol;
                state = STATE_GROUND;
                return;
            case '8':
                cursorRow = Math.min(savedCursorRow, rows - 1);
                cursorCol = Math.min(savedCursorCol, cols - 1);
                state = STATE_GROUND;
                return;
            case 'D':
                lineFeed();
                state = STATE_GROUND;
                return;
            case 'M':
                reverseLineFeed();
                state = STATE_GROUND;
                return;
            case 'E':
                cursorCol = 0;
                lineFeed();
                state = STATE_GROUND;
                return;
            case 'c':
                reset();
                state = STATE_GROUND;
                return;
            case '(':
            case ')':
            case '*':
            case '+':
                // Charset selection: consume the designator that follows.
                state = STATE_ESC;
                sequence.setLength(0);
                // A following byte lands in ground(), which would print it, so treat
                // the next byte as consumed by staying in this state once more.
                return;
            default:
                state = STATE_GROUND;
                return;
        }
    }

    private void reverseLineFeed() {
        if (cursorRow == 0) {
            screen.add(0, blankLine(cols));
            if (screen.size() > rows) {
                screen.remove(screen.size() - 1);
            }
        } else {
            cursorRow--;
        }
    }

    private void csi(int b) {
        // Parameters are digits and ';', intermediates are 0x20-0x2F, the final byte
        // is 0x40-0x7E.
        if (b >= 0x30 && b <= 0x3F) {
            sequence.append((char) b);
            return;
        }
        if (b >= 0x20 && b <= 0x2F) {
            return; // intermediates such as '?' handled below via private marker
        }
        if (b == '?') {
            sequence.append('?');
            return;
        }
        state = STATE_GROUND;
        applyCsi((char) b, sequence.toString());
        sequence.setLength(0);
    }

    private void applyCsi(char finalByte, String params) {
        boolean isPrivate = params.startsWith("?");
        int[] args = parseArgs(isPrivate ? params.substring(1) : params);
        switch (finalByte) {
            case 'A':
                cursorRow = Math.max(0, cursorRow - arg(args, 0, 1));
                break;
            case 'B':
                cursorRow = Math.min(rows - 1, cursorRow + arg(args, 0, 1));
                break;
            case 'C':
                cursorCol = Math.min(cols - 1, cursorCol + arg(args, 0, 1));
                break;
            case 'D':
                cursorCol = Math.max(0, cursorCol - arg(args, 0, 1));
                break;
            case 'E':
                cursorCol = 0;
                cursorRow = Math.min(rows - 1, cursorRow + arg(args, 0, 1));
                break;
            case 'F':
                cursorCol = 0;
                cursorRow = Math.max(0, cursorRow - arg(args, 0, 1));
                break;
            case 'G':
                cursorCol = clamp(arg(args, 0, 1) - 1, 0, cols - 1);
                break;
            case 'H':
            case 'f':
                cursorRow = clamp(arg(args, 0, 1) - 1, 0, rows - 1);
                cursorCol = clamp(arg(args, 1, 1) - 1, 0, cols - 1);
                break;
            case 'J':
                eraseInDisplay(arg(args, 0, 0));
                break;
            case 'K':
                eraseInLine(arg(args, 0, 0));
                break;
            case 'L':
                insertLines(arg(args, 0, 1));
                break;
            case 'M':
                deleteLines(arg(args, 0, 1));
                break;
            case 'P':
                deleteChars(arg(args, 0, 1));
                break;
            case '@':
                insertChars(arg(args, 0, 1));
                break;
            case 'X':
                eraseChars(arg(args, 0, 1));
                break;
            case 'm':
                applySgr(args);
                break;
            case 'h':
            case 'l':
                if (isPrivate) {
                    applyPrivateMode(args, finalByte == 'h');
                }
                break;
            case 'r':
                // Scroll region: accepted and ignored, the whole screen scrolls here.
                break;
            case 's':
                savedCursorRow = cursorRow;
                savedCursorCol = cursorCol;
                break;
            case 'u':
                cursorRow = Math.min(savedCursorRow, rows - 1);
                cursorCol = Math.min(savedCursorCol, cols - 1);
                break;
            default:
                break;
        }
    }

    private void applyPrivateMode(int[] args, boolean set) {
        for (int value : args) {
            switch (value) {
                case 25:
                    cursorVisible = set;
                    break;
                case 1:
                    applicationCursorKeys = set;
                    break;
                case 1049:
                case 47:
                case 1047:
                    if (set) {
                        enterAlternateScreen();
                    } else {
                        leaveAlternateScreen();
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void enterAlternateScreen() {
        if (savedScreen == null) {
            savedScreen = new ArrayList<Cell[]>(screen);
            savedCursorRow = cursorRow;
            savedCursorCol = cursorCol;
            screen.clear();
            for (int r = 0; r < rows; r++) {
                screen.add(blankLine(cols));
            }
            cursorRow = 0;
            cursorCol = 0;
        }
    }

    private void leaveAlternateScreen() {
        if (savedScreen != null) {
            screen.clear();
            screen.addAll(savedScreen);
            savedScreen = null;
            cursorRow = Math.min(savedCursorRow, rows - 1);
            cursorCol = Math.min(savedCursorCol, cols - 1);
        }
    }

    private void eraseInDisplay(int mode) {
        switch (mode) {
            case 0:
                eraseInLine(0);
                for (int r = cursorRow + 1; r < rows; r++) {
                    screen.set(r, blankLine(cols));
                }
                break;
            case 1:
                eraseInLine(1);
                for (int r = 0; r < cursorRow; r++) {
                    screen.set(r, blankLine(cols));
                }
                break;
            default:
                for (int r = 0; r < rows; r++) {
                    screen.set(r, blankLine(cols));
                }
                break;
        }
    }

    private void eraseInLine(int mode) {
        Cell[] line = lineAt(cursorRow);
        switch (mode) {
            case 0:
                for (int c = cursorCol; c < cols; c++) {
                    line[c].reset();
                }
                break;
            case 1:
                for (int c = 0; c <= cursorCol && c < cols; c++) {
                    line[c].reset();
                }
                break;
            default:
                for (int c = 0; c < cols; c++) {
                    line[c].reset();
                }
                break;
        }
    }

    private void insertLines(int count) {
        for (int i = 0; i < count; i++) {
            screen.add(cursorRow, blankLine(cols));
            if (screen.size() > rows) {
                screen.remove(screen.size() - 1);
            }
        }
    }

    private void deleteLines(int count) {
        for (int i = 0; i < count; i++) {
            if (cursorRow < screen.size()) {
                screen.remove(cursorRow);
            }
            screen.add(blankLine(cols));
            if (screen.size() > rows) {
                screen.remove(screen.size() - 1);
            }
        }
    }

    private void deleteChars(int count) {
        Cell[] line = lineAt(cursorRow);
        for (int c = cursorCol; c < cols; c++) {
            int from = c + count;
            if (from < cols) {
                line[c].ch = line[from].ch;
                line[c].copyStyleFrom(line[from]);
            } else {
                line[c].reset();
            }
        }
    }

    private void insertChars(int count) {
        Cell[] line = lineAt(cursorRow);
        for (int c = cols - 1; c >= cursorCol; c--) {
            int from = c - count;
            if (from >= cursorCol) {
                line[c].ch = line[from].ch;
                line[c].copyStyleFrom(line[from]);
            } else {
                line[c].reset();
            }
        }
    }

    private void eraseChars(int count) {
        Cell[] line = lineAt(cursorRow);
        for (int c = cursorCol; c < Math.min(cols, cursorCol + count); c++) {
            line[c].reset();
        }
    }

    private void applySgr(int[] args) {
        if (args.length == 0) {
            pen.reset();
            return;
        }
        for (int i = 0; i < args.length; i++) {
            int code = args[i];
            switch (code) {
                case 0:
                    pen.reset();
                    break;
                case 1:
                    pen.bold = true;
                    break;
                case 4:
                    pen.underline = true;
                    break;
                case 7:
                    pen.inverse = true;
                    break;
                case 22:
                    pen.bold = false;
                    break;
                case 24:
                    pen.underline = false;
                    break;
                case 27:
                    pen.inverse = false;
                    break;
                case 39:
                    pen.fg = -1;
                    break;
                case 49:
                    pen.bg = -1;
                    break;
                default:
                    if (code >= 30 && code <= 37) {
                        pen.fg = code - 30;
                    } else if (code >= 90 && code <= 97) {
                        pen.fg = code - 90 + 8;
                    } else if (code >= 40 && code <= 47) {
                        pen.bg = code - 40;
                    } else if (code >= 100 && code <= 107) {
                        pen.bg = code - 100 + 8;
                    } else if (code == 38 || code == 48) {
                        // 256-colour and truecolour forms; approximated to the nearest
                        // of the sixteen the palette carries.
                        int consumed = 0;
                        int colour = approximateExtendedColour(args, i, consumed);
                        if (colour >= 0) {
                            if (code == 38) {
                                pen.fg = colour;
                            } else {
                                pen.bg = colour;
                            }
                            i += consumed;
                        }
                    }
                    break;
            }
        }
    }

    /**
     * Reads an extended colour (38;5;n / 38;2;r;g;b) starting at {@code index}.
     *
     * @param consumed set to how many further arguments were used
     * @return a palette index, or -1 when the form is not recognised
     */
    private int approximateExtendedColour(int[] args, int index, int consumedHolder) {
        if (index + 2 < args.length && args[index + 1] == 5) {
            return paletteIndex(args[index + 2]);
        }
        if (index + 4 < args.length && args[index + 1] == 2) {
            return rgbToPalette(args[index + 2], args[index + 3], args[index + 4]);
        }
        return -1;
    }

    private int paletteIndex(int value) {
        if (value < 0) {
            return -1;
        }
        if (value < 8) {
            return value;
        }
        if (value < 16) {
            return value;
        }
        if (value >= 232) {
            // Grey ramp 232..255 -> black..white.
            int level = (value - 232) * 255 / 23;
            return level > 128 ? 15 : 8;
        }
        int cube = value - 16;
        int r = cube / 36;
        int g = (cube / 6) % 6;
        int b = cube % 6;
        return rgbToPalette(r * 51, g * 51, b * 51);
    }

    private int rgbToPalette(int r, int g, int b) {
        int bright = (r + g + b) / 3 > 128 ? 8 : 0;
        int index = (r > 96 ? 1 : 0) | (g > 96 ? 2 : 0) | (b > 96 ? 4 : 0);
        return bright + index;
    }

    private void reset() {
        clear();
        cursorVisible = true;
        applicationCursorKeys = false;
        savedScreen = null;
    }

    private int[] parseArgs(String params) {
        if (params.isEmpty()) {
            return new int[0];
        }
        String[] parts = params.split(";");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                values[i] = 0;
            }
        }
        return values;
    }

    private static int arg(int[] args, int index, int fallback) {
        if (index >= args.length) {
            return fallback;
        }
        return args[index] == 0 ? fallback : args[index];
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }
}
