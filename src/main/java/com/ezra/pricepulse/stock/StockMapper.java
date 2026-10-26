package com.ezra.pricepulse.stock;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockMapper {
    @Select("""
            <script>
            SELECT id, symbol, name, market, currency, latest_price, latest_quote_at,
                   status, created_at, updated_at, rule_index_token
            FROM stocks
            WHERE status = 'ACTIVE'
            <if test='query != null and query != ""'>
              AND (UPPER(symbol) LIKE CONCAT('%', UPPER(#{query}), '%')
                   OR UPPER(name) LIKE CONCAT('%', UPPER(#{query}), '%'))
            </if>
            ORDER BY market, symbol, id
            LIMIT #{limit}
            </script>
            """)
    List<Stock> searchActive(@Param("query") String query, @Param("limit") int limit);

    @Select("""
            SELECT id, symbol, name, market, currency, latest_price, latest_quote_at,
                   status, created_at, updated_at, rule_index_token
            FROM stocks
            WHERE id = #{stockId} AND status = 'ACTIVE'
            """)
    Stock findActiveById(long stockId);

    @Select("""
            <script>
            SELECT id, symbol, name, market, currency, latest_price, latest_quote_at,
                   status, created_at, updated_at, rule_index_token
            FROM stocks
            WHERE symbol = #{symbol} AND status = 'ACTIVE'
            <if test='market != null'>AND market = #{market}</if>
            ORDER BY market, id
            LIMIT 2
            </script>
            """)
    List<Stock> findActiveBySymbol(@Param("symbol") String symbol, @Param("market") String market);

    @Select("""
            SELECT id, symbol, name, market, currency, latest_price, latest_quote_at,
                   status, created_at, updated_at, rule_index_token
            FROM stocks
            WHERE id = #{stockId} AND status = 'ACTIVE'
            FOR UPDATE
            """)
    Stock findActiveByIdForUpdate(long stockId);

    @Update("""
            UPDATE stocks
            SET latest_price = #{price}, latest_quote_at = #{eventTime}
            WHERE id = #{stockId} AND status = 'ACTIVE'
            """)
    int updateLatestQuote(
            @Param("stockId") long stockId,
            @Param("price") java.math.BigDecimal price,
            @Param("eventTime") java.time.LocalDateTime eventTime);

    @Update("UPDATE stocks SET rule_index_token = #{token} WHERE id = #{stockId}")
    int updateRuleIndexToken(@Param("stockId") long stockId, @Param("token") String token);
}
