package dev.akshit.bank.account;

import dev.akshit.bank.api.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts = accounts; }

    @Transactional
    public Account create(CreateAccountRequest request) { return accounts.create(request); }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.find(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + id));
    }
}
