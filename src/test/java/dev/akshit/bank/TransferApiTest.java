package dev.akshit.bank;

import com.fasterxml.jackson.databind.*;
import dev.akshit.bank.account.*;
import dev.akshit.bank.api.ApiException;
import dev.akshit.bank.transfer.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTest {
    static PostgreSQLContainer<?> postgres;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_DB_URL");
        if (url == null) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_DB_USER", "bank"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_DB_PASSWORD", "bank"));
        }
    }

    @AfterAll static void stopDatabase() { if (postgres != null) postgres.stop(); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired TransferService service;
    @MockitoSpyBean TransferRepository transfers;

    @BeforeEach void clear() {
        reset(transfers);
        jdbc.execute("TRUNCATE transfers, accounts");
    }

    UUID account(String balance) {
        return accounts.create(new CreateAccountRequest("Test account", new BigDecimal(balance))).id();
    }
    TransferRequest request(UUID from, UUID to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount));
    }
    ResultActions send(String key, TransferRequest request) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsBytes(request)));
    }
    void balances(UUID from, String expectedFrom, UUID to, String expectedTo) {
        assertThat(accounts.get(from).balance()).isEqualByComparingTo(expectedFrom);
        assertThat(accounts.get(to).balance()).isEqualByComparingTo(expectedTo);
    }
    int transferCount() { return jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class); }

    @Test void createAndReadAccount() throws Exception {
        String body = mvc.perform(post("/api/accounts").contentType("application/json")
                .content("{\"ownerName\":\" Alice \",\"startingBalance\":100.25}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.currency").value("GBP")).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/accounts/" + json.readTree(body).get("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(100.25));
    }

    @Test void movesMoneyAndRecordsHistoryForBothAccounts() throws Exception {
        UUID from = account("100.00"), to = account("10.00");
        send("payment-1", request(from, to, "25.30")).andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"));
        balances(from, "74.70", to, "35.30");
        for (UUID id : List.of(from, to)) {
            mvc.perform(get("/api/accounts/" + id + "/transactions"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].amount").value(25.30));
        }
    }

    @Test void rejectsInsufficientFundsWithoutChangingAnything() throws Exception {
        UUID from = account("10"), to = account("20");
        send("poor", request(from, to, "11")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        balances(from, "10", to, "20");
        assertThat(transferCount()).isZero();
    }

    @Test void replaysSameKeyButRejectsChangedPayload() throws Exception {
        UUID from = account("100"), to = account("0");
        String original = send("same", request(from, to, "5.00")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = send("same", request(from, to, "5")).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay)).isEqualTo(json.readTree(original));
        send("same", request(from, to, "6")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        balances(from, "95", to, "5");
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test void rollbackAfterBothUpdatesIfRecordingFails() throws Exception {
        UUID from = account("100"), to = account("0");
        doThrow(new DataIntegrityViolationException("Injected failure after both balance updates"))
                .when(transfers).insert(eq("rollback"), any());
        send("rollback", request(from, to, "30")).andExpect(status().isServiceUnavailable());
        balances(from, "100", to, "0");
        assertThat(transferCount()).isZero();
        reset(transfers);
        send("rollback", request(from, to, "30")).andExpect(status().isCreated());
        balances(from, "70", to, "30");
    }

    @Test void missingAndSameAccountsHaveCorrectErrors() throws Exception {
        UUID from = account("100"), missing = UUID.randomUUID();
        send("missing", request(from, missing, "1")).andExpect(status().isNotFound());
        send("self", request(from, from, "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/" + missing + "/transactions")).andExpect(status().isNotFound());
        assertThat(accounts.get(from).balance()).isEqualByComparingTo("100");
        assertThat(transferCount()).isZero();
    }

    @Test void validatesMoneyAndRequiredFields() throws Exception {
        UUID from = account("100"), to = account("0");
        for (String amount : List.of("0", "-1", "0.001", "1000000000000")) {
            send("invalid", request(from, to, amount)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/transfers").contentType("application/json")
                .content(json.writeValueAsBytes(request(from, to, "1")))).andExpect(status().isBadRequest());
        send(" ", request(from, to, "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers").header("Idempotency-Key", "null")
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertThat(transferCount()).isZero();
    }

    @Test void validatesAccountInputAndMalformedJson() throws Exception {
        for (String payload : List.of("{}", "{", "{\"ownerName\":\"\",\"startingBalance\":1}",
                "{\"ownerName\":\"A\",\"startingBalance\":-1}",
                "{\"ownerName\":\"A\",\"startingBalance\":1.001}",
                "{\"ownerName\":\"A\",\"startingBalance\":1,\"extra\":true}")) {
            mvc.perform(post("/api/accounts").contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/accounts/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test void capsDestinationBalanceWithoutDebitingSource() throws Exception {
        UUID from = account("10"), to = account("999999999999.99");
        send("overflow", request(from, to, "1")).andExpect(status().isConflict());
        balances(from, "10", to, "999999999999.99");
    }

    @Test void paginatesHistoryAndRejectsBadBounds() throws Exception {
        UUID from = account("100"), to = account("0");
        for (int i = 0; i < 3; i++) service.transfer("page-" + i, request(from, to, "1"));
        mvc.perform(get("/api/accounts/" + from + "/transactions?limit=2&offset=0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/accounts/" + from + "/transactions?limit=2&offset=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        for (String query : List.of("limit=0", "limit=101", "offset=-1")) {
            mvc.perform(get("/api/accounts/" + from + "/transactions?" + query))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void concurrentDuplicatesApplyOnlyOnce() throws Exception {
        UUID from = account("100"), to = account("0");
        List<Boolean> replayed = parallel(8, i ->
                service.transfer("concurrent", request(from, to, "10")).replayed());
        assertThat(replayed.stream().filter(v -> !v).count()).isEqualTo(1);
        balances(from, "90", to, "10");
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test void concurrentSpendingCannotOverdraw() throws Exception {
        UUID from = account("100"), to = account("0");
        List<Boolean> results = parallel(8, i -> {
            try { service.transfer("spend-" + i, request(from, to, "30")); return true; }
            catch (ApiException ex) {
                assertThat(ex.code()).isEqualTo("INSUFFICIENT_FUNDS");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        balances(from, "10", to, "90");
        assertThat(transferCount()).isEqualTo(3);
    }

    @Test void opposingTransfersUseConsistentLockOrder() throws Exception {
        UUID a = account("100"), b = account("100");
        parallel(8, i -> service.transfer("opposite-" + i,
                i % 2 == 0 ? request(a, b, "1") : request(b, a, "1")));
        balances(a, "100", b, "100");
        assertThat(transferCount()).isEqualTo(8);
    }

    @Test void servesOpenApiAndHealth() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/transfers']").exists());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private <T> List<T> parallel(int count, java.util.function.IntFunction<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return task.apply(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) values.add(future.get(20, TimeUnit.SECONDS));
            return values;
        }
    }
}
