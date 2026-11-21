package com.infinity.comicreader.ai

import okhttp3.OkHttpClient
import org.junit.rules.ExternalResource
import java.io.IOException

/**
 * Keeps the model downloads a test starts off the network: each fails at once, and none
 * outlives the test, so no progress of one reaches the screens of later tests.
 */
class OfflineDownloads : ExternalResource() {
    override fun before() {
        ModelDownloads.client = { OFFLINE }
    }

    override fun after() {
        ModelDownloads.reset()
        ModelDownloads.client = { ModelDownloader.defaultClient }
    }

    private companion object {
        /** Refuses every request before it leaves the machine. */
        val OFFLINE: OkHttpClient = OkHttpClient.Builder().addInterceptor { throw IOException("offline in tests") }.build()
    }
}
