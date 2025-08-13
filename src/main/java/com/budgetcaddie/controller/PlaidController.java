package com.budgetcaddie.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import com.budgetcaddie.repository.TransactionRepository;
import com.budgetcaddie.model.PlaidCursor;
import com.budgetcaddie.model.Transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import com.budgetcaddie.model.User;
import com.budgetcaddie.repository.UserRepository;

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

    @Autowired
    private UserRepository userRepository;

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
            // request.put("account_selection", true);

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

            // 🔑 Get currently authenticated user
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userRepository.findByUsernameIgnoreCase(username)
                    .orElseGet(() -> userRepository.findByEmailIgnoreCase(username).orElseThrow());

            // Store Plaid access token for this user
            user.setPlaidAccessToken(accessToken);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of(
                    "message", "Access token stored for user " + username,
                    "access_token", accessToken));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + ex.getMessage());
        }
    }

    @GetMapping("/transactions/get")
    public ResponseEntity<?> getAllTransactionsFromPlaid() {
        try {
            // 1️⃣ Get currently logged-in user from JWT
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userRepository.findByUsernameIgnoreCase(username)
                    .orElseGet(() -> userRepository.findByEmailIgnoreCase(username)
                            .orElseThrow(() -> new RuntimeException("User not found")));

            String accessToken = user.getPlaidAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                return ResponseEntity.badRequest().body("No Plaid access token linked for this user");
            }

            int pageSize = 500; // Plaid max transactions per call
            int offset = 0;
            int totalTransactions = -1;

            // 💾 Store all fetched transactions here
            List<JsonNode> allTransactions = new ArrayList<>();

            // 2️⃣ Loop to fetch all pages from Plaid
            do {
                Map<String, Object> request = new HashMap<>();
                request.put("client_id", clientId);
                request.put("secret", secret);
                request.put("access_token", accessToken);
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

            // 3️⃣ Prepare result
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
    public ResponseEntity<?> syncTransactions() {
        try {
            // 1️⃣ Get logged-in username/email from JWT SecurityContext
            String username = SecurityContextHolder.getContext().getAuthentication().getName();
            User user = userRepository.findByUsernameIgnoreCase(username)
                    .orElseGet(() -> userRepository.findByEmailIgnoreCase(username)
                            .orElseThrow(() -> new RuntimeException("User not found: " + username)));

            String accessToken = user.getPlaidAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                return ResponseEntity.badRequest().body("No Plaid access token linked for this user");
            }

            // 2️⃣ Get stored cursor for this user's access token
            String cursor = cursorRepository.findByAccessToken(accessToken)
                    .map(c -> c.getCursor())
                    .orElse(null);

            boolean hasMore = true;
            int countNew = 0;

            // 3️⃣ Loop until no more transactions
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

                // 4️⃣ Process all "added" transactions
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

                        tx.setUserId(user.getId()); // ✅ important!

                        transactionRepository.save(tx);
                        countNew++;
                    }
                }

                // 5️⃣ Update loop conditions
                hasMore = root.path("has_more").asBoolean();
                cursor = root.path("next_cursor").asText();

                // 6️⃣ Save updated cursor for this access token
                PlaidCursor pc = cursorRepository.findByAccessToken(accessToken)
                        .orElse(new PlaidCursor());
                pc.setAccessToken(accessToken);
                pc.setCursor(cursor);
                cursorRepository.save(pc);
            }

            return ResponseEntity
                    .ok("Synced and stored " + countNew + " new transactions for user " + user.getUsername());

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
