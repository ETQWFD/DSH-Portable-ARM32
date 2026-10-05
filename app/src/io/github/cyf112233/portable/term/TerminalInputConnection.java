// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.term;

import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

/**
 * Routes soft-keyboard output into the terminal.
 *
 * A text field's InputConnection is the right shape for this: the IME handles
 * composition, and delete/enter arrive as key events. Everything is sent straight
 * through and nothing is stored, so the session -- not this view -- remains the only
 * place where the line being edited lives. That is what lets a full-screen program
 * inside the guest own the screen.
 */
final class TerminalInputConnection extends BaseInputConnection {

    private final TerminalView view;

    TerminalInputConnection(TerminalView view, boolean fullEditor) {
        super(view, fullEditor);
        this.view = view;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        view.sendText(text);
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        // Composition (pinyin candidates and the like) is only committed once the
        // IME settles on a result; sending it early would fire spurious keys.
        return true;
    }

    @Override
    public boolean finishComposingText() {
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DEL:
                    view.sendKey("backspace");
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                    view.sendKey("enter");
                    return true;
                case KeyEvent.KEYCODE_TAB:
                    view.sendKey("tab");
                    return true;
                default:
                    break;
            }
        }
        return super.sendKeyEvent(event);
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        // Soft keyboards implement backspace as a delete-around; a terminal has no
        // buffer to delete from, so each one becomes a DEL byte.
        for (int i = 0; i < beforeLength; i++) {
            view.sendKey("backspace");
        }
        return true;
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        view.sendKey("enter");
        return true;
    }
}
