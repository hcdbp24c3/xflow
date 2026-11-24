package com.alchemistaloha.xflow.core.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One GraphQL operation found in an x.com document: its id and the variables the page sent with it. */
data class PageOperation(val name: String, val queryId: String, val variables: JsonObject) {
    /** `/graphql/<id>/<name>`, the form the rest of the client uses for a path. */
    val path: String get() = "/graphql/$queryId/$name"

    /**
     * The request for this operation with [overrides] put over the page's
     * own variables. The page's variables are kept whole: X answers 422
     * when one of its internal switches is missing.
     */
    fun uri(overrides: Map<String, JsonElement>): XUri {
        val variables = LinkedHashMap<String, JsonElement>(this.variables)
        variables.putAll(overrides)
        return XUri.https(
            "x.com", "/i/api$path",
            mapOf("variables" to JsonObject(variables).toString()),
        )
    }
}

/**
 * Since November 2026 the x.com web client no longer ships one table of
 * operation ids. The document of each page carries the operations that page
 * ran on the server: a record `key:"<queryId>{…variables as JSON…}",
 * name:"<Operation>"`. This reads those records, so the ids do not have to
 * be compiled into the app.
 */
object PageOperations {
    private val record = Regex("""key:"([A-Za-z0-9_-]{15,40})(\{(?:[^"\\]|\\.)*\})",name:"([A-Za-z0-9_]+)"""")

    /** Every named operation in [html]; the first record of a name wins. */
    fun parse(html: String): Map<String, PageOperation> {
        val found = LinkedHashMap<String, PageOperation>()
        for (match in record.findAll(html)) {
            val (queryId, escaped, name) = match.destructured
            if (found.containsKey(name)) continue
            val variables = try {
                // The variables sit inside a JavaScript string literal.
                val text = (Json.parseToJsonElement("\"$escaped\"") as JsonPrimitive).content
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: continue
            found[name] = PageOperation(name, queryId, variables)
        }
        return found
    }

    /** The document that runs a search, where `SearchByRawQuery` is found. */
    fun searchPageUri(query: String, latest: Boolean): XUri = XUri.https(
        "x.com", "/search",
        buildMap {
            put("q", query)
            put("src", "typed_query")
            if (latest) put("f", "live")
        },
    )

    /** The variable of [operation] that holds the value [sample], e.g. the query the page was opened with. */
    fun variableHolding(operation: PageOperation, sample: String): String? =
        operation.variables.entries.firstOrNull { (_, value) ->
            value is JsonPrimitive && value.isString && value.content == sample
        }?.key
}
