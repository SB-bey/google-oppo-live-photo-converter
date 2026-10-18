package com.sbbey.motionphoto

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "settings"
        const val KEY_LIQUID_GLASS = "liquid_glass"
        const val KEY_THEME = "theme"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_HEVC_HINT = "hevc_hint"
    }

    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val switchLiquidGlass: MaterialSwitch = findViewById(R.id.switchLiquidGlass)
        val switchAutoSave: MaterialSwitch = findViewById(R.id.switchAutoSave)
        val switchHevcHint: MaterialSwitch = findViewById(R.id.switchHevcHint)
        val themeGroup: ChipGroup = findViewById(R.id.themeGroup)

        switchLiquidGlass.isChecked = prefs.getBoolean(KEY_LIQUID_GLASS, false)
        switchAutoSave.isChecked = prefs.getBoolean(KEY_AUTO_SAVE, false)
        switchHevcHint.isChecked = prefs.getBoolean(KEY_HEVC_HINT, true)

        val theme = prefs.getString(KEY_THEME, "system") ?: "system"
        val themeChipId = when (theme) {
            "blue" -> R.id.chipThemeBlue
            "purple" -> R.id.chipThemePurple
            "green" -> R.id.chipThemeGreen
            "pink" -> R.id.chipThemePink
            else -> R.id.chipThemeSystem
        }
        themeGroup.check(themeChipId)

        switchLiquidGlass.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_LIQUID_GLASS, checked).apply()
            changed = true
            applyLiquidGlass(checked)
        }
        switchAutoSave.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_SAVE, checked).apply()
            changed = true
        }
        switchHevcHint.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_HEVC_HINT, checked).apply()
            changed = true
        }
        themeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val id = checkedIds[0]
                val name = when (id) {
                    R.id.chipThemeBlue -> "blue"
                    R.id.chipThemePurple -> "purple"
                    R.id.chipThemeGreen -> "green"
                    R.id.chipThemePink -> "pink"
                    else -> "system"
                }
                prefs.edit().putString(KEY_THEME, name).apply()
                changed = true
            }
        }

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        // Google 相册凭据（实时保存）
        val inputClientId = findViewById<TextInputEditText>(R.id.inputClientId)
        val inputClientSecret = findViewById<TextInputEditText>(R.id.inputClientSecret)
        val inputApiKey = findViewById<TextInputEditText>(R.id.inputApiKey)
        inputClientId.setText(prefs.getString(GooglePhotosActivity.KEY_CLIENT_ID, ""))
        inputClientSecret.setText(prefs.getString(GooglePhotosActivity.KEY_CLIENT_SECRET, ""))
        inputApiKey.setText(prefs.getString(GooglePhotosActivity.KEY_API_KEY, ""))
        fun save(edit: TextInputEditText, key: String) {
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(key, s?.toString() ?: "").apply()
                    changed = true
                }
            })
        }
        save(inputClientId, GooglePhotosActivity.KEY_CLIENT_ID)
        save(inputClientSecret, GooglePhotosActivity.KEY_CLIENT_SECRET)
        save(inputApiKey, GooglePhotosActivity.KEY_API_KEY)

        applyLiquidGlass(switchLiquidGlass.isChecked)
    }

    private fun applyLiquidGlass(enabled: Boolean) {
        val cards = listOf(
            findViewById<MaterialCardView>(R.id.cardAppearance),
            findViewById<MaterialCardView>(R.id.cardBehavior),
            findViewById<MaterialCardView>(R.id.cardGoogle)
        )
        if (enabled) {
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val surface = ContextCompat.getColor(
                this, if (dark) R.color.liquid_glass_surface_dark else R.color.liquid_glass_surface
            )
            val stroke = ContextCompat.getColor(
                this, if (dark) R.color.liquid_glass_stroke_dark else R.color.liquid_glass_stroke
            )
            for (card in cards) {
                card.setCardBackgroundColor(surface)
                card.strokeColor = stroke
                card.strokeWidth = resources.getDimensionPixelSize(R.dimen.liquid_glass_stroke)
            }
        }
    }

    override fun finish() {
        setResult(if (changed) RESULT_OK else RESULT_CANCELED)
        super.finish()
    }
}
