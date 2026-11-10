/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.BaseAppRefreshRatePatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"unused", "deprecation"})
public class AppRefreshRateListPreference extends CustomDialogListPreference {

    /**
     * List entry that opens the dialog to enter a custom refresh rate.
     */
    private static final String CUSTOM_ENTRY_VALUE = "CUSTOM";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AppRefreshRateListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public AppRefreshRateListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public AppRefreshRateListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AppRefreshRateListPreference(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        try {
            // Populate the list even if the player activity has not applied a mode yet.
            BaseAppRefreshRatePatch.updateAvailableRefreshRates(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read available refresh rates", ex);
        }
        refreshEntries();
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        super.onSetInitialValue(restoreValue, defaultValue);
        refreshEntries();
    }

    @Override
    public void setValue(String value) {
        super.setValue(value);
        refreshEntries();
    }

    @Override
    public boolean callChangeListener(Object newValue) {
        if (CUSTOM_ENTRY_VALUE.equals(newValue)) {
            // The list dialog is dismissed right after this method returns,
            // so open the input dialog on the next main thread loop iteration.
            mainHandler.post(() -> showCustomRefreshRateDialog(null));
            return false;
        }
        return super.callChangeListener(newValue);
    }

    private void refreshEntries() {
        String[] availableRefreshRates = BaseAppRefreshRatePatch.getAvailableRefreshRates();
        final String currentValue = getValue();

        List<CharSequence> entries = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();

        entries.add(str("morphe_app_refresh_rate_default"));
        values.add(SharedYouTubeSettings.APP_REFRESH_RATE.defaultValue);

        if (availableRefreshRates != null) {
            for (String refreshRate : availableRefreshRates) {
                entries.add(str("morphe_app_refresh_rate_fps", refreshRate));
                values.add(refreshRate);
            }
        }

        // Keep the current custom rate selectable, even if the display has no exact mode for it.
        if (parseCustomRefreshRate(currentValue) != null && !values.contains(currentValue)) {
            entries.add(str("morphe_app_refresh_rate_custom_summary", currentValue));
            values.add(currentValue);
        }

        entries.add(str("morphe_app_refresh_rate_custom"));
        values.add(CUSTOM_ENTRY_VALUE);

        setEntries(entries.toArray(new CharSequence[0]));
        setEntryValues(values.toArray(new CharSequence[0]));

        updateSummary();
    }

    private void updateSummary() {
        final String currentValue = getValue();
        if (currentValue == null || SharedYouTubeSettings.APP_REFRESH_RATE.defaultValue.equals(currentValue)) {
            setSummary(str("morphe_app_refresh_rate_default"));
            return;
        }

        final Integer customValue = parseCustomRefreshRate(currentValue);
        if (customValue == null) {
            setSummary(currentValue);
            return;
        }

        // Show the rate the display will really run at (a custom value can be unsupported).
        final int effectiveRefreshRate = getEffectiveRefreshRate();
        setSummary(effectiveRefreshRate > 0 && effectiveRefreshRate != customValue
                ? str("morphe_app_refresh_rate_summary_effective", currentValue, effectiveRefreshRate)
                : str("morphe_app_refresh_rate_fps", currentValue));
    }

    private int getEffectiveRefreshRate() {
        final Context context = getContext();
        if (context == null) {
            return 0;
        }

        try {
            return BaseAppRefreshRatePatch.getEffectiveRefreshRate(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the effective refresh rate", ex);
            return 0;
        }
    }

    /**
     * @param initialText Text to pre-fill the input with, or null to use the current value.
     */
    private void showCustomRefreshRateDialog(@Nullable String initialText) {
        final Context context = getContext();
        if (context == null) {
            return;
        }

        EditText editText = CustomDialog.createEditText(context);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        editText.setHint(str("morphe_app_refresh_rate_custom_hint",
                BaseAppRefreshRatePatch.MIN_CUSTOM_REFRESH_RATE,
                BaseAppRefreshRatePatch.MAX_CUSTOM_REFRESH_RATE));

        final String prefill = initialText != null
                ? initialText
                : (parseCustomRefreshRate(getValue()) != null ? getValue() : "");
        editText.setText(prefill);
        editText.setSelection(prefill.length());

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_app_refresh_rate_custom_title"),
                str("morphe_app_refresh_rate_custom_message",
                        BaseAppRefreshRatePatch.MIN_CUSTOM_REFRESH_RATE,
                        BaseAppRefreshRatePatch.MAX_CUSTOM_REFRESH_RATE),
                editText,
                null, // Default OK text.
                () -> {
                    final String input = editText.getText().toString().trim();
                    final Integer refreshRate = parseCustomRefreshRate(input);
                    if (refreshRate == null) {
                        Utils.showToastLong(str("morphe_app_refresh_rate_custom_invalid",
                                BaseAppRefreshRatePatch.MIN_CUSTOM_REFRESH_RATE,
                                BaseAppRefreshRatePatch.MAX_CUSTOM_REFRESH_RATE));
                        // The dialog is dismissed after this runs, so reopen it with the entered text.
                        mainHandler.post(() -> showCustomRefreshRateDialog(input));
                        return;
                    }

                    setCustomRefreshRate(refreshRate);
                },
                () -> {
                }, // Cancel button.
                null, // No neutral button.
                null,
                true
        );

        dialogPair.first.show();
    }

    private void setCustomRefreshRate(int refreshRate) {
        final String value = String.valueOf(refreshRate);
        if (!callChangeListener(value)) {
            return;
        }

        setValue(value);

        final int effectiveRefreshRate = getEffectiveRefreshRate();
        if (effectiveRefreshRate > 0 && effectiveRefreshRate != refreshRate) {
            final String[] modes = BaseAppRefreshRatePatch.getAvailableRefreshRates();
            final String modesText = modes == null ? "?" : String.join("/", modes);
            Utils.showToastLong(str("morphe_app_refresh_rate_custom_unsupported",
                    refreshRate, modesText));
        }
    }

    /**
     * @return The refresh rate if the value is a valid number, otherwise null.
     */
    @Nullable
    private static Integer parseCustomRefreshRate(@Nullable String value) {
        if (value == null) {
            return null;
        }

        try {
            final int refreshRate = Integer.parseInt(value.trim());
            return BaseAppRefreshRatePatch.isValidCustomRefreshRate(refreshRate) ? refreshRate : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
