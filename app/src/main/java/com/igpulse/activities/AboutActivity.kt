package com.igpulse.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.igpulse.BuildConfig
import com.igpulse.R
import com.igpulse.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.version.text = getString(R.string.igpulse_version) + ": " + BuildConfig.VERSION_NAME
        binding.description.setText(R.string.about_description)
        binding.sourceCode.setOnClickListener { open(BuildConfig.SOURCE_URL) }
        binding.reportIssue.setOnClickListener { open(BuildConfig.ISSUES_URL) }
    }

    private fun open(url: String) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
