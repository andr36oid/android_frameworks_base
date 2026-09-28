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
 * d-pad moves a clearly drawn focus through quick settings, the brightness slider, the footer
 * buttons and the notifications. Buttons:
 * <ul>
 * <li>A: press, hold for a long press (the key layout sends DPAD_CENTER for it)
 * <li>B: back: closes notification controls, then the full quick settings, then the shade
 * <li>X: dismiss the focused notification; on a tile with details (Wi-Fi, Bluetooth, ...)
 * open the details
 * <li>Y: long press: a tile's settings page, a notification's controls
 * <li>R1 / L1: open the full quick settings / go back to the quick row
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
    private static boolean viewTakesKey(View focused, int keyCode) {
        if (focused == null) {
            return false;
        }
        if (focused instanceof EditText) {
            return true;
        }
        if (focused instanceof AbsSeekBar) {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        for (ViewParent parent = focused.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (parent instanceof RecyclerView) {
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
            // The first tile of the new page, once it slides in
            mFocusFirst = true;
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
            mPendingGuts = null;
        }
    }

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (!mMovingFocus && oldFocus != null && oldFocus != newFocus
                && (!oldFocus.isAttachedToWindow() || !oldFocus.isShown())) {
            // The quick row went away when the full quick settings opened, a notification
            // was removed, ...
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
            if ((mFocusFirst || mFocusLost) && mPendingFocus == null) {
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
        if (focused != null && focused != mRoot && focused.isShown()
                && focused.getGlobalVisibleRect(new Rect())) {
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
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L1:
                mPanel.flingQsFromKey(keyCode == KeyEvent.KEYCODE_BUTTON_R1, () -> {
                    mFocusFirst = true;
                    mRoot.invalidate();
                });
                return;
        }
        if (mFocusFirst || focused == null || !isTarget(focused)) {
            // Nothing sensible has focus yet, show where it is first
            focusFirst();
            return;
        }
        final ExpandableNotificationRow row = getRow(focused);
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            if (row != null) {
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

    /** B closes a notification's controls before anything else. */
    private boolean closeGuts(boolean down) {
        final View focused = mRoot.findFocus();
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
            // Below quick settings come the notifications
            final Target first = firstOnScreen(notifications);
            if (first != null && (next == null
                    || rectOf(first.view).top < rectOf(next).top)) {
                focusTarget(first);
                return;
            }
        }
        if (next != null) {
            requestFocus(next);
        }
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
                // Above the first notification are the quick settings
                final View up = findNext(from, panelTargets, null, View.FOCUS_UP);
                if (up != null) {
                    requestFocus(up);
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
        final ArrayList<View> focusables = new ArrayList<>();
        scope.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        final ArrayList<View> targets = new ArrayList<>();
        for (View view : focusables) {
            if (mStack != null && isInside(view, mStack)) {
                continue;
            }
            if (isUsefulFocusable(view) && isOnScreen(view)) {
                targets.add(view);
            }
        }
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
        // With the full quick settings open, the list is pushed out of the way
        if (mStack == null || !mStack.isShown() || mPanel.isQsExpanded()) {
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

    /** Leaves out containers that are only focusable for accessibility, like the tile grid. */
    private static boolean isUsefulFocusable(View view) {
        return !(view instanceof ViewGroup) || view.isClickable() || view.isLongClickable();
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
