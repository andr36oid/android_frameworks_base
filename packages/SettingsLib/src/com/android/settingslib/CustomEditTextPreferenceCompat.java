/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.settingslib;

import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.CallSuper;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.EditTextPreferenceDialogFragmentCompat;

public class CustomEditTextPreferenceCompat extends EditTextPreference {

    private CustomPreferenceDialogFragment mFragment;

    public CustomEditTextPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public CustomEditTextPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomEditTextPreferenceCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomEditTextPreferenceCompat(Context context) {
        super(context);
    }

    public EditText getEditText() {
        if (mFragment != null) {
            final Dialog dialog = mFragment.getDialog();
            if (dialog != null) {
                return (EditText) dialog.findViewById(android.R.id.edit);
            }
        }
        return null;
    }

    public boolean isDialogOpen() {
        return getDialog() != null && getDialog().isShowing();
    }

    public Dialog getDialog() {
        return mFragment != null ? mFragment.getDialog() : null;
    }

    protected void onPrepareDialogBuilder(AlertDialog.Builder builder,
            DialogInterface.OnClickListener listener) {
    }

    protected void onDialogClosed(boolean positiveResult) {
    }

    protected void onClick(DialogInterface dialog, int which) {
    }

    @CallSuper
    protected void onBindDialogView(View view) {
        final EditText editText = view.findViewById(android.R.id.edit);
        if (editText != null) {
            editText.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_SENTENCES);
            editText.requestFocus();
        }
    }

    private void setFragment(CustomPreferenceDialogFragment fragment) {
        mFragment = fragment;
    }

    public static class CustomPreferenceDialogFragment extends
            EditTextPreferenceDialogFragmentCompat {

        public static CustomPreferenceDialogFragment newInstance(String key) {
            final CustomPreferenceDialogFragment fragment = new CustomPreferenceDialogFragment();
            final Bundle b = new Bundle(1);
            b.putString(ARG_KEY, key);
            fragment.setArguments(b);
            return fragment;
        }

        private CustomEditTextPreferenceCompat getCustomizablePreference() {
            return (CustomEditTextPreferenceCompat) getPreference();
        }

        @Override
        protected void onBindDialogView(View view) {
            super.onBindDialogView(view);
            final EditText editText = view.findViewById(android.R.id.edit);
            if (editText != null) {
                editText.setOnEditorActionListener(this::onEditorAction);
            }
            getCustomizablePreference().onBindDialogView(view);
        }

        /**
         * Next or Done on the keyboard closes it and moves the focus to OK, so a controller
         * confirms with one more press. Android's own Next went to the nearest button below
         * the field, which is Cancel. With OK greyed out Android's handling stays.
         */
        private boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            final Dialog dialog = getDialog();
            if (!(dialog instanceof AlertDialog)) {
                return false;
            }
            final Button ok = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE);
            if (ok == null || !ok.isEnabled()) {
                return false;
            }
            final InputMethodManager imm = view.getContext()
                    .getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
            ok.requestFocus();
            return true;
        }

        @Override
        protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
            super.onPrepareDialogBuilder(builder);
            getCustomizablePreference().setFragment(this);
            getCustomizablePreference().onPrepareDialogBuilder(builder, this);
        }

        @Override
        public void onDialogClosed(boolean positiveResult) {
            super.onDialogClosed(positiveResult);
            getCustomizablePreference().onDialogClosed(positiveResult);
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            super.onClick(dialog, which);
            getCustomizablePreference().onClick(dialog, which);
        }
    }
}
