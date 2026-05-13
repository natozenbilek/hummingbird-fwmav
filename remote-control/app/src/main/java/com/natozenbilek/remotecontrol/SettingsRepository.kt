package com.natozenbilek.remotecontrol

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for persisting and loading FlightSettings using DataStore.
 *
 * This ensures user preferences are saved across app restarts.
 */
class SettingsRepository(private val context: Context) {
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "flight_settings")
        
        private val KEY_CONTROL_SCHEME = stringPreferencesKey("control_scheme")
        private val KEY_THROTTLE_BEHAVIOR = stringPreferencesKey("throttle_behavior")
        private val KEY_JOYSTICK_SENSITIVITY = floatPreferencesKey("joystick_sensitivity")
        private val KEY_JOYSTICK_DEADZONE = floatPreferencesKey("joystick_deadzone")
    }

    /**
     * Flow of FlightSettings that emits whenever settings change.
     */
    val settingsFlow: Flow<FlightSettings> = context.dataStore.data.map { preferences ->
        FlightSettings(
            controlScheme = try {
                ControlScheme.valueOf(
                    preferences[KEY_CONTROL_SCHEME] ?: ControlScheme.MODE2.name
                )
            } catch (e: IllegalArgumentException) {
                ControlScheme.MODE2
            },
            throttleBehavior = try {
                ThrottleBehavior.valueOf(
                    preferences[KEY_THROTTLE_BEHAVIOR] ?: ThrottleBehavior.RETURN_TO_CENTER.name
                )
            } catch (e: IllegalArgumentException) {
                ThrottleBehavior.RETURN_TO_CENTER
            },
            joystickSensitivity = preferences[KEY_JOYSTICK_SENSITIVITY] ?: 1.0f,
            joystickDeadzone = preferences[KEY_JOYSTICK_DEADZONE] ?: 0.05f
        )
    }

    /**
     * Save FlightSettings to DataStore.
     */
    suspend fun saveSettings(settings: FlightSettings) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CONTROL_SCHEME] = settings.controlScheme.name
            preferences[KEY_THROTTLE_BEHAVIOR] = settings.throttleBehavior.name
            preferences[KEY_JOYSTICK_SENSITIVITY] = settings.joystickSensitivity
            preferences[KEY_JOYSTICK_DEADZONE] = settings.joystickDeadzone
        }
    }
}







