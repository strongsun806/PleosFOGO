package com.ssafy.fogo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

enum class Page(val title: String) {
    INTRO("FOGO"), CONSENT("위치 기록 동의"), MASCOT("같이 달릴 친구"), ATLAS("아틀라스"),
    JOURNEY("목적지 미리보기"), ARRIVAL("도착 탑승권"), BOARD("내 지도판"), PLACE("스티커·자석 붙이기"),
    REGION("시·도 상세"), GARAGE("차고"), WORKSHOP("공방"), DRESS("입히기"),
    RECAP("리캡"), BOOK("월간 티켓북"), TRANSFER("새 차로 이어가기"), SETTINGS("시연 설정"),
}

class MainActivity : Activity() {
    val fogo get() = application as FogoApplication
    val profile get() = fogo.profile
    val catalog get() = fogo.catalog
    val art get() = fogo.art
    lateinit var ui: AtlasUi
        private set
    val colors get() = ui.colors
    private lateinit var journey: JourneyScreens
    private lateinit var collection: CollectionScreens
    var page = Page.ATLAS
        private set
    private var arrivalSeen: String? = null
    private var permissionDenied = false
    private var permissionFromOnboarding = false
    private var dialog: AlertDialog? = null
    private var scroll: ScrollView? = null
    private var lastPage: Page? = null
    /** True while the current render is the first one for [page]; screens use it for entrance motion. */
    var entering = false
        private set
    private val themePreferences by lazy { getSharedPreferences("fogo_appearance", MODE_PRIVATE) }
    private var themeOverride: Boolean? = null
    private val mainTabs = listOf(Page.ATLAS, Page.GARAGE, Page.RECAP, Page.SETTINGS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page")?.let(Page::valueOf)
            ?: if (profile.onboarded) Page.ATLAS else Page.INTRO
        arrivalSeen = savedInstanceState?.getString("arrivalSeen")
        permissionDenied = savedInstanceState?.getBoolean("permissionDenied") == true
        permissionFromOnboarding = savedInstanceState?.getBoolean("permissionFromOnboarding") == true
        themeOverride = themePreferences.getString("demoTheme", null)?.toBooleanStrictOrNull()
        journey = JourneyScreens(this, savedInstanceState)
        collection = CollectionScreens(this, savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        fogo.redraw = ::render
        fogo.applyLiveTags()
        render()
        fogo.refreshDestination()
    }

    override fun onStop() {
        fogo.redraw = null
        dialog?.dismiss()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page.name)
        outState.putString("arrivalSeen", arrivalSeen)
        outState.putBoolean("permissionDenied", permissionDenied)
        outState.putBoolean("permissionFromOnboarding", permissionFromOnboarding)
        journey.save(outState)
        collection.save(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (!fogo.parked || page in mainTabs || page == Page.INTRO) super.onBackPressed()
        else go(parentPage())
    }

    fun go(next: Page) {
        if (!fogo.parked) return
        page = next
        render()
    }

    private fun parentPage() = when (page) {
        Page.CONSENT -> Page.INTRO
        Page.MASCOT -> if (profile.onboarded) Page.GARAGE else Page.CONSENT
        Page.PLACE -> Page.BOARD
        Page.WORKSHOP, Page.DRESS, Page.TRANSFER -> Page.GARAGE
        Page.BOOK -> Page.RECAP
        else -> Page.ATLAS
    }

    fun render() {
        val now = fogo.trip.arrival?.city
        if (now == null) arrivalSeen = null
        else if (fogo.parked && profile.onboarded && arrivalSeen != now.spotId) {
            page = Page.ARRIVAL
            journey.focus(now)
            arrivalSeen = now.spotId
        }

        val config = resources.configuration
        val wide = config.screenWidthDp >= 760 && config.screenWidthDp > config.screenHeightDp
        // Figma frames are 1700 (wide) / 840 (portrait) units across; grow toward them on large Pleos displays.
        // At 1,700 dp wide (Pleos Connect) this makes 1 Figma px ≈ 1 dp: 40 px titles, 100 px buttons.
        val scale = if (wide) (config.screenWidthDp / 1000f).coerceIn(1f, 1.7f)
            else (config.screenWidthDp / 500f).coerceIn(1f, 1.6f)
        ui = AtlasUi(this, AtlasColors(themeOverride ?: fogo.trip.scene.night), wide, scale)
        window.statusBarColor = colors.navy
        window.navigationBarColor = colors.navy
        if (!fogo.parked) {
            dialog?.dismiss()
            lastPage = null
            scroll = null
            driving()
            return
        }

        entering = page != lastPage
        val oldScroll = if (page == lastPage) scroll?.scrollY ?: 0 else 0
        val root = ui.column().apply { setBackgroundColor(colors.background) }
        val usesRail = page in mainTabs || page in setOf(Page.JOURNEY, Page.BOARD, Page.REGION)
        if (wide && usesRail) {
            val row = ui.row().apply { gravity = Gravity.TOP }
            val content = ui.column()
            row.addView(rail(), LinearLayout.LayoutParams(ui.dp(110), -1))
            row.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(row, LinearLayout.LayoutParams(-1, -1))
            header(content)
            content(content, oldScroll)
        } else {
            // Figma T1: the arrival ticket sits on a full-screen dim, no header.
            if (page != Page.ARRIVAL) header(root)
            if (usesRail) {
                val tabs = ui.column().apply {
                    setBackgroundColor(colors.yellow)
                    setPadding(ui.dp(14), ui.dp(7), ui.dp(14), 0)
                }
                root.addView(tabs)
                ui.strip(tabs, mainTabs.map { it.title to { go(it) } }, tabForPage().title)
            }
            content(root, oldScroll)
        }
        lastPage = page
        setContentView(root)
    }

    private fun tabForPage() = if (page in mainTabs) page else Page.ATLAS

    private fun rail(): View {
        val container = FrameLayout(this)
        val rail = ui.column().apply {
            setBackgroundColor(colors.yellow)
            setPadding(ui.dp(12), ui.dp(24), ui.dp(15), ui.dp(18))
        }
        container.addView(rail, FrameLayout.LayoutParams(-1, -1))
        ui.text(rail, "FOGO", 19f, colors.dark, pixel = true).gravity = Gravity.CENTER
        val icons = listOf(PixelIconView.ATLAS, PixelIconView.GARAGE, PixelIconView.RECAP, PixelIconView.SETTINGS)
        mainTabs.forEachIndexed { index, item ->
            val selected = tabForPage() == item
            val tab = ui.column().apply {
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = item.title
                background = if (selected) ui.box(colors.paper, shadow = true) else null
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                setOnClickListener { go(item) }
            }
            tab.addView(PixelIconView(this, icons[index], if (selected) colors.ink else colors.dark),
                LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { bottomMargin = ui.dp(6) })
            tab.addView(ui.label(item.title, 15f, if (selected) colors.ink else colors.dark, bold = true).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            rail.addView(tab, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(PixelIconView(this, BARCODE, colors.dark), LinearLayout.LayoutParams(-1, ui.dp(34)))
        // Perforated seam between the stub (rail) and the ticket body.
        val seam = DashedLine(this, colors.dark, ui.dp(10).toFloat(), ui.dp(7).toFloat())
        container.addView(seam, FrameLayout.LayoutParams(ui.dp(3), -1, Gravity.END))
        listOf(Gravity.TOP, Gravity.BOTTOM).forEach { edge ->
            val notch = ImageView(this).apply {
                setImageResource(if (colors.night) R.drawable.atlas_rail_night else R.drawable.atlas_rail_day)
            }
            container.addView(notch, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), edge or Gravity.END).apply {
                topMargin = if (edge == Gravity.TOP) -ui.dp(13) else 0
                bottomMargin = if (edge == Gravity.BOTTOM) -ui.dp(13) else 0
                marginEnd = -ui.dp(13)
            })
        }
        return container
    }

    private fun header(parent: LinearLayout) {
        val bar = ui.row().apply {
            setBackgroundColor(colors.navy)
            minimumHeight = ui.dp(70)
            setPadding(ui.dp(22), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, -2))
        if (page !in mainTabs && page != Page.INTRO) {
            val back = ui.action("←") { go(parentPage()) }.apply { contentDescription = "뒤로" }
            bar.addView(back, LinearLayout.LayoutParams(ui.dp(55), -2).apply { marginEnd = ui.dp(10) })
        }
        val titles = if (ui.wide) ui.row() else ui.column()
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        titles.addView(ui.label("FOGO PASS", 13f, colors.lime, pixel = true),
            LinearLayout.LayoutParams(-2, -2).apply { if (ui.wide) marginEnd = ui.dp(14) })
        titles.addView(ui.label(page.title, 25f, colors.white, bold = true))
        if (fogo.replayCity != null) bar.addView(ui.label("시연 재생", 15f, colors.lime, bold = true))
    }

    private fun content(parent: LinearLayout, oldScroll: Int) {
        // Figma frames: content 40 px in from the rail, 8 px under the header.
        val body = ui.column().apply { setPadding(ui.fx(40), ui.fx(8), ui.fx(40), ui.fx(20)) }
        val window = ScrollView(this).apply { isFillViewport = true; addView(body) }
        parent.addView(window, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll = window
        if (fogo.trip.storageFailed) ui.text(body, "기기 저장에 실패했어요", color = colors.muted)
        when (page) {
            Page.INTRO, Page.CONSENT, Page.MASCOT, Page.ATLAS, Page.JOURNEY, Page.ARRIVAL,
            Page.BOARD, Page.PLACE, Page.REGION -> journey.show(page, body)
            Page.GARAGE, Page.WORKSHOP, Page.DRESS, Page.RECAP, Page.BOOK, Page.TRANSFER -> collection.show(page, body)
            Page.SETTINGS -> settings(body)
        }
        if (oldScroll > 0) window.post { window.scrollTo(0, oldScroll) }
    }

    private fun driving() {
        val root = FrameLayout(this).apply { setBackgroundColor(colors.navy) }
        // Pixel scene keeps rolling while driving: nothing to read or touch, just a sense of moving.
        root.addView(DriveSceneView(this, art[if (ui.wide) "drive_wide" else "drive_portrait"], colors.night,
            speed = if (fogo.trip.gear == Gear.DRIVE) 2f else 0f, rain = fogo.trip.scene.rain, headroom = 260f),
            FrameLayout.LayoutParams(-1, -1))
        val status = if (fogo.trip.gear == Gear.DRIVE && (fogo.recording || fogo.replayCity != null)) "기록 중"
            else if (fogo.locationIssue != null) "위치 연결 대기 중" else "정차 후 이용할 수 있어요"
        val badge = ui.label(status, 25f, colors.white, bold = true).apply {
            background = ui.box(colors.navy)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        }
        root.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = ui.dp(30) })
        if (fogo.canOpenReplay && fogo.trip.gear == Gear.UNKNOWN) {
            val button = ui.action("도시 예시 재생", primary = true, click = ::selectReplay)
            root.addView(button, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = ui.dp(22) })
        }
        setContentView(root)
    }

    fun selectReplay() {
        if (!fogo.canOpenReplay && !(fogo.parked && fogo.replayCity != null && !fogo.replayRunning)) return
        val sorted = fogo.cities
        showDialog(AlertDialog.Builder(this).setTitle("도시 예시 재생")
            .setItems(sorted.map { "${it.city} · ${it.spot}" }.toTypedArray()) { _, index ->
                journey.focus(sorted[index])
                page = Page.JOURNEY
                fogo.prepareReplay(sorted[index])
            }.setNegativeButton("닫기", null))
    }

    fun explainLocation() {
        if (!fogo.parked) return
        showDialog(AlertDialog.Builder(this).setTitle("위치 기록 시작")
            .setMessage("차량 위치를 1km 블록으로 바꿔 기기에 저장해요. Android 위치와 Pleos 위치 접근을 허용해 주세요.")
            .setNegativeButton("기록 없이 둘러보기", null)
            .setPositiveButton("계속") { _, _ -> requestRecording(false) })
    }

    fun requestRecording(onboarding: Boolean) {
        if (!fogo.parked) return
        permissionFromOnboarding = onboarding
        val missing = locationPermissions.filter { checkSelfPermission(it) != PERMISSION_GRANTED }
        if (missing.isEmpty()) finishPermission() else requestPermissions(missing.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 7) finishPermission()
    }

    private fun finishPermission() {
        permissionDenied = locationPermissions.any { checkSelfPermission(it) != PERMISSION_GRANTED }
        if (!permissionDenied && fogo.parked) {
            try {
                startForegroundService(Intent(this, RecordingService::class.java))
            } catch (error: Exception) {
                fogo.locationError(error)
            }
        }
        if (permissionFromOnboarding) page = Page.MASCOT
        permissionFromOnboarding = false
        render()
    }

    fun recordingNotice() = if (permissionDenied) "위치 권한 없이 지도판과 차고를 둘러볼 수 있어요." else fogo.locationIssue

    fun stopRecording() {
        if (fogo.parked) stopService(Intent(this, RecordingService::class.java))
    }

    private fun settings(body: LinearLayout) {
        val card = ui.card(body)
        ui.text(card, if (fogo.hybrid) "음성 합성 · Pleos 하이브리드" else "음성 합성 · 차량 내", bold = true)
        ui.button(card, if (fogo.hybrid) "차량 내 음성 사용" else "Pleos 하이브리드 음성 사용") {
            if (fogo.hybrid) fogo.setHybrid() else configureHybrid()
        }
        if (fogo.replayAllowed) {
            ui.text(card, "화면 테마", bold = true)
            ui.strip(card, listOf("자동" to { chooseTheme(null) },
                "주간" to { chooseTheme(false) }, "야간" to { chooseTheme(true) }),
                when (themeOverride) { null -> "자동"; true -> "야간"; false -> "주간" })
            if (!fogo.replayRunning) ui.button(card, "도시 예시 선택", click = ::selectReplay)
            ui.button(card, "시연 기록 초기화") {
                showDialog(AlertDialog.Builder(this).setTitle("시연 기록 초기화")
                    .setMessage("이 기기에서 모은 방문 기록과 수집품, 차량 선택을 초기화합니다.")
                    .setNegativeButton("취소", null).setPositiveButton("초기화") { _, _ ->
                        if (fogo.parked) { page = Page.INTRO; fogo.resetDemo() }
                    })
            }
        }
    }

    private fun chooseTheme(value: Boolean?) {
        if (!fogo.parked || !fogo.replayAllowed) return
        themeOverride = value
        themePreferences.edit().putString("demoTheme", value?.toString()).apply()
        render()
    }

    private fun configureHybrid() {
        val fields = ui.column().apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0) }
        val id = EditText(this).apply {
            hint = "Client ID"
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val secret = EditText(this).apply {
            hint = "Client Secret"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        fields.addView(id)
        fields.addView(secret)
        showDialog(AlertDialog.Builder(this).setTitle("Pleos 하이브리드 음성")
            .setMessage("My Project 자격 증명은 실행 중에만 사용합니다.")
            .setView(fields).setNegativeButton("취소", null).setPositiveButton("사용") { _, _ ->
                if (id.text.isNotBlank() && secret.text.isNotBlank()) fogo.setHybrid(id.text.toString().trim(), secret.text.toString())
                else message("Client ID와 Client Secret을 입력하세요")
                secret.text.clear()
            })
        dialog?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.show()
    }

    fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    companion object {
        private val BARCODE = listOf("#.##.#.###.#.##.#.#", "#.##.#.###.#.##.#.#", "#.##.#.###.#.##.#.#")
        private val locationPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, "pleos.car.permission.FUSED_LOCATION")
    }
}
