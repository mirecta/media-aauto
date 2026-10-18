package sk.talasek.mediaauto.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import sk.talasek.mediaauto.App
import sk.talasek.mediaauto.R
import sk.talasek.mediaauto.sosac.SosacApi
import sk.talasek.mediaauto.sosac.SosacException
import kotlin.reflect.KMutableProperty0

/** Prihlasenie k Sosac a Streamuj.tv a volby prehravania. */
class SettingsActivity : AppCompatActivity() {

    private val app by lazy { App.get(this) }
    private val prefs by lazy { app.prefs }
    private lateinit var box: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.settings)
            navigationIcon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        account(
            "Sosac – metadáta", prefs::sosacUser, prefs::sosacHash, { u, p -> SosacApi.sosacHash(u, p) }
        ) {
            app.api.checkSosac()
            "Prihlásenie je v poriadku"
        }
        account(
            "Streamuj.tv – úložisko", prefs::streamujUser, prefs::streamujHash, { _, p -> SosacApi.streamujHash(p) }
        ) {
            val d = app.api.checkStreamuj()
            when (d.optInt("result")) {
                2 -> "Premium účet, platný do " + d.optString("expiration")
                1 -> "Prihlásenie je v poriadku (bez premium)"
                else -> "Nesprávne meno alebo heslo"
            }
        }

        val qualities = listOf("UHD HDR" to "2160p HDR", "UHD" to "2160p", "FHD" to "1080p", "HD" to "720p", "SD" to "480p")
        val dubs = listOf("CZ" to "CZ", "SK" to "SK", "EN" to "EN")
        section("Prehrávanie")
        option("Kvalita v telefóne", prefs::quality, qualities)
        option("Kvalita v aute (len zvuk)", prefs::carQuality, qualities)
        option("Preferovaný dabing", prefs::dub1, dubs)
        option("Druhý dabing", prefs::dub2, dubs)
        option("Server úložiska", prefs::location, listOf("1" to "1", "2" to "2"))
        box.addView(MaterialSwitch(this).apply {
            text = "Automaticky prehrať ďalšiu epizódu"
            isChecked = prefs.nextEpisode
            setOnCheckedChangeListener { _, checked -> prefs.nextEpisode = checked }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun section(title: String) {
        box.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, dp(24), 0, dp(8))
        })
    }

    private fun field(hint: String, password: Boolean): TextInputEditText {
        val layout = TextInputLayout(this).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        layout.addView(edit)
        box.addView(layout, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        return edit
    }

    private fun account(
        title: String,
        user: KMutableProperty0<String>,
        hash: KMutableProperty0<String>,
        makeHash: (String, String) -> String,
        check: suspend () -> String
    ) {
        section(title)
        val userField = field("Používateľské meno", false).apply { setText(user.get()) }
        val savedHint = "Heslo (uložené – vyplňte len pri zmene)"
        val passField = field(if (hash.get().isNotEmpty()) savedHint else "Heslo", true)
        val status = TextView(this)
        val button = MaterialButton(this).apply { text = "Uložiť a overiť" }
        button.setOnClickListener {
            val u = userField.text.toString().trim()
            val p = passField.text.toString()
            if (p.isNotEmpty()) hash.set(makeHash(u, p))
            else if (u != user.get()) {
                status.text = "Pri zmene mena zadajte aj heslo"
                return@setOnClickListener
            }
            user.set(u)
            passField.setText("")
            if (hash.get().isNotEmpty()) (passField.parent.parent as? TextInputLayout)?.hint = savedHint
            status.text = "Overujem…"
            lifecycleScope.launch {
                status.text = try {
                    check()
                } catch (e: SosacException) {
                    if (e.code == 401) "Nesprávne meno alebo heslo" else e.message
                } catch (e: Exception) {
                    e.message ?: "Neznáma chyba"
                }
            }
        }
        box.addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        box.addView(status)
    }

    private fun option(label: String, prop: KMutableProperty0<String>, values: List<Pair<String, String>>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        row.addView(TextView(this).apply { text = label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map { it.second })
        spinner.setSelection(values.indexOfFirst { it.first == prop.get() }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = prop.set(values[position].first)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        row.addView(spinner)
        box.addView(row)
    }
}
