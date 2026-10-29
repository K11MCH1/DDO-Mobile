package com.ddomobile.launcher.ddo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DdoGlsTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream("ddo/$name")!!.bufferedReader().use { it.readText() }

    private val world = DdoWorld("Thrane [US]", "chat:2900", "http://gls.ddo.com/GLS.DataCenterServer/StatusServer.aspx?s=10.193.146.20")

    @Test
    fun datacenterListsServersAndWorlds() {
        val dc = DdoGls.parseDatacenter(fixture("datacenters.xml"))
        assertEquals("https://gls-auth.ddo.com/GLS.AuthServer/Service.asmx", dc.authServer)
        assertEquals("http://gls.ddo.com/launcher/ddo/dndlauncher.server.config.xml", dc.launcherConfigUrl)
        assertTrue(dc.worlds.any { it.name == "Thrane [US]" })
        assertTrue(dc.worlds.all { it.statusServerUrl.contains("StatusServer.aspx") })
    }

    @Test
    fun launcherConfigGivesTheClientAndQueue() {
        val config = DdoGls.parseLauncherConfig(fixture("launcher.server.config.xml"))
        assertEquals("dndclient64.exe", config.win64Filename)
        assertTrue(config.argTemplate.startsWith("-a {SUBSCRIPTION} -h {LOGIN} --glsticketdirect {GLS}"))
        assertEquals("https://gls.ddo.com/GLS.AuthServer/LoginQueue.aspx", config.loginQueueUrl)
        assertTrue(config.takeANumberParameters.contains("command=TakeANumber"))
    }

    @Test
    fun clientArgumentsFillEveryToken() {
        val config = DdoGls.parseLauncherConfig(fixture("launcher.server.config.xml"))
        val args = DdoGls.clientArguments(config, "sub-1", "1.2.3.4:9004", "TICKET{GLS}", world)
        assertEquals(listOf("-a", "sub-1", "-h", "1.2.3.4:9004", "--glsticketdirect", "TICKET{GLS}"), args.take(6))
        assertTrue(args.containsAll(listOf("--chatserver", "chat:2900", "--language", "English", "--gametype", "DDO")))
        assertTrue(args.contains("https://gls.ddo.com/GLS.AuthServer/Service.asmx"))
        assertFalse(args.any { it.startsWith("{") && it.endsWith("}") })
        assertFalse(args.contains("--HighResOutOfDate"))
        assertTrue(DdoGls.clientArguments(config, "s", "l", "t", world, highRes = false).contains("--HighResOutOfDate"))
    }

    @Test
    fun worldStatusPicksTheFirstLoginServer() {
        val status = DdoGls.parseWorldStatus("Thrane", fixture("status-thrane.xml"))
        assertTrue(status.loginServer.matches(Regex("[0-9.]+:\\d+")))
        assertTrue(status.queueUrl.endsWith("/LoginQueue"))
    }

    @Test(expected = DdoGlsException::class)
    fun emptyStatusMeansTheWorldIsDown() {
        DdoGls.parseWorldStatus("Thrane", "")
    }

    @Test
    fun loginReadsTicketAndDdoSubscription() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
            <LoginAccountResponse xmlns="http://www.turbine.com/SE/GLS"><LoginAccountResult>
            <Subscriptions>
              <GameSubscription><Game>LOTRO</Game><Name>lotro-sub</Name><Description>x</Description><Status>Active</Status></GameSubscription>
              <GameSubscription><Game>DDO</Game><Name>ddo-sub</Name><Description>DDO</Description><Status>Active</Status></GameSubscription>
            </Subscriptions><Ticket>abc==</Ticket>
            </LoginAccountResult></LoginAccountResponse></soap:Body></soap:Envelope>"""
        val login = DdoGls.parseLogin(xml)
        assertEquals("abc==", login.ticket)
        assertEquals(listOf("ddo-sub"), login.ddoSubscriptions().map { it.name })
    }

    @Test(expected = DdoWrongCredentialsException::class)
    fun loginFaultForUnknownUser() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
            <faultcode>soap:Server</faultcode><faultstring>No Subscriber Formal Entity was found</faultstring>
            </soap:Fault></soap:Body></soap:Envelope>"""
        DdoGls.parseLogin(xml)
    }

    @Test
    fun queueResultReadsHexNumbers() {
        val q = DdoGls.parseQueue(
            "<Result><Command>TakeANumber</Command><HResult>0x00000000</HResult>" +
                "<QueueNumber>0x1E40</QueueNumber><NowServingNumber>0x1E36</NowServingNumber></Result>"
        )
        assertEquals(10L, q.ahead)
        assertEquals(0L, DdoGls.parseQueue("<Result><HResult>0x00000000</HResult></Result>").ahead)
    }

    @Test(expected = DdoGlsException::class)
    fun queueFailureIsAnError() {
        DdoGls.parseQueue("<Result><HResult>0x80004005</HResult></Result>")
    }

    @Test
    fun officialHttpUrlsAreUpgraded() {
        assertEquals("https://gls.ddo.com/launcher/ddo/x.xml", DdoGls.secure("http://gls.ddo.com/launcher/ddo/x.xml"))
        assertEquals("http://10.0.0.1:7082/LoginQueue", DdoGls.secure("http://10.0.0.1:7082/LoginQueue"))
    }

    @Test
    fun iniEditKeepsOtherKeys() {
        val ini = DdoLaunch.Ini.parse("[Render]\r\nGraphicsCore=D3D9\r\nGamma=1.01\r\n[UI]\r\nX=1\r\n")
        ini["Render", "GraphicsCore"] = "D3D11"
        ini["Launcher", "DdoMobileQuality"] = "LOW"
        val text = ini.toString()
        assertTrue(text.contains("GraphicsCore=D3D11"))
        assertTrue(text.contains("Gamma=1.01"))
        assertTrue(text.contains("[UI]\r\nX=1"))
        assertEquals("LOW", DdoLaunch.Ini.parse(text)["Launcher", "DdoMobileQuality"])
    }
}
