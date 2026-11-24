package com.nova.browser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class NovaAiProvider(val label: String, val defaultModel: String) {
    CHATGPT("ChatGPT", "gpt-4o-mini"), CLAUDE("Claude", "claude-3-5-haiku-latest"),
    GROK("Grok", "grok-3-mini"), GEMINI("Gemini", "gemini-2.0-flash"),
    KIMI("Kimi", "kimi-k2.5"), DEEPSEEK("DeepSeek", "deepseek-chat")
}
internal data class NovaAiMessage(val role:String,val text:String,val time:Long=System.currentTimeMillis())

/** Curated premium-tier model IDs are shown even when a provider omits them from
 * the account's model list. API model-list visibility is used as an availability
 * signal; Nova does not claim to verify a consumer-app subscription. */
private object NovaAiModelCatalog {
    fun premium(provider: NovaAiProvider): List<String> = when (provider) {
        NovaAiProvider.CHATGPT -> listOf("gpt-5-pro", "o3-pro")
        NovaAiProvider.CLAUDE -> listOf("claude-opus-4-1", "claude-opus-4-5")
        NovaAiProvider.GROK -> listOf("grok-4", "grok-4-heavy")
        NovaAiProvider.GEMINI -> listOf("gemini-2.5-pro", "gemini-3-pro-preview")
        NovaAiProvider.KIMI -> listOf("kimi-k2-thinking")
        NovaAiProvider.DEEPSEEK -> listOf("deepseek-reasoner")
    }
}


/** API credentials and chat history are encrypted at rest using an Android Keystore AES key. */
internal class NovaAiSecureStore(context:Context) {
    private val p=context.getSharedPreferences("nova_ai_secure",Context.MODE_PRIVATE)
    private val alias="com.nova.browser.ai.v1"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(s:String):String { val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return android.util.Base64.encodeToString(c.iv+c.doFinal(s.toByteArray()),android.util.Base64.NO_WRAP) }
    private fun decrypt(s:String):String { val b=android.util.Base64.decode(s,android.util.Base64.NO_WRAP);require(b.size>28);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));return String(c.doFinal(b.copyOfRange(12,b.size))) }
    fun setKey(provider:NovaAiProvider,value:String){
        val normalized = value.trim()
        require(normalized.length <= 4096) { "API key is too long" }
        if(normalized.isBlank()) check(p.edit().remove("key_${provider.name}").commit())
        else check(p.edit().putString("key_${provider.name}",encrypt(normalized)).commit())
    }
    fun getKey(provider:NovaAiProvider)=p.getString("key_${provider.name}",null)?.let(::decrypt)
    fun hasKey(provider:NovaAiProvider)=runCatching{!getKey(provider).isNullOrBlank()}.getOrDefault(false)
    fun model(provider:NovaAiProvider)=p.getString("model_${provider.name}",provider.defaultModel)?:provider.defaultModel
    fun setModel(provider:NovaAiProvider,value:String){
        require(NovaAiInputPolicy.isSafeModelIdentifier(value)) { "Invalid model identifier" }
        check(p.edit().putString("model_${provider.name}",value).commit())
    }
    private companion object {
        const val MAX_HISTORY_BYTES = 512 * 1024
        const val MAX_HISTORY_MESSAGE_CHARS = 32 * 1024
        const val MAX_LEGACY_HISTORY_BYTES = 4 * 1024 * 1024
        const val MAX_ENCRYPTED_HISTORY_CHARS = 768 * 1024
        const val MAX_LEGACY_ENCRYPTED_HISTORY_CHARS = 4 * 1024 * 1024
    }

    private fun readMessageArray(a:JSONArray):MutableList<NovaAiMessage> =
        (0 until minOf(a.length(), 100)).mapNotNull { i ->
            runCatching {
                val o=a.getJSONObject(i)
                val role=o.getString("role")
                if (role != "user" && role != "assistant") return@runCatching null
                val text=o.getString("text")
                if (text.length > MAX_HISTORY_MESSAGE_CHARS) return@runCatching null
                NovaAiMessage(role,text,o.optLong("time"))
            }.getOrNull()
        }.toMutableList()
    private fun serializeSessions(all:List<List<NovaAiMessage>>):String {
        val root=JSONObject(); val array=JSONArray()
        all.forEach { session ->
            val entries=JSONArray()
            session.forEach { m -> entries.put(JSONObject().put("role",m.role).put("text",m.text).put("time",m.time)) }
            array.put(entries)
        }
        root.put("sessions",array)
        return root.toString()
    }
    private fun trimSessionsForStorage(all:List<List<NovaAiMessage>>):MutableList<MutableList<NovaAiMessage>> {
        val bounded=all.takeLast(20).map { session ->
            session.takeLast(100).map { it.copy(text=it.text.take(MAX_HISTORY_MESSAGE_CHARS)) }.toMutableList()
        }.toMutableList()
        while(bounded.isNotEmpty() && serializeSessions(bounded).toByteArray(Charsets.UTF_8).size>MAX_HISTORY_BYTES) {
            if(bounded.size>1) bounded.removeAt(0)
            else {
                val current=bounded.last()
                if(current.size>1) {
                    val nextUser=(1 until current.size).firstOrNull { current[it].role=="user" }
                    val removeCount=nextUser ?: 1
                    repeat(removeCount.coerceAtMost(current.size)) { current.removeAt(0) }
                } else if(current.isNotEmpty()) current.removeAt(0) else bounded.removeAt(0)
            }
        }
        return bounded
    }
    private fun sessions():MutableList<MutableList<NovaAiMessage>> {
        val encrypted=p.getString("history","") ?: ""
        if(encrypted.isBlank() || encrypted.length>MAX_LEGACY_ENCRYPTED_HISTORY_CHARS) return mutableListOf()
        val raw=runCatching { decrypt(encrypted) }.getOrNull() ?: return mutableListOf()
        if(raw.toByteArray(Charsets.UTF_8).size>MAX_LEGACY_HISTORY_BYTES) return mutableListOf()
        val parsed=runCatching {
            if(raw.trimStart().startsWith("{")) {
                val all=JSONObject(raw).optJSONArray("sessions") ?: JSONArray()
                (0 until minOf(all.length(),20)).mapNotNull { all.optJSONArray(it)?.let(::readMessageArray) }.toMutableList()
            } else mutableListOf(readMessageArray(JSONArray(raw)))
        }.getOrDefault(mutableListOf())
        val bounded=trimSessionsForStorage(parsed)
        if(bounded != parsed) runCatching { saveSessions(bounded) }
        return bounded
    }
    private fun saveSessions(all:List<List<NovaAiMessage>>) {
        val bounded=trimSessionsForStorage(all)
        val plain=serializeSessions(bounded)
        require(plain.toByteArray(Charsets.UTF_8).size<=MAX_HISTORY_BYTES) { "AI history exceeds the storage byte limit" }
        val encrypted=encrypt(plain)
        require(encrypted.length<=MAX_ENCRYPTED_HISTORY_CHARS) { "Encrypted AI history exceeds the storage limit" }
        check(p.edit().putString("history",encrypted).commit())
    }
    fun history():List<NovaAiMessage> = sessions().flatten().takeLast(100)
    fun currentHistory():List<NovaAiMessage> = sessions().lastOrNull()?.takeLast(100) ?: emptyList()
    fun startNewConversation() {
        val all=sessions()
        if(all.isEmpty() || all.last().isNotEmpty()) { all.add(mutableListOf()); saveSessions(all) }
    }
    fun append(m:NovaAiMessage) {
        val all=sessions()
        if(all.isEmpty()) all.add(mutableListOf())
        all.last().add(m)
        saveSessions(all)
    }
    fun clear(){p.edit().remove("history").commit()}
}

internal class NovaAiUsageLimitException(detail: String) : Exception(detail)

internal object NovaAiApi {
    private val pool=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())
    private fun enc(s:String)=java.net.URLEncoder.encode(s,"UTF-8")
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_REQUEST_BYTES = 512 * 1024

    private fun readBounded(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_RESPONSE_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            require(input.read() == -1) { "Provider response exceeded the 1 MB safety limit" }
            String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun call(method:String,url:String,headers:Map<String,String>,body:JSONObject?=null):JSONObject {
        require(url.startsWith("https://", ignoreCase = true)) { "AI providers must use HTTPS" }
        val requestBytes = body?.toString()?.toByteArray(StandardCharsets.UTF_8)
        require(requestBytes == null || requestBytes.size <= MAX_REQUEST_BYTES) { "AI request is too large" }
        val c=URL(url).openConnection() as HttpsURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod=method
            c.connectTimeout=20000
            c.readTimeout=60000
            headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            c.setRequestProperty("Accept","application/json")
            if(requestBytes!=null){
                c.doOutput=true
                c.setRequestProperty("Content-Type","application/json")
                c.setFixedLengthStreamingMode(requestBytes.size)
                c.outputStream.use{it.write(requestBytes)}
            }
            val code=c.responseCode
            val raw=readBounded(if(code in 200..299)c.inputStream else c.errorStream)
            val j=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
            if(code !in 200..299) {
                val e=j.optJSONObject("error")
                val message=(e?.optString("message")?.takeIf{it.isNotBlank()} ?: j.optString("message").ifBlank{"Request failed"})
                    .replace(Regex("[\\r\\n\\t]+"), " ").take(240)
                val safeMessage = headers.values.filter { it.length >= 8 }.fold(message) { value, secret -> value.replace(secret, "[redacted]") }
                val errorCode=(e?.optString("code").orEmpty()+" "+e?.optString("type").orEmpty()+" "+e?.optString("status").orEmpty()+" "+j.optString("status")).lowercase()
                if(code==429 || errorCode.contains("rate_limit") || errorCode.contains("resource_exhausted") || errorCode.contains("insufficient_quota") || errorCode.contains("quota_exceeded")) throw NovaAiUsageLimitException(safeMessage)
                error("Provider API error ($code): $safeMessage")
            }
            return j
        } finally {c.disconnect()}
    }
    fun models(p:NovaAiProvider,key:String,done:(Result<List<String>>)->Unit){pool.execute{
        val r=runCatching {
            val (url,h)=when(p){
                NovaAiProvider.CHATGPT->"https://api.openai.com/v1/models" to mapOf("Authorization" to "Bearer $key")
                NovaAiProvider.GROK->"https://api.x.ai/v1/models" to mapOf("Authorization" to "Bearer $key")
                NovaAiProvider.CLAUDE->"https://api.anthropic.com/v1/models?limit=100" to mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01")
                NovaAiProvider.GEMINI->"https://generativelanguage.googleapis.com/v1beta/models" to mapOf("x-goog-api-key" to key)
                NovaAiProvider.KIMI->"https://api.moonshot.ai/v1/models" to mapOf("Authorization" to "Bearer $key")
                NovaAiProvider.DEEPSEEK->"https://api.deepseek.com/models" to mapOf("Authorization" to "Bearer $key")
            }
            val j=call("GET",url,h);val a=j.optJSONArray(if(p==NovaAiProvider.CLAUDE||p==NovaAiProvider.GEMINI)"models" else "data")
            (0 until (a?.length()?:0)).mapNotNull{i->val o=a!!.optJSONObject(i)?:return@mapNotNull null
                val id=if(p==NovaAiProvider.GEMINI)o.optString("name").removePrefix("models/") else o.optString("id")
                id.takeIf{it.isNotBlank() && (p!=NovaAiProvider.GEMINI||o.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent")==true)}
            }.distinct().sorted().ifEmpty{listOf(p.defaultModel)}
        };main.post{done(r)}
    }}
    fun send(p:NovaAiProvider,key:String,model:String,history:List<NovaAiMessage>,done:(Result<String>)->Unit){pool.execute{
        val r=runCatching {
            val recent=NovaAiInputPolicy.boundedHistory(history, MAX_REQUEST_BYTES)
            when(p){
                NovaAiProvider.CHATGPT,NovaAiProvider.GROK,NovaAiProvider.KIMI,NovaAiProvider.DEEPSEEK->{
                    val a=JSONArray();recent.forEach{a.put(JSONObject().put("role",it.role).put("content",it.text))}
                    val url=when(p){
                        NovaAiProvider.GROK->"https://api.x.ai/v1/chat/completions"
                        NovaAiProvider.KIMI->"https://api.moonshot.ai/v1/chat/completions"
                        NovaAiProvider.DEEPSEEK->"https://api.deepseek.com/chat/completions"
                        else->"https://api.openai.com/v1/chat/completions"
                    }
                    call("POST",url,mapOf("Authorization" to "Bearer $key"),JSONObject().put("model",model).put("messages",a))
                        .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().ifBlank{error("Empty AI response")}
                }
                NovaAiProvider.CLAUDE->{
                    val a=JSONArray();recent.forEach{a.put(JSONObject().put("role",it.role).put("content",it.text))}
                    call("POST","https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"),
                        JSONObject().put("model",model).put("max_tokens",2048).put("messages",a))
                        .optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty().ifBlank{error("Empty AI response")}
                }
                NovaAiProvider.GEMINI->{
                    val a=JSONArray();recent.forEach{a.put(JSONObject().put("role",if(it.role=="assistant")"model" else "user").put("parts",JSONArray().put(JSONObject().put("text",it.text))))}
                    call("POST","https://generativelanguage.googleapis.com/v1beta/models/${enc(model)}:generateContent",mapOf("x-goog-api-key" to key),JSONObject().put("contents",a))
                        .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().ifBlank{error("Empty AI response")}
                }
            }
        };main.post{done(r)}
    }}
}

internal class NovaAiAssistantUi(
    private val context: Context,
    private val store: NovaAiSecureStore,
    private val requestPageText: ((String?) -> Unit) -> Unit,
    private val persistHistory: Boolean,
    private val notify: (String) -> Unit
) {
    private var provider = NovaAiProvider.CHATGPT
    private var model = store.model(provider)
    private var availableModelIds = emptySet<String>()
    private var displayedModelIds = listOf(model)
    private var changingModelSelection = false

    private fun isPremiumModel(id: String) = id in NovaAiModelCatalog.premium(provider)
    private fun isModelLocked(id: String) = isPremiumModel(id) && id !in availableModelIds

    /** Monochrome lock: black body/white keyhole in light mode, inverted in dark mode. */
    private inner class ModelLockDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dark = NovaThemeManager.isDark(context)
        private val bodyColor = if (dark) Color.WHITE else Color.BLACK
        private val holeColor = if (dark) Color.BLACK else Color.WHITE

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val side = minOf(w, h)
            val left = b.left + (w - side) / 2f
            val top = b.top + (h - side) / 2f
            val scale = side / 24f

            paint.color = bodyColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * scale
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(RectF(left + 6f * scale, top + 2f * scale,
                left + 18f * scale, top + 17f * scale), 180f, 180f, false, paint)

            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(left + 3f * scale, top + 11f * scale,
                left + 21f * scale, top + 23f * scale), 2.5f * scale, 2.5f * scale, paint)

            paint.color = holeColor
            canvas.drawCircle(left + 12f * scale, top + 15.5f * scale, 2f * scale, paint)
            canvas.drawRoundRect(RectF(left + 11f * scale, top + 16f * scale,
                left + 13f * scale, top + 20f * scale), 1f * scale, 1f * scale, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun modelRow(id: String, dropdown: Boolean): TextView = TextView(context).apply {
        text = id
        textSize = 13f
        setTextColor(primary)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (dropdown) 14 else 8), dp(8), dp(12), dp(8))
        if (isModelLocked(id)) {
            val lock = ModelLockDrawable()
            lock.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawables(lock, null, null, null)
            compoundDrawablePadding = dp(9)
        } else {
            setCompoundDrawables(null, null, null, null)
        }
    }

    private fun createModelAdapter(): ArrayAdapter<String> =
        object : ArrayAdapter<String>(context, 0, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                modelRow(getItem(position).orEmpty(), false)
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                modelRow(getItem(position).orEmpty(), true)
        }
    private fun updateModelChoices(preferred: String = model) {
        val choices = (listOf(preferred) + availableModelIds.sorted() + NovaAiModelCatalog.premium(provider))
            .filter { it.isNotBlank() }.distinct()
        displayedModelIds = choices
        modelAdapter.clear()
        modelAdapter.addAll(choices)
        modelAdapter.notifyDataSetChanged()
        val index = choices.indexOf(preferred).coerceAtLeast(0)
        changingModelSelection = true
        models.setSelection(index)
        changingModelSelection = false
    }
    private val messages = mutableListOf<NovaAiMessage>()
    private lateinit var feed: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var models: Spinner
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var contextChip: TextView
    private lateinit var usageWarning: TextView
    private var attachedPageText: String? = null
    private var busy = false
    private var conversationGeneration = 0L
    private var dialog: AlertDialog? = null

    private val palette = NovaThemeManager.palette(context)
    private val bg = palette.background
    private val panel = palette.surface
    private val raised = palette.raised
    private val line = palette.stroke
    private val accent = palette.accent
    private val primary = palette.text
    private val muted = palette.muted

    private fun rounded(color: Int, radius: Int = 16, stroke: Int = 0): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            if (stroke != 0) setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 13f, color: Int = primary, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun action(value: String, onClick: () -> Unit): TextView =
        label(value, 13f, primary, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(raised, 12, line)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(bg)
        }

        // Cursor-inspired workspace header.
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(10))
        }
        val menu = action("☰") { showWorkspaceMenu() }
        header.addView(menu, LinearLayout.LayoutParams(dp(44), dp(42)))
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        heading.addView(label("Nova AI", 17f, primary, true))
        heading.addView(label("Assistant workspace", 11f, muted))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("＋ New") { newChat() })
        root.addView(header)

        val providerRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 14, line)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val providers = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                NovaAiProvider.values().map { it.label })
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        providerRow.addView(label("PROVIDER", 9f, muted, true).apply {
            letterSpacing = .08f
            setPadding(dp(5), 0, dp(5), 0)
        })
        providerRow.addView(providers, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(providerRow)

        models = Spinner(context)
        modelAdapter = createModelAdapter()
        models.adapter = modelAdapter
        updateModelChoices(model)

        status = label("", 11f, muted).apply {
            setPadding(dp(5), 0, dp(5), dp(7))
        }
        root.addView(status)

        scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        feed = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }
        scroll.addView(feed, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Composer, visually separated from the conversation.
        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 18, line)
            setPadding(dp(10), dp(9), dp(10), dp(8))
        }
        contextChip = label("", 11f, accent).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(3), dp(8), dp(7))
        }
        composer.addView(contextChip)
        usageWarning = label("", 11f, palette.warning).apply {
            visibility = View.GONE
            setPadding(dp(7), dp(5), dp(7), dp(7))
        }
        composer.addView(usageWarning)
        input = EditText(context).apply {
            hint = "Ask anything… use current page as context"
            textSize = 14f
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(primary)
            setHintTextColor(muted)
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        val composerActions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        composerActions.addView(action("＋ Context") { attachPage() })
        composerActions.addView(action("Summarise") { summarise() }, LinearLayout.LayoutParams(-2, dp(38)).apply {
            leftMargin = dp(6)
        })
        composerActions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        composer.addView(composerActions)

        val modelActions = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        modelActions.addView(label("MODEL", 9f, muted, true).apply { setPadding(dp(4), 0, dp(6), 0) })
        models.background = rounded(raised, 10, line)
        models.setPadding(dp(5), 0, dp(5), 0)
        modelActions.addView(models, LinearLayout.LayoutParams(0, dp(40), 1f))
        modelActions.addView(action("↻") { refreshModels() }, LinearLayout.LayoutParams(dp(42), dp(38)).apply { leftMargin = dp(5) })
        val sendButton = action("↑ Send") { send() }.apply {
            setTextColor(bg)
            background = rounded(accent, 12)
        }
        modelActions.addView(sendButton, LinearLayout.LayoutParams(dp(76), dp(40)).apply { leftMargin = dp(6) })
        composer.addView(modelActions)
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })

        providers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                provider = NovaAiProvider.values()[position]
                usageWarning.visibility = View.GONE
                model = store.model(provider)
                availableModelIds = emptySet()
                updateModelChoices(model)
                status.text = if (store.hasKey(provider)) "Checking provider model access…" else "Add ${provider.label} API key to connect"
                if (store.hasKey(provider)) refreshModels()
            }
        }
        models.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (changingModelSelection) return
                val selectedIndex = models.selectedItemPosition
                val selectedId = displayedModelIds.getOrNull(selectedIndex) ?: store.model(provider)
                usageWarning.visibility = View.GONE
                if (isModelLocked(selectedId)) {
                    notify("🔒 $selectedId requires provider-side access. Enable API access or billing with ${provider.label}, then refresh models.")
                    val currentIndex = displayedModelIds.indexOf(model).coerceAtLeast(0)
                    changingModelSelection = true
                    models.setSelection(currentIndex)
                    changingModelSelection = false
                    return
                }
                model = selectedId
                store.setModel(provider, model)
            }
        }

        if (persistHistory) {
            messages += store.currentHistory()
            if (messages.isNotEmpty()) messages.forEach(::render)
        }
        if (messages.isEmpty()) showWelcome()
        status.text = if (store.hasKey(provider)) "Checking provider model access…" else "Add a provider API key to start"
        dialog = AlertDialog.Builder(context).setView(root).setNegativeButton("Close", null).create()
        dialog?.show()
        if (store.hasKey(provider)) refreshModels()
        dialog?.window?.setLayout(-1, -1)
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showWelcome() {
        val welcome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(30), dp(12), dp(20))
        }
        welcome.addView(label("Ask Nova anything", 21f, primary, true))
        welcome.addView(label("Explore a webpage, research a topic, or get help writing.", 13f, muted).apply {
            setPadding(0, dp(7), 0, dp(18))
        })
        listOf("Explain this webpage", "Help me research a topic", "Write or improve text").forEach { prompt ->
            val chip = action(prompt) {
                input.setText(when (prompt) {
                    "Explain this webpage" -> "Explain the current webpage in simple terms."
                    "Help me research a topic" -> "Help me research: "
                    else -> "Help me write or improve: "
                })
                input.setSelection(input.text.length)
            }
            welcome.addView(chip, LinearLayout.LayoutParams(-1, dp(44)).apply {
                bottomMargin = dp(8)
            })
        }
        feed.addView(welcome)
    }

    private fun showWorkspaceMenu() {
        val items = arrayOf("AI history", "Current conversation", "New chat", "API key", "Refresh model list")
        AlertDialog.Builder(context).setTitle("Nova AI workspace").setItems(items) { _, which ->
            when (which) {
                0 -> historyDialog()
                1 -> showCurrentConversation()
                2 -> newChat()
                3 -> editKey()
                4 -> refreshModels()
            }
        }.show()
    }

    private fun showCurrentConversation() {
        feed.removeAllViews()
        if (messages.isEmpty()) {
            showWelcome()
            status.text = "No messages in this conversation yet"
        } else {
            messages.forEach(::render)
            status.text = "Current conversation"
        }
    }

    private fun newChat() {
        conversationGeneration++
        busy = false
        messages.clear()
        if (persistHistory) runCatching { store.startNewConversation() }
        attachedPageText = null
        contextChip.visibility = View.GONE
        feed.removeAllViews()
        showWelcome()
        input.setText("")
        status.text = "New conversation"
        // The encrypted store keeps the existing transcript until the user clears history.
        // This screen starts a fresh in-memory conversation.
    }

    private fun attachPage() {
        status.text = "Reading current page…"
        requestPageText { page ->
            if (dialog?.isShowing != true) return@requestPageText
            if (page.isNullOrBlank()) {
                status.text = "No readable page found"
                notify("Could not read the current webpage")
            } else {
                attachedPageText = page.take(24000)
                contextChip.text = "▣ Current page attached · ${attachedPageText!!.length} characters   ×"
                contextChip.visibility = View.VISIBLE
                contextChip.setOnClickListener {
                    attachedPageText = null
                    contextChip.visibility = View.GONE
                }
                status.text = "Current page attached"
            }
        }
    }

    private fun summarise() {
        status.text = "Preparing page summary…"
        requestPageText { page ->
            if (dialog?.isShowing != true) return@requestPageText
            if (page.isNullOrBlank()) {
                status.text = "No readable page found"
                notify("Could not read the current webpage")
                return@requestPageText
            }
            attachedPageText = page.take(24000)
            contextChip.text = "▣ Current page attached · tap × to remove"
            contextChip.visibility = View.VISIBLE
            input.setText("Summarise this webpage. Include an overview, key points, important facts and conclusions. Do not invent details.")
            send()
        }
    }

    private fun editKey() {
        val field = EditText(context).apply {
            hint = "Paste API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(context).setTitle("${provider.label} API key")
            .setMessage("Stored encrypted with Android Keystore. Nova sends the key directly to ${provider.label}'s official API, not through a Nova server. Provider API usage and billing are controlled by ${provider.label}; a consumer-app subscription does not necessarily include API credits.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                runCatching { store.setKey(provider, field.text.toString()) }
                    .onSuccess {
                        status.text = if (store.hasKey(provider)) "API key saved · ${provider.label}" else "API key removed"
                        if (store.hasKey(provider)) refreshModels()
                    }
                    .onFailure { notify("Could not save key: ${it.message}") }
            }
            .setNeutralButton("Remove") { _, _ ->
                store.setKey(provider, "")
                status.text = "API key removed"
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshModels() {
        val key = store.getKey(provider)
        if (key.isNullOrBlank()) {
            notify("Add the ${provider.label} API key first")
            editKey()
            return
        }
        status.text = "Checking models available to this API account…"
        val requestedProvider = provider
        NovaAiApi.models(requestedProvider, key) { result ->
            if (dialog?.isShowing != true || provider != requestedProvider) return@models
            result.onSuccess { list ->
                availableModelIds = list.toSet()
                val preferred = if (model in availableModelIds || !isPremiumModel(model)) model else store.model(provider)
                updateModelChoices(preferred)
                model = preferred
                status.text = "${availableModelIds.size} models available · premium models locked unless provider API access is confirmed"
            }.onFailure {
                availableModelIds = emptySet()
                updateModelChoices(model)
                status.text = "Could not verify model access · premium models remain locked"
                notify(it.message ?: "Model access could not be checked")
            }
        }
    }

    private fun send() {
        if (busy) return
        val prompt = input.text.toString().trim()
        if (prompt.isBlank()) return
        if (prompt.length > 8000) { notify("Keep each prompt under 8,000 characters"); return }
        if (isModelLocked(model)) {
            notify("🔒 $model is locked. Enable access to this model through ${provider.label}'s API account, then refresh the model list.")
            return
        }
        val key = runCatching { store.getKey(provider) }.getOrNull()
        if (key.isNullOrBlank()) {
            editKey()
            return
        }
        val fullPrompt = attachedPageText?.let {
            "$prompt\n\n--- Current webpage context (user attached) ---\n${NovaAiInputPolicy.limitPageContext(it)}\n--- End webpage context ---"
        } ?: prompt
        input.setText("")
        val user = NovaAiMessage("user", fullPrompt)
        messages += user
        render(user)
        if (persistHistory) runCatching { store.append(user) }
        busy = true
        status.text = "Thinking · ${provider.label} · ${model}"
        val requestGeneration = conversationGeneration
        NovaAiApi.send(provider, key, model, messages.toList()) { result ->
            if (requestGeneration != conversationGeneration) return@send
            if (dialog?.isShowing != true) return@send
            busy = false
            result.onSuccess { answer ->
                usageWarning.visibility = View.GONE
                val reply = NovaAiMessage("assistant", answer)
                messages += reply
                render(reply)
                if (persistHistory) runCatching { store.append(reply) }
                status.text = "${provider.label} · ${model}"
            }.onFailure { failure ->
                if (failure is NovaAiUsageLimitException) {
                    status.text = "Usage limit reached · ${provider.label}"
                    usageWarning.text = "⚠ $model is temporarily unavailable — usage limit reached. Try another model or wait for the limit to reset."
                    usageWarning.visibility = View.VISIBLE
                } else {
                    status.text = "Request failed"
                    notify(failure.message ?: "AI request failed")
                }
            }
        }
    }

    private fun render(message: NovaAiMessage) {
        val isAssistant = message.role == "assistant"
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(if (isAssistant) panel else palette.userBubble, 15)
        }
        block.addView(label(if (isAssistant) "NOVA" else "YOU", 10f,
            if (isAssistant) accent else muted, true))
        block.addView(TextView(context).apply {
            text = message.text
            textSize = 14f
            setTextColor(primary)
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, 0)
            linksClickable = true
        })
        feed.addView(block, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun historyDialog() {
        val all = if (persistHistory) store.history() else messages.toList()
        if (all.isEmpty()) {
            notify("No saved AI conversation yet")
            return
        }
        val recent = all.takeLast(60)
        val labels = recent.map {
            (if (it.role == "user") "You: " else "Nova: ") +
                it.text.replace('\n', ' ').take(90)
        }
        AlertDialog.Builder(context).setTitle("Conversation history")
            .setItems(labels.toTypedArray()) { _, index ->
                AlertDialog.Builder(context).setTitle(if (recent[index].role == "user") "Your message" else "Nova AI")
                    .setMessage(recent[index].text).setPositiveButton("Close", null).show()
            }
            .setNeutralButton("Clear history") { _, _ ->
                conversationGeneration++
                busy = false
                if (persistHistory) store.clear()
                messages.clear()
                feed.removeAllViews()
                showWelcome()
                notify("Conversation history cleared")
            }
            .setNegativeButton("Close", null).show()
    }
}
