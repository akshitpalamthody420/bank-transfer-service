package dev.akshit.bank.account;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String ownerName,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal startingBalance) {}
