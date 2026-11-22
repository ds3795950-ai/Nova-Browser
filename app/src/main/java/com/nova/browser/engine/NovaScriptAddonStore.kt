package com.nova.browser.engine
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NovaScriptAddon(val id:String,val name:String,val allowedOriginRules:Set<String>,val script:String,val enabled:Boolean)

/** Local-only script add-ons for Normal Cefrium tabs. No GeckoView or cloud-sync integration. */
class NovaScriptAddonStore(context:android.content.Context) {
    private val root=File(context.filesDir,"nova_cefrium_addons").apply { mkdirs() }
    private val index=File(root,"index.json")
    @Synchronized fun list():List<NovaScriptAddon> {
        if(!index.isFile) return emptyList()
        return runCatching {
            val rows=JSONArray(index.readText(Charsets.UTF_8))
            buildList<NovaScriptAddon> {
                for(i in 0 until rows.length()) {
                    val row=rows.optJSONObject(i)?:continue
                    val id=row.optString("id")
                    if(!id.matches(Regex("[a-f0-9-]{36}"))) continue
                    val file=File(root,"$id.js")
                    if(!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) continue
                    val a=row.optJSONArray("origins")?:continue
                    val rules=(0 until a.length()).map{a.optString(it)}.filter{validOrigin(it)}.toSet()
                    if(rules.isEmpty()) continue
                    NovaScriptAddon(id,row.optString("name").take(64).ifBlank{"Nova Script Add-on"},rules,file.readText(Charsets.UTF_8),row.optBoolean("enabled",false))
                }
            }
        }.getOrDefault(emptyList())
    }
    fun enabled()=list().filter{it.enabled}
    @Synchronized fun add(name:String, originsText:String, script:String):NovaScriptAddon {
        val clean=name.trim().take(64)
        require(clean.isNotBlank()){"Enter an add-on name"}
        require(script.isNotBlank()){"Add-on script is empty"}
        require(script.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"Script must be 256 KB or smaller"}
        val rules=originsText.split(',', '\n',';').map{it.trim().trimEnd('/')}.filter{it.isNotBlank()}.distinct()
        require(rules.isNotEmpty()){"Add at least one allowed website origin"}
        require(rules.size<=20){"Use no more than 20 website origins"}
        rules.forEach{require(validOrigin(it)){"Invalid origin: $it. Use https://example.com or https://*.example.org"}}
        val id=UUID.randomUUID().toString()
        File(root,"$id.js").writeText(script,Charsets.UTF_8)
        val item=NovaScriptAddon(id,clean,rules.toSet(),script,true)
        writeIndex(list()+item)
        return item
    }
    @Synchronized fun setEnabled(id:String,enabled:Boolean) {
        val items=list(); require(items.any{it.id==id}){"Add-on not found"}
        writeIndex(items.map{if(it.id==id)it.copy(enabled=enabled) else it})
    }
    @Synchronized fun remove(id:String) {
        val items=list(); require(items.any{it.id==id}){"Add-on not found"}
        File(root,"$id.js").delete(); writeIndex(items.filterNot{it.id==id})
    }
    private fun writeIndex(items:List<NovaScriptAddon>) {
        val rows=JSONArray()
        items.forEach{rows.put(JSONObject().put("id",it.id).put("name",it.name).put("enabled",it.enabled).put("origins",JSONArray(it.allowedOriginRules.toList().sorted())))}
        val tmp=File(root,"index.json.tmp"); tmp.writeText(rows.toString(),Charsets.UTF_8)
        if(index.exists()&&!index.delete()) throw IllegalStateException("Could not update add-on index")
        if(!tmp.renameTo(index)) throw IllegalStateException("Could not save add-on index")
    }
    private fun validOrigin(rule:String):Boolean {
        if(rule=="*"||rule.contains('@')||rule.contains('?')||rule.contains('#'))return false
        val wild=rule.startsWith("https://*.")||rule.startsWith("http://*.")
        val parsed=if(wild)rule.replace("://*.","://wildcard-check.")else rule
        val u=runCatching{Uri.parse(parsed)}.getOrNull()?:return false
        val scheme=u.scheme?.lowercase(); val host=u.host?.lowercase().orEmpty()
        if(scheme!="https"&&!(scheme=="http"&&(host=="localhost"||host=="127.0.0.1")))return false
        if(host.isBlank()||u.userInfo!=null||(u.path?.let{it.isNotEmpty()&&it!="/"}==true)||u.query!=null||u.fragment!=null)return false
        if(!wild&&rule.contains('*'))return false
        return true
    }
    private companion object{const val MAX_BYTES=256*1024}
}
