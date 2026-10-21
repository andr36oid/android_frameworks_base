/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.display;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayTransactionListener;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;

import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;

import libcore.io.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * <p>
 * Animates a screen transition from on to off or off to on by applying
 * some GL transformations to a screenshot.
 * </p><p>
 * This component must only be created or accessed by the {@link Looper} thread
 * that belongs to the {@link DisplayPowerController}.
 * </p>
 */
final class ColorFade {
    private static final String TAG = "ColorFade";

    private static final boolean DEBUG = false;

    // The layer for the electron beam surface.
    // This is currently hardcoded to be one layer above the boot animation.
    private static final int COLOR_FADE_LAYER = WindowManagerPolicy.COLOR_FADE_LAYER;

    // The number of frames to draw when preparing the animation so that it will
    // be ready to run smoothly.  We use 3 frames because we are triple-buffered.
    // See code for details.
    private static final int DEJANK_FRAMES = 3;

    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT = 0x3490;

    private final int mDisplayId;

    // Set to true when the animation context has been fully prepared.
    private boolean mPrepared;
    private boolean mCreatedResources;
    private int mMode;

    private final DisplayManagerInternal mDisplayManagerInternal;
    private int mDisplayLayerStack; // layer stack associated with primary display
    private int mDisplayWidth;      // real width, not rotated
    private int mDisplayHeight;     // real height, not rotated
    private SurfaceSession mSurfaceSession;
    private SurfaceControl mSurfaceControl;
    private Surface mSurface;
    private NaturalSurfaceLayout mSurfaceLayout;
    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private boolean mSurfaceVisible;
    private float mSurfaceAlpha;
    private boolean mIsWideColor;

    // Texture names.  We only use one texture, which contains the screenshot.
    private final int[] mTexNames = new int[1];
    private boolean mTexNamesGenerated;
    private final float mTexMatrix[] = new float[16];
    private final float mProjMatrix[] = new float[16];
    private final int[] mGLBuffers = new int[2];
    private int mTexCoordLoc, mVertexLoc, mTexUnitLoc, mProjMatrixLoc, mTexMatrixLoc;
    private int mOpacityLoc, mGammaLoc;
    private int mCrtLocP, mCrtLocTime, mCrtLocAspect, mCrtLocRotated, mCrtLocShape;
    private int mCrtLocFlash, mCrtLocAfterglow, mCrtLocTear, mCrtLocSplit, mCrtLocNoise;
    private int mCrtLocRoll, mCrtLocFlicker, mCrtLocTint, mCrtLocWobble, mCrtLocColour;
    private int mCrtLocRes;
    private boolean mRotated;
    // what the backlight does along with the picture, see backlight()
    private float mLight, mLightFlash, mLightAfterglow, mLightFlicker;
    private int mLightShape;
    private long mCrtStart;
    private int mProgram;

    // Vertex and corresponding texture coordinates.
    // We have 4 2D vertices, so 8 elements.  The vertices form a quad.
    private final FloatBuffer mVertexBuffer = createNativeFloatBuffer(8);
    private final FloatBuffer mTexCoordBuffer = createNativeFloatBuffer(8);

    private final Transaction mTransaction = new Transaction();

    /**
     * Animates an color fade warming up.
     */
    public static final int MODE_WARM_UP = 0;

    /**
     * Animates an color fade shutting off.
     */
    public static final int MODE_COOL_DOWN = 1;

    /**
     * Animates a simple dim layer to fade the contents of the screen in or out progressively.
     */
    public static final int MODE_FADE = 2;

    /**
     * andr36oid: an old TV switching off. The picture collapses into a bright line and a
     * dot that fades, with optional glitches, all set in Settings > Display > CRT effect
     * (persist.andr36oid.crtoff.*). Low-RAM consoles get this one even though they
     * otherwise skip the colour fade.
     */
    public static final int MODE_CRT = 3;

    /**
     * andr36oid: an old TV switching on. The picture wobbles and rainbow patches swirl
     * over it, then it settles (persist.andr36oid.crtoff.on_*).
     */
    public static final int MODE_DEGAUSS = 4;

    /** persist.andr36oid.crtoff.on_style: what the screen does when it switches on */
    public static final int ON_NONE = 0;
    public static final int ON_DEGAUSS = 1;

    /** persist.andr36oid.crtoff.style: what the screen does when it switches off */
    public static final int OFF_NONE = 0;
    public static final int OFF_FADE = 1;
    public static final int OFF_CRT = 2;

    private static final String CRT_PROP = "persist.andr36oid.crtoff.";

    /** line, dot and glow colours: white, andr36oid orange, green, amber, blue */
    private static final float[][] CRT_TINTS = {
        {1f, 1f, 1f}, {1f, 0.55f, 0.15f}, {0.45f, 1f, 0.55f}, {1f, 0.75f, 0.3f}, {0.6f, 0.8f, 1f},
    };

    public static int offStyle() {
        return SystemProperties.getInt(CRT_PROP + "style", OFF_NONE);
    }

    /** How long the switch-off animation runs, in ms. */
    public static int offLengthMillis() {
        return Math.max(150, Math.min(5000, SystemProperties.getInt(CRT_PROP + "length", 700)));
    }

    public static int onStyle() {
        return SystemProperties.getInt(CRT_PROP + "on_style", ON_NONE);
    }

    public static int onLengthMillis() {
        return Math.max(150, Math.min(5000, SystemProperties.getInt(CRT_PROP + "on_length", 1200)));
    }

    private static float crtProp(String name, int def) {
        return Math.max(0, Math.min(100, SystemProperties.getInt(CRT_PROP + name, def))) / 100f;
    }

    private static final String CRT_VERT =
            "uniform mat4 proj_matrix;\n"
            + "attribute vec2 position;\n"
            + "attribute vec2 uv;\n"
            + "varying vec2 POS;\n"
            + "void main() {\n"
            + "    POS = uv;\n"
            + "    gl_Position = proj_matrix * vec4(position.x, position.y, 0.0, 1.0);\n"
            + "}\n";

    // p runs from 0 (just switched on) to 1 (settled). The picture shakes and colour
    // patches swirl over it, both dying away; e is how much is left of it.
    private static final String DEGAUSS_FRAG =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES texUnit;\n"
            + "uniform mat4 tex_matrix;\n"
            + "uniform float p, time, rotated, wobble, colour;\n"
            + "uniform vec2 res;\n"
            + "varying vec2 POS;\n"
            + "vec3 pic(vec2 u) {\n"
            + "    vec2 n = rotated > 0.5 ? u.yx : u;\n"
            + "    vec4 t = tex_matrix * vec4(n, 1.0, 1.0);\n"
            + "    return texture2D(texUnit, t.st / t.q).rgb;\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 u = rotated > 0.5 ? POS.yx : POS;\n"
            // e: how much of the disturbance is left, exactly 0 when it has settled
            + "    float e = exp(-p * 3.5) * (1.0 - p);\n"
            + "    vec2 c0 = u - 0.5;\n"
            + "    float r = length(c0 * vec2(res.x / res.y, 1.0));\n"
            // the coil's field shakes the picture, damped, bending it most at the edges
            + "    float o1 = sin(time * 43.98);\n"
            + "    float o2 = sin(time * 69.12 + 1.3);\n"
            + "    vec2 bend = vec2(o1 * (0.6 + 3.2 * c0.y * c0.y) * sin(u.y * 6.2832 + time * 9.0),\n"
            + "            o2 * 0.5 * sin(u.x * 9.4248 - time * 7.0));\n"
            + "    vec2 su = clamp(u + bend * 0.018 * wobble * e, 0.0, 1.0);\n"
            // the three colour guns miss each other, most towards the corners
            + "    float ang = time * 3.0;\n"
            + "    vec2 co = c0 * 0.024 * colour * e + vec2(cos(ang), sin(ang)) * 0.004 * colour * e;\n"
            + "    vec3 c = vec3(pic(clamp(su + co, 0.0, 1.0)).r, pic(su).g, pic(clamp(su - co, 0.0, 1.0)).b);\n"
            // purity: big rainbow blotches swirling round the screen, strongest at the corners
            + "    float sa = sin(time * 1.8), ca = cos(time * 1.8);\n"
            + "    vec2 w = vec2(ca * c0.x - sa * c0.y, sa * c0.x + ca * c0.y);\n"
            + "    float h = sin(w.x * 6.0) + sin(w.y * 5.0 + 1.7) + sin((w.x - w.y) * 4.0 + time * 2.0)\n"
            + "            + 0.7 * sin(r * 9.0 - time * 6.0);\n"
            + "    vec3 rainbow = 0.5 + 0.5 * cos(6.2832 * (h * 0.22 + vec3(0.0, 0.333, 0.667)));\n"
            + "    float edge = 0.35 + 0.65 * smoothstep(0.15, 0.7, r);\n"
            + "    c *= mix(vec3(1.0), 0.3 + 1.4 * rainbow, colour * e * edge);\n"
            // the thunk: dark for an instant, a surge, then it settles
            + "    c *= 1.0 - 0.45 * (1.0 - smoothstep(0.0, 0.06, p)) + 0.18 * sin(3.14159 * clamp((p - 0.04) / 0.25, 0.0, 1.0));\n"
            // a faint hum bar rolling up the screen
            + "    c *= 1.0 - 0.12 * e * wobble * (0.5 + 0.5 * sin((u.y + time * 0.8) * 6.2832));\n"
            + "    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    // p runs from 0 (the picture) to 1 (black). The picture squashes into a line (A),
    // the line shrinks into a dot (B), what is left fades (C). Glitches mostly hit the
    // picture while it is still there.
    private static final String CRT_FRAG =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES texUnit;\n"
            + "uniform mat4 tex_matrix;\n"
            + "uniform float p, time, rotated, shape, flash, afterglow;\n"
            + "uniform float tear, split, noise, roll, flicker;\n"
            + "uniform vec2 res;\n"
            + "uniform vec3 tint;\n"
            + "varying vec2 POS;\n"
            + "float hash(float n) { return fract(sin(n) * 43758.5453); }\n"
            + "float hash2(vec2 v) { return fract(sin(dot(v, vec2(12.9898, 78.233))) * 43758.5453); }\n"
            + "vec3 pic(vec2 u) {\n"
            + "    vec2 n = rotated > 0.5 ? u.yx : u;\n"
            + "    vec4 t = tex_matrix * vec4(n, 1.0, 1.0);\n"
            + "    vec2 m = step(vec2(0.0), u) * step(u, vec2(1.0));\n"
            + "    return texture2D(texUnit, t.st / t.q).rgb * m.x * m.y;\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 u = rotated > 0.5 ? POS.yx : POS;\n"
            + "    vec2 P = (u - 0.5) * res;\n"
            // A: the picture collapses into a line, B: the line into a dot, C: what is left fades
            + "    float a1 = shape < 0.5 ? 0.55 : (shape < 1.5 ? 0.6 : 0.7);\n"
            + "    float a2 = shape < 0.5 ? 0.78 : a1;\n"
            + "    float A = clamp(p / a1, 0.0, 1.0);\n"
            + "    float B = shape < 0.5 ? clamp((p - a1) / (a2 - a1), 0.0, 1.0) : 0.0;\n"
            + "    float C = clamp((p - a2) / (1.0 - a2), 0.0, 1.0);\n"
            + "    float k = pow(A, 2.4);\n"
            + "    float hy = mix(res.y * 0.5, 0.75, k);\n"
            + "    float hx = res.x * 0.5 * (1.0 + 0.05 * smoothstep(0.3, 1.0, A));\n"
            + "    if (shape > 1.5) {\n"
            + "        hx = mix(res.x * 0.5, 1.2, k);\n"
            + "        hy = mix(res.y * 0.5, 1.2, k);\n"
            + "    } else if (shape < 0.5) {\n"
            + "        hx = mix(hx, 1.2, pow(B, 1.6));\n"
            + "        hy = mix(hy, 1.2, B);\n"
            + "    }\n"
            + "    hx = mix(hx, max(hx * 0.85, 1.2), C);\n"
            // glitches: in bursts, while there is still a picture
            + "    float tick = floor(time * 24.0);\n"
            + "    float burst = 0.45 + 0.55 * step(0.5, hash(floor(time * 12.0)));\n"
            + "    float gA = (1.0 - smoothstep(0.55, 0.95, A)) * smoothstep(0.0, 0.06, p) * burst;\n"
            + "    vec2 src = P / (2.0 * vec2(hx, hy)) + 0.5;\n"
            + "    float slip = roll * gA * (0.25 * step(0.6, hash(floor(time * 7.0) + 3.0)) * hash(floor(time * 7.0)) + 0.35 * k);\n"
            + "    float yy = src.y + slip;\n"
            + "    float bar = mix(1.0, smoothstep(0.0, 0.05, fract(yy)) * smoothstep(0.0, 0.05, 1.0 - fract(yy)), step(0.001, slip));\n"
            + "    src.y = mix(src.y, fract(yy), step(0.001, slip));\n"
            + "    float row = floor(src.y * 90.0);\n"
            + "    float rowHit = step(1.0 - 0.4 * tear, hash(row * 1.7 + tick * 3.1));\n"
            + "    float band = 1.0 - smoothstep(0.0, 0.08, abs(src.y - hash(floor(time * 5.0))));\n"
            + "    src.x += tear * gA * ((hash(row + tick) - 0.5) * 0.12 * rowHit + 0.06 * band\n"
            + "            + 0.012 * sin(src.y * 23.0 + time * 31.0));\n"
            + "    float off = split * (0.004 + 0.02 * A) * (0.5 + gA);\n"
            + "    vec3 c = vec3(pic(src + vec2(off, 0.0)).r, pic(src).g, pic(src - vec2(off, 0.0)).b) * bar;\n"
            + "    c *= 1.0 - flicker * 0.45 * hash(tick + 7.0) * gA;\n"
            // the same light in less height: brighter, then white hot
            + "    float squeeze = res.y * 0.5 / hy;\n"
            + "    float gain = 1.0 + flash * (min(squeeze, 4.0) - 1.0) * 0.35;\n"
            + "    float w = max(flash * (1.0 - smoothstep(2.0, 30.0, hy)), flash * step(0.001, B + C));\n"
            + "    vec3 beam = tint * (1.0 + 1.5 * flash);\n"
            + "    vec3 core = mix(c * gain, beam, w);\n"
            // the beam: soft edges, brighter in the middle of the line
            + "    vec2 dd = abs(P) - vec2(hx, hy);\n"
            + "    float inside = (1.0 - smoothstep(hx - 0.5, hx + 1.0, abs(P.x)))\n"
            + "            * (1.0 - smoothstep(hy - 0.5, hy + 1.0, abs(P.y)));\n"
            + "    float taper = 1.0 - 0.6 * pow(clamp(abs(P.x) / max(hx, 1.0), 0.0, 1.0), 3.0) * smoothstep(0.7, 1.0, A);\n"
            + "    float d = length(max(dd, 0.0));\n"
            + "    float hs = mix(k, 1.0, step(0.001, B + C));\n"
            + "    float bloom = flash * hs * (0.55 * exp(-d / (3.0 + 6.0 * flash)) + 0.3 * exp(-d / (18.0 + 50.0 * flash)));\n"
            // phosphor persistence: the picture glows on where the beam has left it
            + "    vec3 ghost = pic(u) * pow(1.0 - A, 1.5) * mix(0.25, 0.7, afterglow) * vec3(0.9, 1.0, 0.85);\n"
            // the energy of the shrinking line ends up in one hot dot
            + "    float dotGlow = flash * B * B * exp(-length(P) / (6.0 + 14.0 * flash)) * (shape < 0.5 ? 1.0 : 0.0)\n"
            + "            + flash * (shape > 1.5 ? k * k : 0.0) * exp(-length(P) / (6.0 + 14.0 * flash));\n"
            + "    vec3 col = inside * core * taper + (1.0 - inside) * ghost + (bloom + dotGlow) * tint;\n"
            + "    col *= pow(1.0 - C, mix(3.0, 0.6, afterglow));\n"
            // the glass: a little darker towards the corners while it happens
            + "    col *= 1.0 - 0.3 * smoothstep(0.35, 1.0, length(u - 0.5) * 1.5) * smoothstep(0.0, 0.3, A);\n"
            + "    float grain = hash2(gl_FragCoord.xy + fract(time * 7.0) * 113.0) - 0.5;\n"
            + "    col += grain * noise * (0.5 * gA * inside + 0.12 * (1.0 - A));\n"
            // light past white bleeds into white, like an overdriven tube
            + "    float over = max(max(col.r, max(col.g, col.b)) - 1.0, 0.0);\n"
            + "    col += over * 0.6;\n"
            + "    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n"
            + "}\n";

    public ColorFade(int displayId) {
        mDisplayId = displayId;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
    }

    /**
     * Warms up the color fade in preparation for turning on or off.
     * This method prepares a GL context, and captures a screen shot.
     *
     * @param mode The desired mode for the upcoming animation.
     * @return True if the color fade is ready, false if it is uncontrollable.
     */
    public boolean prepare(Context context, int mode) {
        if (DEBUG) {
            Slog.d(TAG, "prepare: mode=" + mode);
        }

        // andr36oid: the black surface left from switching off must be gone before the
        // degauss takes its still of the screen, or the still is black
        if (mode == MODE_DEGAUSS && mSurfaceControl != null) {
            dismissResources();
            destroySurface(true);
            mPrepared = false;
        }

        mMode = mode;

        // Get the display size and layer stack.
        // This is not expected to change while the color fade surface is showing.
        DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
        mDisplayLayerStack = displayInfo.layerStack;
        mDisplayWidth = displayInfo.getNaturalWidth();
        mDisplayHeight = displayInfo.getNaturalHeight();
        mRotated = displayInfo.rotation == Surface.ROTATION_90
                || displayInfo.rotation == Surface.ROTATION_270;

        // Prepare the surface for drawing.
        if (!(createSurface() && createEglContext() && createEglSurface() &&
              captureScreenshotTextureAndSetViewport())) {
            dismiss();
            return false;
        }

        // Init GL
        if (!attachEglContext()) {
            return false;
        }
        try {
            if(!initGLShaders(context) || !initGLBuffers() || checkGlErrors("prepare")) {
                detachEglContext();
                dismiss();
                return false;
            }
        } finally {
            detachEglContext();
        }

        // Done.
        mCreatedResources = true;
        mPrepared = true;

        // Dejanking optimization.
        // Some GL drivers can introduce a lot of lag in the first few frames as they
        // initialize their state and allocate graphics buffers for rendering.
        // Work around this problem by rendering the first frame of the animation a few
        // times.  The rest of the animation should run smoothly thereafter.
        // The frames we draw here aren't visible because we are essentially just
        // painting the screenshot as-is.
        mCrtStart = SystemClock.uptimeMillis();
        if (mode == MODE_COOL_DOWN || mode == MODE_CRT || mode == MODE_DEGAUSS) {
            for (int i = 0; i < DEJANK_FRAMES; i++) {
                draw(1.0f);
            }
        }
        return true;
    }

    private String readFile(Context context, int resourceId) {
        try{
            InputStream stream = context.getResources().openRawResource(resourceId);
            return new String(Streams.readFully(new InputStreamReader(stream)));
        }
        catch (IOException e) {
            Slog.e(TAG, "Unrecognized shader " + Integer.toString(resourceId));
            throw new RuntimeException(e);
        }
    }

    private int loadShader(Context context, int resourceId, int type) {
        return compileShader(readFile(context, resourceId), type);
    }

    private int compileShader(String source, int type) {
        int shader = GLES20.glCreateShader(type);

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Slog.e(TAG, "Could not compile shader " + shader + ", " + type + ":");
            Slog.e(TAG, GLES20.glGetShaderSource(shader));
            Slog.e(TAG, GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }

        return shader;
    }

    private boolean initGLShaders(Context context) {
        final boolean crt = mMode == MODE_CRT || mMode == MODE_DEGAUSS;
        int vshader = crt ? compileShader(CRT_VERT, GLES20.GL_VERTEX_SHADER)
                : loadShader(context, com.android.internal.R.raw.color_fade_vert,
                        GLES20.GL_VERTEX_SHADER);
        int fshader = crt ? compileShader(mMode == MODE_CRT ? CRT_FRAG : DEGAUSS_FRAG,
                        GLES20.GL_FRAGMENT_SHADER)
                : loadShader(context, com.android.internal.R.raw.color_fade_frag,
                        GLES20.GL_FRAGMENT_SHADER);
        GLES20.glReleaseShaderCompiler();
        if (vshader == 0 || fshader == 0) return false;

        mProgram = GLES20.glCreateProgram();

        GLES20.glAttachShader(mProgram, vshader);
        GLES20.glAttachShader(mProgram, fshader);
        GLES20.glDeleteShader(vshader);
        GLES20.glDeleteShader(fshader);

        GLES20.glLinkProgram(mProgram);

        mVertexLoc = GLES20.glGetAttribLocation(mProgram, "position");
        mTexCoordLoc = GLES20.glGetAttribLocation(mProgram, "uv");

        mProjMatrixLoc = GLES20.glGetUniformLocation(mProgram, "proj_matrix");
        mTexMatrixLoc = GLES20.glGetUniformLocation(mProgram, "tex_matrix");

        mOpacityLoc = GLES20.glGetUniformLocation(mProgram, "opacity");
        mGammaLoc = GLES20.glGetUniformLocation(mProgram, "gamma");
        mTexUnitLoc = GLES20.glGetUniformLocation(mProgram, "texUnit");

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mTexUnitLoc, 0);
        if (crt) initCrtUniforms();
        GLES20.glUseProgram(0);

        return true;
    }

    /** The CRT settings for this switch-off or on; they don't change while it runs. */
    private void initCrtUniforms() {
        mCrtLocP = GLES20.glGetUniformLocation(mProgram, "p");
        mCrtLocTime = GLES20.glGetUniformLocation(mProgram, "time");
        mCrtLocAspect = GLES20.glGetUniformLocation(mProgram, "aspect");
        mCrtLocRes = GLES20.glGetUniformLocation(mProgram, "res");
        mCrtLocRotated = GLES20.glGetUniformLocation(mProgram, "rotated");
        mCrtLocShape = GLES20.glGetUniformLocation(mProgram, "shape");
        mCrtLocFlash = GLES20.glGetUniformLocation(mProgram, "flash");
        mCrtLocAfterglow = GLES20.glGetUniformLocation(mProgram, "afterglow");
        mCrtLocTear = GLES20.glGetUniformLocation(mProgram, "tear");
        mCrtLocSplit = GLES20.glGetUniformLocation(mProgram, "split");
        mCrtLocNoise = GLES20.glGetUniformLocation(mProgram, "noise");
        mCrtLocRoll = GLES20.glGetUniformLocation(mProgram, "roll");
        mCrtLocFlicker = GLES20.glGetUniformLocation(mProgram, "flicker");
        mCrtLocTint = GLES20.glGetUniformLocation(mProgram, "tint");
        mCrtLocWobble = GLES20.glGetUniformLocation(mProgram, "wobble");
        mCrtLocColour = GLES20.glGetUniformLocation(mProgram, "colour");
        // each shader only has some of these; the others are -1 and ignored
        GLES20.glUniform1f(mCrtLocWobble, crtProp("on_wobble", 60));
        GLES20.glUniform1f(mCrtLocColour, crtProp("on_colour", 70));
        final float w = mRotated ? mDisplayHeight : mDisplayWidth;
        final float h = mRotated ? mDisplayWidth : mDisplayHeight;
        GLES20.glUniform1f(mCrtLocAspect, h > 0 ? w / h : 1f);
        GLES20.glUniform2f(mCrtLocRes, w, h);
        GLES20.glUniform1f(mCrtLocRotated, mRotated ? 1f : 0f);
        mLightShape = Math.max(0, Math.min(2, SystemProperties.getInt(CRT_PROP + "shape", 0)));
        mLightFlash = crtProp("flash", 70);
        mLightAfterglow = crtProp("afterglow", 50);
        mLightFlicker = crtProp("flicker", 25);
        mLight = mMode == MODE_CRT ? crtProp("backlight", 60) : crtProp("on_backlight", 60);
        GLES20.glUniform1f(mCrtLocShape, mLightShape);
        GLES20.glUniform1f(mCrtLocFlash, mLightFlash);
        GLES20.glUniform1f(mCrtLocAfterglow, mLightAfterglow);
        GLES20.glUniform1f(mCrtLocTear, crtProp("tear", 25));
        GLES20.glUniform1f(mCrtLocSplit, crtProp("split", 30));
        GLES20.glUniform1f(mCrtLocNoise, crtProp("noise", 20));
        GLES20.glUniform1f(mCrtLocRoll, crtProp("roll", 0));
        GLES20.glUniform1f(mCrtLocFlicker, crtProp("flicker", 25));
        final int tint = SystemProperties.getInt(CRT_PROP + "tint", 0);
        final float[] c = CRT_TINTS[tint >= 0 && tint < CRT_TINTS.length ? tint : 0];
        GLES20.glUniform3f(mCrtLocTint, c[0], c[1], c[2]);
    }

    private void destroyGLShaders() {
        GLES20.glDeleteProgram(mProgram);
        checkGlErrors("glDeleteProgram");
    }

    private boolean initGLBuffers() {
        //Fill vertices
        setQuad(mVertexBuffer, 0, 0, mDisplayWidth, mDisplayHeight);

        // Setup GL Textures
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        // Setup GL Buffers
        GLES20.glGenBuffers(2, mGLBuffers, 0);

        // fill vertex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mVertexBuffer.capacity() * 4,
                            mVertexBuffer, GLES20.GL_STATIC_DRAW);

        // fill tex buffer
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[1]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mTexCoordBuffer.capacity() * 4,
                            mTexCoordBuffer, GLES20.GL_STATIC_DRAW);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        return true;
    }

    private void destroyGLBuffers() {
        GLES20.glDeleteBuffers(2, mGLBuffers, 0);
        checkGlErrors("glDeleteBuffers");
    }

    private static void setQuad(FloatBuffer vtx, float x, float y, float w, float h) {
        if (DEBUG) {
            Slog.d(TAG, "setQuad: x=" + x + ", y=" + y + ", w=" + w + ", h=" + h);
        }
        vtx.put(0, x);
        vtx.put(1, y);
        vtx.put(2, x);
        vtx.put(3, y + h);
        vtx.put(4, x + w);
        vtx.put(5, y + h);
        vtx.put(6, x + w);
        vtx.put(7, y);
    }

    /**
     * Dismisses the color fade animation resources.
     *
     * This function destroys the resources that are created for the color fade
     * animation but does not clean up the surface.
     */
    public void dismissResources() {
        if (DEBUG) {
            Slog.d(TAG, "dismissResources");
        }

        if (mCreatedResources) {
            attachEglContext();
            try {
                destroyScreenshotTexture();
                destroyGLShaders();
                destroyGLBuffers();
                destroyEglSurface();
            } finally {
                detachEglContext();
            }
            // This is being called with no active context so shouldn't be
            // needed but is safer to not change for now.
            GLES20.glFlush();
            mCreatedResources = false;
        }
    }

    /**
     * Dismisses the color fade animation surface and cleans up.
     *
     * To prevent stray photons from leaking out after the color fade has been
     * turned off, it is a good idea to defer dismissing the animation until the
     * color fade has been turned back on fully.
     */
    public void dismiss() {
        if (DEBUG) {
            Slog.d(TAG, "dismiss");
        }

        if (mPrepared) {
            dismissResources();
            destroySurface();
            mPrepared = false;
        }
    }

    /**
     * Draws an animation frame showing the color fade activated at the
     * specified level.
     *
     * @param level The color fade level.
     * @return True if successful.
     */
    public boolean draw(float level) {
        if (DEBUG) {
            Slog.d(TAG, "drawFrame: level=" + level);
        }

        if (!mPrepared) {
            return false;
        }

        if (mMode == MODE_FADE) {
            return showSurface(1.0f - level);
        }

        if (!attachEglContext()) {
            return false;
        }
        try {
            // Clear frame to solid black.
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Draw the frame.
            double one_minus_level = 1 - level;
            double cos = Math.cos(Math.PI * one_minus_level);
            double sign = cos < 0 ? -1 : 1;
            float opacity = (float) -Math.pow(one_minus_level, 2) + 1;
            float gamma = (float) ((0.5d * sign * Math.pow(cos, 2) + 0.5d) * 0.9d + 0.1d);
            if (mMode == MODE_CRT) {
                drawCrt(1f - level);
            } else if (mMode == MODE_DEGAUSS) {
                drawCrt(level);
            } else {
                drawFaded(opacity, 1.f / gamma);
            }
            if (checkGlErrors("drawFrame")) {
                return false;
            }

            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        } finally {
            detachEglContext();
        }
        return showSurface(1.0f);
    }

    private void drawFaded(float opacity, float gamma) {
        if (DEBUG) {
            Slog.d(TAG, "drawFaded: opacity=" + opacity + ", gamma=" + gamma);
        }
        // Use shaders
        GLES20.glUseProgram(mProgram);

        // Set Uniforms
        GLES20.glUniformMatrix4fv(mProjMatrixLoc, 1, false, mProjMatrix, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, mTexMatrix, 0);
        GLES20.glUniform1f(mOpacityLoc, opacity);
        GLES20.glUniform1f(mGammaLoc, gamma);

        // Use textures
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);

        // draw the plane
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[0]);
        GLES20.glEnableVertexAttribArray(mVertexLoc);
        GLES20.glVertexAttribPointer(mVertexLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[1]);
        GLES20.glEnableVertexAttribArray(mTexCoordLoc);
        GLES20.glVertexAttribPointer(mTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        // clean up
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /**
     * andr36oid: the backlight during the old TV animations, b is what it would be
     * otherwise. Switching off, it flares with the line, flickers with the glitches and
     * dims with the afterglow; switching on, it thumps and hums while the picture settles.
     */
    public float backlight(float level, float b) {
        if (!mPrepared || mLight <= 0f || b < 0f) return b;
        final float t = (SystemClock.uptimeMillis() - mCrtStart) / 1000f;
        final float room = 1f - Math.min(1f, b);
        float out = b;
        if (mMode == MODE_CRT) {
            final float p = 1f - level;
            final float a1 = mLightShape == 0 ? 0.55f : mLightShape == 1 ? 0.6f : 0.7f;
            final float a2 = mLightShape == 0 ? 0.78f : a1;
            final float a = clamp01(p / a1);
            final float c = clamp01((p - a2) / (1f - a2));
            final float glitch = (1f - smoothstep(0.55f, 0.95f, a)) * smoothstep(0f, 0.06f, p);
            // the tube flares as the picture turns into the white-hot line
            out += room * mLight * mLightFlash * smoothstep(0.8f, 1f, a) * (1f - c);
            out *= 1f - mLight * mLightFlicker * 0.5f * glitch * hash((float) Math.floor(t * 24f) + 7f);
            final float fade = (float) Math.pow(1f - c, 3f - 2.4f * mLightAfterglow);
            out *= 1f - mLight + mLight * fade;
        } else if (mMode == MODE_DEGAUSS) {
            final float e = (float) Math.exp(-level * 3.5f) * (1f - level);
            out *= 1f - mLight * 0.5f * (1f - smoothstep(0f, 0.12f, level));
            out += room * mLight * 0.35f * (float) Math.sin(Math.PI * clamp01((level - 0.08f) / 0.3f));
            out *= 1f - mLight * 0.25f * e * (float) Math.abs(Math.sin(t * 43.98f));
        }
        return Math.max(0f, Math.min(1f, out));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smoothstep(float e0, float e1, float x) {
        final float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    private static float hash(float n) {
        final double v = Math.sin(n) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private void drawCrt(float progress) {
        GLES20.glUseProgram(mProgram);
        GLES20.glUniformMatrix4fv(mProjMatrixLoc, 1, false, mProjMatrix, 0);
        GLES20.glUniformMatrix4fv(mTexMatrixLoc, 1, false, mTexMatrix, 0);
        GLES20.glUniform1f(mCrtLocP, progress);
        GLES20.glUniform1f(mCrtLocTime, (SystemClock.uptimeMillis() - mCrtStart) / 1000f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexNames[0]);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[0]);
        GLES20.glEnableVertexAttribArray(mVertexLoc);
        GLES20.glVertexAttribPointer(mVertexLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mGLBuffers[1]);
        GLES20.glEnableVertexAttribArray(mTexCoordLoc);
        GLES20.glVertexAttribPointer(mTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void ortho(float left, float right, float bottom, float top, float znear, float zfar) {
        mProjMatrix[0] = 2f / (right - left);
        mProjMatrix[1] = 0;
        mProjMatrix[2] = 0;
        mProjMatrix[3] = 0;
        mProjMatrix[4] = 0;
        mProjMatrix[5] = 2f / (top - bottom);
        mProjMatrix[6] = 0;
        mProjMatrix[7] = 0;
        mProjMatrix[8] = 0;
        mProjMatrix[9] = 0;
        mProjMatrix[10] = -2f / (zfar - znear);
        mProjMatrix[11] = 0;
        mProjMatrix[12] = -(right + left) / (right - left);
        mProjMatrix[13] = -(top + bottom) / (top - bottom);
        mProjMatrix[14] = -(zfar + znear) / (zfar - znear);
        mProjMatrix[15] = 1f;
    }

    private boolean captureScreenshotTextureAndSetViewport() {
        if (!attachEglContext()) {
            return false;
        }
        try {
            if (!mTexNamesGenerated) {
                GLES20.glGenTextures(1, mTexNames, 0);
                if (checkGlErrors("glGenTextures")) {
                    return false;
                }
                mTexNamesGenerated = true;
            }

            final SurfaceTexture st = new SurfaceTexture(mTexNames[0]);
            final Surface s = new Surface(st);
            try {
                final IBinder token = SurfaceControl.getInternalDisplayToken();
                if (token == null) {
                    Slog.e(TAG,
                            "Failed to take screenshot because internal display is disconnected");
                    return false;
                }

                mIsWideColor = SurfaceControl.getActiveColorMode(token)
                        == Display.COLOR_MODE_DISPLAY_P3;
                SurfaceControl.screenshot(token, s);
                st.updateTexImage();
                st.getTransformMatrix(mTexMatrix);
            } finally {
                s.release();
                st.release();
            }

            // Set up texture coordinates for a quad.
            // We might need to change this if the texture ends up being
            // a different size from the display for some reason.
            mTexCoordBuffer.put(0, 0f); mTexCoordBuffer.put(1, 0f);
            mTexCoordBuffer.put(2, 0f); mTexCoordBuffer.put(3, 1f);
            mTexCoordBuffer.put(4, 1f); mTexCoordBuffer.put(5, 1f);
            mTexCoordBuffer.put(6, 1f); mTexCoordBuffer.put(7, 0f);

            // Set up our viewport.
            GLES20.glViewport(0, 0, mDisplayWidth, mDisplayHeight);
            ortho(0, mDisplayWidth, 0, mDisplayHeight, -1, 1);
        } finally {
            detachEglContext();
        }
        return true;
    }

    private void destroyScreenshotTexture() {
        if (mTexNamesGenerated) {
            mTexNamesGenerated = false;
            GLES20.glDeleteTextures(1, mTexNames, 0);
            checkGlErrors("glDeleteTextures");
        }
    }

    private boolean createEglContext() {
        if (mEglDisplay == null) {
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                logEglError("eglGetDisplay");
                return false;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
                mEglDisplay = null;
                logEglError("eglInitialize");
                return false;
            }
        }

        if (mEglConfig == null) {
            int[] eglConfigAttribList = new int[] {
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            int[] numEglConfigs = new int[1];
            EGLConfig[] eglConfigs = new EGLConfig[1];
            if (!EGL14.eglChooseConfig(mEglDisplay, eglConfigAttribList, 0,
                    eglConfigs, 0, eglConfigs.length, numEglConfigs, 0)) {
                logEglError("eglChooseConfig");
                return false;
            }
            if (numEglConfigs[0] <= 0) {
                Slog.e(TAG, "no valid config found");
                return false;
            }

            mEglConfig = eglConfigs[0];
        }

        if (mEglContext == null) {
            int[] eglContextAttribList = new int[] {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEglContext = EGL14.eglCreateContext(mEglDisplay, mEglConfig,
                    EGL14.EGL_NO_CONTEXT, eglContextAttribList, 0);
            if (mEglContext == null) {
                logEglError("eglCreateContext");
                return false;
            }
        }
        return true;
    }

    private boolean createSurface() {
        if (mSurfaceSession == null) {
            mSurfaceSession = new SurfaceSession();
        }

        if (mSurfaceControl == null) {
            Transaction t = new Transaction();
            try {
                final SurfaceControl.Builder builder = new SurfaceControl.Builder(mSurfaceSession)
                        .setName("ColorFade")
                        .setCallsite("ColorFade.createSurface");
                if (mMode == MODE_FADE) {
                    builder.setColorLayer();
                } else {
                    builder.setBufferSize(mDisplayWidth, mDisplayHeight);
                }
                mSurfaceControl = builder.build();
            } catch (OutOfResourcesException ex) {
                Slog.e(TAG, "Unable to create surface.", ex);
                return false;
            }

            t.setLayerStack(mSurfaceControl, mDisplayLayerStack);
            t.setWindowCrop(mSurfaceControl, mDisplayWidth, mDisplayHeight);
            mSurface = new Surface();
            mSurface.copyFrom(mSurfaceControl);

            mSurfaceLayout = new NaturalSurfaceLayout(mDisplayManagerInternal,
                    mDisplayId, mSurfaceControl);
            mSurfaceLayout.onDisplayTransaction(t);
            t.apply();
        }
        return true;
    }

    private boolean createEglSurface() {
        if (mEglSurface == null) {
            int[] eglSurfaceAttribList = new int[] {
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE,
                    EGL14.EGL_NONE
            };

            // If the current display is in wide color, then so is the screenshot.
            if (mIsWideColor) {
                eglSurfaceAttribList[0] = EGL_GL_COLORSPACE_KHR;
                eglSurfaceAttribList[1] = EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT;
            }
            // turn our SurfaceControl into a Surface
            mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, mEglConfig, mSurface,
                    eglSurfaceAttribList, 0);
            if (mEglSurface == null) {
                logEglError("eglCreateWindowSurface");
                return false;
            }
        }
        return true;
    }

    private void destroyEglSurface() {
        if (mEglSurface != null) {
            if (!EGL14.eglDestroySurface(mEglDisplay, mEglSurface)) {
                logEglError("eglDestroySurface");
            }
            mEglSurface = null;
        }
    }

    private void destroySurface() {
        destroySurface(false);
    }

    /** sync: wait until SurfaceFlinger has taken the surface off the screen */
    private void destroySurface(boolean sync) {
        if (mSurfaceControl != null) {
            mSurfaceLayout.dispose();
            mSurfaceLayout = null;
            new Transaction().remove(mSurfaceControl).apply(sync);
            mSurface.release();
            mSurfaceControl = null;
            mSurfaceVisible = false;
            mSurfaceAlpha = 0f;
        }
    }

    private boolean showSurface(float alpha) {
        if (!mSurfaceVisible || mSurfaceAlpha != alpha) {
            mTransaction.setLayer(mSurfaceControl, COLOR_FADE_LAYER)
                    .setAlpha(mSurfaceControl, alpha)
                    .show(mSurfaceControl)
                    .apply();
            mSurfaceVisible = true;
            mSurfaceAlpha = alpha;
        }
        return true;
    }

    private boolean attachEglContext() {
        if (mEglSurface == null) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            logEglError("eglMakeCurrent");
            return false;
        }
        return true;
    }

    private void detachEglContext() {
        if (mEglDisplay != null) {
            EGL14.eglMakeCurrent(mEglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    private static FloatBuffer createNativeFloatBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }

    private static void logEglError(String func) {
        Slog.e(TAG, func + " failed: error " + EGL14.eglGetError(), new Throwable());
    }

    private static boolean checkGlErrors(String func) {
        return checkGlErrors(func, true);
    }

    private static boolean checkGlErrors(String func, boolean log) {
        boolean hadError = false;
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            if (log) {
                Slog.e(TAG, func + " failed: error " + error, new Throwable());
            }
            hadError = true;
        }
        return hadError;
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Color Fade State:");
        pw.println("  mPrepared=" + mPrepared);
        pw.println("  mMode=" + mMode);
        pw.println("  mDisplayLayerStack=" + mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + mDisplayWidth);
        pw.println("  mDisplayHeight=" + mDisplayHeight);
        pw.println("  mSurfaceVisible=" + mSurfaceVisible);
        pw.println("  mSurfaceAlpha=" + mSurfaceAlpha);
    }

    /**
     * Keeps a surface aligned with the natural orientation of the device.
     * Updates the position and transformation of the matrix whenever the display
     * is rotated.  This is a little tricky because the display transaction
     * callback can be invoked on any thread, not necessarily the thread that
     * owns the color fade.
     */
    private static final class NaturalSurfaceLayout implements DisplayTransactionListener {
        private final DisplayManagerInternal mDisplayManagerInternal;
        private final int mDisplayId;
        private SurfaceControl mSurfaceControl;

        public NaturalSurfaceLayout(DisplayManagerInternal displayManagerInternal,
                int displayId, SurfaceControl surfaceControl) {
            mDisplayManagerInternal = displayManagerInternal;
            mDisplayId = displayId;
            mSurfaceControl = surfaceControl;
            mDisplayManagerInternal.registerDisplayTransactionListener(this);
        }

        public void dispose() {
            synchronized (this) {
                mSurfaceControl = null;
            }
            mDisplayManagerInternal.unregisterDisplayTransactionListener(this);
        }

        @Override
        public void onDisplayTransaction(Transaction t) {
            synchronized (this) {
                if (mSurfaceControl == null) {
                    return;
                }

                DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(mDisplayId);
                switch (displayInfo.rotation) {
                    case Surface.ROTATION_0:
                        t.setPosition(mSurfaceControl, 0, 0);
                        t.setMatrix(mSurfaceControl, 1, 0, 0, 1);
                        break;
                    case Surface.ROTATION_90:
                        t.setPosition(mSurfaceControl, 0, displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, 0, -1, 1, 0);
                        break;
                    case Surface.ROTATION_180:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth,
                                displayInfo.logicalHeight);
                        t.setMatrix(mSurfaceControl, -1, 0, 0, -1);
                        break;
                    case Surface.ROTATION_270:
                        t.setPosition(mSurfaceControl, displayInfo.logicalWidth, 0);
                        t.setMatrix(mSurfaceControl, 0, 1, -1, 0);
                        break;
                }
            }
        }
    }
}
