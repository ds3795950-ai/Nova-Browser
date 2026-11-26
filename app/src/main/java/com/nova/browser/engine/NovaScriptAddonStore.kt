package com.nova.browser.engine
import java.net.IDN
import java.net.InetAddress
import android.util.AtomicFile
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
        return runCatching {
            val rows = AtomicFile(index).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                JSONArray(reader.readText())
            }
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
                    add(NovaScriptAddon(id,row.optString("name").take(64).ifBlank{"Nova Script Add-on"},rules,file.readText(Charsets.UTF_8),row.optBoolean("enabled",false)))
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
        val scriptFile = File(root,"$id.js")
        scriptFile.writeText(script,Charsets.UTF_8)
        val item=NovaScriptAddon(id,clean,rules.toSet(),script,true)
        try {
            writeIndex(list()+item)
        } catch (error: Exception) {
            scriptFile.delete()
            throw error
        }
        return item
    }
    @Synchronized fun setEnabled(id:String,enabled:Boolean) {
        val items=list(); require(items.any{it.id==id}){"Add-on not found"}
        writeIndex(items.map{if(it.id==id)it.copy(enabled=enabled) else it})
    }
    @Synchronized fun remove(id:String) {
        val items=list(); require(items.any{it.id==id}){"Add-on not found"}
        writeIndex(items.filterNot{it.id==id})
        File(root,"$id.js").delete()
    }
    private fun writeIndex(items:List<NovaScriptAddon>) {
        val rows=JSONArray()
        items.forEach{rows.put(JSONObject().put("id",it.id).put("name",it.name).put("enabled",it.enabled).put("origins",JSONArray(it.allowedOriginRules.toList().sorted())))}
        val atomic = AtomicFile(index)
        val expected = rows.toString()
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(expected.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            output = null
            // finishWrite logs rename failures on some Android versions instead of throwing.
            // Read through AtomicFile so its backup recovery is honored, then verify the commit.
            val committed = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(committed == expected) { "Add-on index replacement did not commit" }
        } catch (error: Exception) {
            output?.let(atomic::failWrite)
            throw IllegalStateException("Could not save add-on index", error)
        }
    }
    private fun validOrigin(rule: String): Boolean {
        val match = ORIGIN_PATTERN.matchEntire(rule) ?: return false
        val scheme = match.groupValues[1].lowercase()
        val wildcard = match.groupValues[2].isNotEmpty()
        val rawHost = match.groupValues[3]
        val port = match.groupValues[4]
        if (port.isNotEmpty() && port.toIntOrNull()?.let { it in 1..65535 } != true) return false

        if (rawHost.startsWith("[") && rawHost.endsWith("]")) {
            if (wildcard) return false
            val literal = rawHost.substring(1, rawHost.length - 1)
            if (':' !in literal || '%' in literal) return false
            // A colon-containing literal is parsed locally; no DNS lookup is performed.
            return runCatching { InetAddress.getByName(literal) }.isSuccess && scheme == "https"
        }

        if (rawHost.contains(':')) return false
        val host = runCatching { IDN.toASCII(rawHost).lowercase() }.getOrNull() ?: return false
        if (host.isBlank() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.any { label ->
                label.isBlank() || label.length > 63 ||
                    !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }) return false
        val numericAddress = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        if (numericAddress && host.split('.').any { it.toIntOrNull()?.let { n -> n in 0..255 } != true }) return false
        if (wildcard && (labels.size < 2 || numericAddress || host == "localhost")) return false
        if (scheme == "http" && host != "localhost" && host != "127.0.0.1") return false
        return true
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
        val ORIGIN_PATTERN = Regex(
            """^(https?)://(\*\.)?(\[[0-9A-Fa-f:.]+\]|[^/?#@:*]+)(?::([0-9]{1,5}))?$""",
            RegexOption.IGNORE_CASE
        )
    }
}
