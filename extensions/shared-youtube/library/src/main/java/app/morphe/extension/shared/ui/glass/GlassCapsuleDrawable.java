/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4338
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.ui.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Draws a navigation bar as a floating, rounded, translucent capsule, shared by the apps.
 * <p>
 * No extra copy of the content behind the bar is made. If the app draws its own blurred background,
 * that background is kept and clipped to the capsule. The frosted look otherwise comes from a tinted fill,
 * a soft gradient, a rim lit from outside, a dark line inside it and a shadow, all drawn by this drawable,
 * which is set as the background of the navigation bar. Everything outside the capsule is left transparent.
 * <p>
 * The selected button has a rounded indicator, which moves to the next selected button. The movement is
 * spring based and keeps the velocity of a previous switch, like the floating navigation bar of BakaSU
 * ({@code FloatingBottomBar}/{@code DampedDragAnimation}): the indicator stretches while it travels and
 * settles with a very small overshoot instead of following a fixed 200 ms curve. Pressing the bar makes
 * the capsule breathe slightly and draws a soft highlight under the finger, which fades out on release.
 */
@SuppressWarnings("deprecation")
public final class GlassCapsuleDrawable extends Drawable {

    /**
     * What the app tells the drawable.
     */
    public interface Host {
        /**
         * Sets where the capsule is drawn.
         *
         * @param bounds  The bounds of the drawable, the size of the navigation bar.
         * @param capsule Set to the capsule.
         */
        void getCapsule(Rect bounds, RectF capsule);

        /**
         * @return The view of the selected button, or null if none.
         */
        @Nullable
        View getSelectedTabView();

        /**
         * @return If the bar is over a player that is always dark, whatever the app theme,
         *         such as the Shorts player. The bar is then kept black around the capsule.
         */
        default boolean isOverDarkPlayer() {
            return false;
        }

        /**
         * Draws what is behind the bar, such as blurred, if the app does not give the bar a blurred
         * background. The canvas is clipped to the capsule, and the fill of the glass is drawn over it.
         */
        default void drawBackdrop(Canvas canvas) {
        }
    }

    private static final int STYLE_UNKNOWN = -1;
    private static final int STYLE_LIGHT = 0;
    private static final int STYLE_DARK = 1;
    private static final int STYLE_DARK_PLAYER = 2;

    private static final int SHADOW_LAYERS = 3;

    private static final int INDICATOR_INSET = Dim.dp4;
    private static final int INDICATOR_MAX_WIDTH = Dim.dp(96);
    private static final float STROKE_WIDTH = Dim.dp1;
    /**
     * The rim of the capsule, lit from outside, and the dark line inside it, which give the glass its thickness.
     */
    private static final float RIM_WIDTH = Dim.dp(1.5f);
    private static final float BEVEL_WIDTH = Dim.dp1;

    /**
     * Spring of the selected button indicator, taken from the BakaSU floating bar. A stiffness of
     * about 850 1/s² settles in roughly 200 ms, and the damping ratio below leaves a tiny overshoot,
     * so a tab switch feels alive instead of ending abruptly. The velocity is carried over when the
     * user switches tabs again before the previous movement finished.
     */
    private static final float INDICATOR_STIFFNESS = 850f;
    private static final float INDICATOR_DAMPING_RATIO = 0.82f;
    private static final float INDICATOR_SETTLE_DISTANCE = 0.25f;
    private static final float INDICATOR_SETTLE_VELOCITY = 4f;

    /**
     * Press spring: the capsule grows by {@link #PRESS_SCALE} and the highlight fades in quickly.
     */
    private static final float PRESS_STIFFNESS = 600f;
    private static final float PRESS_DAMPING_RATIO = 1f;
    private static final float PRESS_SCALE = 0.02f;
    private static final float PRESS_SETTLE = 0.002f;

    /**
     * The indicator stretches along its movement, and squashes across it, by at most this fraction.
     * Like the velocity based scale of the BakaSU pill.
     */
    private static final float MAX_INDICATOR_STRETCH = 0.12f;
    private static final float INDICATOR_STRETCH_PER_VELOCITY = 1f / 12000f;

    /**
     * The view the drawable is the background of.
     */
    private final View view;
    private final Host host;

    /**
     * How opaque the glass is: 1 keeps the default alphas, 0 is fully clear, 2 doubles them.
     * Not final, so the opacity slider takes effect without restarting the app.
     */
    private float opacityScale;

    /**
     * If the glass is tinted white with the dark theme.
     */
    private final boolean lightTint;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF capsule = new RectF();
    private final Path capsulePath = new Path();
    private final RectF scratch = new RectF();

    /**
     * The blurred background of the app, drawn inside the capsule, or null if the app
     * gave the bar a plain color.
     */
    @Nullable
    private Drawable appBackground;

    /**
     * If drawing the background of the app failed, so it is no longer drawn.
     */
    private static boolean appBackgroundFailed;

    private final Drawable.Callback appBackgroundCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    };

    private int style = STYLE_UNKNOWN;
    private int shadowAlpha;
    @ColorInt
    private int sheenTopColor;
    @ColorInt
    private int strokeTopColor;
    @ColorInt
    private int strokeBottomColor;
    /**
     * The rim is brightest at the rounded ends of the capsule, where the light hits the edge.
     */
    @ColorInt
    private int rimEndColor;
    @ColorInt
    private int rimMiddleColor;

    /**
     * The edges of the capsule the gradients were made for.
     */
    private float shaderTop = Float.NaN;
    private float shaderBottom = Float.NaN;
    private float shaderLeft = Float.NaN;
    private float shaderRight = Float.NaN;

    @ColorInt
    private int fillColor;
    /**
     * There is no fill over the blurred background of the app, which has its own tint.
     */
    private boolean fillOverBlur;

    /**
     * A small spring, the same model the BakaSU navigation bar uses for its pill and press scale.
     */
    private static final class Spring {
        float value;
        float target;
        float velocity;

        /**
         * Advances the spring by one frame.
         *
         * @return If the spring is still moving.
         */
        boolean step(float deltaSeconds, float stiffness, float dampingRatio,
                     float distanceEpsilon, float velocityEpsilon) {
            if (Math.abs(value - target) <= distanceEpsilon
                    && Math.abs(velocity) <= velocityEpsilon) {
                value = target;
                velocity = 0;
                return false;
            }

            final float damping = 2f * dampingRatio * (float) java.lang.Math.sqrt(stiffness);
            final float acceleration = -stiffness * (value - target) - damping * velocity;
            velocity += acceleration * deltaSeconds;
            value += velocity * deltaSeconds;
            return true;
        }
    }

    private final Spring indicatorLeft = new Spring();
    private final Spring indicatorRight = new Spring();
    private final Spring press = new Spring();
    private boolean indicatorPositionValid;

    /**
     * If the "no selected tab view" skip of the indicator was logged, so it is logged only when
     * the state changes and not on every frame.
     */
    private boolean indicatorSkipLogged;

    /**
     * The finger position, in the coordinates of the navigation bar.
     */
    private float touchX = Float.NaN;
    private float touchY = Float.NaN;
    private boolean trackingTouch;

    /**
     * The highlight shader is reused while the finger is down, and remade when the finger moved.
     */
    private float highlightShaderX = Float.NaN;
    private float highlightShaderY = Float.NaN;
    private float highlightShaderRadius = Float.NaN;
    private float highlightShaderPress = Float.NaN;

    private boolean animating;
    private long lastFrameNanos;

    /**
     * @param view         The view the drawable is set as the background of.
     * @param opacityScale How opaque the glass is: 1 keeps the default alphas, 0 is fully clear, 2 doubles them.
     */
    public GlassCapsuleDrawable(View view, Host host, float opacityScale) {
        this(view, host, opacityScale, false);
    }

    /**
     * @param lightTint If the glass is tinted white with the dark theme, a lighter and clearer glass,
     *                  instead of with a lighter shade of the background.
     */
    public GlassCapsuleDrawable(View view, Host host, float opacityScale, boolean lightTint) {
        this.view = view;
        this.host = host;
        this.opacityScale = opacityScale;
        this.lightTint = lightTint;

        fillPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(STROKE_WIDTH);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(RIM_WIDTH);
        bevelPaint.setStyle(Paint.Style.STROKE);
        bevelPaint.setStrokeWidth(BEVEL_WIDTH);
        specularPaint.setStyle(Paint.Style.FILL);
        indicatorPaint.setStyle(Paint.Style.FILL);
        indicatorPressPaint.setStyle(Paint.Style.FILL);
        highlightPaint.setStyle(Paint.Style.FILL);

        updateStyle();
    }

    /**
     * @return The alpha scaled by the opacity, at most fully opaque.
     */
    public static float scaledAlpha(float alpha, float opacityScale) {
        return Math.max(0, Math.min(1, alpha * opacityScale));
    }

    /**
     * Updates how opaque the glass is without recreating the drawable. The colors are
     * recalculated and the drawable is invalidated, so a changed slider is visible right away.
     */
    public void setOpacityScale(float opacityScale) {
        if (this.opacityScale == opacityScale) return;

        this.opacityScale = opacityScale;
        style = STYLE_UNKNOWN;
        updateStyle();
        invalidateSelf();
    }

    public float getOpacityScale() {
        return opacityScale;
    }

    private float scaledAlpha(float alpha) {
        return scaledAlpha(alpha, opacityScale);
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
    }

    @ColorInt
    private static int blend(@ColorInt int from, float fraction) {
        final float inverse = 1 - fraction;
        return Color.rgb(
                Math.round(Color.red(from) * inverse + Color.red(Color.WHITE) * fraction),
                Math.round(Color.green(from) * inverse + Color.green(Color.WHITE) * fraction),
                Math.round(Color.blue(from) * inverse + Color.blue(Color.WHITE) * fraction)
        );
    }

    /**
     * Updates the colors if the theme changed or the bar moved over or away from a dark player.
     */
    public void updateStyle() {
        final int newStyle = host.isOverDarkPlayer()
                ? STYLE_DARK_PLAYER
                : Utils.isDarkModeEnabled() ? STYLE_DARK : STYLE_LIGHT;
        if (newStyle == style) return;
        style = newStyle;

        switch (newStyle) {
            case STYLE_DARK_PLAYER -> {
                // The player is always dark, whatever the app theme.
                fillColor = withAlpha(Color.BLACK, scaledAlpha(0.45f));
                sheenTopColor = withAlpha(Color.WHITE, 0.06f);
                strokeTopColor = withAlpha(Color.WHITE, 0.20f);
                strokeBottomColor = withAlpha(Color.WHITE, 0.05f);
                rimEndColor = withAlpha(Color.WHITE, 0.45f);
                rimMiddleColor = withAlpha(Color.WHITE, 0.10f);
                bevelPaint.setColor(withAlpha(Color.BLACK, 0.35f));
                indicatorPaint.setColor(withAlpha(Color.WHITE, 0.15f));
                shadowAlpha = Math.round(0.10f * 255);
            }
            case STYLE_DARK -> {
                final int background = ThemeUtils.getAppBackgroundColor();
                fillColor = lightTint
                        ? withAlpha(Color.WHITE, scaledAlpha(0.30f))
                        : withAlpha(blend(background, 0.14f), scaledAlpha(0.50f));
                sheenTopColor = withAlpha(Color.WHITE, lightTint ? 0.16f : 0.10f);
                strokeTopColor = withAlpha(Color.WHITE, 0.30f);
                strokeBottomColor = withAlpha(Color.WHITE, 0.06f);
                rimEndColor = withAlpha(Color.WHITE, 0.50f);
                rimMiddleColor = withAlpha(Color.WHITE, 0.12f);
                bevelPaint.setColor(withAlpha(Color.BLACK, 0.35f));
                indicatorPaint.setColor(withAlpha(Color.WHITE, 0.15f));
                shadowAlpha = Math.round(0.10f * 255);
            }
            default -> {
                final int background = ThemeUtils.getAppBackgroundColor();
                fillColor = withAlpha(blend(background, 0.6f), scaledAlpha(0.50f));
                sheenTopColor = withAlpha(Color.WHITE, 0.35f);
                strokeTopColor = withAlpha(Color.WHITE, 0.95f);
                strokeBottomColor = withAlpha(Color.BLACK, 0.07f);
                rimEndColor = withAlpha(Color.WHITE, 0.95f);
                rimMiddleColor = withAlpha(Color.WHITE, 0.50f);
                bevelPaint.setColor(withAlpha(Color.BLACK, 0.08f));
                indicatorPaint.setColor(withAlpha(Color.BLACK, 0.08f));
                shadowAlpha = Math.round(0.05f * 255);
            }
        }

        fillPaint.setColor(fillOverBlur ? Color.TRANSPARENT : fillColor);

        // The gradients are made again with the new colors.
        shaderTop = Float.NaN;
        invalidateSelf();
    }

    /**
     * @param background The background the app gave the navigation bar.
     */
    public void setAppBackground(@Nullable Drawable background) {
        if (appBackground != null && appBackground != background) {
            appBackground.setCallback(null);
        }

        // A plain color or an opaque background is the solid bar of the app, such as in
        // the Shorts player, and is left out so the capsule stays translucent.
        appBackground = background == null || appBackgroundFailed
                || background instanceof ColorDrawable
                || background.getOpacity() == PixelFormat.OPAQUE
                ? null
                : background;
        if (appBackground != null) {
            appBackground.setCallback(appBackgroundCallback);
            appBackground.setVisible(true, false);
            appBackground.setBounds(getBounds());
        }
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        if (appBackground != null) {
            appBackground.setBounds(bounds);
        }
    }

    /**
     * Moves the indicator to the selected button. The spring keeps its current position and
     * velocity, so switching tabs quickly does not restart the movement from a standstill.
     */
    public void onSelectedTabChanged() {
        startAnimation();
        invalidateSelf();
    }

    /**
     * Called from the touch hook of the navigation bar. The events are only observed: the app
     * still receives them and handles the tab clicks itself.
     */
    public void onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                trackingTouch = true;
                touchX = event.getX();
                touchY = event.getY();
                press.target = 1f;
                startAnimation();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return;
                touchX = event.getX();
                touchY = event.getY();
                invalidateSelf();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false;
                press.target = 0f;
                startAnimation();
            }
            default -> {
            }
        }
    }

    private void startAnimation() {
        if (animating) return;
        animating = true;
        lastFrameNanos = 0L;
        view.postOnAnimation(animationFrame);
    }

    private final Runnable animationFrame = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;

            final long now = System.nanoTime();
            final float deltaSeconds = lastFrameNanos == 0L
                    ? 1f / 60f
                    : Math.max(1f / 240f, Math.min(1f / 30f, (now - lastFrameNanos) / 1e9f));
            lastFrameNanos = now;

            final boolean active = stepSprings(deltaSeconds);
            invalidateSelf();

            if (active) {
                view.postOnAnimation(this);
            } else {
                animating = false;
                lastFrameNanos = 0L;
            }
        }
    };

    private boolean stepSprings(float deltaSeconds) {
        boolean active = false;

        if (indicatorPositionValid) {
            active |= indicatorLeft.step(deltaSeconds, INDICATOR_STIFFNESS,
                    INDICATOR_DAMPING_RATIO, INDICATOR_SETTLE_DISTANCE, INDICATOR_SETTLE_VELOCITY);
            active |= indicatorRight.step(deltaSeconds, INDICATOR_STIFFNESS,
                    INDICATOR_DAMPING_RATIO, INDICATOR_SETTLE_DISTANCE, INDICATOR_SETTLE_VELOCITY);
        }
        active |= press.step(deltaSeconds, PRESS_STIFFNESS, PRESS_DAMPING_RATIO, PRESS_SETTLE, 0.01f);

        return active;
    }

    private void setIndicatorTarget(float left, float right) {
        if (indicatorPositionValid
                && Math.abs(indicatorLeft.target - left) < 0.5f
                && Math.abs(indicatorRight.target - right) < 0.5f) {
            return;
        }

        final boolean snap = !indicatorPositionValid;
        indicatorLeft.target = left;
        indicatorRight.target = right;
        if (!indicatorPositionValid) {
            indicatorLeft.value = left;
            indicatorRight.value = right;
            indicatorPositionValid = true;
        } else {
            startAnimation();
        }
        Logger.printDebug(() -> "Glass navigation bar indicator target: " + left + " - " + right
                + (snap ? " (snapped)" : " (spring)"));
    }

    private void updateShaders() {
        if (capsule.top == shaderTop && capsule.bottom == shaderBottom
                && capsule.left == shaderLeft && capsule.right == shaderRight) return;
        shaderTop = capsule.top;
        shaderBottom = capsule.bottom;
        shaderLeft = capsule.left;
        shaderRight = capsule.right;

        final float radius = capsule.height() / 2;
        capsulePath.reset();
        capsulePath.addRoundRect(capsule, radius, radius, Path.Direction.CW);

        // Only made again when the size of the capsule or the colors change, not on every frame.
        sheenPaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                sheenTopColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        strokePaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                strokeTopColor, strokeBottomColor, Shader.TileMode.CLAMP));
        final float end = Math.min(0.5f, radius / capsule.width());
        rimPaint.setShader(new LinearGradient(capsule.left, 0, capsule.right, 0,
                new int[]{rimEndColor, rimMiddleColor, rimMiddleColor, rimEndColor},
                new float[]{0, end, 1 - end, 1}, Shader.TileMode.CLAMP));

        // A soft glint in the upper left, the way a rounded glass edge catches the light.
        final float glintAlpha = style == STYLE_DARK_PLAYER
                ? 0.06f
                : lightTint ? 0.14f : 0.10f;
        specularPaint.setShader(new RadialGradient(
                capsule.left + capsule.width() * 0.22f,
                capsule.top + capsule.height() * 0.04f,
                Math.max(capsule.height() * 1.6f, capsule.width() * 0.42f),
                new int[]{withAlpha(Color.WHITE, glintAlpha), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        try {
            host.getCapsule(getBounds(), capsule);
            if (capsule.width() <= 0 || capsule.height() <= 0) return;

            final float radius = capsule.height() / 2;
            updateShaders();

            final int saveCount = canvas.save();
            try {
                final float pressProgress = press.value;
                if (pressProgress > 0.001f) {
                    final float scale = 1f + PRESS_SCALE * pressProgress;
                    canvas.scale(scale, scale, capsule.centerX(), capsule.centerY());
                }

                // The dark player can end above the navigation bar, so the background of the window
                // would show around the capsule, which is white with the light theme.
                // The player is always dark, so the bar is kept black around the capsule.
                if (style == STYLE_DARK_PLAYER) {
                    canvas.drawColor(Color.BLACK);
                }

                // Soft shadow below the capsule.
                for (int i = SHADOW_LAYERS; i >= 1; i--) {
                    scratch.set(capsule);
                    scratch.inset(-i * 0.5f * STROKE_WIDTH, -i * 0.5f * STROKE_WIDTH);
                    scratch.offset(0, i * STROKE_WIDTH);
                    shadowPaint.setAlpha(shadowAlpha / i);
                    final float shadowRadius = scratch.height() / 2;
                    canvas.drawRoundRect(scratch, shadowRadius, shadowRadius, shadowPaint);
                }

                final boolean overBlur = appBackground != null && style != STYLE_DARK_PLAYER;
                if (overBlur != fillOverBlur) {
                    fillOverBlur = overBlur;
                    fillPaint.setColor(overBlur ? Color.TRANSPARENT : fillColor);
                }
                if (overBlur) {
                    // The blurred background of the app, only inside the capsule.
                    final int backgroundSaveCount = canvas.save();
                    try {
                        canvas.clipPath(capsulePath);
                        appBackground.draw(canvas);
                    } catch (Exception ex) {
                        // The background of the app may expect to be drawn by the bar itself.
                        Logger.printException(() -> "Glass navigation bar app background failure", ex);
                        appBackgroundFailed = true;
                        setAppBackground(null);
                    } finally {
                        canvas.restoreToCount(backgroundSaveCount);
                    }
                } else if (style != STYLE_DARK_PLAYER) {
                    final int backdropSaveCount = canvas.save();
                    try {
                        canvas.clipPath(capsulePath);
                        host.drawBackdrop(canvas);
                    } finally {
                        canvas.restoreToCount(backdropSaveCount);
                    }
                }

                if (Color.alpha(fillPaint.getColor()) != 0) {
                    canvas.drawRoundRect(capsule, radius, radius, fillPaint);
                }
                canvas.drawRoundRect(capsule, radius, radius, sheenPaint);
                if (style != STYLE_DARK_PLAYER) {
                    canvas.drawRoundRect(capsule, radius, radius, specularPaint);
                }

                drawInteractiveHighlight(canvas);
                drawIndicator(canvas);

                // The dark line inside the rim.
                scratch.set(capsule);
                scratch.inset(RIM_WIDTH + BEVEL_WIDTH / 2, RIM_WIDTH + BEVEL_WIDTH / 2);
                final float bevelRadius = scratch.height() / 2;
                canvas.drawRoundRect(scratch, bevelRadius, bevelRadius, bevelPaint);

                // The rim lit from outside, brighter at the top.
                scratch.set(capsule);
                scratch.inset(RIM_WIDTH / 2, RIM_WIDTH / 2);
                final float rimRadius = scratch.height() / 2;
                canvas.drawRoundRect(scratch, rimRadius, rimRadius, rimPaint);
                canvas.drawRoundRect(scratch, rimRadius, rimRadius, strokePaint);
            } finally {
                canvas.restoreToCount(saveCount);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Glass navigation bar draw failure", ex);
        }
    }

    /**
     * The soft light under the finger, adapted from the interactive highlight of the BakaSU bar.
     * It is clipped to the capsule and fades with the press spring.
     */
    private void drawInteractiveHighlight(Canvas canvas) {
        final float pressProgress = press.value;
        if (pressProgress <= 0.01f || Float.isNaN(touchX) || Float.isNaN(touchY)) return;

        try {
            final int saveCount = canvas.save();
            try {
                canvas.clipPath(capsulePath);

                final float radius = Math.max(capsule.height() * 2.2f, Dim.dp(96));
                // The alpha follows the press spring, so the shader is remade while the finger
                // is down. The values are kept to avoid a pointless allocation when nothing moved.
                if (highlightPaint.getShader() == null
                        || Math.abs(highlightShaderX - touchX) > 0.5f
                        || Math.abs(highlightShaderY - touchY) > 0.5f
                        || Math.abs(highlightShaderRadius - radius) > 0.5f
                        || Math.abs(pressProgress - highlightShaderPress) > 0.01f) {
                    highlightShaderX = touchX;
                    highlightShaderY = touchY;
                    highlightShaderRadius = radius;
                    highlightShaderPress = pressProgress;
                    highlightPaint.setShader(new RadialGradient(touchX, touchY, radius,
                            withAlpha(Color.WHITE, 0.14f * pressProgress),
                            Color.TRANSPARENT, Shader.TileMode.CLAMP));
                }
                canvas.drawRect(capsule, highlightPaint);

                highlightPaint.setShader(null);
                highlightPaint.setColor(withAlpha(Color.WHITE, 0.04f * pressProgress));
                canvas.drawRect(capsule, highlightPaint);
            } finally {
                canvas.restoreToCount(saveCount);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Glass navigation bar highlight failure", ex);
        }
    }

    private void drawIndicator(Canvas canvas) {
        View tab = host.getSelectedTabView();
        if (tab == null || tab.getVisibility() != View.VISIBLE || tab.getWidth() <= 0) {
            if (indicatorPositionValid && !indicatorSkipLogged) {
                indicatorSkipLogged = true;
                Logger.printDebug(() -> "Glass navigation bar indicator skipped, no selected tab view");
            }
            return;
        }
        indicatorSkipLogged = false;

        // The position of the tab inside the navigation bar.
        final float tabLeft = GlassNavigationBarLayout.getLeftIn(tab, view);
        if (Float.isNaN(tabLeft)) return;

        final float minLeft = capsule.left + INDICATOR_INSET;
        final float maxRight = capsule.right - INDICATOR_INSET;
        float targetLeft = tabLeft + INDICATOR_INSET;
        float targetRight = tabLeft + tab.getWidth() - INDICATOR_INSET;
        if (targetRight - targetLeft > INDICATOR_MAX_WIDTH) {
            final float center = (targetLeft + targetRight) / 2;
            targetLeft = center - INDICATOR_MAX_WIDTH / 2f;
            targetRight = center + INDICATOR_MAX_WIDTH / 2f;
        }
        targetLeft = Math.max(targetLeft, minLeft);
        targetRight = Math.min(targetRight, maxRight);
        if (targetRight - targetLeft <= 0) return;

        setIndicatorTarget(targetLeft, targetRight);

        final float left = indicatorLeft.value;
        final float right = indicatorRight.value;
        if (right - left <= 0.5f) return;

        // The pill stretches along its movement and squashes across it while it travels,
        // like the velocity based scale of the BakaSU indicator.
        final float velocity = (indicatorLeft.velocity + indicatorRight.velocity) * 0.5f;
        final float stretch = Math.min(MAX_INDICATOR_STRETCH,
                Math.abs(velocity) * INDICATOR_STRETCH_PER_VELOCITY);
        final float scaleX = 1f + stretch;
        final float scaleY = 1f - stretch * 0.30f;

        final int saveCount = canvas.save();
        try {
            canvas.scale(scaleX, scaleY,
                    (left + right) / 2f, (capsule.top + capsule.bottom) / 2f);

            scratch.set(left, capsule.top + INDICATOR_INSET,
                    right, capsule.bottom - INDICATOR_INSET);
            if (scratch.height() <= 0) return;
            final float radius = scratch.height() / 2;

            canvas.drawRoundRect(scratch, radius, radius, indicatorPaint);
            if (press.value > 0.01f) {
                final float pressAlpha = (style == STYLE_LIGHT ? 0.06f : 0.04f) * press.value;
                indicatorPressPaint.setColor(withAlpha(
                        style == STYLE_LIGHT ? Color.BLACK : Color.WHITE, pressAlpha));
                canvas.drawRoundRect(scratch, radius, radius, indicatorPressPaint);
            }

            // The indicator is a small lens of the same glass: a top sheen and the bright rim.
            canvas.drawRoundRect(scratch, radius, radius, sheenPaint);
            canvas.drawRoundRect(scratch, radius, radius, strokePaint);
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // The glass keeps its own translucency.
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The colors come from the theme.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
