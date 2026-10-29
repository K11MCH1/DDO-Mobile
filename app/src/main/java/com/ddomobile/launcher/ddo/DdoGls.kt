package com.ddomobile.launcher.ddo

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

class DdoGlsException(message: String, cause: Throwable? = null) : IOException(message, cause)

class DdoWrongCredentialsException(message: String) : IOException(message)

data class DdoWorld(
    val name: String,
    val chatServerUrl: String,
    val statusServerUrl: String,
)

data class DdoDatacenter(
    val authServer: String,
    val patchServer: String,
    val launcherConfigUrl: String,
    val worlds: List<DdoWorld>,
)

data class DdoLauncherConfig(
    val win64Filename: String,
    val argTemplate: String,
    val values: Map<String, String>,
) {
    val loginQueueUrl: String get() = values.getValue("WorldQueue.LoginQueue.URL")
    val takeANumberParameters: String get() = values.getValue("WorldQueue.TakeANumber.Parameters")
}

data class DdoSubscription(
    val game: String,
    val name: String,
    val description: String,
    val status: String?,
)

data class DdoLoginResult(
    val ticket: String,
    val subscriptions: List<DdoSubscription>,
) {
    fun ddoSubscriptions(): List<DdoSubscription> = subscriptions.filter { it.game == DdoGls.GAME }
}

data class DdoWorldStatus(
    val loginServer: String,
    val queueUrl: String,
    val allowedBillingRoles: Set<String>?,
)

data class DdoQueueResult(val queueNumber: Long, val nowServing: Long) {
    val ahead: Long get() = (queueNumber - nowServing).coerceAtLeast(0)
}

object DdoGls {
    const val GAME = "DDO"
    const val DATACENTER_SERVICE = "https://gls.ddo.com/GLS.DataCenterServer/Service.asmx"
    private const val NS = "http://www.turbine.com/SE/GLS"
    private const val TIMEOUT_MS = 20_000

    fun datacenter(): DdoDatacenter {
        val body = """<GetDatacenters xmlns="$NS"><game>$GAME</game></GetDatacenters>"""
        return parseDatacenter(soap(DATACENTER_SERVICE, "GetDatacenters", body))
    }

    internal fun parseDatacenter(xml: String): DdoDatacenter {
        val root = soapBody(xml, "GetDatacenters")
        val dc = root.firstDescendant("Datacenter") ?: throw DdoGlsException("GetDatacenters returned no datacenter")
        val worlds = dc.descendants("World").map { w ->
            DdoWorld(
                name = w.childText("Name"),
                chatServerUrl = w.childText("ChatServerUrl"),
                statusServerUrl = w.childText("StatusServerUrl"),
            )
        }
        return DdoDatacenter(
            authServer = dc.childText("AuthServer"),
            patchServer = dc.childText("PatchServer"),
            launcherConfigUrl = dc.childText("LauncherConfigurationServer"),
            worlds = worlds,
        )
    }

    fun launcherConfig(url: String): DdoLauncherConfig = parseLauncherConfig(httpGet(url))

    internal fun parseLauncherConfig(xml: String): DdoLauncherConfig {
        val doc = parse(xml)
        val values = linkedMapOf<String, String>()
        doc.descendants("add").forEach { values[it.getAttribute("key")] = it.getAttribute("value") }
        val exe = values["GameClient.WIN64.Filename"] ?: throw DdoGlsException("Launcher config has no 64-bit client")
        val template = values["GameClient.WIN64.ArgTemplate"]
            ?: values["GameClient.WIN32.ArgTemplate"]
            ?: values["GameClient.ArgTemplate"]
            ?: throw DdoGlsException("Launcher config has no argument template")
        return DdoLauncherConfig(exe, template, values)
    }

    fun login(authServer: String, username: String, password: String): DdoLoginResult {
        val body = """<LoginAccount xmlns="$NS"><username>${xml(username)}</username>""" +
            """<password>${xml(password)}</password><additionalInfo></additionalInfo></LoginAccount>"""
        return parseLogin(soap(authServer, "LoginAccount", body))
    }

    internal fun parseLogin(xml: String): DdoLoginResult {
        val root = try {
            soapBody(xml, "LoginAccount")
        } catch (e: DdoGlsException) {
            val msg = e.message.orEmpty().lowercase()
            when {
                "no subscriber formal entity" in msg -> throw DdoWrongCredentialsException("Username or password is incorrect")
                "user name is too short" in msg -> throw DdoWrongCredentialsException("Username is too short")
                "password is too short" in msg -> throw DdoWrongCredentialsException("Password is too short")
                else -> throw e
            }
        }
        val profile = root.firstDescendant("LoginAccountResult") ?: throw DdoGlsException("LoginAccount returned no profile")
        val subs = profile.descendants("GameSubscription").map { s ->
            DdoSubscription(
                game = s.childText("Game"),
                name = s.childText("Name"),
                description = s.childTextOrNull("Description").orEmpty(),
                status = s.childTextOrNull("Status"),
            )
        }
        return DdoLoginResult(ticket = profile.childText("Ticket"), subscriptions = subs)
    }

    fun worldStatus(world: DdoWorld): DdoWorldStatus = parseWorldStatus(world.name, httpGet(secure(world.statusServerUrl)))

    internal fun parseWorldStatus(name: String, xml: String): DdoWorldStatus {
        if (xml.isBlank()) throw DdoGlsException("$name is unavailable")
        val status = parse(xml).documentElement
        val queues = status.childTextOrNull("queueurls").orEmpty().split(';').filter { it.isNotBlank() }
        val logins = status.childTextOrNull("loginservers").orEmpty().split(';').filter { it.isNotBlank() }
        if (queues.isEmpty() || logins.isEmpty()) throw DdoGlsException("$name is unavailable")
        val roles = status.childTextOrNull("allow_billing_role")?.split(',')?.filter { it.isNotBlank() }?.toSet()
        return DdoWorldStatus(loginServer = logins.first(), queueUrl = queues.first(), allowedBillingRoles = roles)
    }

    fun takeANumber(config: DdoLauncherConfig, subscription: String, ticket: String, queueUrl: String): DdoQueueResult {
        val form = config.takeANumberParameters.split('&').joinToString("&") { pair ->
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val value = v.replace("{0}", subscription).replace("{1}", ticket).replace("{2}", queueUrl)
            "${enc(k)}=${enc(value)}"
        }
        return parseQueue(httpPost(secure(config.loginQueueUrl), "application/x-www-form-urlencoded", form, null))
    }

    internal fun parseQueue(xml: String): DdoQueueResult {
        val root = parse(xml).documentElement
        val hresult = hex(root.childText("HResult"))
        if (hresult and 0x80000000L != 0L) {
            throw DdoGlsException(
                if (hresult == 0x80004005L) "Failed to join the world login queue. Try again later."
                else "World login queue error 0x${hresult.toString(16)}"
            )
        }
        return DdoQueueResult(
            queueNumber = hex(root.childTextOrNull("QueueNumber")),
            nowServing = hex(root.childTextOrNull("NowServingNumber")),
        )
    }

    fun clientArguments(
        config: DdoLauncherConfig,
        subscription: String,
        loginServer: String,
        ticket: String,
        world: DdoWorld,
        language: String = "English",
        highRes: Boolean = true,
    ): List<String> {
        val v = config.values
        val mapping = mapOf(
            "{SUBSCRIPTION}" to subscription,
            "{LOGIN}" to loginServer,
            "{GLS}" to ticket,
            "{CHAT}" to world.chatServerUrl,
            "{LANG}" to language,
            "{CRASHRECEIVER}" to v["GameClient.Arg.crashreceiver"],
            "{UPLOADTHROTTLE}" to v["GameClient.Arg.DefaultUploadThrottleMbps"],
            "{BUGURL}" to v["GameClient.Arg.bugurl"],
            "{AUTHSERVERURL}" to v["GameClient.Arg.authserverurl"],
            "{GLSTICKETLIFETIME}" to v["GameClient.Arg.glsticketlifetime"],
            "{SUPPORTURL}" to v["GameClient.Arg.supporturl"],
            "{SUPPORTSERVICEURL}" to v["GameClient.Arg.supportserviceurl"],
        )
        // Substitute per token so a ticket containing "{" can't be re-expanded.
        val args = config.argTemplate.split(' ').filter { it.isNotEmpty() }.map { token ->
            if (token.startsWith("{") && token.endsWith("}")) {
                mapping[token] ?: throw DdoGlsException("Launch argument $token has no value")
            } else {
                token
            }
        }.toMutableList()
        if (!highRes) {
            args += (v["GameClient.HighResPatchArg"]?.trim()?.takeIf { it.isNotEmpty() } ?: "--HighResOutOfDate")
        }
        return args
    }

    /** Official GLS hosts also answer on HTTPS; never send tickets in the clear. */
    fun secure(url: String): String {
        val u = URL(url)
        val official = u.host.endsWith(".ddo.com") || u.host == "ddo.com"
        return if (official && u.protocol == "http") URL("https", u.host, u.file).toString() else url
    }

    private fun soap(endpoint: String, action: String, body: String): String {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>$body</soap:Body></soap:Envelope>"""
        return httpPost(secure(endpoint), "text/xml; charset=utf-8", envelope, "\"$NS/$action\"", allowErrorBody = true)
    }

    private fun soapBody(text: String, action: String): Element {
        val doc = parse(text)
        doc.firstDescendant("Fault")?.let { fault ->
            throw DdoGlsException(fault.childTextOrNull("faultstring") ?: "$action failed")
        }
        return doc.documentElement
    }

    private fun httpGet(url: String): String = request(url, "GET", null, null, null, false)

    private fun httpPost(url: String, type: String, body: String, soapAction: String?, allowErrorBody: Boolean = false): String =
        request(url, "POST", type, body, soapAction, allowErrorBody)

    private fun request(url: String, method: String, type: String?, body: String?, soapAction: String?, allowErrorBody: Boolean): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (type != null) conn.setRequestProperty("Content-Type", type)
            if (soapAction != null) conn.setRequestProperty("SOAPAction", soapAction)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299 && !(allowErrorBody && text.contains("Fault"))) {
                throw DdoGlsException("HTTP $code from ${URL(url).host}")
            }
            return text
        } catch (e: DdoGlsException) {
            throw e
        } catch (e: IOException) {
            throw DdoGlsException("Network error: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's parser never resolves external entities and rejects these feature names.
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return try {
            factory.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            throw DdoGlsException("Unreadable server response", e)
        }
    }

    private fun hex(value: String?): Long =
        value?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16) ?: 0L

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun Node.localNameOrName(): String = localName ?: nodeName.substringAfter(':')

    private fun org.w3c.dom.Document.descendants(name: String): List<Element> = documentElement.descendants(name)

    private fun org.w3c.dom.Document.firstDescendant(name: String): Element? = documentElement.firstDescendant(name)

    private fun Element.descendants(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is Element) {
                    if (c.localNameOrName() == name) out += c
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(this)
        return out
    }

    private fun Element.firstDescendant(name: String): Element? =
        if (localNameOrName() == name) this else descendants(name).firstOrNull()

    private fun Element.childTextOrNull(name: String): String? {
        var c = firstChild
        while (c != null) {
            if (c is Element && c.localNameOrName() == name) return c.textContent?.trim()
            c = c.nextSibling
        }
        return null
    }

    private fun Element.childText(name: String): String =
        childTextOrNull(name) ?: throw DdoGlsException("Server response is missing $name")
}
