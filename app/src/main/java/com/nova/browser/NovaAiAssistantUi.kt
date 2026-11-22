internal class NovaAiAssistantUi(
    private val context: Context,
    private val store: NovaAiSecureStore,
    private val requestPageText: ((String?) -> Unit) -> Unit,
    private val persistHistory: Boolean,
    private val notify: (String) -> Unit
) {
    private var provider = NovaAiProvider.CHATGPT
    private var model = store.model(provider)
    private val messages = mutableListOf<NovaAiMessage>()
    private lateinit var feed: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var models: Spinner
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var contextChip: TextView
    private var attachedPageText: String? = null
    private var busy = false
    private var dialog: AlertDialog? = null

    private val bg = 0xFF111016.toInt()
    private val panel = 0xFF1B1922.toInt()
    private val raised = 0xFF25222E.toInt()
    private val line = 0xFF393443.toInt()
    private val accent = 0xFFB99AFF.toInt()
    private val primary = 0xFFF4F0FA.toInt()
    private val muted = 0xFFAAA3B5.toInt()

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

        val modelRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(7))
        }
        models = Spinner(context)
        modelAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(model))
        models.adapter = modelAdapter
        modelRow.addView(label("MODEL", 9f, muted, true).apply {
            setPadding(dp(5), 0, dp(8), 0)
        })
        modelRow.addView(models, LinearLayout.LayoutParams(0, dp(42), 1f))
        modelRow.addView(action("↻") { refreshModels() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        modelRow.addView(action("Key") { editKey() }, LinearLayout.LayoutParams(dp(54), dp(40)).apply {
            leftMargin = dp(6)
        })
        root.addView(modelRow)

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
        scroll.addView(feed, ScrollView.LayoutParams(-1, -2))
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
        val sendButton = action("↑ Send") { send() }.apply {
            setTextColor(bg)
            background = rounded(accent, 12)
        }
        composerActions.addView(sendButton, LinearLayout.LayoutParams(dp(82), dp(40)))
        composer.addView(composerActions)
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })

        providers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                provider = NovaAiProvider.values()[position]
                model = store.model(provider)
                modelAdapter.clear()
                modelAdapter.add(model)
                modelAdapter.notifyDataSetChanged()
                status.text = if (store.hasKey(provider)) "Ready · ${provider.label}" else "Add ${provider.label} API key to connect"
            }
        }
        models.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model = models.selectedItem?.toString() ?: store.model(provider)
                store.setModel(provider, model)
            }
        }

        if (persistHistory) {
            messages += store.history()
            if (messages.isNotEmpty()) messages.forEach(::render)
        }
        if (messages.isEmpty()) showWelcome()
        status.text = if (store.hasKey(provider)) "Connected · ChatGPT" else "Add a provider API key to start"
        dialog = AlertDialog.Builder(context).setView(root).setNegativeButton("Close", null).create()
        dialog?.show()
        dialog?.window?.setLayout(-1, -1)
        dialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun showWelcome() {
        val welcome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(30), dp(12), dp(20))
        }
        welcome.addView(label("What are we working on?", 23f, primary, true))
        welcome.addView(label("Ask questions, explore a page, or work through an idea.", 13f, muted).apply {
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
        val items = arrayOf("New chat", "Conversation history", "Manage API key", "Refresh model list")
        AlertDialog.Builder(context).setTitle("Nova AI workspace").setItems(items) { _, which ->
            when (which) {
                0 -> newChat()
                1 -> historyDialog()
                2 -> editKey()
                3 -> refreshModels()
            }
        }.show()
    }

    private fun newChat() {
        messages.clear()
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
            .setMessage("Stored encrypted with Android Keystore. Nova sends the key directly to ${provider.label}'s official API.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                runCatching { store.setKey(provider, field.text.toString()) }
                    .onSuccess { status.text = if (store.hasKey(provider)) "API key saved · ${provider.label}" else "API key removed" }
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
        status.text = "Fetching available models…"
        NovaAiApi.models(provider, key) { result ->
            result.onSuccess { list ->
                modelAdapter.clear()
                modelAdapter.addAll(list)
                modelAdapter.notifyDataSetChanged()
                models.setSelection(list.indexOf(store.model(provider)).coerceAtLeast(0))
                status.text = "${list.size} models available · ${provider.label}"
            }.onFailure {
                status.text = "Could not load models"
                notify(it.message ?: "Model list unavailable")
            }
        }
    }

    private fun send() {
        if (busy) return
        val prompt = input.text.toString().trim()
        if (prompt.isBlank()) return
        val key = runCatching { store.getKey(provider) }.getOrNull()
        if (key.isNullOrBlank()) {
            editKey()
            return
        }
        val fullPrompt = attachedPageText?.let {
            "$prompt\n\n--- Current webpage context (user attached) ---\n${it}\n--- End webpage context ---"
        } ?: prompt
        input.setText("")
        val user = NovaAiMessage("user", fullPrompt)
        messages += user
        render(user)
        if (persistHistory) runCatching { store.append(user) }
        busy = true
        status.text = "Thinking · ${provider.label} · ${model}"
        NovaAiApi.send(provider, key, model, messages.toList()) { result ->
            busy = false
            result.onSuccess { answer ->
                val reply = NovaAiMessage("assistant", answer)
                messages += reply
                render(reply)
                if (persistHistory) runCatching { store.append(reply) }
                status.text = "${provider.label} · ${model}"
            }.onFailure {
                status.text = "Request failed"
                notify(it.message ?: "AI request failed")
            }
        }
    }

    private fun render(message: NovaAiMessage) {
        val isAssistant = message.role == "assistant"
        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(if (isAssistant) panel else 0xFF292334.toInt(), 15)
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
                if (persistHistory) store.clear()
                messages.clear()
                feed.removeAllViews()
                showWelcome()
                notify("Conversation history cleared")
            }
            .setNegativeButton("Close", null).show()
    }
}
