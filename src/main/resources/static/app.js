// --- Global Variables & Constants ---
const API_BASE = "http://localhost:8080/api";
let JWT_TOKEN = "";
let authMode = "login"; // or "register"

// --- UI Element References ---
const authModal = document.getElementById("auth-modal");
const authTitle = document.getElementById("auth-title");
const authBtn = document.getElementById("auth-btn");
const authMessage = document.getElementById("auth-message");
const authEmail = document.getElementById("auth-email");
const authUsername = document.getElementById("auth-username");
const authPassword = document.getElementById("auth-password");
const heroSection = document.getElementById("hero-section");
const appSection = document.getElementById("app-section");
const plaidMessage = document.getElementById("plaid-message");

// --- Modal Functions ---
function openModal(mode) {
    authMode = mode;
    authModal.style.display = "flex";
    authTitle.innerText = mode === "login" ? "Login" : "Create Account";
    authBtn.innerText = mode === "login" ? "Login" : "Register";

    // Toggle email field visibility
    authEmail.style.display = mode === "register" ? "block" : "none";

    // Reset form state
    authMessage.innerText = "";
    authUsername.value = "";
    authEmail.value = "";
    authPassword.value = "";

    // Animate modal in
    setTimeout(() => authModal.classList.add("show"), 10);
}

function closeModal() {
    authModal.classList.remove("show");
    setTimeout(() => {
        authModal.style.display = "none";
    }, 300); // Wait for animation to finish
}

// Close modal on outside click
window.onclick = (event) => {
    if (event.target === authModal) {
        closeModal();
    }
};

// --- Authentication ---
async function handleAuth() {
    const username = authUsername.value.trim();
    const password = authPassword.value.trim();
    const email = authEmail.value.trim();

    if (!username || !password || (authMode === "register" && !email)) {
        authMessage.innerText = "Please fill in all required fields.";
        authMessage.style.color = "var(--accent-danger)";
        return;
    }

    authMessage.innerText = authMode === "login" ? "Logging in..." : "Registering...";
    authMessage.style.color = "var(--text-secondary)";

    try {
        if (authMode === "register") {
            const res = await fetch(`${API_BASE}/auth/register`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, email, password }),
            });
            if (res.ok) {
                authMessage.innerText = "✅ Registered successfully! Please login.";
                authMessage.style.color = "var(--accent-primary)";
                setTimeout(() => openModal("login"), 1500);
            } else {
                const errorData = await res.json();
                throw new Error(errorData.message || 'Registration failed.');
            }
        } else { // Login mode
            const res = await fetch(`${API_BASE}/auth/login`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });
            const json = await res.json();
            if (res.ok && json.token) {
                JWT_TOKEN = json.token;
                closeModal();
                heroSection.style.opacity = "0";
                setTimeout(() => {
                    heroSection.style.display = "none";
                    appSection.style.display = "block";
                }, 500);
            } else {
                throw new Error(json.message || "Invalid credentials.");
            }
        }
    } catch (error) {
        authMessage.innerText = `❌ ${error.message}`;
        authMessage.style.color = "var(--accent-danger)";
    }
}

function handleLogout() {
    JWT_TOKEN = "";
    appSection.style.display = "none";
    heroSection.style.display = "flex";
    setTimeout(() => (heroSection.style.opacity = "1"), 10); // Fade back in
    
    // Clear sensitive data from UI
    plaidMessage.innerText = "";
    document.getElementById('transactions-table-body').innerHTML = '';
    document.getElementById('no-transactions-message').style.display = 'block';
    document.getElementById('no-transactions-message').textContent = "No transactions loaded yet. Click 'View Transactions' to fetch data.";
}

// --- Plaid Integration ---
async function createLinkToken() {
    plaidMessage.innerText = "🔄 Creating Plaid Link token...";
    try {
        const res = await fetch(`${API_BASE}/plaid/create_link_token`, {
            headers: { Authorization: `Bearer ${JWT_TOKEN}` },
        });
        if (!res.ok) throw new Error(await res.text());
        const json = await res.json();

        if (json.link_token) {
            plaidMessage.innerText = "🔗 Opening Plaid Link...";
            const handler = Plaid.create({
                token: json.link_token,
                onSuccess: async (public_token) => {
                    plaidMessage.innerText = "⏳ Exchanging public token...";
                    try {
                        const exchRes = await fetch(`${API_BASE}/plaid/exchange_public_token`, {
                            method: "POST",
                            headers: {
                                "Content-Type": "application/json",
                                Authorization: `Bearer ${JWT_TOKEN}`,
                            },
                            body: JSON.stringify({ public_token }),
                        });
                        if (!exchRes.ok) throw new Error(await exchRes.text());
                        plaidMessage.innerText = "✅ Bank account linked successfully!";
                    } catch (err) {
                        plaidMessage.innerText = `❌ Token exchange error: ${err.message}`;
                    }
                },
                onExit: (err) => {
                    plaidMessage.innerText = err ? `⚠️ Plaid exited: ${err.display_message || err.error_message}` : "ℹ️ Plaid flow exited.";
                },
            });
            handler.open();
        } else {
            throw new Error("No link token received from server.");
        }
    } catch (error) {
        plaidMessage.innerText = `❌ Failed to create link token: ${error.message}`;
    }
}

async function syncTransactions() {
    plaidMessage.innerText = "🔄 Syncing transactions...";
    try {
        const res = await fetch(`${API_BASE}/plaid/transactions/sync`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Authorization: `Bearer ${JWT_TOKEN}`,
            },
            body: JSON.stringify({}),
        });
        const text = await res.text();
        if (res.ok) {
            plaidMessage.innerText = `✅ ${text}`;
        } else {
            plaidMessage.innerText = `❌ Sync error: ${text}`;
        }
    } catch (err) {
        plaidMessage.innerText = `❌ Network error during sync: ${err.message}`;
    }
}

async function getTransactions() {
    plaidMessage.innerText = "📜 Fetching transactions...";
    const tableBody = document.getElementById("transactions-table-body");
    const noTransactionsMessage = document.getElementById("no-transactions-message");
    tableBody.innerHTML = ''; // Clear previous results

    try {
        const res = await fetch(`${API_BASE}/plaid/transactions/get`, {
            headers: { Authorization: `Bearer ${JWT_TOKEN}` },
        });
        if (!res.ok) throw new Error(await res.text());
        const json = await res.json();
        
        const transactions = Array.isArray(json) ? json : json.transactions || [];

        if (transactions && transactions.length > 0) {
            noTransactionsMessage.style.display = 'none';

            transactions.forEach(transaction => {
                const row = tableBody.insertRow();
                
                // Cell 1: Merchant Name (using snake_case from Plaid API)
                const cellMerchant = row.insertCell();
                cellMerchant.textContent = transaction.merchant_name || transaction.name || 'N/A';

                // Cell 2: Amount
                const cellAmount = row.insertCell();
                const amount = transaction.amount;
                cellAmount.textContent = new Intl.NumberFormat('en-US', {
                    style: 'currency',
                    currency: transaction.iso_currency_code || 'USD',
                }).format(amount);
                cellAmount.style.color = amount > 0 ? 'var(--text-primary)' : 'var(--accent-success)';

                // Cell 3: Category (looking inside personal_finance_category object)
                const cellCategory = row.insertCell();
                const pfc = transaction.personal_finance_category;
                let categoryText = 'Uncategorized';
                if (pfc && pfc.primary) {
                    // Only show the primary category as requested
                    categoryText = pfc.primary;
                }
                cellCategory.textContent = categoryText;

                // Cell 4: Currency Code (using snake_case from Plaid API)
                const cellCurrency = row.insertCell();
                cellCurrency.textContent = transaction.iso_currency_code || 'N/A';
            });

            plaidMessage.innerText = `📜 Fetched ${transactions.length} transactions.`;
        } else {
            noTransactionsMessage.style.display = 'block';
            noTransactionsMessage.textContent = "No transactions found. Try syncing first.";
            plaidMessage.innerText = "📜 No transactions found.";
        }
    } catch (error) {
        tableBody.innerHTML = '';
        noTransactionsMessage.style.display = 'block';
        noTransactionsMessage.textContent = `Error fetching transactions: ${error.message}`;
        plaidMessage.innerText = `❌ Fetch error: ${error.message}`;
    }
}
