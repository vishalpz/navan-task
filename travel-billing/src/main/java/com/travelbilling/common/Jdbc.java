package com.travelbilling.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** Two small JDBC patterns used by every repository. */
public final class Jdbc {

    private Jdbc() {
    }

    /**
     * Batch-loading children: run ONE query for many parents ({@code WHERE parent_id IN (:ids)})
     * and split the rows into a list per parent. This is how we avoid N+1 queries.
     * Row order is kept, so ORDER BY position in the SQL gives correctly ordered lists.
     */
    public static <T> Map<Long, List<T>> groupBy(NamedParameterJdbcTemplate jdbc, String sql,
                                                 Map<String, ?> params, String parentIdColumn,
                                                 RowMapper<T> mapper) {
        Map<Long, List<T>> byParent = new HashMap<>();
        jdbc.query(sql, params, (RowCallbackHandler) rs ->
            byParent.computeIfAbsent(rs.getLong(parentIdColumn), id -> new ArrayList<>())
                    .add(mapper.mapRow(rs, rs.getRow())));
        return byParent;
    }

    /**
     * One parameter set per list item for {@code batchUpdate}, each tagged with the parent id
     * and its index in the list (stored as "position" so the list comes back in the same order).
     */
    public static <T> SqlParameterSource[] positioned(String parentIdParam, long parentId, List<T> items,
                                                      BiConsumer<MapSqlParameterSource, T> fill) {
        SqlParameterSource[] batch = new SqlParameterSource[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue(parentIdParam, parentId)
                .addValue("position", i);
            fill.accept(params, items.get(i));
            batch[i] = params;
        }
        return batch;
    }
}
