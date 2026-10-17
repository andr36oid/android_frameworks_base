/*
 * Copyright (C) 2026 andr36oid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsSeekBar;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.tileimpl.QSTileBaseView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Lets a game controller use the open notification shade without touching the screen. The
 * shade opens with the full quick settings and the notifications below them (see
 * NotificationPanelViewController.mQsScrollAway). The d-pad moves a clearly drawn focus
 * through quick settings, the brightness slider, the footer buttons and on down into the
 * notifications, which scrolls the quick settings up out of view. Buttons:
 * <ul>
 * <li>A: press, hold for a long press (the key layout sends DPAD_CENTER for it)
 * <li>B: back: closes notification controls, tile details or the tile editor, then the shade
 * <li>X: dismiss the focused notification or media player; on a tile with details (Wi-Fi,
 * Bluetooth, ...) open the details
 * <li>Y: long press: a tile's settings page, a notification's or the media player's controls
 * <li>R1 / L1: jump to the notifications / back up to the first tile
 * <li>Right / Left on a notification: expand / collapse it
 * </ul>
 *
 * <p>A and B go to the focused view and the shade as before. The d-pad moves are done here
 * (except for the brightness slider, which moves with left and right) because the stock focus
 * search can't find the notifications: they are all laid out at the top of the list and moved
 * into place with a translation, and the ones past the bottom are hidden in the shelf.
 */
public class ShadeKeyNavigation implements ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnGlobalFocusChangeListener, PanelExpansionListener {

    private static final String TAG = "ShadeKeyNav";
    private static final long PENDING_FOCUS_TIMEOUT_MS = 1000;
    // Views faded further than this are gone as far as focus is concerned
    private static final float MIN_ALPHA = 0.1f;
    private static final int FOCUS_FILL_ALPHA = 0x40;

    private final NotificationShadeWindowView mRoot;
    private final NotificationPanelViewController mPanel;
    private final NotificationStackScrollLayout mStack;
    // The shade is open and not the lock screen or bouncer, which keep their own key handling
    private final BooleanSupplier mShadeOpen;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mStrokeWidth;
    private final float mCornerRadius;
    private final RectF mFocusRect = new RectF();
    private boolean mShowFocus;
    private final int[] mTmpLocation = new int[2];

    // Focus changes made here, so they are not taken for a lost focus
    private boolean mMovingFocus;
    // Start on the first tile: the shade was just opened or quick settings changed size
    private boolean mFocusFirst = true;
    // The focused view was hidden or removed
    private boolean mFocusLost;
    // Focus this once it shows, e.g. a notification that is being scrolled into view
    private View mPendingFocus;
    // A quick settings page sliding in: its tile gets the focus once it is in place
    private ViewPager mPendingPager;
    private boolean mPendingPageRight;
    // Focus the first button of these notification controls once they are open
    private ExpandableNotificationRow mPendingGuts;
    private long mPendingUntil;

    /** A place the focus can go in the notification list. */
    private static class Target {
        final View view;
        // The notification or other list item the view belongs to
        final ExpandableView item;

        Target(View view, ExpandableView item) {
            this.view = view;
            this.item = item;
        }
    }

    public ShadeKeyNavigation(NotificationShadeWindowView root,
            NotificationPanelViewController panel, NotificationStackScrollLayout stack,
            BooleanSupplier shadeOpen) {
        mRoot = root;
        mPanel = panel;
        mStack = stack;
        mShadeOpen = shadeOpen;

        final Context context = root.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        mStrokeWidth = 3 * density;
        mCornerRadius = 8 * density;
        final int accent = Utils.getColorAttrDefaultColor(context, android.R.attr.colorAccent);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mStrokeWidth);
        mStrokePaint.setColor(accent);
        mFillPaint.setColor(accent);
        mFillPaint.setAlpha(FOCUS_FILL_ALPHA);

        final ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        observer.addOnGlobalFocusChangeListener(this);
        panel.addExpansionListener(this);
    }

    private boolean isActive() {
        return mRoot.hasWindowFocus() && mShadeOpen.getAsBoolean();
    }

    /**
     * Keys handled before the focused view sees them. Returns true if the key was used.
     */
    public boolean onKeyBeforeViews(KeyEvent event) {
        if (!isActive()) {
            return false;
        }
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down && event.getRepeatCount() == 0) {
                    onButton(event.getKeyCode());
                }
                // Used either way, so the key's fallback (delete, space) never runs here
                return true;
            case KeyEvent.KEYCODE_BACK:
                return closeGuts(down);
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (viewTakesKey(mRoot.findFocus(), event.getKeyCode())) {
                    return false;
                }
                if (down) {
                    moveFocus(getDirection(event.getKeyCode()));
                }
                return true;
        }
        return false;
    }

    /**
     * Views that use the d-pad themselves get it first: the brightness slider moves with left
     * and right, text fields move the cursor, the tile editor's list moves by itself. The rest
     * (scroll views, the tile pager) would move the focus with the stock focus search.
     */
    /** The tile editor's list if the view is in it. */
    private static RecyclerView getEditorList(View view) {
        RecyclerView list = null;
        for (ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (list == null && parent instanceof RecyclerView) {
                list = (RecyclerView) parent;
            }
            if (list != null && ((View) parent).getId() == R.id.qs_customize) {
                return list;
            }
        }
        return null;
    }

    private boolean viewTakesKey(View focused, int keyCode) {
        if (focused == null) {
            return false;
        }
        if (focused instanceof EditText) {
            return true;
        }
        if (focused instanceof AbsSeekBar) {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        boolean inList = false;
        for (ViewParent parent = focused.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (parent instanceof RecyclerView) {
                inList = true;
            }
            // Only the tile editor's list moves by itself; elsewhere a list would keep the
            // keys and get nowhere
            if (inList && ((View) parent).getId() == R.id.qs_customize) {
                return true;
            }
        }
        return false;
    }

    private static int getDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return View.FOCUS_RIGHT;
            default:
                return 0;
        }
    }

    /**
     * Left and right on a tile of the full quick settings go along the page and on to the next
     * page at the edge. Returns false if the focus is not on a tile in the pager.
     */
    private boolean moveInPager(View focused, boolean right) {
        if (!(focused instanceof QSTileBaseView)) {
            return false;
        }
        ViewPager pager = null;
        for (ViewParent parent = focused.getParent(); parent instanceof View && pager == null;
                parent = parent.getParent()) {
            if (parent instanceof ViewPager) {
                pager = (ViewPager) parent;
            }
        }
        if (pager == null) {
            return false;
        }
        final ArrayList<View> tiles = new ArrayList<>();
        for (View view : getPanelTargets()) {
            if (isInside(view, pager)) {
                tiles.add(view);
            }
        }
        final View next = findNext(rectOf(focused), tiles, focused,
                right ? View.FOCUS_RIGHT : View.FOCUS_LEFT);
        if (next != null) {
            requestFocus(next);
            return true;
        }
        final int page = pager.getCurrentItem() + (right ? 1 : -1);
        if (pager.getAdapter() != null && page >= 0 && page < pager.getAdapter().getCount()) {
            pager.setCurrentItem(page, true /* smoothScroll */);
            // A tile of the new page once it has slid in; asking now would find no tile fully
            // on the screen and land on the brightness slider
            mPendingPager = pager;
            mPendingPageRight = right;
            mPendingUntil = SystemClock.uptimeMillis() + PENDING_FOCUS_TIMEOUT_MS;
            mRoot.invalidate();
        }
        return true;
    }

    /**
     * Keys the focused view did not use. Returns true if the key was used.
     */
    public boolean onKeyAfterViews(KeyEvent event) {
        final int direction = getDirection(event.getKeyCode());
        if (!isActive() || direction == 0) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            final View focused = mRoot.findFocus();
            if (focused != null && viewTakesKey(focused, event.getKeyCode())
                    && getEditorList(focused) != null) {
                // The tile editor's list: its own focus search lays out and scrolls to rows
                // that are off the screen, ours only knows what is on it
                final View next = focused.focusSearch(direction);
                final View editor = mRoot.findViewById(R.id.qs_customize);
                if (next != null && editor != null && isInside(next, editor)) {
                    next.requestFocus(direction);
                }
                return true;
            }
            // E.g. the slider is at its end: move on
            moveFocus(direction);
        }
        // Also when nothing is there, the stock focus search would only get lost
        return true;
    }

    /** The shade leaves touch mode on a d-pad press: start on the first tile. */
    public boolean restoreDefaultFocus() {
        return isActive() && focusFirst();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        mPendingFocus = null;
        mPendingPager = null;
        mPendingGuts = null;
        mRoot.invalidate();
    }

    @Override
    public void onPanelExpansionChanged(float expansion, boolean tracking) {
        if (expansion == 0f) {
            // Closed: the next time it opens, start over on the first tile
            mFocusFirst = true;
            mFocusLost = false;
            mPendingFocus = null;
            mPendingPager = null;
            mPendingGuts = null;
        }
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (!mMovingFocus && oldFocus != null && oldFocus != newFocus
                && (!oldFocus.isAttachedToWindow() || !oldFocus.isShown())) {
            // The quick row went away when the full quick settings opened, a notification
            // was removed, ...
            Log.d(TAG, "focus lost: " + describe(oldFocus) + " went away, now "
                    + describe(newFocus));
            mFocusLost = true;
        }
        mRoot.invalidate();
    }

    @Override
    public boolean onPreDraw() {
        final boolean active = isActive() && !mRoot.isInTouchMode();
        if (active) {
            final boolean timedOut = SystemClock.uptimeMillis() > mPendingUntil;
            if (mPendingFocus != null) {
                if (mPendingFocus.isShown()) {
                    requestFocus(mPendingFocus);
                    mPendingFocus = null;
                } else if (timedOut) {
                    Log.d(TAG, "gave up waiting for " + describe(mPendingFocus));
                    mPendingFocus = null;
                }
            }
            if (mPendingGuts != null) {
                final View first = mPendingGuts.areGutsExposed()
                        ? firstTarget(mPendingGuts.getGuts()) : null;
                if (first != null) {
                    requestFocus(first);
                    mPendingGuts = null;
                } else if (timedOut) {
                    mPendingGuts = null;
                }
            }
            final View focused = mRoot.findFocus();
            final View scope = getPanelScope();
            if (focused != null && scope != mRoot && !isInside(focused, scope)) {
                // Tile details or the tile editor opened over the focused tile
                mFocusLost = true;
            }
            if (focused != null && focused != mRoot && !isUsefulFocusable(focused)) {
                // A whole container got the focus (the quick settings panel, the scrollable
                // notification list): it would be outlined over most of the screen and keys
                // would do nothing there
                focused.setDefaultFocusHighlightEnabled(false);
                mFocusLost = true;
            }
            if (mPendingPager != null) {
                final int width = mPendingPager.getWidth() + mPendingPager.getPageMargin();
                final boolean settled = width > 0
                        && mPendingPager.getScrollX() == mPendingPager.getCurrentItem() * width;
                if (settled || timedOut) {
                    final ViewPager pager = mPendingPager;
                    mPendingPager = null;
                    mFocusLost = false;
                    if (!focusPageTile(pager, mPendingPageRight)) {
                        focusFirst();
                    }
                } else {
                    // keep checking while it slides
                    mRoot.postInvalidateOnAnimation();
                }
            } else if ((mFocusFirst || mFocusLost) && mPendingFocus == null) {
                if (mFocusLost) {
                    Log.d(TAG, "back to the first tile, focus was on " + describe(focused));
                }
                focusFirst();
            }
        }
        updateFocusRect(active);
        return true;
    }

    /** Draws the focus highlight over the shade. */
    public void drawFocus(Canvas canvas) {
        if (mShowFocus) {
            canvas.drawRoundRect(mFocusRect, mCornerRadius, mCornerRadius, mFillPaint);
            canvas.drawRoundRect(mFocusRect, mCornerRadius, mCornerRadius, mStrokePaint);
        }
    }

    private void updateFocusRect(boolean active) {
        final View focused = active ? mRoot.findFocus() : null;
        boolean show = false;
        float left = 0, top = 0, right = 0, bottom = 0;
        if (focused != null && focused != mRoot && isUsefulFocusable(focused)
                && focused.isShown() && focused.getGlobalVisibleRect(new Rect())) {
            final Rect rect = rectOf(focused);
            mRoot.getLocationInWindow(mTmpLocation);
            final float inset = mStrokeWidth / 2;
            left = rect.left - mTmpLocation[0] + inset;
            top = rect.top - mTmpLocation[1] + inset;
            right = rect.right - mTmpLocation[0] - inset;
            bottom = rect.bottom - mTmpLocation[1] - inset;
            show = right > left && bottom > top;
        }
        if (show != mShowFocus || (show && (left != mFocusRect.left || top != mFocusRect.top
                || right != mFocusRect.right || bottom != mFocusRect.bottom))) {
            mShowFocus = show;
            mFocusRect.set(left, top, right, bottom);
            mRoot.invalidate();
        }
    }

    private void onButton(int keyCode) {
        final View focused = mRoot.findFocus();
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_R1: {
                // Down to the notifications
                final ArrayList<Target> notifications = getNotificationTargets();
                if (!notifications.isEmpty()) {
                    focusTarget(notifications.get(0));
                } else {
                    mPanel.flingQsFromKey(true, this::focusFirstLater);
                }
                return;
            }
            case KeyEvent.KEYCODE_BUTTON_L1:
                // Back up to the first tile
                if (!mPanel.flingQsFromKey(true, this::focusFirstLater)) {
                    mPanel.animateQsScrollAway(0);
                    focusFirst();
                }
                return;
        }
        // Anywhere on the media player (a button, the album art, its menu, or the card itself
        // when the focus ended up there), X dismisses it and Y opens its menu
        final View player = !mFocusFirst && focused != null ? getMediaPlayer(focused) : null;
        if (player != null && (keyCode == KeyEvent.KEYCODE_BUTTON_X
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y)) {
            onMediaButton(player, keyCode);
            return;
        }
        if (mFocusFirst || focused == null || !isTarget(focused)) {
            // Nothing sensible has focus yet, show where it is first
            focusFirst();
            return;
        }
        final ExpandableNotificationRow row = getRow(focused);
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            final View editor = mRoot.findViewById(R.id.qs_customize);
            if (editor instanceof QSCustomizer && isInside(focused, editor)) {
                // The tile editor: X takes the tile out
                ((QSCustomizer) editor).removeTile(focused);
            } else if (row != null) {
                dismiss(focused, row);
            } else if (focused instanceof QSTileBaseView) {
                openTileDetails(focused);
            }
        } else if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (row != null && (focused == row || !focused.isLongClickable())) {
                if (!row.areGutsExposed()) {
                    row.doLongClickCallback(row.getWidth() / 2, row.getActualHeight() / 2);
                    mPendingGuts = row;
                    mPendingUntil = SystemClock.uptimeMillis() + PENDING_FOCUS_TIMEOUT_MS;
                }
            } else if (focused.isLongClickable()) {
                focused.performLongClick();
            }
        }
    }

    private void focusFirstLater() {
        mFocusFirst = true;
        mRoot.invalidate();
    }

    /** Tiles like Wi-Fi and Bluetooth have a second button in their label for the details. */
    private void openTileDetails(View tile) {
        final ArrayList<View> views = new ArrayList<>();
        tile.addFocusables(views, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        for (View view : views) {
            if (view != tile && view.isClickable() && view.isShown()) {
                view.performClick();
                return;
            }
        }
    }

    private void dismiss(View focused, ExpandableNotificationRow row) {
        final ArrayList<Target> targets = getNotificationTargets();
        final int index = indexOf(targets, focused);
        Target next = null;
        // The next notification, or else the one before
        for (int i = index + 1; index >= 0 && i < targets.size() && next == null; i++) {
            if (!isInside(targets.get(i).view, row)) {
                next = targets.get(i);
            }
        }
        for (int i = index - 1; i >= 0 && next == null; i--) {
            if (!isInside(targets.get(i).view, row)) {
                next = targets.get(i);
            }
        }
        if (!mStack.dismissChildFromKey(row)) {
            return;
        }
        if (next != null) {
            focusTarget(next);
        } else {
            focusFirst();
        }
    }

    /** The media player card the view is in, or null. */
    private View getMediaPlayer(View view) {
        for (View v = view; v != null && v != mRoot; ) {
            if (v.getId() == R.id.qs_media_controls) {
                return v;
            }
            final ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * X on the media player dismisses it at once, also while it plays (Dismiss stops it), with
     * its menu open or not. Y opens its menu (settings, dismiss, cancel) with the focus on
     * Dismiss.
     */
    private void onMediaButton(View player, int keyCode) {
        final View dismiss = player.findViewById(R.id.dismiss);
        final View cancel = player.findViewById(R.id.cancel);
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            if (dismiss != null && dismiss.isEnabled()) {
                dismiss.performClick();
                focusFirstLater();
            }
            return;
        }
        if (isMediaMenuOpen(player)) {
            return;
        }
        if (player.performLongClick()) {
            mPendingFocus = dismiss != null && dismiss.isEnabled() ? dismiss : cancel;
            mPendingUntil = SystemClock.uptimeMillis() + PENDING_FOCUS_TIMEOUT_MS;
            mRoot.invalidate();
        }
    }

    private static boolean isMediaMenuOpen(View player) {
        final View cancel = player.findViewById(R.id.cancel);
        return cancel != null && cancel.isShown() && cancel.getAlpha() > 0.5f;
    }

    /** B closes a notification's or the media player's controls before anything else. */
    private boolean closeGuts(boolean down) {
        final View focused = mRoot.findFocus();
        final View player = focused != null ? getMediaPlayer(focused) : null;
        if (player != null && isMediaMenuOpen(player)) {
            // B closes the media player's menu, back on its first button
            if (!down) {
                player.findViewById(R.id.cancel).performClick();
                final View first = player.findViewById(R.id.album_art);
                mPendingFocus = first != null && first.isClickable() ? first
                        : player.findViewById(R.id.action0);
                mPendingUntil = SystemClock.uptimeMillis() + PENDING_FOCUS_TIMEOUT_MS;
                mRoot.invalidate();
            }
            return true;
        }
        final ExpandableNotificationRow row = focused != null ? getRow(focused) : null;
        if (row == null || !row.areGutsExposed() || row.getGuts() == null) {
            return false;
        }
        if (!down) {
            final NotificationGuts guts = row.getGuts();
            guts.closeControls(true /* leavebehinds */, true /* controls */,
                    row.getWidth() / 2, row.getActualHeight() / 2, false /* force */);
            requestFocus(row);
        }
        return true;
    }

    private void moveFocus(int direction) {
        mPendingFocus = null;
        final View focused = mRoot.findFocus();
        final ArrayList<View> panelTargets = getPanelTargets();
        final ArrayList<Target> notifications = getNotificationTargets();
        if (mFocusFirst || focused == null) {
            focusFirst(panelTargets, notifications);
            return;
        }
        final int index = indexOf(notifications, focused);
        if (index >= 0) {
            moveInNotifications(notifications, index, direction, panelTargets);
            return;
        }
        if (!panelTargets.contains(focused)) {
            // Also when a container had the focus: start on the first tile
            focusFirst(panelTargets, notifications);
            return;
        }
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)
                && moveInPager(focused, direction == View.FOCUS_RIGHT)) {
            return;
        }
        final Rect from = rectOf(focused);
        final View next = findNext(from, panelTargets, focused, direction);
        if (direction == View.FOCUS_DOWN) {
            // Below quick settings come the notifications, maybe below the screen: the quick
            // settings scroll up to show them
            final Target first = notifications.isEmpty() ? null : notifications.get(0);
            Log.d(TAG, "down from " + describe(focused) + " " + from + ": next "
                    + describe(next) + (next != null ? " " + rectOf(next) : "")
                    + ", first notification " + (first != null
                            ? describe(first.view) + " " + rectOf(first.view)
                            + (first.view.isShown() ? "" : " hidden") : "none")
                    + ", " + panelTargets.size() + " panel targets, away "
                    + mPanel.getQsScrollAway());
            if (first != null && (next == null
                    || rectOf(first.view).top < rectOf(next).top)) {
                focusTarget(first);
                return;
            }
        }
        if (next != null) {
            focusPanelTarget(next);
        }
    }

    /** Focuses something outside the list, scrolling the quick settings back to show it. */
    private void focusPanelTarget(View view) {
        final float away = mPanel.getQsScrollAway();
        if (away > 0) {
            final Rect rect = rectOf(view);
            mRoot.getLocationInWindow(mTmpLocation);
            final int top = mTmpLocation[1] + Math.round(mCornerRadius);
            if (rect.top < top) {
                mPanel.animateQsScrollAway(away - (top - rect.top));
            }
        }
        requestFocus(view);
    }

    private void moveInNotifications(ArrayList<Target> targets, int index, int direction,
            ArrayList<View> panelTargets) {
        final Target current = targets.get(index);
        final Rect from = rectOf(current.view);
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            final int step = direction == View.FOCUS_DOWN ? 1 : -1;
            int next = index + step;
            while (next >= 0 && next < targets.size() && sameLine(current, targets.get(next))) {
                next += step;
            }
            if (next < 0) {
                // Above the first notification are the quick settings, maybe scrolled away
                final View up = findNext(from, panelTargets, null, View.FOCUS_UP);
                if (up != null) {
                    focusPanelTarget(up);
                }
                return;
            }
            if (next >= targets.size()) {
                return;
            }
            // Of the buttons on that line, the one closest to where the focus was
            final Target line = targets.get(next);
            Target best = line;
            int bestDistance = Math.abs(rectOf(best.view).centerX() - from.centerX());
            for (int i = next + step; i >= 0 && i < targets.size()
                    && sameLine(line, targets.get(i)); i += step) {
                final int distance = Math.abs(rectOf(targets.get(i).view).centerX()
                        - from.centerX());
                if (distance < bestDistance) {
                    best = targets.get(i);
                    bestDistance = distance;
                }
            }
            focusTarget(best);
            return;
        }

        // Left and right go along a line of buttons, or else expand and collapse
        final boolean right = direction == View.FOCUS_RIGHT;
        Target best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Target target : targets) {
            if (target == current || !sameLine(current, target)) {
                continue;
            }
            final int dx = rectOf(target.view).centerX() - from.centerX();
            if ((right ? dx > 0 : dx < 0) && Math.abs(dx) < bestDistance) {
                best = target;
                bestDistance = Math.abs(dx);
            }
        }
        if (best != null) {
            focusTarget(best);
        } else if (current.view instanceof ExpandableNotificationRow) {
            setExpanded((ExpandableNotificationRow) current.view, right);
        }
    }

    private void setExpanded(ExpandableNotificationRow row, boolean expand) {
        final boolean expanded = row.isSummaryWithChildren()
                ? row.isGroupExpanded() : row.isExpanded();
        final boolean expandable = row.isSummaryWithChildren() || row.isExpandable();
        if (expanded != expand && (expandable || expanded)) {
            // The same toggle the expand button and accessibility use
            row.performAccessibilityAction(expand ? AccessibilityNodeInfo.ACTION_EXPAND
                    : AccessibilityNodeInfo.ACTION_COLLAPSE, null);
        }
    }

    /** Focuses a notification target, scrolling the list to show it first. */
    private void focusTarget(Target target) {
        final View view = target.view;
        final ExpandableNotificationRow row = getRow(view);
        final ExpandableView owner = view instanceof ExpandableView ? (ExpandableView) view
                : row != null ? row : target.item;
        if (owner == view) {
            mStack.scrollToShow(owner, 0, owner.getIntrinsicHeight());
        } else {
            owner.getLocationInWindow(mTmpLocation);
            final int ownerTop = mTmpLocation[1];
            view.getLocationInWindow(mTmpLocation);
            final int top = mTmpLocation[1] - ownerTop;
            mStack.scrollToShow(owner, top, top + view.getHeight());
        }
        if (view.isShown()) {
            requestFocus(view);
        } else {
            // Hidden in the shelf until the list has scrolled
            mPendingFocus = view;
            mPendingUntil = SystemClock.uptimeMillis() + PENDING_FOCUS_TIMEOUT_MS;
            mRoot.invalidate();
        }
    }

    /** The top-left tile of the page after moving right, the top-right one after moving left. */
    private boolean focusPageTile(ViewPager pager, boolean right) {
        View best = null;
        Rect bestRect = null;
        for (View view : getPanelTargets()) {
            if (!(view instanceof QSTileBaseView) || !isInside(view, pager)) {
                continue;
            }
            final Rect rect = rectOf(view);
            if (best == null || rect.top < bestRect.top - 1
                    || (Math.abs(rect.top - bestRect.top) <= 1
                            && (right ? rect.left < bestRect.left : rect.left > bestRect.left))) {
                best = view;
                bestRect = rect;
            }
        }
        if (best == null) {
            return false;
        }
        requestFocus(best);
        return true;
    }

    private boolean focusFirst() {
        return focusFirst(getPanelTargets(), getNotificationTargets());
    }

    /** Focuses the first quick settings tile, or else whatever comes first. */
    private boolean focusFirst(ArrayList<View> panelTargets, ArrayList<Target> notifications) {
        View first = null;
        Rect firstRect = null;
        for (View view : panelTargets) {
            if (view instanceof QSTileBaseView) {
                final Rect rect = rectOf(view);
                if (first == null || isBefore(rect, firstRect)) {
                    first = view;
                    firstRect = rect;
                }
            }
        }
        if (first == null) {
            final Target target = firstOnScreen(notifications);
            if (target != null) {
                first = target.view;
            }
        }
        if (first == null) {
            for (View view : panelTargets) {
                final Rect rect = rectOf(view);
                if (first == null || isBefore(rect, firstRect)) {
                    first = view;
                    firstRect = rect;
                }
            }
        }
        if (first == null || !requestFocus(first)) {
            return false;
        }
        mFocusFirst = false;
        mFocusLost = false;
        return true;
    }

    private static boolean isBefore(Rect rect, Rect other) {
        return rect.top < other.top || (rect.top == other.top && rect.left < other.left);
    }

    private boolean requestFocus(View view) {
        final boolean wasMoving = mMovingFocus;
        mMovingFocus = true;
        final boolean focused = mRoot.isInTouchMode() ? view.requestFocusFromTouch()
                : view.requestFocus();
        mMovingFocus = wasMoving;
        mRoot.invalidate();
        return focused;
    }

    /** Everything outside the notification list: header, tiles, slider, footer buttons. */
    private ArrayList<View> getPanelTargets() {
        final View scope = getPanelScope();
        final boolean scrolledAway = scope == mRoot && mPanel.getQsScrollAway() > 0;
        final ArrayList<View> focusables = new ArrayList<>();
        scope.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        final ArrayList<View> targets = new ArrayList<>();
        for (View view : focusables) {
            if (mStack != null && isInside(view, mStack)) {
                continue;
            }
            // While the quick settings are scrolled up, their hidden part still counts: moving
            // up scrolls it back
            if (isUsefulFocusable(view)
                    && (isOnScreen(view) || (scrolledAway && isVisible(view)))) {
                targets.add(view);
            }
        }
        // The media player: its buttons are the targets, not the whole card (the album art
        // stands in for the card and opens the app). Not its seek bar: it would keep left
        // and right, and seeking with it only works by touch
        targets.removeIf(view -> view.getId() == R.id.qs_media_controls
                || (view instanceof AbsSeekBar && getMediaPlayer(view) != null));
        // A tile's label can be a second button inside the tile, X presses that one
        final HashSet<View> targetSet = new HashSet<>(targets);
        targets.removeIf(view -> hasAncestorIn(view, targetSet));
        return targets;
    }

    /** The tile editor or tile details when open (the tiles are still there behind them). */
    private View getPanelScope() {
        final View customizer = mRoot.findViewById(R.id.qs_customize);
        if (customizer != null && customizer.isShown()) {
            return customizer;
        }
        final View detail = mRoot.findViewById(R.id.qs_detail);
        if (detail != null && detail.isShown()) {
            return detail;
        }
        return mRoot;
    }

    /** Notifications, their buttons and the other list items, in list order. */
    private ArrayList<Target> getNotificationTargets() {
        final ArrayList<Target> targets = new ArrayList<>();
        // Not while the tile editor or tile details cover everything
        if (mStack == null || !mStack.isShown() || getPanelScope() != mRoot) {
            return targets;
        }
        for (int i = 0; i < mStack.getChildCount(); i++) {
            final View child = mStack.getChildAt(i);
            if (!(child instanceof ExpandableView) || child instanceof NotificationShelf
                    || child.getVisibility() == View.GONE) {
                continue;
            }
            final ExpandableView item = (ExpandableView) child;
            if (item instanceof ExpandableNotificationRow) {
                addRowTargets(targets, item, (ExpandableNotificationRow) item);
            } else {
                addTargetsInside(targets, item, item);
            }
        }
        return targets;
    }

    private void addRowTargets(ArrayList<Target> targets, ExpandableView item,
            ExpandableNotificationRow row) {
        if (row.areGutsExposed() && row.getGuts() != null) {
            final int count = targets.size();
            addTargetsInside(targets, item, row.getGuts());
            if (targets.size() > count) {
                return;
            }
        }
        // Also when hidden in the shelf: the list scrolls to it
        targets.add(new Target(row, item));
        final ArrayList<View> focusables = new ArrayList<>();
        row.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        row.getLocationInWindow(mTmpLocation);
        final int rowBottom = mTmpLocation[1] + row.getActualHeight();
        for (View view : focusables) {
            // Action and reply buttons of this notification, not of the ones grouped in it
            if ((view instanceof Button || view instanceof ImageButton) && getRow(view) == row
                    && isVisible(view)) {
                view.getLocationInWindow(mTmpLocation);
                if (mTmpLocation[1] + view.getHeight() <= rowBottom) {
                    targets.add(new Target(view, item));
                }
            }
        }
        final List<ExpandableNotificationRow> children = row.getAttachedChildren();
        if (children != null && row.isGroupExpanded()) {
            for (ExpandableNotificationRow child : children) {
                if (child.getVisibility() != View.GONE) {
                    addRowTargets(targets, item, child);
                }
            }
        }
    }

    private void addTargetsInside(ArrayList<Target> targets, ExpandableView item, View parent) {
        final ArrayList<View> focusables = new ArrayList<>();
        parent.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        for (View view : focusables) {
            if (isUsefulFocusable(view) && isVisible(view)) {
                targets.add(new Target(view, item));
            }
        }
    }

    private View firstTarget(View parent) {
        final ArrayList<Target> targets = new ArrayList<>();
        addTargetsInside(targets, null, parent);
        return targets.isEmpty() ? null : targets.get(0).view;
    }

    private Target firstOnScreen(ArrayList<Target> targets) {
        for (Target target : targets) {
            if (isOnScreen(target.view) && !rectOf(target.view).isEmpty()) {
                return target;
            }
        }
        return null;
    }

    /** Returns the index of the target that is or holds the focused view, or -1. */
    private int indexOf(ArrayList<Target> targets, View focused) {
        for (View view = focused; view != null && view != mStack; ) {
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i).view == view) {
                    return i;
                }
            }
            final ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return -1;
    }

    private boolean isTarget(View view) {
        return getPanelTargets().contains(view)
                || indexOf(getNotificationTargets(), view) >= 0;
    }

    /** Buttons in one line of the same list item, e.g. a notification's actions. */
    private boolean sameLine(Target a, Target b) {
        if (a.item != b.item || a.view instanceof ExpandableNotificationRow
                || b.view instanceof ExpandableNotificationRow) {
            return false;
        }
        final Rect rectA = rectOf(a.view);
        final Rect rectB = rectOf(b.view);
        return rectA.top < rectB.bottom && rectB.top < rectA.bottom;
    }

    /**
     * Finds the nearest view in a direction, preferring views that line up with where the
     * focus is (like the stock focus search).
     */
    private View findNext(Rect from, List<View> candidates, View exclude, int direction) {
        View best = null;
        boolean bestInBeam = false;
        long bestScore = Long.MAX_VALUE;
        for (View view : candidates) {
            if (view == exclude) {
                continue;
            }
            final Rect to = rectOf(view);
            final boolean vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
            final long major;
            switch (direction) {
                case View.FOCUS_UP:
                    if (!((from.bottom > to.bottom || from.top >= to.bottom)
                            && from.top > to.top)) {
                        continue;
                    }
                    major = Math.max(0, from.top - to.bottom);
                    break;
                case View.FOCUS_DOWN:
                    if (!((from.top < to.top || from.bottom <= to.top)
                            && from.bottom < to.bottom)) {
                        continue;
                    }
                    major = Math.max(0, to.top - from.bottom);
                    break;
                case View.FOCUS_LEFT:
                    if (!((from.right > to.right || from.left >= to.right)
                            && from.left > to.left)) {
                        continue;
                    }
                    major = Math.max(0, from.left - to.right);
                    break;
                default:
                    if (!((from.left < to.left || from.right <= to.left)
                            && from.right < to.right)) {
                        continue;
                    }
                    major = Math.max(0, to.left - from.right);
                    break;
            }
            final long minor = vertical ? Math.abs(to.centerX() - from.centerX())
                    : Math.abs(to.centerY() - from.centerY());
            final boolean inBeam = vertical
                    ? to.left < from.right && from.left < to.right
                    : to.top < from.bottom && from.top < to.bottom;
            final long score = 13 * major * major + minor * minor;
            if ((inBeam && !bestInBeam) || (inBeam == bestInBeam && score < bestScore)) {
                best = view;
                bestInBeam = inBeam;
                bestScore = score;
            }
        }
        return best;
    }

    /** Where a view is in the window, for notifications only the part that is drawn. */
    private Rect rectOf(View view) {
        view.getLocationInWindow(mTmpLocation);
        final Rect rect = new Rect(mTmpLocation[0], mTmpLocation[1],
                mTmpLocation[0] + view.getWidth(), mTmpLocation[1] + view.getHeight());
        if (view instanceof ExpandableView) {
            final ExpandableView expandableView = (ExpandableView) view;
            rect.top = mTmpLocation[1] + expandableView.getClipTopAmount();
            rect.bottom = Math.max(rect.top, mTmpLocation[1] + expandableView.getActualHeight()
                    - expandableView.getClipBottomAmount());
        }
        return rect;
    }

    /**
     * Leaves out containers that are only focusable for accessibility, like the tile grid, and
     * anything as big as most of the shade (the panel, the notification list): outlined it
     * covers the screen, and no other target lies outside it to move to.
     */
    private boolean isUsefulFocusable(View view) {
        // Notifications can be that big when expanded, they stay
        if (!(view instanceof ExpandableView) && view.getWidth() * (long) view.getHeight()
                > mRoot.getWidth() * (long) mRoot.getHeight() / 2) {
            return false;
        }
        if (!(view instanceof ExpandableView) && !hasVisibleContent(view)) {
            // E.g. the media area with no media playing: there, but nothing to see
            return false;
        }
        return !(view instanceof ViewGroup) || view.isClickable() || view.isLongClickable();
    }

    /** Draws something: a background, an image, text, or a child that does. */
    private static boolean hasVisibleContent(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0
                || view.getHeight() <= 0 || view.getAlpha() < MIN_ALPHA) {
            return false;
        }
        if (view.getBackground() != null || view.getForeground() != null) {
            return true;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleContent(group.getChildAt(i))) {
                    return true;
                }
            }
            return false;
        }
        if (view instanceof android.widget.ImageView) {
            return ((android.widget.ImageView) view).getDrawable() != null;
        }
        if (view instanceof android.widget.TextView) {
            return ((android.widget.TextView) view).getText().length() > 0;
        }
        // Anything else draws itself (sliders, custom views)
        return true;
    }

    /** Shown and not faded out. */
    private boolean isVisible(View view) {
        if (!view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        float alpha = 1f;
        for (View v = view; v != null && alpha >= MIN_ALPHA; ) {
            alpha *= v.getAlpha();
            final ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return alpha >= MIN_ALPHA;
    }

    /** Visible and not clipped away, e.g. the full quick settings footer behind the list. */
    private boolean isOnScreen(View view) {
        return isVisible(view) && view.getGlobalVisibleRect(new Rect());
    }

    /** Class and id of a view, for the log. */
    private static String describe(View view) {
        if (view == null) {
            return "nothing";
        }
        String id = "";
        if (view.getId() != View.NO_ID) {
            try {
                id = "/" + view.getResources().getResourceEntryName(view.getId());
            } catch (android.content.res.Resources.NotFoundException e) {
                id = "/" + view.getId();
            }
        }
        return view.getClass().getSimpleName() + id;
    }

    private static boolean isInside(View view, View ancestor) {
        for (View v = view; v != null; ) {
            if (v == ancestor) {
                return true;
            }
            final ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean hasAncestorIn(View view, HashSet<View> views) {
        for (ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (views.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    /** The notification a view belongs to, or null. */
    private ExpandableNotificationRow getRow(View view) {
        for (View v = view; v != null && v != mStack; ) {
            if (v instanceof ExpandableNotificationRow) {
                return (ExpandableNotificationRow) v;
            }
            final ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return null;
    }
}
