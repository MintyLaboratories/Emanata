package minty.emanata.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import minty.emanata.ui.ViewerActivity

/**
 * Debug builds only: opens a book in the reader, for checks on a device. Only the shell
 * may start it:
 *
 * `adb shell am start -n <package>/minty.emanata.debug.ReaderOpenActivity --es path <book> [--ei page <n>]`
 */
class ReaderOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path != null) {
            val open = Intent(this, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, path)
            if (intent.hasExtra("page")) open.putExtra(ViewerActivity.EXTRA_PAGE, intent.getIntExtra("page", 0))
            startActivity(open)
        }
        finish()
    }
}
