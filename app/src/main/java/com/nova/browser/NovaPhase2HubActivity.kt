package com.nova.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** A lightweight, account-free Phase 2 hub. Network content is fetched only when Discover is opened. */
class NovaPhase2HubActivity : Activity() {
    companion object {
        const val EXTRA_URL = "nova.phase2.current_url"
        const val EXTRA_TITLE = "nova.phase2.current_title"
        private const val READING_LIST_KEY = "nova_phase2_reading_list"
    }

    private val prefs by lazy { getSharedPreferences("nova_local", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private var discoverRequestGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val currentUrl by lazy { NovaPhase2Policy.safeHttpUrl(intent.getStringExtra(EXTRA_URL).orEmpty()) }
    private val currentTitle by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().take(200).ifBlank { "Saved page" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyBeforeCreate(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 12, 28)); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val header = TextView(this).apply { text = "NOVA HUB"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        root.addView(header)
        root.addView(TextView(this).apply { text = "Discover · Flow · Offline reading · Data Saver"; textSize = 12f; setTextColor(0xFFBDAAD2.toInt()); setPadding(0, dp(4), 0, dp(12)) })
        status = TextView(this).apply { textSize = 12f; setTextColor(0xFFD5C8E2.toInt()); setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP }
        scroll.addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("Discover news & sports") { showDiscoverTopics() })
        root.addView(button("Flow · Share current page") { shareCurrentPage() })
        root.addView(button("Offline · Reading list") { showReadingList() })
        val dataSaver = Switch(this).apply {
            text = "Data Saver · block page images in GeckoView"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("nova_data_saver_enabled", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("nova_data_saver_enabled", enabled).apply()
                status.text = if (enabled) "Data Saver enabled for GeckoView tabs. Cefrium image control is not exposed by the current adapter." else "Data Saver disabled. Existing image preferences are restored."
            }
        }
        root.addView(dataSaver)
        root.addView(TextView(this).apply {
            text = "Flow uses Android's secure share sheet (including nearby sharing when supported). QR-paired encrypted Nova-to-Nova transfer is not yet available. Offline reading stores links; full page snapshots are not supported by both current browser engines."
            textSize = 11f; setTextColor(0xFFBDAAD2.toInt()); setPadding(0, dp(10), 0, dp(10))
        })
        root.addView(button("Close") { finish() })
        setContentView(root)
        status.text = "Ready · no account required"
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showDiscoverTopics() {
        AlertDialog.Builder(this).setTitle("Discover")
            .setItems(NovaDiscoverTopic.entries.map { it.label }.toTypedArray()) { _, which -> loadDiscover(NovaDiscoverTopic.entries[which]) }
            .setNegativeButton("Cancel", null).show()
    }

    private data class FeedItem(val title: String, val url: String, val source: String)

    private fun loadDiscover(topic: NovaDiscoverTopic) {
        val requestGeneration = ++discoverRequestGeneration
        content.removeAllViews(); status.text = "Loading ${topic.label}…"
        worker.execute {
            val result = runCatching { fetchFeed(topic) }
            mainHandler.post {
                if (isFinishing || isDestroyed || requestGeneration != discoverRequestGeneration) return@post
                content.removeAllViews()
                val items = result.getOrDefault(emptyList())
                status.text = if (items.isEmpty()) "No stories loaded. Check your connection and try again." else "${items.size} stories · ${topic.label}"
                items.forEach { item ->
                    val row = TextView(this).apply {
                        text = item.title + if (item.source.isNotBlank()) "\n${item.source}" else ""
                        textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12))
                        setBackgroundColor(0xFF2A2037.toInt()); isClickable = true; setOnClickListener { openUrl(item.url) }
                    }
                    content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        }
    }

    private fun fetchFeed(topic: NovaDiscoverTopic): List<FeedItem> {
        val connection = (URL(NovaPhase2Policy.discoverFeedUrl(topic)).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 7000; readTimeout = 7000; instanceFollowRedirects = true
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            setRequestProperty("User-Agent", "NovaBrowser/Phase2")
        }
        try {
            check(connection.responseCode == HttpsURLConnection.HTTP_OK) { "Discover feed returned HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(4096); var total = 0
                while (true) { val count = stream.read(buffer); if (count < 0) break; total += count; require(total <= 512 * 1024) { "Feed too large" }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); setInput(bytes.inputStream(), null) }
            val items = mutableListOf<FeedItem>(); var inItem = false; var title = ""; var link = ""; var source = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < 30) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                        "item" -> { inItem = true; title = ""; link = ""; source = "" }
                        "title" -> if (inItem) title = parser.nextText().take(300)
                        "link" -> if (inItem) link = parser.nextText().take(4096)
                        "source" -> if (inItem) source = parser.nextText().take(100)
                    }
                    XmlPullParser.END_TAG -> if (parser.name.equals("item", true)) {
                        val safe = NovaPhase2Policy.safeHttpUrl(link)
                        if (inItem && title.isNotBlank() && safe != null) items += FeedItem(title, safe, source)
                        inItem = false
                    }
                }
                event = parser.next()
            }
            return items
        } finally { connection.disconnect() }
    }

    private fun shareCurrentPage() {
        val url = currentUrl
        if (url == null) { status.text = "Open a normal web page in Nova before sharing."; return }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, currentTitle); putExtra(Intent.EXTRA_TEXT, "$currentTitle\n$url") }
        startActivity(Intent.createChooser(send, "Flow · Share with"))
    }

    private data class SavedLink(val title: String, val url: String, val savedAt: Long)

    private fun readLinks(): List<SavedLink> = runCatching {
        val array = JSONArray(prefs.getString(READING_LIST_KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val url = NovaPhase2Policy.safeHttpUrl(row.optString("url")) ?: return@mapNotNull null
            SavedLink(row.optString("title", "Saved page").take(200), url, row.optLong("savedAt"))
        }.take(200)
    }.getOrDefault(emptyList())

    private fun writeLinks(links: List<SavedLink>) {
        val array = JSONArray(); links.take(200).forEach { array.put(JSONObject().put("title", it.title).put("url", it.url).put("savedAt", it.savedAt)) }
        check(prefs.edit().putString(READING_LIST_KEY, array.toString()).commit()) { "Could not save reading list" }
    }

    private fun showReadingList() {
        val url = currentUrl
        val links = readLinks().toMutableList()
        if (url != null) {
            val add = AlertDialog.Builder(this).setTitle("Offline reading")
                .setMessage("Save this link to your reading list? Full offline page snapshots are not available for the current engine APIs.")
                .setPositiveButton("Save link") { _, _ ->
                    links.removeAll { it.url == url }; links.add(0, SavedLink(currentTitle, url, System.currentTimeMillis()))
                    runCatching { writeLinks(links) }.onSuccess { status.text = "Link saved to reading list" }.onFailure { status.text = it.message ?: "Save failed" }
                    showReadingListItems()
                }.setNegativeButton("View list") { _, _ -> showReadingListItems() }.create()
            add.show()
        } else showReadingListItems()
    }

    private fun showReadingListItems() {
        val links = readLinks()
        if (links.isEmpty()) { status.text = "Your reading list is empty."; return }
        AlertDialog.Builder(this).setTitle("Reading list (${links.size})")
            .setItems(links.map { it.title + "\n" + it.url }.toTypedArray()) { _, which -> openUrl(links[which].url) }
            .setNeutralButton("Clear list") { _, _ ->
                runCatching { writeLinks(emptyList()) }
                    .onSuccess { status.text = "Reading list cleared" }
                    .onFailure { status.text = it.message ?: "Could not clear reading list" }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun openUrl(url: String) {
        val safe = NovaPhase2Policy.safeHttpUrl(url) ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(safe))) }
            .onFailure { status.text = "No app can open this link." }
    }

    override fun onDestroy() { discoverRequestGeneration++; worker.shutdownNow(); super.onDestroy() }
}
