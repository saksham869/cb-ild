# CB-ILD Setup Guide — MX-276

## Prerequisites

- Java 21
- Docker Desktop
- MariaDB running on port 3307 (CB-ILD DB)
- mifos-x-credit-bureau-plugin running on port 8081

---

## Environment Variables

### CB-ILD

| Variable | Purpose | Example |
|---|---|---|
| `CB_ENC_KEY` | AES-256-GCM key for encrypting secrets at rest | See generation below |
| `FINERACT_BASE_URL` | Fineract API base URL | `https://mifos-bank-1.mifos.community/fineract-provider/api/v1` |
| `FINERACT_PASSWORD` | Fineract Basic auth password | `password` |
| `CBILD_DB_USER` | MariaDB username | `root` |
| `CBILD_DB_PASS` | MariaDB password | `root` |

### Plugin (mifos-x-credit-bureau-plugin)

| Variable | Purpose | Example |
|---|---|---|
| `MIFOS_SECURITY_ENCRYPTION_KEY` | AES key for encrypting CDC credentials in DB | See generation below |
| `SPRING_SECURITY_USER_NAME` | Plugin Basic auth username | `tester` |
| `SPRING_SECURITY_USER_PASSWORD` | Plugin Basic auth password | `tempPassword123` |

---

## Key Generation

### CB_ENC_KEY (32 bytes, base64)
```bash
openssl rand -base64 32
```
Set as environment variable — never in application.properties:
```bash
export CB_ENC_KEY="generated-value-here"
```

### MIFOS_SECURITY_ENCRYPTION_KEY (plugin)
```bash
openssl rand -base64 32
```
Set in docker-compose.yml or as env var before starting plugin.

### ECDSA Key Pair (secp384r1) — for CDC signing
```bash
cd ~/Downloads/CB
openssl ecparam -name secp384r1 -genkey -out pri_key.pem
openssl req -new -x509 -days 356 -key pri_key.pem -out certificate.pem -subj "/C=MX/CN=CB-ILD"
```

---

## Startup Order

1. Start MariaDB (CB-ILD):
```bash
docker start mariadb-cb
```

2. Start plugin stack:
```bash
cd ~/path/to/mifos-x-credit-bureau-plugin
export MIFOS_SECURITY_ENCRYPTION_KEY="your-key"
docker compose up -d
```

3. Register CDC credentials (first time only):
```bash
# Create credit bureau
curl -s -u tester:tempPassword123 -X POST http://localhost:8081/credit-bureaus \
  -H "Content-Type: application/json" \
  -d '{"creditBureauName":"Circulo De Credito","country":"Mexico","isActive":true,"isAvailable":true,"registrationParamKeys":["x-api-key","private_key"]}'

# Extract private key hex
PRIVATE_KEY_HEX=$(openssl ec -in ~/Downloads/CB/pri_key.pem -noout -text 2>&1 \
  | awk '/priv:/{p=1;next} /pub:/{p=0} p{printf $0}' \
  | tr -d ' \n:' | tr 'A-F' 'a-f')

# Register credentials (replace {id} with credit bureau id from step above)
curl -s -u tester:tempPassword123 -X PUT http://localhost:8081/credit-bureaus/{id}/configuration \
  -H "Content-Type: application/json" \
  -d "{\"registrationParams\":{\"x-api-key\":\"YOUR_CONSUMER_KEY\",\"private_key\":\"$PRIVATE_KEY_HEX\"}}"
```

4. Start CB-ILD:
```bash
cd ~/path/to/cb-ild
export CB_ENC_KEY="your-key"
./gradlew bootRun
```

---

## RBAC Users (dev/sandbox)

| Username | Password | Role | Endpoints |
|---|---|---|---|
| `kyc_officer` | `password` | KYC_OFFICER | bureau-readiness, disputes |
| `credit_analyst` | `password` | CREDIT_ANALYST | submissions, disputes |
| `compliance` | `password` | COMPLIANCE | all endpoints |

---

## CDC Portal

- Developer portal: https://developer.circulodecredito.com.mx
- Sandbox API: https://services.circulodecredito.com.mx/sandbox/v1
- IP whitelist required — contact Victor Romero to add your outbound IP
- certificate.pem must be uploaded to CDC portal (one-time setup)
