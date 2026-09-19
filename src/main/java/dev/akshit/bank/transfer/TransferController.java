package dev.akshit.bank.transfer;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class TransferController {
    private final TransferService transfers;
    public TransferController(TransferService transfers) { this.transfers = transfers; }

    @Operation(summary = "Transfer GBP between accounts",
            description = "Idempotency-Key is required. Same key and payload replays the original result; a changed payload returns 409.")
    @PostMapping("/api/transfers")
    public ResponseEntity<Transfer> transfer(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transfers.transfer(key, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.transfer());
    }

    @GetMapping("/api/accounts/{id}/transactions")
    public List<Transfer> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return transfers.history(id, limit, offset);
    }
}
