package com.alchemistaloha.xflow.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The records are written the way the x.com documents of 2026-11-24 carry
 * them (checked on the logged-out pages of a profile and of a post); ids
 * and values here are made up.
 */
class PageOperationsTest {
    private val document = """<html><body><script>self.x=((a)=>a)({requests:[""" +
        """{kind:0,requestId:0,key:"abcDEFghiJKL_mnop-qr12{\"__relay_internal__pv__somefeaturerelayprovider\":true,""" +
        """\"count\":20,\"cursor\":null,\"product\":\"Latest\",\"rawQuery\":\"from:someone \\\"two words\\\"\"}",name:"SearchByRawQuery"},""" +
        """{kind:1,requestId:0,key:"abcDEFghiJKL_mnop-qr12{\"count\":20}"},""" +
        """{kind:0,requestId:1,key:"ZYXwvu_987-abcdefghij{\"screenName\":\"someone\",\"withIdentity\":false}",name:"UserByScreenName"},""" +
        """{kind:0,requestId:2,key:"ZYXwvu_987-abcdefghij{\"screenName\":\"someone\",\"withIdentity\":true}",name:"UserByScreenName"}""" +
        """]})</script></body></html>"""

    @Test
    fun readsIdNameAndVariablesOfEachOperation() {
        val operations = PageOperations.parse(document)
        assertEquals(setOf("SearchByRawQuery", "UserByScreenName"), operations.keys)

        val search = operations.getValue("SearchByRawQuery")
        assertEquals("abcDEFghiJKL_mnop-qr12", search.queryId)
        assertEquals("/graphql/abcDEFghiJKL_mnop-qr12/SearchByRawQuery", search.path)
        assertEquals("from:someone \"two words\"", search.variables.getValue("rawQuery").jsonPrimitive.content)
        assertEquals(JsonNull, search.variables.getValue("cursor"))
        assertEquals(true, search.variables.getValue("__relay_internal__pv__somefeaturerelayprovider").jsonPrimitive.content.toBoolean())

        // The first record of a name wins.
        assertEquals("false", operations.getValue("UserByScreenName").variables.getValue("withIdentity").jsonPrimitive.content)
    }

    @Test
    fun theRequestKeepsThePagesVariablesAndPutsOursOverThem() {
        val search = PageOperations.parse(document).getValue("SearchByRawQuery")
        assertEquals("rawQuery", PageOperations.variableHolding(search, "from:someone \"two words\""))
        assertNull(PageOperations.variableHolding(search, "not there"))

        val uri = search.uri(mapOf("rawQuery" to JsonPrimitive("from:other"), "cursor" to JsonPrimitive("c1")))
        assertEquals("x.com", uri.host)
        assertEquals("/i/api/graphql/abcDEFghiJKL_mnop-qr12/SearchByRawQuery", uri.path)
        assertEquals(setOf("variables"), uri.queryParameters.keys)
        assertEquals(
            """{"__relay_internal__pv__somefeaturerelayprovider":true,"count":20,"cursor":"c1","product":"Latest","rawQuery":"from:other"}""",
            uri.queryParameters.getValue("variables"),
        )
    }

    @Test
    fun aDocumentWithoutRecordsGivesNothing() {
        assertTrue(PageOperations.parse("<html><body>login</body></html>").isEmpty())
        // A record whose variables are not JSON is skipped, not thrown.
        assertTrue(PageOperations.parse("""key:"abcDEFghiJKL_mnop-qr12{broken}",name:"X"""").isEmpty())
    }

    @Test
    fun theSearchDocumentAddress() {
        val uri = PageOperations.searchPageUri("from:a OR from:b", latest = true)
        assertEquals("/search", uri.path)
        assertEquals("from:a OR from:b", uri.queryParameters["q"])
        assertEquals("live", uri.queryParameters["f"])
        assertNull(PageOperations.searchPageUri("x", latest = false).queryParameters["f"])
    }
}
