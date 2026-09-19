package dev.akshit.bank.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String ownerName, String currency, BigDecimal balance, Instant createdAt) {}
