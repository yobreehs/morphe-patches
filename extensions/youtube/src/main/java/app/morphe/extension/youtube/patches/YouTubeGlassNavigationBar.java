/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4338
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.glass.GlassCapsuleDrawable;
import app.morphe.extension.shared.ui.glass.GlassNavigationBarLayout;
import app.morphe.extension.youtube.patches.NavigationBarPatch.NavigationBarStyle;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Draws the bottom navigation bar as a floating, rounded, translucent capsule.
 * <p>
 * No extra copy of the content behind the bar is made. If the app draws its own blurred background
 * (the translucent navigation bar style, Android 12 and later, blurred only from Android 14), that
 * background is kept and clipped to the capsule. The frosted look otherwise comes from a tinted fill,
 * a soft gradient, a gradient edge and a shadow, all drawn by one drawable that is set as the
 * background of the navigation bar.
 * The bar keeps its height and vertical layout, and everything outside the capsule is left transparent.
 */
@SuppressWarnings("unused")
public final class YouTubeGlassNavigationBar {

    private static final int PIVOT_BAR = NavigationBarPatch.PIVOT_BAR;

    /**
     * The views of the navigation buttons, by button.
     */
    private static final Map<NavigationButton, WeakReference<View>> tabViews =
            new EnumMap<>(NavigationButton.class);

    @Nullable
    private static volatile NavigationButton selectedButton;

    /**
     * The drawable of the navigation bar currently on screen.
     */
    private static WeakReference<GlassCapsuleDrawable> currentDrawable = new WeakReference<>(null);

    /**
     * The navigation bar the glass was last applied to.
     */
    private static WeakReference<View> currentPivotBar = new WeakReference<>(null);

    static {
        // Registered unconditionally, so turning the glass on or off in the settings works
        // without restarting the app. The listener is called on the main thread.
        NavigationBar.addOnNavigationButtonChangedListener(button -> {
            // Kept up to date also while the glass is off, so the capsule follows the selected
            // tab as soon as the glass is turned on.
            selectedButton = button;
            if (!isEnabled()) return;

            Logger.printDebug(() -> "Glass selected tab: " + button);
            Utils.runOnMainThread(() -> {
                GlassCapsuleDrawable drawable = currentDrawable.get();
                if (drawable != null) {
                    drawable.onSelectedTabChanged();
                }
            });
        });
    }

    /**
     * The opaque style wins if both are turned on. Read for every frame, so a changed setting
     * is applied to the bar without restarting the app, and so the settings are not needed
     * when this class is loaded, which can be before the settings are loaded.
     */
    static boolean isEnabled() {
        return Settings.NAVIGATION_BAR_GLASS.get()
                && Settings.NAVIGATION_BAR_STYLE.get() != NavigationBarStyle.OPAQUE
                && !Settings.HIDE_NAVIGATION_BAR.get()
                && PIVOT_BAR != 0;
    }

    /**
     * The least room between the bottom of the capsule and the bottom of the bar,
     * so the capsule floats above the bottom of the screen.
     */
    private static final int FLOATING_ROOM = Dim.dp10;

    /**
     * The least room between the ends of the capsule and the edges of the screen.
     * Also used by the preview in the settings.
     */
    public static final int MARGIN_HORIZONTAL = Dim.dp12;

    /**
     * The most alpha of the tint the app draws over its blurred background.
     */
    private static final float LIGHT_BLUR_TINT_ALPHA = 0.50f;
    private static final float DARK_BLUR_TINT_ALPHA = 0.40f;

    /**
     * How opaque the glass is, from the opacity slider. The slider is at 50 by default, which
     * keeps the default alphas, and every alpha is scaled by it: 0 is fully clear, 100 doubles them.
     * Read for every frame, so a changed slider is applied without restarting the app.
     */
    private static float getOpacityScale() {
        return Math.max(0f, Math.min(2f,
                SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_OPACITY) / 50f));
    }

    /**
     * Height of the capsule, in percent of the height the app gives the bar
     * or of the height its buttons need, if they need more.
     */
    private static int getHeightPercent() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_HEIGHT);
    }

    /**
     * The most width of the capsule, in percent of the width of the bar.
     */
    private static int getWidthPercent() {
        return SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_WIDTH);
    }

    /**
     * Room between the content (icon and label) of two buttons.
     */
    private static int getButtonSpacing() {
        return Dim.dp(SeekBarPreference.clampToRange(Settings.NAVIGATION_BAR_GLASS_BUTTON_SPACING));
    }

    /**
     * Views of a button that hold its content, the icon and the label. Other views of the button,
     * such as the badge of the new content count, are left out. Also used by the preview in the settings.
     */
    public static final int BUTTON_ICON = ResourceUtils.getIdentifier(ResourceType.ID, "thumbnail_layout");
    public static final int BUTTON_LABEL = ResourceUtils.getIdentifier(ResourceType.ID, "text");

    /**
     * Buttons hidden because they do not fit in the width of the bar, shown again when they fit,
     * such as after a rotation.
     */
    private static final Set<View> hiddenButtons = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * The tint the app draws over its blurred background, kept so the opacity slider can
     * change its alpha without waiting for the app to set the background again.
     */
    private static WeakReference<Paint> blurTint = new WeakReference<>(null);
    private static int blurTintBaseAlpha;
    private static int blurTintAppliedAlpha;

    /**
     * Tabs for which a fallback label was already added, so a failed layout is not retried
     * on every frame.
     */
    private static final Set<View> labelAdded = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Tabs for which a fallback label could not be added, so the failure is logged once
     * instead of on every frame.
     */
    private static final Set<View> labelFailed = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Tabs for which the fallback label was skipped because the tab has no content description
     * to write into the label. Logged once per tab instead of on every frame.
     */
    private static final Set<View> labelNoDescription = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Labels hidden by the glass while the 'hide navigation button labels' setting is on.
     * The labels are hidden again on every frame, and shown again when the setting is turned
     * off, so a label created after the setting changed is handled too.
     */
    private static final Set<View> labelsHiddenByGlass = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Tabs for which no label of another tab was laid out yet. The app creates the labels of
     * its own buttons a frame later than the buttons themselves, so this is not a failure and
     * the label is added as soon as one exists.
     */
    private static final Set<View> labelTemplateMissing = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * How much taller the bar is than the height the app gives it, to hold the capsule.
     * Less than zero if the capsule is lower.
     */
    private static volatile int heightDelta;

    private static boolean floatingRoomLogged;

    /**
     * The room below the buttons the app last added to the height of the bar itself,
     * the room for the system navigation buttons.
     */
    private static volatile int appRoom;

    /**
     * The full height the app gives the bar, without the room below the buttons.
     */
    private static volatile int baseHeight;

    static void setAppRoom(int room) {
        appRoom = room;
    }

    /**
     * The room below the buttons the app was seen adding to the height of the bar,
     * and the bottom padding it was seen with.
     */
    private static volatile int seenAppRoom;
    private static volatile int seenAppRoomPadding;

    /**
     * The room of the last measurement and how many measurements in a row had it.
     */
    private static volatile int pendingAppRoom;
    private static volatile int pendingAppRoomCount;

    /**
     * How far the room the app adds to the height may differ from the bottom padding.
     */
    private static final int SEEN_ROOM_TOLERANCE = Dim.dp2;

    /**
     * How many measurements in a row must agree before the room is remembered. The app animates
     * the height of the bar while hiding and showing it, and those intermediate heights are not
     * the room the app adds.
     */
    private static final int SEEN_ROOM_STABLE_COUNT = 3;

    /**
     * Some configurations, such as with the layout updates of the app disabled, add the room
     * for the system navigation buttons to the height of the bar another way, so it is not known
     * as the room the app added, and it would be added twice. The bar is then given its full
     * height plus exactly its bottom padding, which is remembered for as long as the padding
     * stays the same, also while the app animates the height to hide the bar.
     */
    private static void updateSeenAppRoom(View navigationBar, int height) {
        final int padding = navigationBar.getPaddingBottom();
        if (padding != seenAppRoomPadding) {
            seenAppRoom = 0;
            seenAppRoomPadding = padding;
            pendingAppRoomCount = 0;
        }

        // The app can round the room it adds differently from the padding, such as 109 for a padding of 110.
        final int base = baseHeight;
        final int room = height - base;
        if (base <= 0 || padding <= 0 || Math.abs(room - padding) > SEEN_ROOM_TOLERANCE) {
            pendingAppRoomCount = 0;
            return;
        }

        if (room != pendingAppRoom) {
            pendingAppRoom = room;
            pendingAppRoomCount = 1;
            return;
        }
        if (++pendingAppRoomCount < SEEN_ROOM_STABLE_COUNT) return;

        if (seenAppRoom != room) {
            seenAppRoom = room;
            Logger.printDebug(() -> "Glass navigation bar room added by the app: " + room + " padding: " + padding);
        }
    }

    /**
     * @return The room below the buttons the app added to the height of the bar itself.
     */
    private static int getAppRoom(View navigationBar) {
        final int padding = navigationBar.getPaddingBottom();
        final int seenRoom = padding == seenAppRoomPadding ? seenAppRoom : 0;
        // The stored room can still be the room of a previous bar, such as after switching between
        // gesture and 3-button navigation, and the app never adds more room than the padding.
        return Math.max(0, Math.min(Math.max(padding, seenRoom), Math.max(appRoom, seenRoom)));
    }

    /**
     * Injection point helper.
     * <p>
     * The room below the buttons that the app did not add to the height of the bar, such as the
     * floating room without system navigation buttons, is added when the bar is measured.
     * The app also animates the height of the bar to hide it, so the room is added in proportion,
     * and the animation is left as it is.
     */
    static int getHeightMeasureSpec(View navigationBar, int heightMeasureSpec) {
        if (!isEnabled() || barAnimating) return heightMeasureSpec;

        try {
            if (View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.EXACTLY) {
                return heightMeasureSpec;
            }

            final int height = View.MeasureSpec.getSize(heightMeasureSpec);
            updateSeenAppRoom(navigationBar, height);
            final int added = getAddedRoom(navigationBar, height);
            if (added == 0) return heightMeasureSpec;

            return View.MeasureSpec.makeMeasureSpec(Math.max(0, height + added), View.MeasureSpec.EXACTLY);
        } catch (Exception ex) {
            Logger.printException(() -> "getHeightMeasureSpec failure", ex);
            return heightMeasureSpec;
        }
    }

    /**
     * Injection point helper.
     * <p>
     * The app moves the bar out of the screen by the height it gives the bar: by translation while
     * the player is dragged, and by a negative bottom margin when the bar is hidden on scroll.
     * The room added to the height is not part of it, so the top of the capsule would stay on screen.
     * The bar is moved down by the same part of that room, and is not moved while it is shown.
     */
    static float getTranslationY(View navigationBar, float translationY) {
        if (!isEnabled() || barAnimating) return translationY;

        try {
            ViewGroup.LayoutParams params = navigationBar.getLayoutParams();
            if (params == null || params.height <= 0) return translationY;

            final int added = getAddedRoom(navigationBar, params.height);
            if (added == 0) return translationY;

            final int marginOffset = params instanceof ViewGroup.MarginLayoutParams margins
                    ? Math.max(0, -margins.bottomMargin)
                    : 0;
            final float hidden = Math.min(1, Math.max(0, translationY + marginOffset) / params.height);
            return translationY + added * hidden;
        } catch (Exception ex) {
            Logger.printException(() -> "getTranslationY failure", ex);
            return translationY;
        }
    }

    /**
     * @param height The height the app gives the bar.
     * @return The room below the buttons that the app did not add to the height of the bar, and the
     *         height the capsule adds to the bar (less than zero if the capsule is lower), in proportion
     *         to that height, as the app also animates the height of the bar to hide it.
     */
    private static int getAddedRoom(View navigationBar, int height) {
        final int base = baseHeight;
        final int extra = navigationBar.getPaddingBottom() - getAppRoom(navigationBar) + heightDelta;
        if (base <= 0 || extra == 0 || height <= 0) return 0;

        // A navigation rail along the side.
        if (navigationBar.getWidth() > 0 && navigationBar.getWidth() <= 2 * Math.min(height, base)) {
            return 0;
        }

        return height >= base ? extra : Math.round(extra * (float) height / base);
    }

    /**
     * Called from {@link NativeSnackbarPatch}. The app lays the snackbars out above the height it
     * gives the navigation bar, so they would overlap the room added to it.
     *
     * @return The room added to the height of the navigation bar on screen.
     */
    static int getAddedRoomOnScreen() {
        if (!isEnabled()) return 0;

        View pivotBar = currentPivotBar.get();
        if (pivotBar == null || !pivotBar.isShown()) return 0;

        ViewGroup.LayoutParams params = pivotBar.getLayoutParams();
        return params != null && params.height > 0 ? getAddedRoom(pivotBar, params.height) : 0;
    }

    /**
     * Injection point helper.
     * <p>
     * The room below the buttons is the room the app leaves for the system navigation buttons.
     * Without them, such as with the gesture handle hidden, the capsule would touch the bottom
     * of the screen, so some room is added, the same way the app adds the room for the system
     * navigation buttons to the height of the bar. Pages laid out behind the bar follow its height.
     *
     * @param room The room the app leaves below the buttons.
     * @return The room below the buttons.
     */
    static int getFloatingRoom(View navigationBar, int room) {
        if (!isEnabled() || barAnimating || room >= FLOATING_ROOM) return room;

        try {
            final int width = navigationBar.getWidth();
            if (width > 0 && !GlassNavigationBarLayout.isHorizontalBottomBar(navigationBar)) return room;

            if (!floatingRoomLogged) {
                floatingRoomLogged = true;
                Logger.printDebug(() -> "Glass navigation bar floating room: " + room + " -> " + FLOATING_ROOM);
            }
            return FLOATING_ROOM;
        } catch (Exception ex) {
            Logger.printException(() -> "getFloatingRoom failure", ex);
            return room;
        }
    }

    /**
     * Injection point helper.
     * <p>
     * The tint the app draws over its blurred background makes the capsule dark, so its alpha
     * is set from the opacity slider, and by default more of the blurred content shows.
     */
    static void lightenBlurTint(Paint tint) {
        if (!isEnabled()) return;

        final int alpha = tint.getAlpha();
        // Only the translucent tint, not a solid bar color.
        if (alpha <= 0 || alpha >= 255) return;

        // If the app set the same tint again, keep the original alpha. Otherwise a tint that
        // this class already lightened would be used as the new base and become lighter on
        // every call.
        final int baseAlpha = blurTint.get() == tint && alpha == blurTintAppliedAlpha
                ? blurTintBaseAlpha
                : alpha;
        applyBlurTint(tint, baseAlpha);
    }

    /**
     * Applies the opacity slider to the tint of the blurred background.
     *
     * @param tint      The tint paint of the app.
     * @param baseAlpha The alpha the app gave the paint, before this class changed it.
     */
    private static void applyBlurTint(Paint tint, int baseAlpha) {
        if (baseAlpha <= 0 || baseAlpha >= 255) return;

        final int newAlpha = Math.round(255 * GlassCapsuleDrawable.scaledAlpha(
                Utils.isDarkModeEnabled() ? DARK_BLUR_TINT_ALPHA : LIGHT_BLUR_TINT_ALPHA,
                getOpacityScale()));
        blurTint = new WeakReference<>(tint);
        blurTintBaseAlpha = baseAlpha;
        if (tint.getAlpha() != newAlpha) {
            Logger.printDebug(() -> "Glass navigation bar blur tint alpha: "
                    + tint.getAlpha() + " -> " + newAlpha);
            tint.setAlpha(newAlpha);
        }
        blurTintAppliedAlpha = newAlpha;
    }

    /**
     * Reapplies the current opacity to the tint after the slider changed.
     */
    private static void updateBlurTintOpacity() {
        Paint tint = blurTint.get();
        if (tint != null) {
            applyBlurTint(tint, blurTintBaseAlpha);
        }
    }

    public static final class GlassAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.NAVIGATION_BAR_STYLE.get() != NavigationBarStyle.OPAQUE;
        }
    }

    static void navigationTabCreated(NavigationButton button, View tabView) {
        // Kept also while the glass is off, so the capsule follows the selected tab as soon as
        // the glass is turned on.
        synchronized (tabViews) {
            tabViews.put(button, new WeakReference<>(tabView));
        }
    }

    /**
     * Injection point: forwards the touch events of the navigation bar to the glass drawable,
     * which uses them for the press highlight and the breathing animation. The app still handles
     * the same events, so taps on the tabs are not affected.
     */
    public static void onNavigationBarTouch(MotionEvent event) {
        if (!isEnabled()) return;

        GlassCapsuleDrawable drawable = currentDrawable.get();
        if (drawable != null) {
            final int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_MOVE) {
                Logger.printDebug(() -> "Glass navigation bar touch action: " + action);
            }
            drawable.onTouchEvent(event);
        }
    }

    @Nullable
    private static View getSelectedTabView() {
        NavigationButton button = selectedButton;
        if (button == null) return null;

        WeakReference<View> reference;
        synchronized (tabViews) {
            reference = tabViews.get(button);
        }
        return reference == null ? null : reference.get();
    }

    /**
     * Hides the navigation tabs by the labels the app gave them. This covers the A/B layouts
     * whose items do not reach {@link NavigationBarPatch#navigationTabCreated}, so the normal
     * hide settings still work when the glass bar is enabled.
     */
    private static void hideTabsBySettings(ViewGroup tabs) {
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View tab = tabs.getChildAt(i);
            if (tab.getVisibility() == View.GONE) continue;

            final String label = getTabLabel(tab);
            if (label.isEmpty()) continue;

            final boolean hide =
                    (Settings.HIDE_HOME_BUTTON.get()
                            && (label.startsWith("home") || label.startsWith("главная")))
                    || (Settings.HIDE_SHORTS_BUTTON.get()
                            && (label.startsWith("shorts") || label.startsWith("шортс")))
                    || (Settings.HIDE_CREATE_BUTTON.get()
                            && (label.startsWith("create") || label.startsWith("создать")))
                    || (Settings.HIDE_SUBSCRIPTIONS_BUTTON.get()
                            && (label.startsWith("subscriptions") || label.startsWith("подписки")))
                    || (Settings.HIDE_NOTIFICATIONS_BUTTON.get()
                            && (label.startsWith("notifications") || label.startsWith("уведомления")));

            if (hide) {
                Logger.printDebug(() -> "Glass navigation bar hidden tab by label: " + label);
                tab.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Hides or shows the labels of the buttons while the bar is laid out, regardless of how
     * the app created them. The injection into the label creation covers only the layouts
     * where the label text is set in that constructor; other layouts keep the labels visible.
     *
     * <p>The label is the view with the app label id, or if that id is not used by a layout,
     * any TextView directly inside the tab.
     */
    private static void updateButtonLabelsVisibility(ViewGroup tabs) {
        final boolean hide = Settings.HIDE_NAVIGATION_BUTTON_LABELS.get();
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View tab = tabs.getChildAt(i);
            if (!(tab instanceof ViewGroup group) || group.getVisibility() == View.GONE) continue;

            View label = BUTTON_LABEL == 0 ? null : group.findViewById(BUTTON_LABEL);
            if (!(label instanceof TextView)) {
                label = null;
                for (int j = 0, childCount = group.getChildCount(); j < childCount; j++) {
                    View child = group.getChildAt(j);
                    if (child instanceof TextView) {
                        label = child;
                        break;
                    }
                }
            }
            if (label == null) continue;

            if (hide) {
                if (label.getVisibility() != View.GONE) {
                    label.setVisibility(View.GONE);
                    labelsHiddenByGlass.add(label);
                }
            } else if (labelsHiddenByGlass.remove(label)) {
                label.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * @return The text of the tab, from its content description or its label.
     */
    private static String getTabLabel(View tab) {
        CharSequence text = tab.getContentDescription();
        if (TextUtils.isEmpty(text)) {
            View label = BUTTON_LABEL == 0 ? null : tab.findViewById(BUTTON_LABEL);
            if (label instanceof TextView textView) {
                text = textView.getText();
            }
        }
        return text == null ? "" : text.toString().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Adds a label below the icon of an icon-only tab, if the conversion of the navigation item
     * did not happen. The labels are added as a fallback, so the bar is usable even when the
     * server sends an unknown layout.
     *
     * @return If a label was added and the bar has to be laid out again.
     */
    private static boolean ensureButtonLabels(ViewGroup tabs) {
        if (BUTTON_LABEL == 0
                || !Settings.DISABLE_ICON_ONLY_NAVIGATION_BUTTONS.get()
                || Settings.HIDE_NAVIGATION_BUTTON_LABELS.get()) {
            return false;
        }

        boolean added = false;
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View tab = tabs.getChildAt(i);
            if (!(tab instanceof ViewGroup group) || tab.getVisibility() == View.GONE
                    || labelAdded.contains(tab) || labelFailed.contains(tab)
                    || group.findViewById(BUTTON_LABEL) != null) {
                continue;
            }

            // The labels of the app are created a frame later than the buttons. Nothing is
            // allocated for this button until another button has a label to copy the layout of.
            if (labelTemplateMissing.contains(tab) && !hasLabelTemplate(tabs, tab)) {
                continue;
            }

            final CharSequence text = tab.getContentDescription();
            if (TextUtils.isEmpty(text)) {
                if (labelNoDescription.add(tab)) {
                    Logger.printDebug(() -> "Glass navigation bar label skipped, tab has no content description: "
                            + tab.getClass().getName());
                }
                continue;
            }

            TextView label = new TextView(tab.getContext());
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setTextColor(Utils.isDarkModeEnabled() ? 0xFFB0B0B0 : 0xFF606060);
            label.setId(BUTTON_LABEL);

            if (!addLabelBelowIcon(tabs, group, label)) continue;

            labelAdded.add(tab);
            added = true;
            Logger.printDebug(() -> "Glass navigation bar label added: " + text);
        }
        return added;
    }

    /**
     * Adds the label below the icon of the tab with the layout of the labels of the other tabs,
     * so it is placed below the icon by the constraints of the bar.
     * <p>
     * The class name of the layout params is not used: the app repackages the AndroidX classes,
     * so {@code ConstraintLayout$LayoutParams} could not be found by its name. The label was then
     * never added and the failure was retried on every frame.
     */
    private static boolean addLabelBelowIcon(ViewGroup tabs, ViewGroup parent, TextView label) {
        try {
            ViewGroup.LayoutParams params = createLabelParams(tabs, parent);
            if (params == null) {
                // Not a failure: the labels of the app are laid out a frame later. The label is
                // added once another button can give it its layout, and the skip is logged once.
                if (labelTemplateMissing.add(parent)) {
                    Logger.printDebug(() -> "Glass navigation bar label postponed, no label to copy the layout of");
                }
                return false;
            }
            parent.addView(label, params);
            labelTemplateMissing.remove(parent);
            return true;
        } catch (Throwable ex) {
            labelFailed.add(parent);
            Logger.printDebug(() -> "Glass navigation bar label failure: " + ex);
            return false;
        }
    }

    /**
     * @return If a tab other than the given one has a label to copy the layout of.
     */
    private static boolean hasLabelTemplate(ViewGroup tabs, View tab) {
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View sibling = tabs.getChildAt(i);
            if (sibling == tab || !(sibling instanceof ViewGroup siblingGroup)) continue;
            if (siblingGroup.findViewById(BUTTON_LABEL) != null) return true;
        }
        return false;
    }

    /**
     * @return The layout params for a label, copied from a label the app already made, so the
     * constraints and the class of the params are the ones the bar uses, or null if there is
     * no label to copy the layout of.
     */
    @Nullable
    private static ViewGroup.LayoutParams createLabelParams(ViewGroup tabs, ViewGroup tab) {
        for (int i = 0, count = tabs.getChildCount(); i < count; i++) {
            View sibling = tabs.getChildAt(i);
            if (sibling == tab || !(sibling instanceof ViewGroup siblingGroup)) continue;

            View label = siblingGroup.findViewById(BUTTON_LABEL);
            if (label == null || label.getLayoutParams() == null) continue;

            ViewGroup.LayoutParams params = copyLayoutParams(tab, label.getLayoutParams());
            if (params == null) continue;

            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            return params;
        }
        return null;
    }

    /**
     * Copies the layout params with the override of the bar, such as the one of the constraint
     * layout, which keeps the constraints of the label it is copied from.
     */
    @Nullable
    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup parent, ViewGroup.LayoutParams source) {
        try {
            Method method = ViewGroup.class.getDeclaredMethod(
                    "generateLayoutParams", ViewGroup.LayoutParams.class);
            method.setAccessible(true);
            Object params = method.invoke(parent, source);
            if (params instanceof ViewGroup.LayoutParams copy && copy != source) {
                return copy;
            }

            // The default implementation returns the given params, which cannot be shared with
            // another view, so they are copied by the constructor of their class.
            Constructor<?> constructor = source.getClass().getConstructor(source.getClass());
            return (ViewGroup.LayoutParams) constructor.newInstance(source);
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Glass navigation bar label params failure: " + ex);
            return null;
        }
    }

    static void apply(View bottomBarContainer) {
        View pivotBar = bottomBarContainer.findViewById(PIVOT_BAR);
        if (pivotBar == null || pivotBar == currentPivotBar.get()) return;
        currentPivotBar = new WeakReference<>(pivotBar);
        Logger.printDebug(() -> "Glass navigation bar apply: enabled: " + isEnabled()
                + " bar: " + pivotBar.getClass().getName());

        CapsuleHost capsuleHost = new CapsuleHost(pivotBar);
        GlassCapsuleDrawable glass = new GlassCapsuleDrawable(pivotBar, capsuleHost, getOpacityScale());
        currentDrawable = new WeakReference<>(glass);

        // The height the app gives the bar, without the room below the buttons.
        ViewGroup.LayoutParams params = pivotBar.getLayoutParams();
        // The room of this bar, not the stored room, which can be the room of a previous bar,
        // such as after switching between gesture and 3-button navigation.
        final int barRoom = pivotBar instanceof NavigationBarPatch.PivotBarAccess access
                ? Math.max(0, access.patch_getSystemButtonsRoom())
                : appRoom;
        baseHeight = params != null && params.height > 0 ? params.height - barRoom : 0;
        Logger.printDebug(() -> "Glass navigation bar base height: " + baseHeight + " app room: " + barRoom);

        // The background the app gave the navigation bar, which is put back if the bar
        // is not a horizontal bar at the bottom, such as a navigation rail on large screens.
        Drawable[] appBackground = {pivotBar.getBackground()};
        boolean[] logged = {false, false};
        int[] skippedFrames = {0};
        // The left, top and right padding the app gave the navigation bar.
        int[] appPadding = {pivotBar.getPaddingLeft(), pivotBar.getPaddingTop(), pivotBar.getPaddingRight()};

        // The app hands the navigation bar its own background again, such as on a configuration
        // change or in the Shorts player, so the glass is put back whenever that happens.
        pivotBar.getViewTreeObserver().addOnPreDrawListener(() -> {
            boolean layoutChanged = false;
            try {
                // Read for every frame, so the glass can be turned on or off in the settings
                // without restarting the app.
                if (!isEnabled()) {
                    if (pivotBar.getBackground() == glass) {
                        restoreAppearance(pivotBar, glass, appBackground[0], appPadding, capsuleHost, hiddenButtons);
                        layoutChanged = true;
                    }
                    barAnimating = false;
                    return !layoutChanged;
                }

                // The height the app gives the bar is not final when the container is created,
                // so it is taken again once the bar is laid out.
                if (baseHeight <= 0) {
                    final ViewGroup.LayoutParams barParams = pivotBar.getLayoutParams();
                    if (barParams != null && barParams.height > 0) {
                        final int room = pivotBar instanceof NavigationBarPatch.PivotBarAccess access
                                ? Math.max(0, access.patch_getSystemButtonsRoom())
                                : appRoom;
                        baseHeight = barParams.height - room;
                        Logger.printDebug(() -> "Glass navigation bar base height: " + baseHeight
                                + " app room: " + room);
                    }
                }

                // Whether the app is showing or hiding the bar. The geometry the glass adds to
                // the bar is not touched while it does, so the stock animation stays visible:
                // the glass background follows the bar it is the background of, and overriding
                // it would snap the bar to its final geometry on the first layout pass.
                final boolean animating = isAnimating(pivotBar);
                barAnimating = animating;

                Drawable background = pivotBar.getBackground();
                if (GlassNavigationBarLayout.isHorizontalBottomBar(pivotBar)) {
                    if (background != glass) {
                        appBackground[0] = background;
                        pivotBar.setBackground(glass);
                        // Set after the bar let go of its old background.
                        glass.setAppBackground(background);
                        if (!logged[0]) {
                            logged[0] = true;
                            Logger.printDebug(() -> {
                                int[] location = new int[2];
                                pivotBar.getLocationInWindow(location);
                                View parent = (View) pivotBar.getParent();
                                return "Glass navigation bar applied, height: " + pivotBar.getHeight()
                                        + " bottom padding: " + pivotBar.getPaddingBottom()
                                        + " room below: " + (pivotBar.getRootView().getHeight() - location[1] - pivotBar.getHeight())
                                        + " container height: " + parent.getHeight()
                                        + " container bottom padding: " + parent.getPaddingBottom()
                                        + " app background: " + (background == null ? null : background.getClass().getName());
                            });
                        }
                    }
                    glass.updateStyle();

                    // The settings dialog writes a new value without restarting the app, so the
                    // opacity and the blur tint are refreshed on each frame.
                    final float opacityScale = getOpacityScale();
                    if (opacityScale != glass.getOpacityScale()) {
                        glass.setOpacityScale(opacityScale);
                    }
                    updateBlurTintOpacity();

                    // Without system navigation buttons, the app never sets the room below the buttons,
                    // so it is set here, for the capsule to float above the bottom of the screen.
                    // The padding is kept by the patched setPadding of the bar. If it is still reset,
                    // it is put back right before the frame is drawn, so no frame is drawn with the
                    // buttons moved down.
                    // The room is added to the height of the bar when it is measured.
                    final int room = pivotBar.getPaddingBottom();
                    if (!animating && room < FLOATING_ROOM) {
                        Logger.printDebug(() -> "Glass navigation bar floating room set: " + room + " -> " + FLOATING_ROOM);
                        pivotBar.setPadding(pivotBar.getPaddingLeft(), pivotBar.getPaddingTop(),
                                pivotBar.getPaddingRight(), FLOATING_ROOM);
                        layoutChanged = true;
                    }

                    // The capsule is as wide as the buttons, so the indicator of the first and the last button
                    // has the same room from the ends of the capsule as from its top and bottom.
                    // The size of the buttons comes from their content, the icon and the label, so it follows
                    // the density, the font size and the language, and the buttons that do not fit in the width
                    // of the bar are hidden.
                    // The layout of the buttons is not touched while the app animates the bar:
                    // every layout write would fight the animation and its requestLayout churn
                    // throttles the animator of the buttons to a fraction of the frame rate.
                    if (!animating && pivotBar instanceof ViewGroup bar && bar.getChildCount() > 0
                            && bar.getChildAt(0) instanceof ViewGroup tabs) {
                        // The navigation button enum is not available for every A/B layout, so the
                        // hide settings are also applied to the tab views by their labels.
                        hideTabsBySettings(tabs);
                        if (ensureButtonLabels(tabs)) {
                            layoutChanged = true;
                        }
                        updateButtonLabelsVisibility(tabs);

                        // An icon-only button, such as the profile, is centered in the bar on its
                        // own, so its icon is lower than the icons of the buttons with a label.
                        // The icons are moved in line with the others.
                        GlassNavigationBarLayout.alignIcons(tabs, BUTTON_ICON, BUTTON_LABEL);

                        GlassNavigationBarLayout.Buttons buttons = GlassNavigationBarLayout.measureButtons(
                                tabs, BUTTON_ICON, BUTTON_LABEL, hiddenButtons);
                        final int contentWidth = buttons.contentWidth;
                        final int contentTop = buttons.contentTop;

                        if (buttons.isValid()) {
                            final int[] layout = GlassNavigationBarLayout.getButtonsLayout(pivotBar.getWidth(),
                                    buttons.views.size(), contentWidth, buttons.minWidth, getWidthPercent(), getButtonSpacing(),
                                    MARGIN_HORIZONTAL);
                            final int shown = layout[0];

                            // The buttons that do not fit are hidden from the end, but never the selected one.
                            if (GlassNavigationBarLayout.fitButtons(buttons.views, shown,
                                    getSelectedTabView(), hiddenButtons)) {
                                layoutChanged = true;
                            }
                            if (buttons.views.size() > shown && !logged[1]) {
                                logged[1] = true;
                                Logger.printDebug(() -> "Glass navigation bar buttons shown: " + shown
                                        + " of " + buttons.views.size());
                            }

                            // The content is in the middle of the capsule. The bar is laid out taller or lower,
                            // so the pages laid out behind it follow the height of the capsule.
                            final int contentHeight = buttons.contentHeight();
                            final int capsuleHeight = GlassNavigationBarLayout.getCapsuleHeight(
                                    baseHeight, contentHeight, getHeightPercent());
                            final int shift = (capsuleHeight - contentHeight) / 2 - contentTop;
                            final int paddingTop = Math.max(0, shift);
                            capsuleHost.capsuleTop = Math.max(0, -shift);
                            final int delta = baseHeight > 0 ? capsuleHeight + capsuleHost.capsuleTop - baseHeight : 0;
                            if (delta != heightDelta) {
                                heightDelta = delta;
                                pivotBar.requestLayout();
                                layoutChanged = true;
                            }

                            final int horizontalPadding = Math.max(MARGIN_HORIZONTAL,
                                    (pivotBar.getWidth() - shown * layout[1]) / 2);
                            if (pivotBar.getPaddingLeft() != horizontalPadding || pivotBar.getPaddingRight() != horizontalPadding
                                    || pivotBar.getPaddingTop() != paddingTop) {
                                if (pivotBar.getPaddingLeft() != horizontalPadding) appPadding[0] = pivotBar.getPaddingLeft();
                                if (pivotBar.getPaddingTop() != paddingTop) appPadding[1] = pivotBar.getPaddingTop();
                                if (pivotBar.getPaddingRight() != horizontalPadding) appPadding[2] = pivotBar.getPaddingRight();
                                pivotBar.setPadding(horizontalPadding, paddingTop,
                                        horizontalPadding, pivotBar.getPaddingBottom());
                                layoutChanged = true;
                            }

                            GlassNavigationBarLayout.lastContentWidth = contentWidth;
                            GlassNavigationBarLayout.lastMinButtonWidth = buttons.minWidth;
                            GlassNavigationBarLayout.lastContentHeight = contentHeight;
                            GlassNavigationBarLayout.lastBaseHeight = baseHeight;
                            GlassNavigationBarLayout.setLastButtons(buttons.views);
                        }
                    }
                } else if (background == glass && pivotBar.getWidth() > 0 && pivotBar.getHeight() > 0) {
                    // A bar that is being animated out has no height for a frame. The glass is
                    // kept then, so the capsule does not disappear before the bar has moved out.
                    restoreAppearance(pivotBar, glass, appBackground[0], appPadding, capsuleHost, hiddenButtons);
                    layoutChanged = true;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Glass navigation bar pre draw failure", ex);
            }

            // The frame is drawn again after the new layout, instead of once with the old one.
            // Only a few frames in a row are skipped, so the bar can never stop drawing.
            // Skipping while the app moves the bar would halve the frame rate of the animation,
            // so the frame is drawn during the animation even if the padding had to be put back.
            if (layoutChanged && !isAnimating(pivotBar) && skippedFrames[0] < 2) {
                skippedFrames[0]++;
                return false;
            }
            skippedFrames[0] = 0;
            return true;
        });
    }

    /**
     * If the app is currently animating the bar (showing or hiding it), updated every frame
     * by the pre-draw listener. While it is, all geometry the glass adds to the bar is left out:
     * the extra room is not added when the bar is measured, the translation is not changed,
     * the floating room is not added to the padding, and the layout of the bar is not touched.
     * <p>
     * Otherwise the glass snaps the bar to its final geometry on the first layout pass of the
     * animation, the animation of the bar is not visible, and the layout churn of the glass
     * slows the animation of the buttons the app also runs.
     */
    private static volatile boolean barAnimating;

    /**
     * @return If the app is moving the bar, such as by its translation, a negative bottom margin
     * or a height that is smaller than the height the bar is laid out with.
     */
    private static boolean isAnimating(View navigationBar) {
        if (navigationBar.getTranslationY() != 0f) return true;

        ViewGroup.LayoutParams params = navigationBar.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams margins && margins.bottomMargin < 0) {
            return true;
        }

        final int base = baseHeight;
        final int height = navigationBar.getHeight();
        return base > 0 && height > 0 && height < base;
    }

    /**
     * Puts the background and the padding of the app back and shows the buttons the glass hid,
     * such as when the glass is turned off in the settings, or when the bar is not a bottom bar.
     */
    private static void restoreAppearance(View pivotBar, GlassCapsuleDrawable glass,
                                          @Nullable Drawable appBackground, int[] appPadding,
                                          CapsuleHost capsuleHost, Set<View> hiddenButtons) {
        glass.setAppBackground(null);
        pivotBar.setBackground(appBackground);
        pivotBar.setPadding(appPadding[0], appPadding[1], appPadding[2], pivotBar.getPaddingBottom());
        GlassNavigationBarLayout.showHiddenButtons(hiddenButtons);
        barAnimating = false;
        if (pivotBar instanceof ViewGroup bar && bar.getChildCount() > 0
                && bar.getChildAt(0) instanceof ViewGroup tabs) {
            GlassNavigationBarLayout.resetIconAlignment(tabs, BUTTON_ICON, BUTTON_LABEL);
        }
        heightDelta = 0;
        capsuleHost.capsuleTop = 0;
        Logger.printDebug(() -> "Glass navigation bar restored, enabled: " + isEnabled());
    }

    /**
     * Where the capsule is drawn in the navigation bar.
     */
    private static final class CapsuleHost implements GlassCapsuleDrawable.Host {
        /**
         * How far the capsule reaches into the room below the buttons.
         */
        private static final int MAX_MARGIN_BOTTOM = Dim.dp4;

        private final View host;

        /**
         * The room between the top of the bar and the top of the capsule, if the capsule is lower than the
         * content of the buttons laid out by the app would need, as the content is not moved above the bar.
         */
        int capsuleTop;

        CapsuleHost(View host) {
            this.host = host;
        }

        @Override
        public void getCapsule(Rect bounds, RectF capsule) {
            // The bottom padding of the navigation bar is the room for the system navigation
            // buttons or gesture handle. The capsule holds the whole height of the buttons
            // and reaches only slightly into that room, so it floats above the gesture handle.
            final int bottomRoom = host.getPaddingBottom();
            float bottom = bounds.bottom - bottomRoom
                    + Math.max(0, Math.min(bottomRoom - FLOATING_ROOM, MAX_MARGIN_BOTTOM));
            // Some accounts lay the slim status bar ("No connection", "You're incognito") out over the room
            // below the buttons, and it is drawn over the bar. The capsule then does not reach into the room
            // the slim status bar covers, or its edge is cut. The bar's own translation is left out,
            // so the capsule keeps its size while the bar is moved behind the slim status bar to hide it.
            View slimStatusBar = host.getParent() instanceof ViewGroup container
                    ? container.findViewById(NavigationBarPatch.SLIM_STATUS_BAR)
                    : null;
            if (slimStatusBar != null && slimStatusBar.getParent() == host.getParent()
                    && slimStatusBar.getVisibility() == View.VISIBLE) {
                final float slimStatusBarTop = bounds.top + slimStatusBar.getTop()
                        + slimStatusBar.getTranslationY() - host.getTop();
                bottom = Math.min(bottom, Math.max(bounds.bottom - bottomRoom, slimStatusBarTop));
            }
            // The capsule ends at the edges of the buttons. The narrow buttons don't fill the bar,
            // and are in the middle of it.
            float left = bounds.left + host.getPaddingLeft();
            float right = bounds.right - host.getPaddingRight();
            View tab = getSelectedTabView();
            if (tab != null && tab.getParent() instanceof View tabs && tabs.getParent() == host
                    && tabs.getWidth() > 0) {
                left = Math.max(left, tabs.getLeft() + tabs.getTranslationX());
                right = Math.min(right, tabs.getRight() + tabs.getTranslationX());
            }
            float top = bounds.top + capsuleTop;
            if (barAnimating) {
                // The app animates the height of the bar. Keep the capsule inside it, anchored
                // to its bottom, so it grows with the bar when it is shown and sinks with it
                // when it is hidden, instead of sticking out of the shrinking bar.
                final float available = Math.max(0, bounds.height() - bottomRoom);
                top = Math.max(top, bottom - available);
            }
            capsule.set(left, top, right, bottom);
        }

        @Nullable
        @Override
        public View getSelectedTabView() {
            return YouTubeGlassNavigationBar.getSelectedTabView();
        }

        @Override
        public boolean isOverDarkPlayer() {
            // The Shorts player is always dark, whatever the app theme.
            return ShortsPlayerState.isOpen();
        }
    }

    private YouTubeGlassNavigationBar() {
    }
}
