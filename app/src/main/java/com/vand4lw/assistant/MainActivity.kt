package com.vand4lw.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** Pantalla de configuración: key de Gemini, permisos y pruebas. La lógica vive en ControlService/Assistant. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this)
        val inputKey = EditText(this).apply {
            hint = "Pegá tu GEMINI API KEY"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val btnSave = Button(this).apply { text = "1. Guardar key" }
        val btnPerms = Button(this).apply { text = "2. Permisos (mic, llamadas, contactos)" }
        val btnBright = Button(this).apply { text = "3. Permiso de brillo" }
        val btnAcc = Button(this).apply { text = "4. Activar accesibilidad" }
        val btnBubble = Button(this).apply { text = "5. Mostrar / ocultar bola" }
        val inputCmd = EditText(this).apply {
            hint = "Escribí una orden (probar sin voz)"
            setText("reproducí Duki en Spotify y subí el volumen al 80")
        }
        val btnSend = Button(this).apply { text = "Enviar orden" }
        logView = TextView(this).apply {
            text = ControlService.lastLog
            setTextIsSelectable(true)
        }

        listOf(status, inputKey, btnSave, btnPerms, btnBright, btnAcc, btnBubble, inputCmd, btnSend, logView)
            .forEach { layout.addView(it) }
        setContentView(ScrollView(this).apply { addView(layout) })

        btnSave.setOnClickListener {
            val key = inputKey.text.toString().trim()
            if (key.isEmpty()) {
                toast("Pegá la API key")
            } else {
                prefs.edit().putString("key", key).apply()
                toast("Key guardada")
            }
        }

        btnPerms.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS),
                1
            )
        }

        btnBright.setOnClickListener {
            if (Settings.System.canWrite(this)) {
                toast("El permiso de brillo ya está dado")
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Entrá a 'Vand4lw Assistant' y activalo")
        }

        btnBubble.setOnClickListener {
            val s = ControlService.instance
            if (s == null) toast("Primero activá la accesibilidad (paso 4)")
            else toast(if (s.toggleBubble()) "Bola visible" else "Bola oculta")
        }

        btnSend.setOnClickListener {
            val s = ControlService.instance
            val key = inputKey.text.toString().trim()
            if (key.isNotEmpty()) prefs.edit().putString("key", key).apply()
            if (s == null) toast("Primero activá la accesibilidad (paso 4)")
            else s.runCommand(inputCmd.text.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (ControlService.instance != null) "✅ Accesibilidad activa" else "⚠️ Accesibilidad apagada (paso 4)"
        logView.text = ControlService.lastLog
        ControlService.logListener = { msg -> runOnUiThread { logView.text = msg } }
    }

    override fun onPause() {
        ControlService.logListener = null
        super.onPause()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
