package sk.talasek.mediaauto.video

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Obrazovka v telefone: povolenie rychlosti auta (ziadost z auta by sa ukazala na zamknutom
 * telefone) a kratky navod. Samotne prehravanie a vyber su v aute.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val SPEED = "com.google.android.gms.permission.CAR_SPEED"
    }

    private lateinit var status: TextView

    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 17f }
        val help = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
            text = "Ako to funguje:\n\n" +
                "1. V aute otvorte Médiá Video (zo zoznamu aplikácií Android Auto).\n" +
                "2. Ťuknite na Vybrať a zvoľte film alebo seriál – obraz sa ukáže na displeji auta.\n" +
                "3. Počas jazdy je obraz zakrytý a pokračuje len zvuk.\n\n" +
                "Prihlásenie, sťahovanie a nastavenia sú v aplikácii Médiá Auto. " +
                "Pred jazdou zatvorte prehrávač v telefóne, inak si obraz ponechá telefón."
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(help)
        }
        setContentView(ScrollView(this).apply { addView(box); fitsSystemWindows = true })
        if (!granted()) request.launch(SPEED)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, SPEED) == PackageManager.PERMISSION_GRANTED

    private fun mainAppInstalled() = runCatching { packageManager.getPackageInfo("sk.talasek.mediaauto", 0) }.isSuccess

    private fun render() {
        status.text = buildString {
            append(if (granted()) "✓ Rýchlosť auta povolená" else "✗ Povoľte rýchlosť auta – bez nej sa obraz neukáže")
            append("\n")
            append(if (mainAppInstalled()) "✓ Médiá Auto je nainštalovaná" else "✗ Nainštalujte aplikáciu Médiá Auto")
        }
        status.setOnClickListener { if (!granted()) request.launch(SPEED) }
    }
}
