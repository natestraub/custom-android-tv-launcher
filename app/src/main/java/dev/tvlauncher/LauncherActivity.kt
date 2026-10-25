package dev.tvlauncher

import android.app.Activity
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tvlauncher.apps.AppEntry
import dev.tvlauncher.apps.AppOrder
import dev.tvlauncher.apps.AppOrderStore
import dev.tvlauncher.apps.HdmiInputs
import dev.tvlauncher.apps.PinnedIcons
import dev.tvlauncher.apps.ContinueRowView
import dev.tvlauncher.apps.ContinueWatching
import dev.tvlauncher.apps.AppRepository
import dev.tvlauncher.apps.AppRowView
import dev.tvlauncher.apps.AppTileView
import dev.tvlauncher.firetv.FireTvHome
import dev.tvlauncher.firetv.HomeOverlay
import dev.tvlauncher.muse.MuseSecrets
import dev.tvlauncher.muse.link.MuseLinkService
import dev.tvlauncher.muse.link.MuseRuntime
import dev.tvlauncher.muse.ui.MuseButton
import dev.tvlauncher.ui.FrameAnimator
import dev.tvlauncher.ui.QuickBar
import dev.tvlauncher.ui.StatusBlock
import dev.tvlauncher.wallpaper.WallpaperController
import dev.tvlauncher.weather.WeatherController
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Home screen. Views are built in code (no XML inflation), and everything
 * slow — app/banner loading, network, image decode — runs on
 * background-priority threads.
 */
class LauncherActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    // Separate threads so a slow app scan (e.g. right after boot) never delays the wallpaper.
    private val io = backgroundThread("launcher-apps")
    private val wallpaperIo = backgroundThread("launcher-wallpaper")
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var appRow: AppRowView
    private lateinit var networkIcon: ImageView
    private lateinit var wallpaper: WallpaperController
    private lateinit var weather: WeatherController
    private lateinit var statusBlock: StatusBlock
    private lateinit var appRepository: AppRepository
    private lateinit var quickBar: QuickBar
    private lateinit var continueRow: ContinueRowView
    private lateinit var bottomStack: LinearLayout
    private var foldOpen = false
    private var foldFrom = 0f
    private var foldTo = 0f
    private val foldAnim = FrameAnimator(FOLD_MS) { f -> applyFold(foldFrom + (foldTo - foldFrom) * f) }
    private lateinit var continueWatching: ContinueWatching
    private lateinit var pinnedIcons: PinnedIcons
    private val rowPrefs by lazy { AppOrderStore(this) }
    private var allApps: List<AppEntry> = emptyList() // main thread
    private var knownApps: List<AppEntry> = emptyList() // io thread only
    private var appsLoaded = false
    // Fire TV: the home screen can be lent to an overlay over Amazon's (FireTvHome); it runs
    // while it's on screen either way.
    private var started = false
    private var lent = false
    private var running = false
    private val museButton by lazy { MuseButton(this) }
    private val menus by lazy { LauncherMenus(this, menuHost) }
    private val menuHost = object : LauncherMenus.Host {
        override val allApps get() = this@LauncherActivity.allApps
        override val rowPrefs get() = this@LauncherActivity.rowPrefs
        override val wallpaper get() = this@LauncherActivity.wallpaper
        override val weather get() = this@LauncherActivity.weather

        override fun startMove(packageName: String) = appRow.startMove(packageName)

        override fun redisplayApps() = displayApps(allApps)

        override fun reloadPinned() = this@LauncherActivity.reloadPinned()

        override fun focusRow() {
            appRow.requestFocus()
        }

        override fun pinLabel(key: String) = pinLabels(allApps)[key] ?: key
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tileW = 128.dp
        val tileH = 72.dp
        // Tiles are rendered at their focused size so the scale-up stays sharp.
        appRepository = AppRepository(
            this,
            (tileW * AppTileView.FOCUS_SCALE).roundToInt(),
            (tileH * AppTileView.FOCUS_SCALE).roundToInt(),
        )
        // Read the cached row while the views are built below.
        pinnedIcons = PinnedIcons(this, 26.dp) { port ->
            HdmiInputs.LOGOS[rowPrefs.hdmiLogo(port)]?.first ?: R.drawable.ic_input
        }
        val cachedPins = io.submit<List<Pair<String, Bitmap>>> { pinnedIcons.load(rowPrefs.pinned) }
        val cachedApps = io.submit<List<AppEntry>> {
            val t0 = android.os.SystemClock.uptimeMillis()
            appRepository.loadCached().also {
                knownApps = it
                Log.i(TAG, "cached row: ${it.size} apps in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            }
        }

        root = FrameLayout(this)

        val back = wallpaperView().also { root.addView(it) }
        val front = wallpaperView().also { root.addView(it) }
        root.addView(scrim(top = true), FrameLayout.LayoutParams(MATCH_PARENT, 150.dp, Gravity.TOP))
        root.addView(scrim(top = false), FrameLayout.LayoutParams(MATCH_PARENT, 220.dp, Gravity.BOTTOM))

        val gear = ImageView(this).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_settings)
            setBackgroundResource(R.drawable.icon_button_bg)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            isFocusable = true
            contentDescription = getString(R.string.settings)
            setOnClickListener { inFront { menus.showMenu() } }
        }
        root.addView(gear, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.START).apply {
            leftMargin = 32.dp
            topMargin = 22.dp
        })
        networkIcon = ImageView(this).apply { alpha = 0.9f }
        root.addView(networkIcon, FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.TOP or Gravity.START).apply {
            leftMargin = 84.dp
            topMargin = 30.dp
        })
        statusBlock = StatusBlock(this)
        root.addView(statusBlock.view, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = 40.dp
            topMargin = 20.dp
        })

        appRow = AppRowView(
            this,
            tileW = tileW,
            tileH = tileH,
            gap = 18.dp,
            padH = 16.dp,
            padV = 14.dp,
            radius = 20.dp.toFloat(),
        ).apply {
            onLaunch = ::launch
            onLongPress = { inFront { menus.showAppMenu(it) } }
            onOrderChanged = { rowPrefs.order = it }
        }
        // Bottom stack, top to bottom: quick bar (search + pins), app row, Continue watching.
        // Continue watching sits below the fold: the stack is pushed down by the row's height
        // until focus enters it, then slides up (translationY only — no relayout).
        // Focus chain: gear ↕ quick bar ↕ app row ↕ Continue watching; each returns to where you were.
        quickBar = QuickBar(
            this,
            onSearch = ::openSearch,
            onMuse = { inFront { museButton.open() } },
            onLaunch = ::openPin,
            onLongPress = { inFront { menus.showPinnedMenu(it) } },
            onDown = { appRow.requestFocus() },
        )
        quickBar.setUpFocus(gear.id)
        appRow.upFocusId = quickBar.search.id
        appRow.onDown = { continueRow.hasItems && continueRow.requestFocus() }

        continueRow = ContinueRowView(
            this,
            getString(R.string.continue_watching),
            cardW = CARD_W.dp,
            cardH = CARD_H.dp,
            gap = 18.dp,
            padV = 12.dp,
        ).apply {
            visibility = View.GONE
            onUp = { appRow.requestFocus() }
        }
        continueWatching = ContinueWatching(this, main, continueRow, CARD_W.dp, CARD_H.dp, focusScale = 1.1f)
        continueRow.onOpen = {
            FireTvHome.picked()
            continueWatching.open(it)
        }
        if (!continueWatching.hasPermission()) {
            requestPermissions(arrayOf(ContinueWatching.PERMISSION), REQUEST_TV_LISTINGS)
        }

        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(quickBar, LinearLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 44.dp).apply {
                leftMargin = 8.dp
                bottomMargin = 10.dp
            })
            addView(appRow, LinearLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(continueRow, LinearLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 10.dp
            })
        }
        root.addView(bottomStack, FrameLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = 40.dp
            rightMargin = 40.dp
            bottomMargin = 36.dp
        })

        gear.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && quickBar.search.requestFocus()
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(foldOnFocus)
        // Keep the fold closed (and correct) as the row appears, disappears or changes height.
        continueRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!foldAnim.running) setFold(foldOpen, animate = false)
        }

        setContentView(root)

        wallpaper = WallpaperController(this, back, front, wallpaperIo, main)
        weather = WeatherController(this, io, main, statusBlock::showWeather)
        // Brief wait so a normal start draws the complete screen in its first frame.
        val cached = try {
            cachedApps.get(FIRST_FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
        try {
            quickBar.setPinned(
                cachedPins.get(FIRST_FRAME_WAIT_MS, TimeUnit.MILLISECONDS),
                pinLabels(cached.orEmpty()),
            )
        } catch (_: Exception) {
            reloadPinned()
        }
        if (!cached.isNullOrEmpty()) {
            displayApps(cached)
            appsLoaded = true
            appRow.requestFocus()
        } else {
            io.execute { if (knownApps.isNotEmpty()) showApps(knownApps) }
        }
        refreshApps()
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, packageFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, packageFilter)
        }
        connectivity.registerDefaultNetworkCallback(networkCallback, main)
        // Keep the TV connected to Muse once it's paired (a cheap prefs check, off the main thread).
        io.execute { if (MuseSecrets(this).isPaired) MuseLinkService.start(this) }
        FireTvHome.attach(homeHost)
    }

    override fun onStart() {
        super.onStart()
        started = true
        updateRunning()
    }

    override fun onStop() {
        super.onStop()
        started = false
        updateRunning()
    }

    override fun onResume() {
        super.onResume()
        // Back from the overlay: take the home screen once the window is in (or soon, if it never animates).
        if (lent) main.postDelayed(takeBackFromOverlay, TAKE_BACK_FALLBACK_MS)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (lent) takeBackFromOverlay.run()
    }

    private val takeBackFromOverlay = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (lent) FireTvHome.launcherFront(window.decorView)
        }
    }

    /** The wallpaper, clock and Continue watching run while the home screen is on screen, here or lent. */
    private fun updateRunning() {
        val shouldRun = started || lent
        if (shouldRun == running) return
        running = shouldRun
        if (shouldRun) {
            continueWatching.refresh()
            wallpaper.start()
            weather.start()
        } else {
            wallpaper.stop()
            weather.stop()
        }
    }

    private val foldOnFocus = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        val inRow = focused != null && generateSequence(focused.parent) { it.parent }.any { it === continueRow }
        if (inRow != foldOpen) setFold(inRow, animate = true)
    }

    private val homeHost = object : HomeOverlay.Host {
        override fun lendHome(into: ViewGroup) {
            moveRoot { into.addView(root) }
            // Tree listeners stay with the window they were added in; the overlay is a new one.
            root.viewTreeObserver.addOnGlobalFocusChangeListener(foldOnFocus)
            lent = true
            updateRunning()
        }

        override fun takeHomeBack() {
            lent = false
            main.removeCallbacks(takeBackFromOverlay)
            moveRoot { setContentView(root) }
            updateRunning()
        }

        override fun onHomeAgain() = appRow.reset()
    }

    /** Removing a view clears the focus inside it: put the selection back where it was. */
    private fun moveRoot(attach: () -> Unit) {
        val focused = root.findFocus()
        (root.parent as? ViewGroup)?.removeView(root)
        attach()
        if (focused?.requestFocus() != true) appRow.requestFocus()
    }

    /** Dialogs (menus, Muse) need the activity in front; while lent, bring it forward first. */
    private fun inFront(action: () -> Unit) = FireTvHome.inFront(lent, action)

    /** Closed: Continue watching is pushed below the screen edge. Open: the whole stack slides up. */
    private fun setFold(open: Boolean, animate: Boolean) {
        foldOpen = open
        val closedOffset = if (continueRow.visibility == View.VISIBLE) (continueRow.height + 10.dp).toFloat() else 0f
        val target = if (open) 0f else closedOffset
        if (!animate) {
            foldAnim.cancel()
            applyFold(target)
            return
        }
        foldFrom = bottomStack.translationY
        foldTo = target
        foldAnim.start()
    }

    /** Slide the stack; Continue watching fades with it so nothing of it peeks out when closed. */
    private fun applyFold(offset: Float) {
        bottomStack.translationY = offset
        val closed = (continueRow.height + 10.dp).toFloat()
        continueRow.alpha = if (closed > 0f) (1f - offset / closed).coerceIn(0f, 1f) else 1f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_TV_LISTINGS) continueWatching.refresh()
    }

    override fun onDestroy() {
        FireTvHome.detach(homeHost)
        unregisterReceiver(packageReceiver)
        connectivity.unregisterNetworkCallback(networkCallback)
        io.shutdownNow()
        wallpaperIo.shutdownNow()
        continueWatching.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home (not the redirect bringing us back from the overlay).
        if (hasWindowFocus() && !intent.getBooleanExtra(FireTvHome.EXTRA_HANDOFF, false)) appRow.reset()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Fallback for the remote's Settings button when SettingsKeyService is off.
        if (SettingsButton.matches(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) SettingsButton.open(this)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Home screen: Back does nothing.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = Unit

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) wallpaper.release()
    }

    /** Re-scan installed apps; only new/updated apps are re-rendered, and the row updates if anything changed. */
    private fun refreshApps() {
        io.execute {
            val fresh = appRepository.scan(knownApps)
            if (fresh.map { it.packageName to it.updatedAt } != knownApps.map { it.packageName to it.updatedAt }) {
                showApps(fresh)
                reloadPinned()
            }
            knownApps = fresh
        }
    }

    /** Re-render pinned logos (after a pin change or an app update). */
    private fun reloadPinned() {
        io.execute {
            val icons = pinnedIcons.load(rowPrefs.pinned)
            main.post { quickBar.setPinned(icons, pinLabels(allApps)) }
        }
    }

    private fun showApps(apps: List<AppEntry>) = main.post {
        displayApps(apps)
        if (!appsLoaded) {
            appsLoaded = true
            appRow.requestFocus()
        }
    }

    /** Apply the user's order + hidden set; [allApps] keeps everything for the Hidden apps menu. */
    private fun displayApps(apps: List<AppEntry>) {
        allApps = apps
        val inRow = apps.filter { it.packageName !in rowPrefs.pinned }
        appRow.setApps(AppOrder.arrange(inRow, { it.packageName }, rowPrefs.order, rowPrefs.hidden))
    }

    private fun openPin(key: String) {
        FireTvHome.picked()
        val port = HdmiInputs.port(key)
        if (port != null) HdmiInputs.open(this, port) else allApps.firstOrNull { it.packageName == key }?.let(::launch)
    }

    private fun pinLabels(apps: List<AppEntry>): Map<String, String> =
        apps.associate { it.packageName to it.label } + HdmiInputs.PORTS.associate { HdmiInputs.key(it) to HdmiInputs.label(it) }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        museButton.onActivityResult(requestCode, resultCode, data)
    }

    private fun openSearch() {
        FireTvHome.picked()
        try {
            startActivity(Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "search unavailable", e)
        }
    }

    private fun launch(app: AppEntry) {
        FireTvHome.picked()
        try {
            startActivity(app.launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "launch failed: ${app.packageName}", e)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            MuseRuntime.wake() // reconnect now rather than after the backoff
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networkIcon.setImageResource(
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) R.drawable.ic_ethernet
                else R.drawable.ic_wifi,
            )
            networkIcon.visibility = View.VISIBLE
        }

        override fun onLost(network: Network) {
            networkIcon.visibility = View.INVISIBLE
        }
    }

    private fun wallpaperView() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_XY // bitmaps are pre-cropped to screen size
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun scrim(top: Boolean) = View(this).apply {
        background = GradientDrawable(
            if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(if (top) 0x59000000 else 0x80000000.toInt(), 0x00000000),
        )
    }

    private fun backgroundThread(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "Launcher"
        private const val FIRST_FRAME_WAIT_MS = 150L
        private const val REQUEST_TV_LISTINGS = 2
        private const val FOLD_MS = 220L
        private const val CARD_W = 200 // Continue watching card size, dp
        private const val CARD_H = 112
        private const val TAKE_BACK_FALLBACK_MS = 700L
    }
}
