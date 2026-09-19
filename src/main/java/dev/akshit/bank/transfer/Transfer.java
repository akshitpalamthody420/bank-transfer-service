package dev.akshit.bank.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Transfer(UUID id, UUID fromAccountId, UUID toAccountId,
                       BigDecimal amount, Instant createdAt) {}
