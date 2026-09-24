/*
 * Copyright (C) 2026 The andr36oid Project
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

package com.android.systemui.globalactions;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.HardwareBgDrawable;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Power menu layout for handheld game consoles, which have no touchscreen and get driven with
 * a D-pad.
 *
 * Items are split into sections (quick actions, apps, everything else) stacked on top of each
 * other. Each section is a grid balanced so all of its rows hold about the same number of
 * tiles, never more than power_menu_max_columns, so nothing ever needs an overflow menu. The
 * whole block is centered and capped at a maximum width, and the first tile gets the focus.
 */
public class GlobalActionsConsoleLayout extends GlobalActionsLayout {
    public static final int SECTION_QUICK = 0;
    public static final int SECTION_APPS = 1;
    public static final int SECTION_ACTIONS = 2;

    /**
     * Implemented by adapters that sort their items into sections.
     */
    public interface SectionAdapter {
        /**
         * Return the section of the item at the given position, one of the SECTION_ constants.
         */
        int getSection(int position);

        /**
         * Return the title shown above the given section, or null for none.
         */
        @Nullable
        CharSequence getSectionTitle(int section);
    }

    // Item views of the last update, by adapter position
    private final List<View> mItemViews = new ArrayList<>();

    public GlobalActionsConsoleLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int maxWidth = getResources().getDimensionPixelSize(
                R.dimen.global_actions_console_max_width);
        if (MeasureSpec.getSize(widthMeasureSpec) > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        // Tiles draw their own background
        return null;
    }

    @Override
    protected boolean shouldReverseListItems() {
        // Rows are LinearLayouts, they already follow the layout direction
        return false;
    }

    @Override
    public void onUpdateList() {
        removeAllItems();
        mItemViews.clear();

        final ViewGroup list = getListView();
        final SparseArray<List<View>> sections = new SparseArray<>();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final View v = mAdapter.getView(i, null, list);
            mItemViews.add(v);
            final int section = getSection(i);
            List<View> views = sections.get(section);
            if (views == null) {
                views = new ArrayList<>();
                sections.put(section, views);
            }
            views.add(v);
        }

        // SparseArray keeps its keys sorted, so sections come out in order
        for (int i = 0; i < sections.size(); i++) {
            addSection(list, sections.keyAt(i), sections.valueAt(i), i == 0);
        }
        if (sections.size() > 0) {
            final View first = sections.valueAt(0).get(0);
            // Also where the focus lands if it ever gets lost
            first.setFocusedByDefault(true);
            first.requestFocusFromTouch();
        }
    }

    private int getSection(int position) {
        if (mAdapter instanceof SectionAdapter) {
            return ((SectionAdapter) mAdapter).getSection(position);
        }
        return mAdapter.shouldBeSeparated(position) ? SECTION_QUICK : SECTION_ACTIONS;
    }

    private void addSection(ViewGroup list, int section, List<View> views, boolean first) {
        final LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) {
            params.topMargin = getResources().getDimensionPixelSize(
                    R.dimen.global_actions_console_section_spacing);
        }
        list.addView(container, params);

        final CharSequence title = mAdapter instanceof SectionAdapter
                ? ((SectionAdapter) mAdapter).getSectionTitle(section) : null;
        if (title != null) {
            final TextView titleView = (TextView) LayoutInflater.from(getContext()).inflate(
                    R.layout.global_actions_console_section_title, container, false);
            titleView.setText(title);
            container.addView(titleView);
        }

        final int columns = getBalancedColumns(views.size());
        LinearLayout row = null;
        for (View v : views) {
            if (row == null || row.getChildCount() >= columns) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                // A short last row keeps the tile size of the full rows and sits centered
                row.setWeightSum(columns);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                container.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(v);
        }
    }

    /**
     * Spreads a section over as few rows as possible, keeping the rows evenly filled.
     */
    private int getBalancedColumns(int count) {
        final int maxColumns = Math.max(1,
                getResources().getInteger(R.integer.power_menu_max_columns));
        if (count <= maxColumns) {
            return Math.max(1, count);
        }
        final int rows = (count + maxColumns - 1) / maxColumns;
        return (count + rows - 1) / rows;
    }

    /**
     * Moves the focus to the item at the given adapter position.
     */
    public void focusItem(int position) {
        if (position >= 0 && position < mItemViews.size()) {
            mItemViews.get(position).requestFocusFromTouch();
        }
    }

    @Override
    public float getAnimationOffsetX() {
        // Centered block, just fade it in and out
        return 0f;
    }

    @Override
    public float getAnimationOffsetY() {
        return 0f;
    }
}
