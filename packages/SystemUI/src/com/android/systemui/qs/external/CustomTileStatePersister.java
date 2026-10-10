/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Persists and retrieves the last state of an active {@link CustomTile}, so the tile shows
 * that state after SystemUI restarts without binding to its service.
 *
 * Java port of Android 12's CustomTileStatePersister.kt. Same file name, key and fields; the
 * icon is not stored (the tile shows its manifest icon). Unlike Android 12, a saved state is
 * dropped when the language or the build changed, since the saved label and subtitle are
 * already translated text; the tile then binds once to get fresh text.
 */
public final class CustomTileStatePersister {

    private static final String TAG = "TileServicePersistence";

    private static final String FILE_NAME = "custom_tiles_state";

    private static final String STATE = "state";
    private static final String LABEL = "label";
    private static final String SUBTITLE = "subtitle";
    private static final String CONTENT_DESCRIPTION = "content_description";
    private static final String STATE_DESCRIPTION = "state_description";
    private static final String STAMP = "stamp";

    private CustomTileStatePersister() {
    }

    /** Same key as Android 12's TileServiceKey. */
    @VisibleForTesting
    static String key(ComponentName componentName, int user) {
        return componentName.flattenToString() + ":" + user;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE_NAME, 0);
    }

    /** What the saved text depends on: the language and the build. */
    private static String stamp() {
        return Locale.getDefault().toLanguageTag() + "|" + Build.FINGERPRINT;
    }

    /**
     * Returns the saved state, or {@code null} if there is none or it no longer fits.
     * Fields that were not saved are {@code null}.
     */
    public static Tile readState(Context context, ComponentName componentName, int user) {
        final String state;
        try {
            state = prefs(context).getString(key(componentName, user), null);
        } catch (IllegalStateException e) {
            // Storage not unlocked yet
            return null;
        }
        if (state == null) {
            return null;
        }
        try {
            return readTileFromString(state, stamp());
        } catch (JSONException e) {
            Log.e(TAG, "Bad saved state: " + state, e);
            return null;
        }
    }

    /** Saves the state. Null fields and the icon are not saved. */
    public static void persistState(Context context, ComponentName componentName, int user,
            Tile tile) {
        final String state;
        try {
            state = writeToString(tile, stamp());
        } catch (JSONException e) {
            Log.e(TAG, "Couldn't save the state of " + componentName, e);
            return;
        }
        try {
            final SharedPreferences prefs = prefs(context);
            final String key = key(componentName, user);
            if (!state.equals(prefs.getString(key, null))) {
                prefs.edit().putString(key, state).apply();
            }
        } catch (IllegalStateException e) {
            // Storage not unlocked yet
        }
    }

    /** Removes the saved state, used when the tile is removed from Quick Settings. */
    public static void removeState(Context context, ComponentName componentName, int user) {
        try {
            prefs(context).edit().remove(key(componentName, user)).apply();
        } catch (IllegalStateException e) {
            // Storage not unlocked yet
        }
    }

    @VisibleForTesting
    static Tile readTileFromString(String stateString, String stamp) throws JSONException {
        final JSONObject json = new JSONObject(stateString);
        if (!stamp.equals(getStringOrNull(json, STAMP))) {
            return null;
        }
        final Tile tile = new Tile();
        tile.setState(json.getInt(STATE));
        tile.setLabel(getStringOrNull(json, LABEL));
        tile.setSubtitle(getStringOrNull(json, SUBTITLE));
        tile.setContentDescription(getStringOrNull(json, CONTENT_DESCRIPTION));
        tile.setStateDescription(getStringOrNull(json, STATE_DESCRIPTION));
        return tile;
    }

    // Null values are not written at all, so a missing name reads back as null.
    private static String getStringOrNull(JSONObject json, String name) throws JSONException {
        return json.has(name) ? json.getString(name) : null;
    }

    @VisibleForTesting
    static String writeToString(Tile tile, String stamp) throws JSONException {
        return new JSONObject()
                .put(STATE, tile.getState())
                .put(LABEL, toStringOrNull(tile.getLabel()))
                .put(SUBTITLE, toStringOrNull(tile.getSubtitle()))
                .put(CONTENT_DESCRIPTION, toStringOrNull(tile.getContentDescription()))
                .put(STATE_DESCRIPTION, toStringOrNull(tile.getStateDescription()))
                .put(STAMP, stamp)
                .toString();
    }

    private static String toStringOrNull(CharSequence text) {
        return text != null ? text.toString() : null;
    }
}
