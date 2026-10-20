package io.technoirlab.device.run.core.client

/**
 * Requests every page of a paginated list, following page tokens until a response has none.
 */
internal fun <ItemT, ResponseT> collectPages(
    requestPage: (pageToken: String?) -> ResponseT,
    items: (ResponseT) -> List<ItemT>?,
    nextPageToken: (ResponseT) -> String?,
): List<ItemT> = buildList {
    var pageToken: String? = null
    do {
        val response = requestPage(pageToken)
        addAll(items(response).orEmpty())
        pageToken = nextPageToken(response)
    } while (!pageToken.isNullOrEmpty())
}
