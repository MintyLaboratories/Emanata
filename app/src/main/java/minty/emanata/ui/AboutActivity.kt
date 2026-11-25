package minty.emanata.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import minty.emanata.R
import com.google.android.material.appbar.MaterialToolbar
import minty.emanata.util.Notice
import minty.emanata.util.Notices

/**
 * Version, source and upstream links, the app's license, a tip jar, every third-party license,
 * and the authors and licenses of the AI models the app downloads.
 */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val toolbar = findViewById<MaterialToolbar>(R.id.about_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version_format, versionName())

        val links = findViewById<LinearLayout>(R.id.about_links)
        addRow(links, getString(R.string.about_source), shortUrl(Notices.SOURCE_URL)) { open(Notices.SOURCE_URL) }
        addRow(links, getString(R.string.about_upstream), shortUrl(Notices.UPSTREAM_URL)) { open(Notices.UPSTREAM_URL) }
        addRow(links, getString(R.string.about_license), getString(R.string.about_license_summary)) { showLicense() }
        findViewById<View>(R.id.about_tip).setOnClickListener { open(Notices.TIP_URL) }

        val notices = findViewById<LinearLayout>(R.id.about_notices)
        for (n in Notices.all) addRow(notices, n.name, n.license) { showNotice(n) }

        val models = findViewById<LinearLayout>(R.id.about_models)
        for (n in Notices.models) addRow(models, n.name, n.license) { showNotice(n) }
    }

    /** [notice]'s note and license texts, with a button to its project page. */
    private fun showNotice(notice: Notice) {
        val view = layoutInflater.inflate(R.layout.dialog_notice, null)
        view.findViewById<TextView>(R.id.notice_note).apply {
            text = notice.note
            isVisible = notice.note != null
        }
        view.findViewById<TextView>(R.id.notice_text).text = Notices.fullText(notice) { name ->
            assets.open("${Notices.ASSET_DIR}/$name").bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(this)
            .setTitle(notice.name)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.about_project_page) { _, _ -> open(notice.url) }
            .show()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun addRow(parent: LinearLayout, title: String, subtitle: String, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_notice, parent, false)
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.subtitle).text = subtitle
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    /** The app's license notice, then the GPL's text and upstream's MIT license. */
    private fun showLicense() {
        val notice = resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
        val text = Notices.appLicense(notice) { name ->
            assets.open("${Notices.ASSET_DIR}/$name").bufferedReader().use { it.readText() }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_license)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shortUrl(url: String) = url.removePrefix("https://")
}
