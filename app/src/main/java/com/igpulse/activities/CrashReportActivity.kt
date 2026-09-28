package com.igpulse.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.igpulse.R
import com.igpulse.databinding.ActivityCrashReportBinding

/**
 * Terminal crash surface for both the module app and the hooked Instagram process. In the
 * latter case the module starts this activity from an uncaught-exception handler, so it has to
 * stay dependency-free and tolerate a half-initialised task.
 */
class CrashReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val info = intent?.getStringExtra(EXTRA_CRASH_INFO).orEmpty()
        val trace = intent?.getStringExtra(EXTRA_CRASH_TRACE).orEmpty()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvCrashInfo.text = info
        binding.tvCrashTrace.text = trace

        binding.btnCopy.setOnClickListener { copy(info + "\n\n" + trace) }
        binding.btnShare.setOnClickListener {
            runCatching {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, info + "\n\n" + trace)
                        },
                        getString(R.string.share)
                    )
                )
            }
        }
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("igpulse", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CRASH_INFO = "crash_info"
        const val EXTRA_CRASH_TRACE = "crash_trace"
    }
}
