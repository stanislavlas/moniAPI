# Excel Data Import Implementation Plan

**Goal:** Import monthly financial data from `Expences.xlsx` into LocalStack DynamoDB via the REST API batch endpoint, one user at a time.

**Architecture:** Each Excel row is a monthly aggregate. Every non-zero data column becomes a separate `entry` in the app. The import script reads the sheet with `openpyxl` (`data_only=True`), resolves category names to UUIDs via `GET /api/categories`, and POSTs entries in batches of 25 to `POST /api/entries/batch`. Run the script once per user (Stanislav, then Lea) with the appropriate JWT token.

**Script location:** `personalFinanceAPI/scripts/import-excel.py`

**Excel file location:** `Expences.xlsx` (project root)

**Tech Stack:** Python 3, openpyxl, requests, LocalStack (DynamoDB), Spring Boot backend

---

## Data Overview

| Sheet | Person | Date range | Data columns | Max entries |
|-------|--------|------------|--------------|-------------|
| Stanislav | Stanislav | Jan 2023 – Aug 2026 (44 months) | C–W (21 cols) | ~924 |
| Lea | Lea | Jan 2025 – Jul 2026 (20 months) | C–Q (15 cols) | ~300 |

- **Currency:** CZK
- **Date:** 1st of each month (`YYYY-MM-01`) — no day-level data in the sheet
- **Skip:** zero and null values (no entry created); negatives are imported as-is (refunds/credits)
- **Columns X onwards** are computed sums — ignored by the script

---

## Category Mapping

### Stanislav sheet (columns C–W)

| Excel column | App category | Type | Necessity |
|---|---|---|---|
| Salary | Salary | INCOME | NEED |
| Gastro | Salary | INCOME | NEED |
| Flexi | Salary | INCOME | NEED |
| Rent | Rent | EXPENSE | NEED |
| Energie | Energy | EXPENSE | NEED |
| Elektricity | Electricity *(new)* | EXPENSE | NEED |
| Internet | Internet *(new)* | EXPENSE | NEED |
| Phone | Phone *(new)* | EXPENSE | NEED |
| Insurance | Insurance *(new)* | EXPENSE | NEED |
| Groceries | Groceries | EXPENSE | NEED |
| Household | Household *(new)* | EXPENSE | NEED |
| Transport | Transport | EXPENSE | NEED |
| Clothing | Clothing | EXPENSE | WANT |
| Multisport | Subscription | EXPENSE | WANT |
| Subscription | Subscription | EXPENSE | WANT |
| Restaurants | Restaurants *(new)* | EXPENSE | WANT |
| Alza | Alza *(new)* | EXPENSE | WANT |
| Entertainment | Entertainment *(new)* | EXPENSE | WANT |
| Other | Other | EXPENSE | WANT |
| ETF | Stocks | INVESTMENT | NEED |
| Indepedence | Savings | INVESTMENT | NEED |

### Lea sheet (columns C–Q; R onwards are computed sums, ignored)

| Excel column | App category | Type | Necessity |
|---|---|---|---|
| Salary | Salary | INCOME | NEED |
| Gastro | Salary | INCOME | NEED |
| Rent | Rent | EXPENSE | NEED |
| Phone | Phone *(new)* | EXPENSE | NEED |
| Groceries | Groceries | EXPENSE | NEED |
| Household | Household *(new)* | EXPENSE | NEED |
| Transport | Transport | EXPENSE | NEED |
| Clothing | Clothing | EXPENSE | WANT |
| Restaurants | Restaurants *(new)* | EXPENSE | WANT |
| Alza | Alza *(new)* | EXPENSE | WANT |
| Entertainment | Entertainment *(new)* | EXPENSE | WANT |
| Other | Other | EXPENSE | WANT |
| ETF | Stocks | INVESTMENT | NEED |
| Car | Car *(new)* | EXPENSE | NEED |
| Hypotéka | Mortgage *(new)* | EXPENSE | NEED |

---

## Prerequisites

- Docker running with LocalStack up (`docker-compose up -d` from `localStack/`)
- Backend running (`AWS_URL=http://localhost:4567 ./gradlew bootRun` from `personalFinanceAPI/`)
- Python 3 with `openpyxl` and `requests` installed (`pip install openpyxl requests`)

---

## Implementation Steps

### Step 1: Start LocalStack and verify tables

```bash
# From localStack/
docker-compose up -d

# Verify tables exist
docker exec myLocalstack aws dynamodb list-tables \
  --endpoint-url http://localhost:4566 --region us-west-2
```

### Step 2: Start the backend

```bash
# From personalFinanceAPI/
AWS_URL=http://localhost:4567 ./gradlew bootRun
```

### Step 3: Register both users and set up a shared household

```bash
# Register Stanislav
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"stanislav@dev.local","password":"password123","name":"Stanislav"}' \
  | python3 -m json.tool
# → save accessToken as TOKEN_STANISLAV

# Register Lea
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"lea@dev.local","password":"password123","name":"Lea"}' \
  | python3 -m json.tool
# → save accessToken as TOKEN_LEA
```

Create a household and invite Lea:

```bash
curl -s -X POST http://localhost:8080/api/households \
  -H "Authorization: Bearer $TOKEN_STANISLAV" \
  -H "Content-Type: application/json" \
  -d '{"name":"Home"}' | python3 -m json.tool
# → save householdId

curl -s -X POST http://localhost:8080/api/households/<householdId>/invite \
  -H "Authorization: Bearer $TOKEN_STANISLAV" \
  -H "Content-Type: application/json" \
  -d '{"email":"lea@dev.local"}' | python3 -m json.tool
```

### Step 4: Create the 10 custom categories

Run once per user (both share the same household so categories are shared):

```bash
TOKEN=<paste-any-token>

for PAYLOAD in \
  '{"name":"Electricity","emoji":"⚡","color":"#EFD427","type":"EXPENSE"}' \
  '{"name":"Insurance","emoji":"🛡️","color":"#6EA8D8","type":"EXPENSE"}' \
  '{"name":"Restaurants","emoji":"🍽️","color":"#E07B54","type":"EXPENSE"}' \
  '{"name":"Alza","emoji":"🛒","color":"#FF6600","type":"EXPENSE"}' \
  '{"name":"Internet","emoji":"🌐","color":"#4A90D9","type":"EXPENSE"}' \
  '{"name":"Phone","emoji":"📱","color":"#7ED321","type":"EXPENSE"}' \
  '{"name":"Household","emoji":"🏠","color":"#8B9467","type":"EXPENSE"}' \
  '{"name":"Entertainment","emoji":"🎬","color":"#9B59B6","type":"EXPENSE"}' \
  '{"name":"Mortgage","emoji":"🏡","color":"#A0522D","type":"EXPENSE"}' \
  '{"name":"Car","emoji":"🚙","color":"#708090","type":"EXPENSE"}' \
; do
  curl -s -X POST http://localhost:8080/api/categories \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD" | python3 -m json.tool
done
```

### Step 5: Run the import

Run from the project root:

```bash
# Stanislav (Jan 2023 – Aug 2026)
python3 personalFinanceAPI/scripts/import-excel.py \
  --sheet stanislav \
  --token <TOKEN_STANISLAV> \
  --file Expences.xlsx

# Lea (Jan 2025 – Jul 2026)
python3 personalFinanceAPI/scripts/import-excel.py \
  --sheet lea \
  --token <TOKEN_LEA> \
  --file Expences.xlsx
```

### Step 6: Verify

```bash
curl -s http://localhost:8080/api/entries \
  -H "Authorization: Bearer $TOKEN_STANISLAV" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('Stanislav entries:', len(d))"

curl -s http://localhost:8080/api/entries \
  -H "Authorization: Bearer $TOKEN_LEA" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('Lea entries:', len(d))"
```

---

## Data Notes

- **Currency:** CZK — all amounts are whole Czech crowns
- **Date strategy:** 1st of each month (`YYYY-MM-01`)
- **Negative values:** imported as-is (refunds, credits, returns) — zeros are still skipped
- **Two users, one household:** Stanislav and Lea share a household so entries are visible together
- **Computed sum columns ignored:** Stanislav X onwards (`Príjmy`, `Essentials`, etc.); Lea R onwards
- **Typos in month names handled:** `Nobember`, `Octóber`, `April` (without accent)
- **10 custom categories must exist before running** — script prints a warning and skips entries for any unresolved category
- **Excel `Výdaje` bug:** `Energie` and `Internet` are excluded from the Excel `Výdaje` formula, causing the Excel balance to differ from the app. This is a bug in the Excel file — the app is correct.
- **Re-running creates duplicates** — reset with `docker-compose down && docker-compose up -d` from `localStack/` if needed
