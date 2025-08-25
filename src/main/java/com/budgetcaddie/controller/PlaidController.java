package com.budgetcaddie.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import com.budgetcaddie.repository.TransactionRepository;
import com.budgetcaddie.model.Transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/plaid")
public class PlaidController {

    @Value("${plaid.client_id}")
    private String clientId;

    @Value("${plaid.secret}")
    private String secret;

    @Value("${plaid.environment}")
    private String plaidBaseUrl;

    @PostConstruct
    public void init() {
        System.out.println("✅ PLAID BASE URL LOADED: " + plaidBaseUrl);
    }

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private com.budgetcaddie.repository.PlaidCursorRepository cursorRepository;

    // =========================
    // LINK TOKEN CREATION
    // =========================
    @GetMapping("/create_link_token")
    public ResponseEntity<?> createLinkToken() {
        try {
            // ⚠️ Checklist: Use a FRESH link_token after any Dashboard change
            // This code already generates a new token per request (no caching).
            String uniqueUserId = java.util.UUID.randomUUID().toString();

            Map<String, Object> user = new HashMap<>();
            user.put("client_user_id", uniqueUserId);

            Map<String, Object> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("client_name", "BudgetCaddie");
            request.put("language", "en");
            request.put("country_codes", new String[] { "US", "CA" });
            request.put("user", user);

            // 💡 Transactions covers depository + credit (credit cards). Keep only "transactions" here.
            request.put("products", new String[] { "transactions" });

            // ✅ Ensure your intended customization is applied to THIS token
            // (You said "canada" has Multiple Account Selection enabled.)
            // request.put("link_customization_name", "canada");

            // Ask for max history Plaid will allow (up to ~24 months depending on FI)
            Map<String, Object> transactionsConfig = new HashMap<>();
            transactionsConfig.put("days_requested", 180);
            request.put("transactions", transactionsConfig);

            // DEBUG: log the outgoing payload so you can confirm customization + filters
            try {
                System.out.println("📝 /link/token/create payload => " + objectMapper.writeValueAsString(request));
            } catch (Exception ignore) {}

            String url = plaidBaseUrl + "/link/token/create";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                return ResponseEntity.ok(json);
            } else {
                return ResponseEntity.badRequest().body("Failed to create link token");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    // =========================
    // PUBLIC TOKEN EXCHANGE
    // =========================
    @PostMapping("/exchange_public_token")
    public ResponseEntity<?> exchangePublicToken(@RequestBody Map<String, String> body) {
        String publicToken = body.get("public_token");
        if (publicToken == null || publicToken.isEmpty()) {
            return ResponseEntity.badRequest().body("public_token is required");
        }

        try {
            Map<String, String> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("public_token", publicToken);

            String url = plaidBaseUrl + "/item/public_token/exchange";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode())
                        .body("Failed to exchange public token");
            }

            JsonNode json = objectMapper.readTree(response.getBody());
            String accessToken = json.path("access_token").asText();

            // TODO: Persist accessToken per user (securely)

            return ResponseEntity.ok(Map.of("access_token", accessToken));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + ex.getMessage());
        }
    }

    // =========================
    // FULL TRANSACTION BACKFILL (PAGINATED)
    // =========================
    @PostMapping("/transactions/get")
    public ResponseEntity<?> getAllTransactionsFromPlaid(@RequestBody Map<String, String> body) {
        String accessToken = body.get("access_token");

        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body("access_token is required");
        }

        try {
            int pageSize = 500; // Plaid max per call
            int offset = 0;
            int totalTransactions = -1;

            java.util.List<JsonNode> allTransactions = new java.util.ArrayList<>();

            do {
                Map<String, Object> request = new HashMap<>();
                request.put("client_id", clientId);
                request.put("secret", secret);
                request.put("access_token", accessToken);

                request.put("start_date", LocalDate.now().minusMonths(6).toString());
                request.put("end_date", LocalDate.now().toString());

                Map<String, Object> options = new HashMap<>();
                options.put("count", pageSize);
                options.put("offset", offset);
                request.put("options", options);

                String url = plaidBaseUrl + "/transactions/get";
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    return ResponseEntity.status(response.getStatusCode())
                            .body("Failed to fetch transactions from Plaid");
                }

                JsonNode root = objectMapper.readTree(response.getBody());
                totalTransactions = root.path("total_transactions").asInt(0);

                JsonNode txs = root.path("transactions");
                for (JsonNode t : txs) {
                    allTransactions.add(t);
                }

                offset += pageSize;

            } while (offset < totalTransactions);

            Map<String, Object> result = new HashMap<>();
            result.put("total_transactions", totalTransactions);
            result.put("transactions", allTransactions);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Error fetching transactions: " + e.getMessage());
        }
    }

    @PostMapping("/transactions/sync")
    public ResponseEntity<?> syncTransactions(@RequestBody Map<String, String> body) {
        String accessToken = body.get("access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body("access_token is required");
        }

        try {
            String cursor = cursorRepository.findByAccessToken(accessToken)
                    .map(c -> c.getCursor())
                    .orElse(null);

            boolean hasMore = true;
            int countNew = 0;

            while (hasMore) {
                Map<String, Object> req = new HashMap<>();
                req.put("client_id", clientId);
                req.put("secret", secret);
                req.put("access_token", accessToken);
                if (cursor != null)
                    req.put("cursor", cursor);

                String url = plaidBaseUrl + "/transactions/sync";
                ResponseEntity<String> response = restTemplate.postForEntity(url, req, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    return ResponseEntity.status(response.getStatusCode())
                            .body("Error from Plaid: " + response.getBody());
                }

                JsonNode root = objectMapper.readTree(response.getBody());

                JsonNode added = root.path("added");
                for (JsonNode t : added) {
                    String plaidId = t.path("transaction_id").asText();
                    if (!transactionRepository.existsByPlaidTransactionId(plaidId)) {
                        Transaction tx = new Transaction();
                        tx.setPlaidTransactionId(plaidId);
                        tx.setAccountId(t.path("account_id").asText());
                        tx.setAmount(t.path("amount").asDouble());
                        tx.setName(t.path("name").asText());
                        JsonNode cat = t.path("personal_finance_category");
                        tx.setCategory(cat.path("primary").asText(null));
                        tx.setSubcategory(cat.path("detailed").asText(null));
                        tx.setDate(LocalDate.parse(t.path("date").asText()));
                        tx.setCurrencyCode(t.path("iso_currency_code").asText(null));
                        tx.setMerchantName(t.path("merchant_name").asText(null));
                        transactionRepository.save(tx);
                        countNew++;
                    }
                }

                hasMore = root.path("has_more").asBoolean();
                cursor = root.path("next_cursor").asText(null);

                com.budgetcaddie.model.PlaidCursor pc = cursorRepository.findByAccessToken(accessToken)
                        .orElse(new com.budgetcaddie.model.PlaidCursor());
                pc.setAccessToken(accessToken);
                pc.setCursor(cursor);
                cursorRepository.save(pc);
            }

            return ResponseEntity.ok("Synced and stored " + countNew + " new transactions.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error syncing transactions: " + e.getMessage());
        }
    }

    @GetMapping("/transactions/all")
    public java.util.List<Transaction> getAllStoredTransactions() {
        return transactionRepository.findAll();
    }
}
