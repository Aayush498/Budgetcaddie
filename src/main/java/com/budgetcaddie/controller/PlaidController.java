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

    // Example endpoint to create a Link token
    @GetMapping("/create_link_token")
    public ResponseEntity<?> createLinkToken() {
        try {
            // ✅ Generate a temporary unique user ID (for demo/testing without user linking)
            // In the future, replace with your actual DB user ID
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
            request.put("products", new String[] { "transactions" });

            // ✅ Allow manual account selection
            request.put("account_selection", true);

            // ✅ Use your dashboard customization profile
            request.put("link_customization_name", "default"); // or "canada"

            // ✅ Request maximum transaction history (Plaid allows ~2 years, 730 days)
            Map<String, Object> transactionsConfig = new HashMap<>();
            transactionsConfig.put("days_requested", 730);
            request.put("transactions", transactionsConfig);

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

    @PostMapping("/exchange_public_token")
    public ResponseEntity<?> exchangePublicToken(@RequestBody Map<String, String> body) {
        String publicToken = body.get("public_token");
        if (publicToken == null || publicToken.isEmpty()) {
            return ResponseEntity.badRequest().body("public_token is required");
        }

        try {
            // Build request body for exchange
            Map<String, String> request = new HashMap<>();
            request.put("client_id", clientId);
            request.put("secret", secret);
            request.put("public_token", publicToken);

            // Call Plaid exchange endpoint
            String url = plaidBaseUrl + "/item/public_token/exchange";
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(response.getStatusCode())
                        .body("Failed to exchange public token");
            }

            // Optional: parse response JSON to get access_token
            JsonNode json = objectMapper.readTree(response.getBody());
            String accessToken = json.path("access_token").asText();

            // TODO: Save accessToken securely per user in your DB here

            return ResponseEntity.ok(Map.of("access_token", accessToken));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + ex.getMessage());
        }
    }

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

            // Store all fetched transactions here
            java.util.List<JsonNode> allTransactions = new java.util.ArrayList<>();

            do {
                Map<String, Object> request = new HashMap<>();
                request.put("client_id", clientId);
                request.put("secret", secret);
                request.put("access_token", accessToken);

                // Request maximum allowed history (Plaid will return what's available)
                request.put("start_date", LocalDate.now().minusYears(2).toString());
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
            // 1️⃣ Get stored cursor from DB (if exists)
            String cursor = cursorRepository.findByAccessToken(accessToken)
                    .map(c -> c.getCursor())
                    .orElse(null);

            boolean hasMore = true;
            int countNew = 0;

            // 2️⃣ Loop until Plaid says there’s no more data
            while (hasMore) {
                Map<String, Object> req = new HashMap<>();
                req.put("client_id", clientId);
                req.put("secret", secret);
                req.put("access_token", accessToken);
                if (cursor != null) {
                    req.put("cursor", cursor);
                }

                String url = plaidBaseUrl + "/transactions/sync";
                ResponseEntity<String> response = restTemplate.postForEntity(url, req, String.class);

                if (!response.getStatusCode().is2xxSuccessful()) {
                    return ResponseEntity.status(response.getStatusCode())
                            .body("Error from Plaid: " + response.getBody());
                }

                JsonNode root = objectMapper.readTree(response.getBody());

                // 3️⃣ Process new transactions from "added"
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

                // 4️⃣ Update loop control values
                hasMore = root.path("has_more").asBoolean();
                cursor = root.path("next_cursor").asText();

                // 5️⃣ Save the updated cursor in DB
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
