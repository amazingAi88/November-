package com.hindidub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private var video: Uri? = null
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var go: Button
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // dubbing के दौरान स्क्रीन ON
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val pick = Button(this).apply { text = "1. वीडियो चुनें" }
        go = Button(this).apply { text = "2. हिंदी Dub शुरू करें"; isEnabled = false }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        status = TextView(this).apply { text = "वीडियो चुनिए"; textSize = 16f; setPadding(0, 32, 0, 0) }
        listOf(pick, go, bar, status).forEach { l.addView(it) }
        setContentView(l)
        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }, 1)
        }
        go.setOnClickListener {
            val u = video ?: return@setOnClickListener
            go.isEnabled = false
            scope.launch {
                val msg = try { Pipeline(this@MainActivity) { t, p ->
                    runOnUiThread { status.text = t; bar.progress = p } }.run(u)
                } catch (e: Throwable) { "Error: ${e.message}" }
                runOnUiThread { status.text = msg; bar.progress = 100; go.isEnabled = true }
            }
        }
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK && d?.data != null) {
            video = d.data; go.isEnabled = true; status.text = "वीडियो चुन लिया ✔"
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
