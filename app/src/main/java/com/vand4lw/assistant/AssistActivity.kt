package com.vand4lw.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Entrada para gestos de "asistente" del sistema (mantener Home, etc.). Escucha y se cierra. */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = ControlService.instance
        if (svc != null) {
            svc.listen()
        } else {
            Toast.makeText(this, "Activá el servicio de accesibilidad de Vand4lw Assistant", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
