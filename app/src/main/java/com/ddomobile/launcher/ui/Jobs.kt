package com.ddomobile.launcher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One background job at a time for a launcher page, with its stage and progress for the UI. */
class Jobs(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())

    var busy by mutableStateOf<String?>(null)
        private set
    /** 0..1, or -1 while unknown. */
    var progress by mutableFloatStateOf(-1f)
        private set
    /** What the job was for, so a row can show its own progress. */
    var target by mutableStateOf<String?>(null)
        private set

    /** Runs [job] off the main thread; its result, or its error, is shown as a toast, then [done] runs. */
    fun run(label: String, target: String? = null, done: () -> Unit = {}, job: (stage: (String, Float) -> Unit) -> String?) {
        if (busy != null) return
        busy = label; progress = -1f; this.target = target
        Thread({
            val result = runCatching { job { stage, p -> ui.post { busy = stage; progress = p } } }
            ui.post {
                busy = null; this.target = null
                val text = result.fold({ it }, { it.message ?: it.javaClass.simpleName })
                if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                done()
            }
        }, "launcher-job").start()
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
