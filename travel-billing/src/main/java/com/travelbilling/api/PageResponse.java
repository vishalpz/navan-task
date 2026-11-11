package com.travelbilling.api;

import java.util.List;

/**
 * JSON shape of a list page: { "data": [...], "limit": 50, "next_cursor": "txn_50" }.
 * next_cursor is left out on the last page. Pass it back as ?cursor=... to get the next page.
 */
public record PageResponse<T>(List<T> data, int limit, String nextCursor) {
}
