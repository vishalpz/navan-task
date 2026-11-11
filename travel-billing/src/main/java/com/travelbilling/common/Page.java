package com.travelbilling.common;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * One page of a list.
 *
 * @param items      at most `limit` items, ordered by id
 * @param nextAfterId the last id on this page if there's another page, otherwise null
 */
public record Page<T>(List<T> items, Long nextAfterId) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /**
     * Shared keyset-paging logic. `fetchIds(afterId, n)` must return up to n ids greater than afterId,
     * in id order. We ask for limit + 1: if that extra id comes back, there is a next page.
     */
    public static <T> Page<T> of(long afterId, int limit,
                                 IdFetcher fetchIds,
                                 Function<List<Long>, List<T>> loadByIds,
                                 ToLongFunction<T> idOf) {
        List<Long> ids = fetchIds.fetch(afterId, limit + 1);
        boolean hasMore = ids.size() > limit;
        List<T> items = loadByIds.apply(hasMore ? ids.subList(0, limit) : ids);
        Long next = hasMore ? idOf.applyAsLong(items.get(items.size() - 1)) : null;
        return new Page<>(items, next);
    }

    /** Rejects limits outside 1..100 with a 400. Too big is an error, not silently capped, so the client knows. */
    public static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new RequestValidationException(List.of("limit: must be between 1 and " + MAX_LIMIT));
        }
    }

    @FunctionalInterface
    public interface IdFetcher {
        List<Long> fetch(long afterId, int count);
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextAfterId);
    }
}
