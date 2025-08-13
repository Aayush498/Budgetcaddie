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
const transactionsOutput = document.getElementById("transactions-output");

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
    transactionsOutput.innerText = "No transactions loaded yet. Click 'View Transactions' to fetch data.";
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
    try {
        const res = await fetch(`${API_BASE}/plaid/transactions/get`, {
            headers: { Authorization: `Bearer ${JWT_TOKEN}` },
        });
        if (!res.ok) throw new Error(await res.text());
        const json = await res.json();
        transactionsOutput.innerText = JSON.stringify(json, null, 2);
        const count = json.length || json.total_transactions || 0;
        plaidMessage.innerText = `📜 Fetched ${count} transactions.`;
    } catch (error) {
        transactionsOutput.innerText = `// Error fetching transactions: ${error.message}`;
        plaidMessage.innerText = `❌ Fetch error: ${error.message}`;
    }
}
