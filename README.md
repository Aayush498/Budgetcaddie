# Budgetcaddie

Assumptions:

* DNS **A record** for `app.budgetcaddie.com → 52.5.31.134` is already set. (I completed this)
* Spring Boot runs on **port 8080** on the EC2 box.
* Server OS is **Ubuntu**. 


# ✅ Goal

Serve **[https://app.budgetcaddie.com](https://app.budgetcaddie.com)** through **Nginx** (reverse proxy) → **Spring Boot (8080)** with a Let’s Encrypt TLS certificate.

# 0) Connect to the server (from your Mac/PC)

```bash
ssh -i /path/to/key.pem ubuntu@52.5.31.134
```

# 1) Open the ports in AWS (one-time)

In the **EC2 console → Instances → (your instance) → Security → Security group → Inbound rules**:

* Add: **HTTP** TCP **80** from `0.0.0.0/0`
* Add: **HTTPS** TCP **443** from `0.0.0.0/0`

If Ubuntu has UFW enabled:

```bash
sudo ufw allow 'Nginx Full'
```

# 2) Verify the app is alive on 8080

(on the server)

```bash
curl -I http://127.0.0.1:8080
```

You should see an HTTP response (e.g., `200 OK` or `301` headers).
If “Connection refused”, start the app (adjust the path):

```bash
# example – replace with your real jar path/name
nohup java -jar /home/ubuntu/app/app.jar > /home/ubuntu/app/app.log 2>&1 &
```

# 3) Install & start Nginx

**Ubuntu:**

```bash
sudo apt update
sudo apt install -y nginx
sudo systemctl enable nginx
sudo systemctl start nginx
```

# 4) Create the Nginx site for app.budgetcaddie.com

### Ubuntu layout (sites-available / sites-enabled)

```bash
sudo bash -c 'cat >/etc/nginx/sites-available/app.budgetcaddie.com <<EOF
server {
    listen 80;
    server_name app.budgetcaddie.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 300;
    }
}
EOF'

sudo ln -s /etc/nginx/sites-available/app.budgetcaddie.com /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default  # (optional)
sudo nginx -t
sudo systemctl reload nginx
```

# 5) Test HTTP on the domain

* From the server:

  ```bash
  curl -I http://localhost
  ```

  You should get a 200/301 from your app (proxied by Nginx).
* From your browser:
  **[http://app.budgetcaddie.com](http://app.budgetcaddie.com)** should load your app **without :8080**.

If you get 502/504:

* Make sure the app is running on **127.0.0.1:8080** (`curl -I http://127.0.0.1:8080`).
* Check Nginx logs: `sudo tail -n 100 /var/log/nginx/error.log`.

# 6) Install Certbot (Let’s Encrypt) and issue TLS

**Ubuntu:**

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d app.budgetcaddie.com
```

# 7) Verify HTTPS

Open **[https://app.budgetcaddie.com](https://app.budgetcaddie.com)** in your browser → you should see the padlock (no “Not secure” message).

# 8) Renewal check 

Let’s Encrypt auto-installs a timer for renewals; verify with:

```bash
sudo certbot renew --dry-run
```

## Acceptance Criteria (what “Done” looks like)

* `http://app.budgetcaddie.com` redirects to `https://app.budgetcaddie.com`.
* Browser shows a **padlock** (valid certificate).
* App content renders correctly (proxied from port 8080).
* `sudo certbot certificates` lists a cert for `app.budgetcaddie.com`.

