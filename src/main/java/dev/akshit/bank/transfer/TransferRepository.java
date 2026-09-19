package dev.akshit.bank.transfer;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Transfer> MAPPER = (rs, row) -> new Transfer(
            rs.getObject("id", UUID.class), rs.getObject("from_account_id", UUID.class),
            rs.getObject("to_account_id", UUID.class), rs.getBigDecimal("amount"),
            rs.getTimestamp("created_at").toInstant());

    public TransferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lockKey(String key) {
        // Transaction-scoped: another request with this key waits for commit or rollback.
        // Hash collisions only serialize unrelated requests; the full key is stored below.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) { /* consume PostgreSQL's void result */ } }, key);
    }

    public Optional<Transfer> findByKey(String key) {
        return jdbc.query("SELECT * FROM transfers WHERE idempotency_key = ?", MAPPER, key)
                .stream().findFirst();
    }

    public Transfer insert(String key, TransferRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO transfers (id, idempotency_key, from_account_id, to_account_id, amount)
                VALUES (?, ?, ?, ?, ?) RETURNING *
                """, MAPPER, UUID.randomUUID(), key, request.fromAccountId(),
                request.toAccountId(), request.amount());
    }

    public List<Transfer> history(UUID accountId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM transfers WHERE from_account_id = ? OR to_account_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, MAPPER, accountId, accountId, limit, offset);
    }
}
