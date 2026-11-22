package com.example

import android.app.Application
import com.example.data.repository.KodAppContainer

/**
 * Custom Application entry point hosting the process-wide [KodAppContainer] singleton graph.
 */
class KodApplication : Application() {
    val container: KodAppContainer by lazy { KodAppContainer(this) }
}
