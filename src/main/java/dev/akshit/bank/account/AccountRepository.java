package dev.akshit.bank.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;
    public static final RowMapper<Account> MAPPER = AccountRepository::map;

    public AccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Account create(CreateAccountRequest request) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO accounts (id, owner_name, balance) VALUES (?, ?, ?)
                RETURNING *
                """, MAPPER, id, request.ownerName().trim(), request.startingBalance());
    }

    public Optional<Account> find(UUID id) {
        return jdbc.query("SELECT * FROM accounts WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), rs.getString("owner_name"),
                rs.getString("currency"), rs.getBigDecimal("balance"),
                rs.getTimestamp("created_at").toInstant());
    }
}
