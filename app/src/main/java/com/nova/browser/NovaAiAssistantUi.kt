package com.nova.browser

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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
    GROK("Grok", "grok-3-mini"), GEMINI("Gemini", "gemini-2.0-flash")
}
internal data class NovaAiMessage(val role:String,val text:String,val time:Long=System.currentTimeMillis())

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
    fun setKey(provider:NovaAiProvider,value:String){if(value.isBlank())p.edit().remove("key_${provider.name}").commit() else check(p.edit().putString("key_${provider.name}",encrypt(value.trim())).commit())}
    fun getKey(provider:NovaAiProvider)=p.getString("key_${provider.name}",null)?.let(::decrypt)
    fun hasKey(provider:NovaAiProvider)=runCatching{!getKey(provider).isNullOrBlank()}.getOrDefault(false)
    fun model(provider:NovaAiProvider)=p.getString("model_${provider.name}",provider.defaultModel)?:provider.defaultModel
    fun setModel(provider:NovaAiProvider,value:String){require(value.isNotBlank()&&value.length<160);p.edit().putString("model_${provider.name}",value).apply()}
    fun history():List<NovaAiMessage> = runCatching { val a=JSONArray(decrypt(p.getString("history","")!!));(0 until a.length()).map{val o=a.getJSONObject(it);NovaAiMessage(o.getString("role"),o.getString("text"),o.optLong("time"))}.takeLast(100) }.getOrDefault(emptyList())
    fun append(m:NovaAiMessage){val a=JSONArray();(history()+m).takeLast(100).forEach{a.put(JSONObject().put("role",it.role).put("text",it.text).put("time",it.time))};check(p.edit().putString("history",encrypt(a.toString())).commit())}
    fun clear(){p.edit().remove("history").commit()}
}

internal object NovaAiApi {
    private val pool=Executors.newFixedThreadPool(2)
    private fun enc(s:String)=java.net.URLEncoder.encode(s,"UTF-8")
    private fun call(method:String,url:String,headers:Map<String,String>,body:JSONObject?=null):JSONObject {
        val c=URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod=method;c.connectTimeout=20000;c.readTimeout=60000
            headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            c.setRequestProperty("Accept","application/json")
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(StandardCharsets.UTF_8))}}
            val code=c.responseCode;val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            val j=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
            if(code !in 200..299) error("Provider API error ($code): ${j.optJSONObject("error")?.optString("message")?:j.optString("message").ifBlank{"Request failed"}}")
            return j
        } finally {c.disconnect()}
    }
    fun models(p:NovaAiProvider,key:String,done:(Result<List<String>>)->Unit){pool.execute{
        val r=runCatching {
            val (url,h)=when(p){
                NovaAiProvider.CHATGPT->"https://api.openai.com/v1/models" to mapOf("Authorization" to "Bearer $key")
                NovaAiProvider.GROK->"https://api.x.ai/v1/models" to mapOf("Authorization" to "Bearer $key")
                NovaAiProvider.CLAUDE->"https://api.anthropic.com/v1/models?limit=100" to mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01")
                NovaAiProvider.GEMINI->"https://generativelanguage.googleapis.com/v1beta/models?key=${enc(key)}" to emptyMap()
            }
            val j=call("GET",url,h);val a=j.optJSONArray(if(p==NovaAiProvider.CLAUDE||p==NovaAiProvider.GEMINI)"models" else "data")
            (0 until (a?.length()?:0)).mapNotNull{i->val o=a!!.optJSONObject(i)?:return@mapNotNull null
                val id=if(p==NovaAiProvider.GEMINI)o.optString("name").removePrefix("models/") else o.optString("id")
                id.takeIf{it.isNotBlank() && (p!=NovaAiProvider.GEMINI||o.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent")==true)}
            }.distinct().sorted().ifEmpty{listOf(p.defaultModel)}
        };done(r)
    }}
    fun send(p:NovaAiProvider,key:String,model:String,history:List<NovaAiMessage>,done:(Result<String>)->Unit){pool.execute{
        val r=runCatching {
            val recent=history.takeLast(20)
            when(p){
                NovaAiProvider.CHATGPT,NovaAiProvider.GROK->{
                    val a=JSONArray();recent.forEach{a.put(JSONObject().put("role",it.role).put("content",it.text))}
                    val url=if(p==NovaAiProvider.GROK)"https://api.x.ai/v1/chat/completions" else "https://api.openai.com/v1/chat/completions"
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
                    call("POST","https://generativelanguage.googleapis.com/v1beta/models/${enc(model)}:generateContent?key=${enc(key)}",emptyMap(),JSONObject().put("contents",a))
                        .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty().ifBlank{error("Empty AI response")}
                }
            }
        };done(r)
    }}
}

internal class NovaAiAssistantUi(
    private val context:Context,private val store:NovaAiSecureStore,
    private val requestPageText:((String?)->Unit)->Unit,private val persistHistory:Boolean,private val notify:(String)->Unit
) {
    private var provider=NovaAiProvider.CHATGPT;private var model=store.model(provider);private val messages=mutableListOf<NovaAiMessage>()
    private lateinit var feed:LinearLayout;private lateinit var scroll:ScrollView;private lateinit var input:EditText
    private lateinit var status:TextView;private lateinit var models:Spinner;private lateinit var modelAdapter:ArrayAdapter<String>
    private var busy=false
    fun show(){
        val root=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(14,10,14,8)}
        val top=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        val providers=Spinner(context).apply{adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,NovaAiProvider.values().map{it.label})}
        top.addView(providers,LinearLayout.LayoutParams(0,48,1f))
        top.addView(Button(context).apply{text="API key";isAllCaps=false;setOnClickListener{editKey()}})
        root.addView(top)
        val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        models=Spinner(context);modelAdapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,listOf(model));models.adapter=modelAdapter
        row.addView(models,LinearLayout.LayoutParams(0,48,1f))
        row.addView(Button(context).apply{text="↻";contentDescription="Refresh models";setOnClickListener{refreshModels()}})
        root.addView(row)
        status=TextView(context).apply{textSize=12f;setPadding(4,4,4,8)};root.addView(status)
        scroll=ScrollView(context);feed=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(4,8,4,8)};scroll.addView(feed)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        input=EditText(context).apply{hint="Message Nova AI…";minLines=2;maxLines=4;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE};root.addView(input)
        val actions=LinearLayout(context).apply{gravity=Gravity.CENTER}
        actions.addView(Button(context).apply{text="Summarise page";isAllCaps=false;setOnClickListener{summarise()}},LinearLayout.LayoutParams(0,48,1f))
        actions.addView(Button(context).apply{text="History";isAllCaps=false;setOnClickListener{historyDialog()}},LinearLayout.LayoutParams(0,48,1f))
        actions.addView(Button(context).apply{text="Send";isAllCaps=false;setOnClickListener{send()}},LinearLayout.LayoutParams(0,48,1f));root.addView(actions)
        val d=AlertDialog.Builder(context).setTitle("Nova AI Assistant").setView(root).setNegativeButton("Close",null).create()
        providers.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(p:android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,i:Int,id:Long){provider=NovaAiProvider.values()[i];model=store.model(provider);modelAdapter.clear();modelAdapter.add(model);modelAdapter.notifyDataSetChanged();status.text=if(store.hasKey(provider))"Ready · ${provider.label}" else "Add ${provider.label} API key to start"}
        }
        models.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(p:android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,i:Int,id:Long){model=models.selectedItem?.toString()?:store.model(provider);store.setModel(provider,model)}
        }
        if(persistHistory){messages+=store.history();messages.forEach(::render)}
        status.text=if(store.hasKey(provider))"Ready · ChatGPT" else "Add ChatGPT API key to start"
        d.show();d.window?.setLayout(-1,-1);d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun editKey(){
        val field=EditText(context).apply{hint="Paste API key";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        AlertDialog.Builder(context).setTitle("${provider.label} API key")
            .setMessage("Stored encrypted with Android Keystore. Nova sends the key directly to ${provider.label}'s official API.")
            .setView(field).setPositiveButton("Save"){_,_->runCatching{store.setKey(provider,field.text.toString())}.onSuccess{status.text=if(store.hasKey(provider))"API key saved" else "API key removed"}.onFailure{notify("Could not save key: ${it.message}")}}
            .setNeutralButton("Remove"){_,_->store.setKey(provider,"");status.text="API key removed"}.setNegativeButton("Cancel",null).show()
    }
    private fun refreshModels(){
        val key=store.getKey(provider);if(key.isNullOrBlank()){notify("Add the ${provider.label} API key first");return};status.text="Loading models…"
        NovaAiApi.models(provider,key){r->r.onSuccess{list->modelAdapter.clear();modelAdapter.addAll(list);modelAdapter.notifyDataSetChanged();models.setSelection(list.indexOf(store.model(provider)).coerceAtLeast(0));status.text="Loaded ${list.size} models"}.onFailure{status.text="Model list unavailable";notify(it.message?:"Could not load models")}}
    }
    private fun summarise(){
        status.text="Extracting readable page text…"
        requestPageText{page->if(page.isNullOrBlank()){status.text="No readable page text";notify("Could not read the current page");return@requestPageText}
            input.setText("Summarise this webpage. Give an overview, key points, important facts and conclusions. Do not invent details.\n\n"+page.take(24000));send()}
    }
    private fun send(){
        if(busy)return;val text=input.text.toString().trim();if(text.isBlank())return
        val key=store.getKey(provider);if(key.isNullOrBlank()){editKey();return}
        input.setText("");val user=NovaAiMessage("user",text);messages+=user;render(user);if(persistHistory)runCatching{store.append(user)}
        busy=true;status.text="Thinking · ${provider.label} · ${model}"
        NovaAiApi.send(provider,key,model,messages.toList()){r->busy=false;r.onSuccess{answer->val m=NovaAiMessage("assistant",answer);messages+=m;render(m);if(persistHistory)runCatching{store.append(m)};status.text="${provider.label} · ${model}"}.onFailure{status.text="Request failed";notify(it.message?:"AI request failed")}}
    }
    private fun render(m:NovaAiMessage){val v=TextView(context).apply{text=(if(m.role=="assistant")"Nova AI" else "You")+"\n"+m.text;textSize=14f;setPadding(14,10,14,10);setTypeface(null,if(m.role=="user")Typeface.BOLD else Typeface.NORMAL);setBackgroundColor(if(m.role=="assistant")0xFFEAEFF7.toInt() else 0xFFE3F2FD.toInt())};feed.addView(v,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,4,0,8)});scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
    private fun historyDialog(){
        val all=if(persistHistory)store.history() else messages.toList();if(all.isEmpty()){notify("No AI history yet");return}
        val recent=all.takeLast(50);val labels=recent.map{(if(it.role=="user")"You: " else "AI: ")+it.text.replace('\n',' ').take(100)}
        AlertDialog.Builder(context).setTitle("Nova AI history").setItems(labels.toTypedArray()){_,i->AlertDialog.Builder(context).setMessage(recent[i].text).setPositiveButton("OK",null).show()}
            .setNeutralButton("Clear history"){_,_->if(persistHistory)store.clear();messages.clear();feed.removeAllViews();notify("AI history cleared")}.setNegativeButton("Close",null).show()
    }
}
