/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.graphics.drawable;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.FloatProperty;
import android.view.animation.LinearInterpolator;

/**
 * Draws a ripple background.
 */
class RippleBackground extends RippleComponent {

    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();

    private static final int OPACITY_DURATION = 80;

    private ObjectAnimator mAnimator;

    private float mOpacity = 0;

    /**
     * The consoles have no touchscreen, so the d-pad focus fill is the only thing that shows
     * where you are. Stock .6 of the 10% Material highlight is a 6% tint, invisible on the black
     * theme; 2.4 gives about 24%.
     */
    private static final float FOCUS_OPACITY = 2.4f;
    private static final int FOCUS_MIN_CAP = 64;

    /** Whether this ripple is bounded. */
    private boolean mIsBounded;

    private boolean mFocused = false;
    private boolean mHovered = false;

    public RippleBackground(RippleDrawable owner, Rect bounds, boolean isBounded) {
        super(owner, bounds);

        mIsBounded = isBounded;
    }

    public boolean isVisible() {
        return mOpacity > 0;
    }

    public void draw(Canvas c, Paint p) {
        final int origAlpha = p.getAlpha();
        // Capped at the ripple colour's own alpha (at least FOCUS_MIN_CAP), so the stronger
        // focus fill below never gets more solid than a press would.
        final int alpha = Math.min((int) (origAlpha * mOpacity + 0.5f),
                Math.min(Math.max(origAlpha, FOCUS_MIN_CAP), 255));
        if (alpha > 0) {
            p.setAlpha(alpha);
            c.drawCircle(0, 0, mTargetRadius, p);
            p.setAlpha(origAlpha);
        }
    }

    public void setState(boolean focused, boolean hovered, boolean pressed) {
        if (!mFocused) {
            focused = focused && !pressed;
        }
        if (!mHovered) {
            hovered = hovered && !pressed;
        }
        if (mHovered != hovered || mFocused != focused) {
            mHovered = hovered;
            mFocused = focused;
            onStateChanged();
        }
    }

    private void onStateChanged() {
        // Hover             = .2 * alpha
        // Focus             = FOCUS_OPACITY * alpha (stock: .6)
        // Focused + Hovered = FOCUS_OPACITY * alpha
        float newOpacity = mFocused ? FOCUS_OPACITY : mHovered ? .2f : 0f;
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mAnimator = ObjectAnimator.ofFloat(this, OPACITY, newOpacity);
        mAnimator.setDuration(OPACITY_DURATION);
        mAnimator.setInterpolator(LINEAR_INTERPOLATOR);
        mAnimator.start();
    }

    public void jumpToFinal() {
        if (mAnimator != null) {
            mAnimator.end();
            mAnimator = null;
        }
    }

    private static abstract class BackgroundProperty extends FloatProperty<RippleBackground> {
        public BackgroundProperty(String name) {
            super(name);
        }
    }

    private static final BackgroundProperty OPACITY = new BackgroundProperty("opacity") {
        @Override
        public void setValue(RippleBackground object, float value) {
            object.mOpacity = value;
            object.invalidateSelf();
        }

        @Override
        public Float get(RippleBackground object) {
            return object.mOpacity;
        }
    };
}
