/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings({"deprecation", "unused"})
public final class BaseAppRefreshRatePatch {

    /**
     * Lowest allowed custom refresh rate.
     */
    public static final int MIN_CUSTOM_REFRESH_RATE = 30;

    /**
     * Highest allowed custom refresh rate.
     * <p>
     * No display supports this, it is only the upper bound the user can enter.
     * The closest supported display mode is used instead.
     */
    public static final int MAX_CUSTOM_REFRESH_RATE = 999;

    public enum AppRefreshType {
        ALWAYS,
        PORTRAIT,
        FULLSCREEN,
        PORTRAIT_FULLSCREEN
    }

    public static class RefreshRateType implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return !SharedYouTubeSettings.APP_REFRESH_RATE.isSetToDefault();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(SharedYouTubeSettings.APP_REFRESH_RATE);
        }
    }

    private static final List<WeakReference<Window>> trackedWindows = new ArrayList<>();
    @Nullable
    private static Integer preferredDisplayModeId;
    @Nullable
    private static Float preferredRefreshRate;
    @Nullable
    private static String[] availableRefreshRates;
    @Nullable
    private static String computedForRate;

    private static boolean isPlaybackPortrait;
    private static boolean isPlaybackFullscreen;

    /**
     * @return If the entered value is a valid custom refresh rate.
     */
    public static boolean isValidCustomRefreshRate(int refreshRate) {
        return refreshRate >= MIN_CUSTOM_REFRESH_RATE && refreshRate <= MAX_CUSTOM_REFRESH_RATE;
    }

    /**
     * Reads the refresh rate straight from the preferences instead of the cached {@link Setting} value.
     * The cache is updated by the settings screen listener, but the order in which that listener and
     * this patch's own listener are called is not guaranteed.
     */
    @NonNull
    private static String getConfiguredRefreshRate() {
        try {
            return Setting.preferences.getString(SharedYouTubeSettings.APP_REFRESH_RATE.key,
                    SharedYouTubeSettings.APP_REFRESH_RATE.defaultValue);
        } catch (Exception ex) {
            return SharedYouTubeSettings.APP_REFRESH_RATE.get();
        }
    }

    /**
     * Same as {@link #getConfiguredRefreshRate()} but for the override type.
     */
    @NonNull
    private static AppRefreshType getConfiguredRefreshType() {
        try {
            return Setting.preferences.getEnum(SharedYouTubeSettings.APP_REFRESH_RATE_TYPE.key,
                    SharedYouTubeSettings.APP_REFRESH_RATE_TYPE.defaultValue);
        } catch (Exception ex) {
            return SharedYouTubeSettings.APP_REFRESH_RATE_TYPE.get();
        }
    }

    private static boolean isRefreshRateDefault(@NonNull String refreshRate) {
        return SharedYouTubeSettings.APP_REFRESH_RATE.defaultValue.equals(refreshRate);
    }

    public static boolean isPatchEnabled() {
        return isPatchIncluded() && !isRefreshRateDefault(getConfiguredRefreshRate());
    }

    /**
     * Frame rate compatibility used when the frame rate is forced on a surface.
     * {@code Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE}.
     */
    private static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;

    /**
     * {@code Surface.CHANGE_FRAME_RATE_ALWAYS}.
     */
    private static final int CHANGE_FRAME_RATE_ALWAYS = 1;

    private static final List<WeakReference<Surface>> trackedSurfaces = new ArrayList<>();

    /**
     * Guard so the frame rate re-asserted by this patch is not intercepted by its own hook.
     */
    private static boolean settingSurfaceFrameRate;

    /**
     * Replaces frame rate requests made by the host app (video playback, scrolling animations).
     * <p>
     * Window attributes alone are not enough: on devices where the system peak refresh rate
     * votes higher than the application request (Pixel, Android 13+), the window mode is only a
     * hint, while the frame rate requested for the surface is what the display compositor
     * actually follows. On LTPO panels this is also the only way to reach a frame rate that is
     * not a display mode (for example 40 FPS on a 60/120 Hz panel).
     *
     * @param requestedFrameRate Frame rate requested by the app.
     * @return The frame rate that should be requested instead.
     */
    public static float overrideSurfaceFrameRate(float requestedFrameRate) {
        if (settingSurfaceFrameRate || !isPatchIncluded() || requestedFrameRate <= 0) {
            return requestedFrameRate;
        }

        try {
            if (!isPatchEnabled() || !shouldOverrideRefreshRate()) {
                return requestedFrameRate;
            }

            final int targetRefreshRate = getConfiguredRefreshRateValue();
            return targetRefreshRate <= 0 ? requestedFrameRate : targetRefreshRate;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideSurfaceFrameRate failure", ex);
            return requestedFrameRate;
        }
    }

    /**
     * Remembers a surface the host app set a frame rate for, so the rate can be re-asserted
     * even while the app is idle (and therefore not requesting anything by itself).
     */
    public static void trackSurface(@Nullable Surface surface) {
        if (surface == null) {
            return;
        }

        Iterator<WeakReference<Surface>> iterator = trackedSurfaces.iterator();
        while (iterator.hasNext()) {
            Surface tracked = iterator.next().get();
            if (tracked == null) {
                iterator.remove();
            } else if (tracked == surface) {
                return;
            }
        }
        trackedSurfaces.add(new WeakReference<>(surface));
    }

    /**
     * Forces the configured frame rate on the tracked surfaces. The app requests nothing while
     * the screen is idle, so without this the display simply falls back to its idle rate.
     */
    public static void reassertSurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        Utils.runOnMainThreadDelayed(() -> {
            if (!isPatchEnabled() || !shouldOverrideRefreshRate()) {
                return;
            }

            final float frameRate = getConfiguredRefreshRateValue();
            if (frameRate <= 0) {
                return;
            }

            settingSurfaceFrameRate = true;
            try {
                Iterator<WeakReference<Surface>> iterator = trackedSurfaces.iterator();
                while (iterator.hasNext()) {
                    Surface surface = iterator.next().get();
                    if (surface == null || !surface.isValid()) {
                        iterator.remove();
                        continue;
                    }
                    surface.setFrameRate(frameRate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            CHANGE_FRAME_RATE_ALWAYS);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "reassertSurfaceFrameRate failure", ex);
            } finally {
                settingSurfaceFrameRate = false;
            }
        }, SURFACE_FRAME_RATE_REASSERT_DELAY_MS);
    }

    /**
     * How long to wait before re-asserting the frame rate. Long enough for the activity window
     * to be attached and the host app to make its own request first.
     */
    private static final long SURFACE_FRAME_RATE_REASSERT_DELAY_MS = 700;

    @Nullable
    public static String[] getAvailableRefreshRates() {
        Utils.verifyOnMainThread();
        return availableRefreshRates;
    }

    /**
     * @return The refresh rate that will actually be used by the display, or 0 if the setting is
     * the default one. Custom values the display cannot run at are mapped to a supported mode.
     */
    public static int getEffectiveRefreshRate(@NonNull Context context) {
        Utils.verifyOnMainThread();
        try {
            updateAvailableRefreshRatesIfNeeded(context);
        } catch (Exception ex) {
            Logger.printException(() -> "getEffectiveRefreshRate failure", ex);
        }
        return preferredRefreshRate == null ? 0 : Math.round(preferredRefreshRate);
    }

    /**
     * @return The refresh rate typed by the user, or 0 when the default value is used.
     */
    public static int getConfiguredRefreshRateValue() {
        final String configuredRefreshRate = getConfiguredRefreshRate();
        if (isRefreshRateDefault(configuredRefreshRate)) {
            return 0;
        }

        try {
            final int refreshRate = Integer.parseInt(configuredRefreshRate.trim());
            return Math.max(MIN_CUSTOM_REFRESH_RATE,
                    Math.min(MAX_CUSTOM_REFRESH_RATE, refreshRate));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Resolves the display mode if the cached value is missing (for example when the hook runs
     * before any activity applied the refresh rate).
     */
    private static void ensureRefreshRateComputed() {
        if (preferredDisplayModeId != null || !isPatchEnabled()) {
            return;
        }

        try {
            final Context context = Utils.getContext();
            if (context != null) {
                updateAvailableRefreshRatesIfNeeded(context);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not resolve the display modes", ex);
        }
    }

    /**
     * @return If this patch was included during patching.
     */
    private static boolean isPatchIncluded() {
        // Modified during patching upstream. FemAlloy applies this patch with Xposed hooks
        // instead of the patcher's smali injection, so it is always included here.
        return true;
    }

    private static boolean shouldOverrideRefreshRate() {
        return switch (getConfiguredRefreshType()) {
            case ALWAYS -> true;
            case PORTRAIT -> isPlaybackPortrait;
            case FULLSCREEN -> isPlaybackFullscreen;
            case PORTRAIT_FULLSCREEN -> isPlaybackPortrait || isPlaybackFullscreen;
        };
    }

    /**
     * Injection point.
     */
    public static void setActivityRefreshRate(Activity activity) {
        setWindowRefreshRate(activity, activity.getWindow());
    }

    public static void setWindowRefreshRate(Context context, @Nullable Window window) {
        if (!isPatchIncluded() || window == null) {
            return;
        }

        Utils.verifyOnMainThread();
        try {
            updateAvailableRefreshRatesIfNeeded(context);

            // Track the window even when the patch is disabled, so switching back to
            // 'Default' or changing the override type clears any previously applied mode.
            trackWindow(window);
            applyRefreshRateToWindow(window);
            reassertSurfaceFrameRate();
        } catch (Exception ex) {
            Logger.printException(() -> "setWindowRefreshRate failure", ex);
        }
    }

    /**
     * Recomputes the display modes when they are not known yet, or when the configured value changed.
     */
    private static void updateAvailableRefreshRatesIfNeeded(@NonNull Context context) {
        final String configuredRefreshRate = getConfiguredRefreshRate();
        if (availableRefreshRates != null && configuredRefreshRate.equals(computedForRate)) {
            return;
        }

        computedForRate = configuredRefreshRate;
        preferredDisplayModeId = null;
        preferredRefreshRate = null;

        Display display = getDisplay(context);
        if (display == null) {
            Logger.printDebug(() -> "No Display available; cannot set preferred mode");
            availableRefreshRates = null;
            return;
        }

        Display.Mode[] resolutionModes = getResolutionModes(display);
        if (resolutionModes.length == 0) {
            Logger.printDebug(() -> "No supported display modes reported");
            availableRefreshRates = null;
            return;
        }

        // Detect and store available refresh rates for the current resolution.
        availableRefreshRates = Arrays.stream(resolutionModes)
                .map(mode -> String.valueOf(Math.round(mode.getRefreshRate())))
                .distinct()
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toArray(String[]::new);

        Logger.printDebug(() -> "Refresh rates available: " + Arrays.toString(availableRefreshRates));

        if (isRefreshRateDefault(configuredRefreshRate) || !isPatchEnabled()) {
            return;
        }

        final int targetRefreshRate = parseRefreshRate(configuredRefreshRate);
        if (targetRefreshRate < 0) {
            // Invalid value, the setting was reset to the default above.
            return;
        }

        Display.Mode bestMode = findBestMode(resolutionModes, targetRefreshRate);
        if (bestMode == null) {
            Logger.printException(() -> "Could not find any suitable display modes");
            return;
        }

        preferredDisplayModeId = bestMode.getModeId();
        preferredRefreshRate = bestMode.getRefreshRate();

        Logger.printDebug(() -> "Refresh rate " + targetRefreshRate + " FPS -> mode "
                + bestMode.getModeId() + " (" + bestMode.getRefreshRate() + " FPS)");
    }

    /**
     * Refreshes the list of selectable refresh rates without applying anything to a window.
     * Used by the settings preference so the list is populated even before the player is opened.
     */
    public static void updateAvailableRefreshRates(@NonNull Context context) {
        Utils.verifyOnMainThread();
        try {
            if (availableRefreshRates != null) {
                return;
            }
            computedForRate = null;
            updateAvailableRefreshRatesIfNeeded(context);
        } catch (Exception ex) {
            Logger.printException(() -> "updateAvailableRefreshRates failure", ex);
        }
    }

    /**
     * @return The entered refresh rate, or -1 if the value is not a number (the setting is reset to default).
     * Out of range values are clamped to the allowed range.
     */
    private static int parseRefreshRate(@NonNull String refreshRateString) {
        final int refreshRate;
        try {
            refreshRate = Integer.parseInt(refreshRateString.trim());
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid refresh rate: " + refreshRateString, ex);
            SharedYouTubeSettings.APP_REFRESH_RATE.resetToDefault();
            return -1;
        }

        if (isValidCustomRefreshRate(refreshRate)) {
            return refreshRate;
        }

        final int clamped = Math.max(MIN_CUSTOM_REFRESH_RATE, Math.min(MAX_CUSTOM_REFRESH_RATE, refreshRate));
        Logger.printDebug(() -> "Clamping " + refreshRate + " FPS to " + clamped + " FPS");
        return clamped;
    }

    /**
     * Finds the highest supported mode that does not exceed the requested refresh rate.
     * If every mode is faster than the request (for example 30 FPS on a 60/120 Hz display),
     * the slowest mode is used.
     */
    @Nullable
    private static Display.Mode findBestMode(@NonNull Display.Mode[] modes, int targetRefreshRate) {
        Display.Mode bestMode = null;
        for (Display.Mode mode : modes) {
            if (Math.round(mode.getRefreshRate()) <= targetRefreshRate
                    && (bestMode == null || mode.getRefreshRate() > bestMode.getRefreshRate())) {
                bestMode = mode;
            }
        }

        if (bestMode != null) {
            return bestMode;
        }

        for (Display.Mode mode : modes) {
            if (bestMode == null || mode.getRefreshRate() < bestMode.getRefreshRate()) {
                bestMode = mode;
            }
        }
        return bestMode;
    }

    @Nullable
    private static Display getDisplay(@NonNull Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? context.getDisplay()
                : ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
    }

    @NonNull
    private static Display.Mode[] getResolutionModes(@NonNull Display display) {
        Display.Mode[] supportedModes = display.getSupportedModes();
        if (supportedModes == null || supportedModes.length == 0) {
            return new Display.Mode[0];
        }

        Display.Mode currentMode = display.getMode();
        return Arrays.stream(supportedModes)
                .filter(mode -> mode.getPhysicalWidth() == currentMode.getPhysicalWidth() &&
                        mode.getPhysicalHeight() == currentMode.getPhysicalHeight())
                .toArray(Display.Mode[]::new);
    }

    private static void trackWindow(@NonNull Window window) {
        Iterator<WeakReference<Window>> iterator = trackedWindows.iterator();
        while (iterator.hasNext()) {
            Window tracked = iterator.next().get();
            if (tracked == null) {
                iterator.remove();
            } else if (tracked == window) {
                return;
            }
        }
        trackedWindows.add(new WeakReference<>(window));
    }

    private static void applyRefreshRateToWindow(@NonNull Window window) {
        if (applyToWindowAttributes(window.getAttributes())) {
            window.setAttributes(window.getAttributes());
        }
    }

    /**
     * Writes (or clears) the preferred display mode on arbitrary window attributes.
     * <p>
     * This is also used by hooks on window attribute updates: the host app rewrites its window
     * parameters at any time (on the home feed, when the video player starts, when scrolling),
     * which silently dropped the override until the next activity resume. Re-injecting the mode
     * here makes the override stick.
     *
     * @return If the attributes were changed.
     */
    public static boolean applyToWindowAttributes(@NonNull WindowManager.LayoutParams params) {
        if (!isPatchIncluded()) {
            return false;
        }

        try {
            ensureRefreshRateComputed();

            final boolean shouldOverride = isPatchEnabled() && shouldOverrideRefreshRate();
            final int modeId = shouldOverride ? getPreferredDisplayModeId() : 0;
            final float refreshRate = shouldOverride ? getPreferredRefreshRate() : 0f;

            final boolean supportsRefreshRate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
            final boolean paramsEqual = params.preferredDisplayModeId == modeId
                    && (!supportsRefreshRate || params.preferredRefreshRate == refreshRate)
                    && getMaxDisplayRefreshRate(params) == refreshRate
                    && getMinDisplayRefreshRate(params) == refreshRate;
            if (paramsEqual) {
                return false;
            }

            params.preferredDisplayModeId = modeId;
            if (supportsRefreshRate) {
                params.preferredRefreshRate = refreshRate;
            }
            // 'preferredDisplayModeId' alone only anchors the base mode group on Android 13+:
            // the panel still runs at the system peak refresh rate. Capping the app requested
            // refresh rate range is what actually lowers the display mode.
            setMinDisplayRefreshRate(params, refreshRate);
            setMaxDisplayRefreshRate(params, refreshRate);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "applyToWindowAttributes failure", ex);
            return false;
        }
    }

    /**
     * The maximum refresh rate the app requests from the display.
     * <p>
     * {@code LayoutParams.preferredMaxDisplayRefreshRate} is public since Android 12 but was
     * removed from the public API later, so it is accessed reflectively.
     */
    @Nullable
    private static java.lang.reflect.Field maxDisplayRefreshRateField;
    private static boolean maxDisplayRefreshRateFieldResolved;

    @Nullable
    private static java.lang.reflect.Field minDisplayRefreshRateField;

    @Nullable
    private static java.lang.reflect.Field getMaxDisplayRefreshRateField() {
        if (!maxDisplayRefreshRateFieldResolved) {
            maxDisplayRefreshRateFieldResolved = true;
            try {
                maxDisplayRefreshRateField =
                        WindowManager.LayoutParams.class.getField("preferredMaxDisplayRefreshRate");
                minDisplayRefreshRateField =
                        WindowManager.LayoutParams.class.getField("preferredMinDisplayRefreshRate");
            } catch (Exception ex) {
                Logger.printDebug(() -> "preferredMinMaxDisplayRefreshRate is not available");
            }
        }
        return maxDisplayRefreshRateField;
    }

    private static float getMinDisplayRefreshRate(@NonNull WindowManager.LayoutParams params) {
        getMaxDisplayRefreshRateField();
        if (minDisplayRefreshRateField == null) {
            return 0f;
        }
        try {
            return minDisplayRefreshRateField.getFloat(params);
        } catch (Exception ex) {
            return 0f;
        }
    }

    private static void setMinDisplayRefreshRate(@NonNull WindowManager.LayoutParams params, float refreshRate) {
        getMaxDisplayRefreshRateField();
        if (minDisplayRefreshRateField == null) {
            return;
        }
        try {
            minDisplayRefreshRateField.setFloat(params, refreshRate);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set preferredMinDisplayRefreshRate", ex);
        }
    }

    private static float getMaxDisplayRefreshRate(@NonNull WindowManager.LayoutParams params) {
        java.lang.reflect.Field field = getMaxDisplayRefreshRateField();
        if (field == null) {
            return 0f;
        }
        try {
            return field.getFloat(params);
        } catch (Exception ex) {
            return 0f;
        }
    }

    private static void setMaxDisplayRefreshRate(@NonNull WindowManager.LayoutParams params, float refreshRate) {
        java.lang.reflect.Field field = getMaxDisplayRefreshRateField();
        if (field == null) {
            return;
        }
        try {
            field.setFloat(params, refreshRate);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set preferredMaxDisplayRefreshRate", ex);
        }
    }

    private static int getPreferredDisplayModeId() {
        return preferredDisplayModeId != null && preferredDisplayModeId > 0 ? preferredDisplayModeId : 0;
    }

    private static float getPreferredRefreshRate() {
        return preferredRefreshRate != null && preferredRefreshRate > 0 ? preferredRefreshRate : 0f;
    }

    public static void setVideoPlayerIsActive(boolean portrait, boolean fullscreen) {
        Utils.verifyOnMainThread();

        isPlaybackPortrait = portrait;
        isPlaybackFullscreen = fullscreen;

        Iterator<WeakReference<Window>> iterator = trackedWindows.iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().get();
            if (window == null) {
                iterator.remove();
            } else {
                applyRefreshRateToWindow(window);
            }
        }
    }
}
