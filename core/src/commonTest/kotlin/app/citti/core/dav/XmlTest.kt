package app.citti.core.dav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class XmlTest {
    @Test
    fun readsNamespacesPrefixesAndTheDefaultNamespace() {
        val root =
            Xml.parse(
                """<?xml version="1.0" encoding="utf-8"?>
                <!-- a comment -->
                <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/remote.php/dav/calendars/alice/personal/</d:href>
                    <d:propstat><d:prop>
                      <x:calendar-color xmlns:x="http://apple.com/ns/ical/">#0082c9</x:calendar-color>
                      <resourcetype xmlns="DAV:"><collection/><cal:calendar/></resourcetype>
                      <cal:supported-calendar-component-set><cal:comp name="VEVENT"/></cal:supported-calendar-component-set>
                    </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  </d:response>
                </d:multistatus>""",
            )
        assertEquals("DAV:", root.ns)
        assertEquals("multistatus", root.name)
        val response = assertNotNull(root.child("DAV:", "response"))
        assertEquals("/remote.php/dav/calendars/alice/personal/", response.child("DAV:", "href")?.text)
        val prop = response.descendants("DAV:", "prop").single()
        assertEquals("#0082c9", prop.child("http://apple.com/ns/ical/", "calendar-color")?.text)
        val type = assertNotNull(prop.child("DAV:", "resourcetype"))
        assertEquals(listOf("DAV:" to "collection", "urn:ietf:params:xml:ns:caldav" to "calendar"), type.children.map { it.ns to it.name })
        assertEquals("VEVENT", root.descendants("urn:ietf:params:xml:ns:caldav", "comp").single().attributes["name"])
    }

    @Test
    fun decodesEntitiesAndCdata() {
        val root = Xml.parse("<a>x &amp; y &lt;z&gt; &#233;&#x1F600;<![CDATA[<raw> & ]]>end</a>")
        assertEquals("x & y <z> é😀<raw> & end", root.text)
    }

    @Test
    fun refusesDoctypeAndBrokenDocuments() {
        assertFailsWith<XmlException> { Xml.parse("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>") }
        assertFailsWith<XmlException> { Xml.parse("<a>&x;</a>") }
        assertFailsWith<XmlException> { Xml.parse("<a><b></a>") }
        assertFailsWith<XmlException> { Xml.parse("<a>") }
        assertFailsWith<XmlException> { Xml.parse("<p:a/>") }
        assertFailsWith<XmlException> { Xml.parse("") }
    }
}
