package minty.emanata.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import minty.emanata.ai.AiPicks
import minty.emanata.ai.ChainKind
import minty.emanata.net.NetPolicy
import minty.emanata.net.NetRules
import minty.emanata.net.RemoteChannel
import minty.emanata.reader.Adjustments
import minty.emanata.reader.Scaling
import minty.emanata.reader.TallPages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Library screen settings as one value, so a screen can diff what changed. */
data class ListSettings(
    val showHidden: Boolean,
    val thumbnails: Boolean,
    /** Each tab's view, by tab name ([Prefs.TABS]). */
    val views: Map<String, TabView>,
    val coverShape: CoverShape,
    val coverSpacing: Int,
    val toolbarButtons: ToolbarButtons,
    val thumbFit: ThumbFit,
    val cropAnchor: CropAnchor,
    /** Each tab's sort and filter, by tab name. */
    val sorts: Map<String, TabSort>,
    val filters: Map<String, LibraryFilter>,
    val pinchPeek: PeekMode,
    val titleDisplay: TitleDisplay,
    val shortenPrefixes: Boolean,
    val pathView: Boolean,
    val statusBar: Boolean,
    val quickFab: Boolean,
    val fabTap: RingOption,
    /** What the floating button does with one item selected, and with several. */
    val fabOne: FabAction,
    val fabMany: FabAction,
    val leftHanded: Boolean,
    val groupFavorites: Boolean,
    val linearLight: Boolean,
    val detectDirection: Boolean,
    val charset: String,
    val thumbScaling: Scaling = Scaling.DEFAULT,
    val thrift: Thrift = Thrift.NONE
) {
    companion object {
        fun from(p: Prefs, saving: Boolean = false) = ListSettings(
            showHidden = p.showHidden,
            thumbnails = p.thumbnails,
            views = Prefs.TABS.associateWith { p.view(it) },
            coverShape = p.coverShape,
            coverSpacing = p.coverSpacing,
            toolbarButtons = p.toolbarButtons,
            thumbFit = p.thumbFit,
            cropAnchor = p.cropAnchor,
            sorts = Prefs.TABS.associateWith { p.sort(it) },
            filters = Prefs.TABS.associateWith { p.filter(it) },
            pinchPeek = p.pinchPeek,
            titleDisplay = p.titleDisplay,
            shortenPrefixes = p.shortenPrefixes,
            pathView = p.listPathView,
            statusBar = p.listStatusBar,
            quickFab = p.listQuickFab,
            fabTap = p.fabTap,
            fabOne = p.fabOne,
            fabMany = p.fabMany,
            leftHanded = p.leftHanded,
            groupFavorites = p.groupFavorites,
            linearLight = p.linearLight,
            detectDirection = p.detectDirection,
            charset = p.charset,
            thumbScaling = p.thumbScaling,
            thrift = Thrift.of(saving, p)
        )
    }
}

/** Reader settings (global defaults; a folder's remembered layout is layered on top). */
data class ReaderSettings(
    val fullscreen: Boolean,
    val keepScreenOn: Boolean,
    val readingDir: String,
    val pageMode: String,
    val fit: String,
    val filter: String,
    val adjustments: Adjustments,
    val pageSpacing: Int,
    val autoNext: Boolean,
    val resumeMode: ResumeMode,
    val twoPage: TwoPage,
    val nightFilter: Boolean,
    val autoCrop: Boolean,
    val linearLight: Boolean,
    val preload: Int,
    val autoSpeed: Float,
    val perSeries: Boolean,
    val detectDirection: Boolean,
    val tapZones: String,
    val mirrorTapZones: Boolean,
    val volumeKeys: Boolean,
    val brightnessGesture: Boolean,
    val doubleTapZoom: Boolean,
    val fling: Boolean,
    val charset: String,
    val spreads: SpreadDisplay = SpreadDisplay.MERGE,
    val coverAlone: Boolean = true,
    val widePages: WidePages = WidePages.IMAGE,
    /** Which way a wide page's picture is turned ([WidePages.IMAGE]). */
    val leftHanded: Boolean = false,
    val joinSpreads: Boolean = true,
    val tallThreshold: Int = TallPages.DEFAULT_THRESHOLD,
    val pageScaling: Scaling = Scaling.DEFAULT,
    val zoomScaling: Scaling = Scaling.DEFAULT,
    val thumbScaling: Scaling = Scaling.DEFAULT,
    val thrift: Thrift = Thrift.NONE,
    /** The AI models chosen in Settings, by default chain, while AI processing is on; null while off. */
    val ai: Map<ChainKind, AiPicks>? = null,
    /** The reader's AI indicator shows ([Prefs.aiDot]). */
    val aiDot: Boolean = false,
    /** What optional network work is skipped on metered connections and slow shares: read-ahead and scans of share books. */
    val netRules: NetRules = NetRules()
) {
    companion object {
        fun from(p: Prefs, saving: Boolean = false) = ReaderSettings(
            fullscreen = p.fullscreen,
            keepScreenOn = p.keepScreenOn,
            readingDir = p.readingDir,
            pageMode = p.pageMode,
            fit = p.fit,
            filter = p.filter,
            adjustments = p.defaultAdjustments,
            pageSpacing = p.pageSpacing,
            autoNext = p.autoNext,
            resumeMode = p.resumeMode,
            twoPage = p.twoPage,
            nightFilter = p.nightFilter,
            autoCrop = p.autoCrop,
            linearLight = p.linearLight,
            preload = p.preload,
            autoSpeed = p.autoSpeed,
            perSeries = p.perSeries,
            detectDirection = p.detectDirection,
            tapZones = p.tapZones,
            mirrorTapZones = p.mirrorTapZones,
            volumeKeys = p.volumeKeys,
            brightnessGesture = p.brightnessGesture,
            doubleTapZoom = p.doubleTapZoom,
            fling = p.fling,
            charset = p.charset,
            spreads = p.spreads,
            coverAlone = p.coverAlone,
            widePages = p.widePages,
            leftHanded = p.leftHanded,
            joinSpreads = p.joinSpreads,
            tallThreshold = p.tallThreshold,
            pageScaling = p.pageScaling,
            zoomScaling = p.zoomScaling,
            thumbScaling = p.thumbScaling,
            thrift = Thrift.of(saving, p),
            ai = if (p.aiEnabled) ChainKind.entries.associateWith { AiPicks.of(p, it) } else null,
            aiDot = p.aiDot,
            netRules = p.netRules
        )
    }
}

/**
 * App-wide change feed for [Prefs]. Reads and writes still go through [Prefs];
 * this republishes a fresh snapshot whenever any setting changes, from whichever
 * screen wrote it, so consumers re-apply what changed live or when they start
 * again; likewise when Android's battery saver turns on or off ([Thrift]). Theme and
 * language are not here: they apply by recreating activities.
 */
class SettingsRepository internal constructor(private val app: Context) {
    private val prefs = Prefs(app)

    /** Whether battery saver is on, as last heard. */
    @Volatile
    private var saving = Thrift.saving(app)

    private val _list = MutableStateFlow(ListSettings.from(prefs, saving))
    val list: StateFlow<ListSettings> = _list.asStateFlow()

    private val _reader = MutableStateFlow(ReaderSettings.from(prefs, saving))
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    /** What battery saver holds back now. */
    val thrift: Thrift get() = _reader.value.thrift

    /** The library settings as stored now, whatever [list] last published. */
    fun listNow(): ListSettings = ListSettings.from(prefs, saving)

    /** The reader settings as stored now, whatever [reader] last published. */
    fun readerNow(): ReaderSettings = ReaderSettings.from(prefs, saving)

    private fun publish() {
        // First: screens applying the new settings ask the network policy what may run.
        holdBackShares()
        _list.value = ListSettings.from(prefs, saving)
        _reader.value = ReaderSettings.from(prefs, saving)
    }

    /** Share reads fetch nothing ahead while battery saver holds back read-ahead; optional network work follows the skip choices. */
    private fun holdBackShares() {
        RemoteChannel.thrifty = Thrift.of(saving, prefs).lessReadAhead
        NetPolicy.rules = prefs.netRules
    }

    /** Stops hearing stored settings change; tests use it to stand for a missed feed. */
    @VisibleForTesting
    internal fun stopHearingSettings() = prefs.unregisterListeners()

    private val saverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = Thrift.saving(app)
            if (now == saving) return
            saving = now
            publish()
        }
    }

    init {
        holdBackShares()
        prefs.registerListener { _, _ -> publish() }
        // A protected broadcast: only the system sends it.
        ContextCompat.registerReceiver(
            app, saverReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_EXPORTED
        )
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        /** One per application object; tests start a fresh application for each case. */
        fun get(context: Context): SettingsRepository {
            val app = context.applicationContext
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.app === app } ?: SettingsRepository(app).also { instance = it }
            }
        }
    }
}
