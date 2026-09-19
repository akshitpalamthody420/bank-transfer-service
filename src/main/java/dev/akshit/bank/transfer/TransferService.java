package dev.akshit.bank.transfer;

import dev.akshit.bank.account.*;
import dev.akshit.bank.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TransferService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("999999999999.99");
    private final JdbcTemplate jdbc;
    private final TransferRepository transfers;
    private final AccountService accounts;

    public TransferService(JdbcTemplate jdbc, TransferRepository transfers, AccountService accounts) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.accounts = accounts;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult transfer(String key, TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_ACCOUNT",
                    "Source and destination accounts must be different.");
        }
        transfers.lockKey(key);
        Optional<Transfer> previous = transfers.findByKey(key);
        if (previous.isPresent()) {
            Transfer transfer = previous.get();
            if (!transfer.fromAccountId().equals(request.fromAccountId())
                    || !transfer.toAccountId().equals(request.toAccountId())
                    || transfer.amount().compareTo(request.amount()) != 0) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "This key was already used for a different transfer.");
            }
            return new TransferResult(transfer, true);
        }

        // The database orders UUIDs consistently, including opposite-direction transfers.
        List<Account> locked = jdbc.query("""
                SELECT * FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """, AccountRepository.MAPPER, request.fromAccountId(), request.toAccountId());
        if (locked.size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    "One or both accounts do not exist.");
        }
        Account from = locked.stream().filter(a -> a.id().equals(request.fromAccountId())).findFirst().orElseThrow();
        Account to = locked.stream().filter(a -> a.id().equals(request.toAccountId())).findFirst().orElseThrow();
        if (from.balance().compareTo(request.amount()) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "The source account does not have enough funds.");
        }
        BigDecimal destinationBalance = to.balance().add(request.amount());
        if (destinationBalance.compareTo(MAX_BALANCE) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_LIMIT",
                    "The destination balance would exceed the supported limit.");
        }
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?",
                from.balance().subtract(request.amount()), from.id());
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", destinationBalance, to.id());
        // If this insert fails, Spring rolls both balance updates back.
        Transfer transfer = transfers.insert(key, request);
        return new TransferResult(transfer, false);
    }

    @Transactional(readOnly = true)
    public List<Transfer> history(UUID accountId, int limit, int offset) {
        accounts.get(accountId);
        return transfers.history(accountId, limit, offset);
    }
}
