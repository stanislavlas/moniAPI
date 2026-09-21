# Split Monorepo into 3 Repos Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the current `personalFinance` monorepo into three independent Git repositories: `LocalStack`, `PersonalFinanceAPI`, and `PersonalFinanceMobile`.

**Architecture:** The monorepo's `.git` history is discarded. Each new repo gets a fresh `git init`. Files are moved (not copied) from the monorepo into their respective subdirectories. Once all three repos are initialised and pushed, the monorepo root scaffolding (old `.git`, root `README.md`, `repository.yaml`, `AGENTS.md`, `docker-compose.yml`, `docs/`, `backend/`, `mobile/`, `localstack/`, `bruno/`) is deleted, leaving only the three clean repos inside `~/Documents/personalFinance/`.

Root-level file distribution:
- `docker-compose.yml` → **dropped** (LocalStack repo gets its own)
- `bruno/` → `PersonalFinanceAPI/bruno/`
- `docs/` → `PersonalFinanceAPI/docs/`
- `AGENTS.md` → each repo gets its own tailored version
- `repository.yaml` → **dropped** (each repo is its own HA add-on repo)
- `README.md` → **dropped** (each repo has its own)

**Tech Stack:** Git, Bash, existing Kotlin/Spring Boot (Gradle), Expo/React Native, Docker/LocalStack.

---

## File Mapping

### LocalStack repo
| Source (in monorepo) | Destination |
|---|---|
| `localstack/Dockerfile` | `Dockerfile` |
| `localstack/config.yaml` | `config.yaml` |
| `localstack/build.yaml` | `build.yaml` |
| *(new)* | `init-dynamodb.sh` |
| *(new)* | `run.sh` |
| *(new)* | `docker-compose.yml` |
| *(new)* | `AGENTS.md` |
| *(new)* | `.gitignore` |
| *(new)* | `README.md` |

### PersonalFinanceAPI repo
| Source (in monorepo) | Destination |
|---|---|
| `backend/src/` | `src/` |
| `backend/build.gradle.kts` | `build.gradle.kts` |
| `backend/settings.gradle.kts` | `settings.gradle.kts` |
| `backend/gradlew` | `gradlew` |
| `backend/gradlew.bat` | `gradlew.bat` |
| `backend/gradle/` | `gradle/` |
| `backend/application.properties` | `application.properties` |
| `backend/Dockerfile` | `Dockerfile` |
| `backend/run.sh` | `run.sh` |
| `backend/config.yaml` | `config.yaml` |
| `backend/scripts/` | `scripts/` |
| `bruno/` | `bruno/` |
| `docs/` | `docs/` |
| *(new)* | `AGENTS.md` |
| *(new)* | `.gitignore` |
| *(new)* | `README.md` |

### PersonalFinanceMobile repo
| Source (in monorepo) | Destination |
|---|---|
| `mobile/src/` | `src/` |
| `mobile/app/` | `app/` |
| `mobile/assets/` | `assets/` |
| `mobile/App.jsx` | `App.jsx` |
| `mobile/index.js` | `index.js` |
| `mobile/app.json` | `app.json` |
| `mobile/babel.config.js` | `babel.config.js` |
| `mobile/package.json` | `package.json` |
| `mobile/package-lock.json` | `package-lock.json` |
| `mobile/eas.json` | `eas.json` |
| *(new)* | `AGENTS.md` |
| *(new)* | `.env.example` |
| *(new)* | `.gitignore` |
| *(new)* | `README.md` |

---

## Task 1: Create LocalStack repo

**Files:**
- Create: `LocalStack/` (inside personalFinance/)
- Create: `../LocalStack/Dockerfile`
- Create: `../LocalStack/init-dynamodb.sh`
- Create: `../LocalStack/run.sh`
- Create: `../LocalStack/config.yaml`
- Create: `../LocalStack/build.yaml`
- Create: `../LocalStack/docker-compose.yml`
- Create: `../LocalStack/.gitignore`
- Create: `../LocalStack/README.md`

- [ ] **Step 1: Create the directory and move existing files**

```bash
mkdir -p ~/Documents/personalFinance/LocalStack
mv ~/Documents/personalFinance/localstack/Dockerfile ~/Documents/personalFinance/LocalStack/
mv ~/Documents/personalFinance/localstack/config.yaml ~/Documents/personalFinance/LocalStack/
mv ~/Documents/personalFinance/localstack/build.yaml ~/Documents/personalFinance/LocalStack/
```

> `init-dynamodb.sh` and `run.sh` from the monorepo are discarded — they are written from scratch in the steps below with the idempotent pattern and prefixed table names.

- [ ] **Step 2: Create docker-compose.yml for standalone use**

Create `~/Documents/personalFinance/LocalStack/docker-compose.yml`:

```yaml
services:
  mylocalstack:
    build: .
    container_name: myLocalstack
    ports:
      - "4567:4566"
    environment:
      - SERVICES=dynamodb
      - DEFAULT_REGION=eu-central-1
      - AWS_DEFAULT_REGION=eu-central-1
      - DEBUG=1
    volumes:
      - localstack_data:/data
    networks:
      - localstack-network

volumes:
  localstack_data:

networks:
  localstack-network:
    driver: bridge
```

> Note: The container listens on 4566 internally; host port is 4567 (`4567:4566`).

- [ ] **Step 3: Create run.sh (always-run idempotent pattern)**

Create `~/Documents/personalFinance/LocalStack/run.sh`:

```bash
#!/bin/bash

echo "Starting LocalStack..."

export SERVICES=dynamodb
export DEFAULT_REGION=eu-central-1
export AWS_DEFAULT_REGION=eu-central-1
export DEBUG=1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Persist LocalStack state to /data so it survives add-on restarts
export LOCALSTACK_VOLUME_DIR=/data/localstack
mkdir -p /data/localstack

# Start LocalStack using its own entrypoint in the background
/usr/local/bin/docker-entrypoint.sh &
LOCALSTACK_PID=$!

echo "Waiting for LocalStack to be ready..."

# Poll until the DynamoDB endpoint responds
until aws --endpoint-url=http://localhost:4566 \
          --region eu-central-1 \
          dynamodb list-tables \
          --output text > /dev/null 2>&1; do
    echo "LocalStack not ready yet, retrying in 3s..."
    sleep 3
done

echo "LocalStack is up. Running idempotent table initialization..."
/init-dynamodb.sh
echo "Initialization complete."

# Keep container running
wait $LOCALSTACK_PID
```

```bash
chmod +x ~/Documents/personalFinance/LocalStack/run.sh
```

- [ ] **Step 4: Create init-dynamodb.sh (idempotent, prefixed table names)**

Each `create-table` call is wrapped with a `describe-table` check — safe to re-run on every restart. New tables added in future will be created automatically; existing ones are skipped.

Create `~/Documents/personalFinance/LocalStack/init-dynamodb.sh`:

```bash
#!/bin/bash

echo "Initializing DynamoDB tables..."

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
ENDPOINT="http://localhost:4566"
REGION="eu-central-1"

create_table_if_not_exists() {
  local TABLE_NAME=$1
  shift
  local TABLE_ARGS=("$@")

  RESULT=$(aws --endpoint-url=$ENDPOINT --region $REGION \
    dynamodb describe-table --table-name "$TABLE_NAME" 2>&1)

  if echo "$RESULT" | grep -q "ResourceNotFoundException"; then
    echo "Creating table: $TABLE_NAME"
    aws --endpoint-url=$ENDPOINT --region $REGION \
      dynamodb create-table --table-name "$TABLE_NAME" "${TABLE_ARGS[@]}"
    echo "Created: $TABLE_NAME"
  else
    echo "Table already exists, skipping: $TABLE_NAME"
  fi
}

# personalFinance_users
create_table_if_not_exists "personalFinance_users" \
  --key-schema AttributeName=userId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=userId,AttributeType=S \
    AttributeName=email,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "email-index",
      "KeySchema": [{"AttributeName": "email", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST

# personalFinance_refresh_tokens
create_table_if_not_exists "personalFinance_refresh_tokens" \
  --key-schema AttributeName=tokenId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=tokenId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "userId-index",
      "KeySchema": [{"AttributeName": "userId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST

# personalFinance_categories
create_table_if_not_exists "personalFinance_categories" \
  --key-schema AttributeName=categoryId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=categoryId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
    AttributeName=householdId,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "userId-index",
      "KeySchema": [{"AttributeName": "userId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    },
    {
      "IndexName": "householdId-index",
      "KeySchema": [{"AttributeName": "householdId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST

# personalFinance_households
create_table_if_not_exists "personalFinance_households" \
  --key-schema AttributeName=householdId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=householdId,AttributeType=S \
    AttributeName=ownerId,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "ownerId-index",
      "KeySchema": [{"AttributeName": "ownerId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST

# personalFinance_entries
create_table_if_not_exists "personalFinance_entries" \
  --key-schema AttributeName=entryId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=entryId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
    AttributeName=householdId,AttributeType=S \
    AttributeName=date,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "userId-date-index",
      "KeySchema": [
        {"AttributeName": "userId", "KeyType": "HASH"},
        {"AttributeName": "date", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    },
    {
      "IndexName": "householdId-date-index",
      "KeySchema": [
        {"AttributeName": "householdId", "KeyType": "HASH"},
        {"AttributeName": "date", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST

echo "All tables initialized."
aws --endpoint-url=$ENDPOINT --region $REGION dynamodb list-tables
```

```bash
chmod +x ~/Documents/personalFinance/LocalStack/init-dynamodb.sh
```

- [ ] **Step 5: Create .gitignore**

Create `~/Documents/personalFinance/LocalStack/.gitignore`:

```
# OS
.DS_Store
Thumbs.db

# Docker volumes (local data)
localstack_data/
.docker/

# Logs
*.log
logs/

# IDE
.idea/
.vscode/
```

- [ ] **Step 6: Create README.md**

Create `~/Documents/personalFinance/LocalStack/README.md`:

```markdown
# LocalStack

General-purpose LocalStack add-on providing a local DynamoDB instance.
Shared across multiple apps — each app uses its own table prefix to avoid naming collisions.
Designed to run as a Home Assistant add-on or standalone via Docker Compose.

## Table naming convention

Tables are prefixed by app name: `<appName>_<tableName>`.

| App | Tables |
|---|---|
| personalFinance | `personalFinance_users`, `personalFinance_refresh_tokens`, `personalFinance_categories`, `personalFinance_households`, `personalFinance_entries` |

## Adding tables for a new app

1. Add `create_table_if_not_exists` calls to `init-dynamodb.sh` using your app's prefix
2. Push the change
3. In HA: update the add-on → restart
4. Existing tables are skipped (idempotent); new tables are created

## Standalone (Docker Compose)

```bash
docker-compose up -d
```

DynamoDB endpoint (from host): `http://localhost:4567`

Verify tables:
```bash
docker exec myLocalstack aws dynamodb list-tables \
  --endpoint-url http://localhost:4566 \
  --region eu-central-1
```

> Reach LocalStack from the **host** on port **4567**. Inside the container the AWS CLI targets `localhost:4566`.

Reset (destroys all data):
```bash
docker-compose down -v && docker-compose up -d
```

## Home Assistant add-on

Add this repository URL to HA add-on store, then install **LocalStack**.
Data is persisted to `/data/localstack` and survives restarts.

Port exposed: `4566/tcp` (DynamoDB endpoint).

## WSL2 line-ending fix

If the init script fails on WSL2:
```bash
dos2unix init-dynamodb.sh run.sh
docker-compose restart myLocalstack
```
```

- [ ] **Step 7: Initialise git repo**

```bash
cd ~/Documents/personalFinance/LocalStack
git init
git add .
git commit -m "feat: initial LocalStack add-on repo — idempotent init, personalFinance_ table prefix"
```

- [ ] **Step 8: Verify**

```bash
cd ~/Documents/personalFinance/LocalStack
ls -la
git log --oneline
```

Expected: all files present, one commit.

---

## Task 2: Create PersonalFinanceAPI repo

**Files:**
- Create: `PersonalFinanceAPI/` (inside personalFinance/)
- Move all backend source, Gradle wrapper, Dockerfile, scripts, bruno collection, and docs
- Create: `PersonalFinanceAPI/.gitignore`
- Create: `PersonalFinanceAPI/AGENTS.md`
- Create: `PersonalFinanceAPI/README.md`

- [ ] **Step 1: Create the directory and move backend files**

```bash
mkdir -p ~/Documents/personalFinance/PersonalFinanceAPI
# Source code and build files
mv ~/Documents/personalFinance/backend/src ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/build.gradle.kts ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/settings.gradle.kts ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/gradlew ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/gradlew.bat ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/gradle ~/Documents/personalFinance/PersonalFinanceAPI/
# Config and Docker
mv ~/Documents/personalFinance/backend/application.properties ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/Dockerfile ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/run.sh ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/backend/config.yaml ~/Documents/personalFinance/PersonalFinanceAPI/
# Scripts
mv ~/Documents/personalFinance/backend/scripts ~/Documents/personalFinance/PersonalFinanceAPI/
# Bruno API collection and docs (from monorepo root)
mv ~/Documents/personalFinance/bruno ~/Documents/personalFinance/PersonalFinanceAPI/
mv ~/Documents/personalFinance/docs ~/Documents/personalFinance/PersonalFinanceAPI/
```

- [ ] **Step 2: Make gradlew executable**

```bash
chmod +x ~/Documents/personalFinance/PersonalFinanceAPI/gradlew
```

- [ ] **Step 3: Create .gitignore**

Create `~/Documents/personalFinance/PersonalFinanceAPI/.gitignore`:

```
# Gradle
.gradle/
build/
out/
bin/
.apt_generated
.classpath
.factorypath
.project
.settings
.springBeans
.sts4-cache
.kotlin
!gradle/wrapper/gradle-wrapper.jar
!**/src/main/**/build/
!**/src/test/**/build/
!**/src/main/**/bin/
!**/src/test/**/bin/
!**/src/main/**/out/
!**/src/test/**/out/

# NetBeans
nbproject/private/
nbbuild/
dist/
nbdist/
.nb-gradle/

# IDE
.idea/
.vscode/
*.iml
*.iws
*.ipr

# OS
.DS_Store
Thumbs.db

# Logs
*.log
logs/

# Docker
.docker/
```

- [ ] **Step 4: Create README.md**

Create `~/Documents/personalFinance/PersonalFinanceAPI/README.md`:

```markdown
# PersonalFinanceAPI

Kotlin 1.9.25 / Spring Boot 3.3.4 REST API for the Personal Finance app.
Designed to run as a Home Assistant add-on or standalone.

## Tech Stack

- Kotlin 1.9.25 / Java 21
- Spring Boot 3.3.4
- DynamoDB via AWS SDK for Kotlin
- JWT authentication (jjwt 0.11.5)
- BCrypt password hashing

## Quick Start

### Prerequisites

- Java 21
- LocalStack running on `http://localhost:4567` (host port) — see [LocalStack](https://github.com/stanislavlas/LocalStack)

### Run locally

```bash
AWS_URL=http://localhost:4567 ./gradlew bootRun
```

Backend runs on `http://localhost:8080`.

### Build

```bash
./gradlew build
```

### Run tests

```bash
./gradlew test
```

### Production JAR

```bash
./gradlew bootJar
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

## Configuration

Edit `application.properties` or set environment variables:

| Property | Env var | Default | Description |
|---|---|---|---|
| `jwt.secret` | `JWT_SECRET` | `change-me-default` | JWT signing secret (min 32 chars) |
| `aws.url` | `AWS_URL` | `http://localhost:4566` | DynamoDB endpoint (use `http://localhost:4567` for LocalStack docker-compose) |
| `aws.region` | — | `eu-central-1` | AWS region |
| `aws.accessKeyId` | `AWS_ACCESS_KEY_ID` | `dummy` | AWS key (any value for LocalStack) |
| `aws.secretAccessKey` | `AWS_SECRET_ACCESS_KEY` | `dummy` | AWS secret (any value for LocalStack) |

> **Note:** For local dev with docker-compose from the LocalStack repo, LocalStack host port is 4567.

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | /api/auth/create | Register |
| POST | /api/auth/login | Login |
| POST | /api/auth/refresh | Refresh token |
| POST | /api/auth/logout | Revoke refresh token |
| GET | /api/categories | List categories |
| POST | /api/categories | Create category |
| DELETE | /api/categories/:id | Delete category |
| GET | /api/entries | List entries |
| POST | /api/entries | Create entry |
| PUT | /api/entries/:id | Update entry |
| DELETE | /api/entries/:id | Delete entry |
| GET | /api/dashboard | Analytics |
| POST | /api/households | Create household |
| GET | /api/households/:id | Get household |
| POST | /api/households/:id/members | Add member |
| DELETE | /api/households/:id/members/:uid | Remove member |

All protected endpoints require `Authorization: Bearer <accessToken>`.

## Bruno API Collection

`/bruno` folder — open in [Bruno](https://www.usebruno.com/), select `Local` environment.
Run `Auth > Register` first; tokens are auto-saved.

## Category Seeding

Default categories are auto-seeded on first `GET /api/categories`.
For existing users:
```bash
cd scripts
./seed-categories.sh <userId-uuid>
```

## Home Assistant Add-on

Install **Personal Finance - Backend** from the add-on store.
Set `jwt_secret` in the Configuration tab (generate with `openssl rand -base64 32`).
Start LocalStack add-on first.

## Package Structure

```
src/main/kotlin/personalFinance/
├── auth/          # JWT auth, login, register, refresh tokens
├── category/      # Category CRUD + default seeding
├── entry/         # Transaction CRUD
├── household/     # Household management, invite codes
├── dashboard/     # Analytics aggregations
├── dataStore/     # DynamoDB repositories
├── config/        # Security, CORS, exception handling
├── models/        # Domain models + API DTOs
├── user/          # User profile
├── currency/      # Currency conversion
└── health/        # Health check endpoint
```
```

- [ ] **Step 5: Initialise git repo**

```bash
cd ~/Documents/personalFinance/PersonalFinanceAPI
git init
git add .
git commit -m "feat: initial API repo split from personalFinance monorepo"
```

- [ ] **Step 6: Verify build compiles**

```bash
cd ~/Documents/personalFinance/PersonalFinanceAPI
./gradlew build --no-daemon
```

Expected: `BUILD SUCCESSFUL`

---

## Task 3: Create PersonalFinanceMobile repo

**Files:**
- Create: `PersonalFinanceMobile/` (inside personalFinance/)
- Move all mobile source, assets, config
- Create: `PersonalFinanceMobile/.env.example`
- Create: `PersonalFinanceMobile/.gitignore`
- Create: `PersonalFinanceMobile/AGENTS.md`
- Create: `PersonalFinanceMobile/README.md`

- [ ] **Step 1: Create the directory and move mobile files**

```bash
mkdir -p ~/Documents/personalFinance/PersonalFinanceMobile
mv ~/Documents/personalFinance/mobile/src ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/app ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/assets ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/App.jsx ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/index.js ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/app.json ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/babel.config.js ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/package.json ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/package-lock.json ~/Documents/personalFinance/PersonalFinanceMobile/
mv ~/Documents/personalFinance/mobile/eas.json ~/Documents/personalFinance/PersonalFinanceMobile/
```

> Do NOT move `node_modules/`, `.expo/`, `dist/`, or `.env` / `.env.local` — these are either gitignored or machine-specific.

- [ ] **Step 2: Create .env.example**

Create `~/Documents/personalFinance/PersonalFinanceMobile/.env.example`:

```
# Copy this file to .env and fill in your backend IP
# Never use "localhost" - it refers to the phone, not your computer
EXPO_PUBLIC_API_BASE_URL=http://192.168.1.100:8080
```

- [ ] **Step 3: Create .gitignore**

Create `~/Documents/personalFinance/PersonalFinanceMobile/.gitignore`:

```
# Dependencies
node_modules/

# Expo
.expo/
dist/
web-build/
.metro-health-check*

# Native keys / certs
*.orig.*
*.jks
*.p8
*.p12
*.key
*.mobileprovision

# Environment files (contain local IP addresses)
.env
.env.local
.env.*.local

# OS
.DS_Store
Thumbs.db

# IDE
.idea/
.vscode/
*.iml

# Logs
*.log
logs/
npm-debug.log*
yarn-debug.log*
yarn-error.log*
```

- [ ] **Step 4: Create README.md**

Create `~/Documents/personalFinance/PersonalFinanceMobile/README.md`:

```markdown
# PersonalFinanceMobile

Expo SDK ~57 / React Native 0.86 mobile app for the Personal Finance tracker.

## Tech Stack

- Expo SDK ~57
- React Native 0.86.3
- React 19.2.3
- React Navigation v7
- AsyncStorage for offline caching
- Expo SecureStore for token storage
- Expo Local Authentication (biometrics)

## Quick Start

### Prerequisites

- Node.js 18+
- Expo Go app on your phone (Android or iOS)
- [PersonalFinanceAPI](https://github.com/stanislavlas/PersonalFinanceAPI) running on your local machine
- Phone and computer on the same WiFi network

### Setup

```bash
npm install
```

Copy `.env.example` to `.env` and set your machine's LAN IP:
```bash
cp .env.example .env
# Edit .env:
# EXPO_PUBLIC_API_BASE_URL=http://<YOUR_MACHINE_IP>:8080
```

Finding your IP:
- **macOS:** `ipconfig getifaddr en0`
- **Linux:** `hostname -I | awk '{print $1}'`
- **Windows:** `ipconfig` (IPv4 Address)

### Run

```bash
npx expo start
```

Scan the QR code with Expo Go.

### Android emulator

```bash
npx expo start --android
```

Use `http://10.0.2.2:8080` as the API URL when running in an Android emulator (maps to host `localhost`).

### Clear Metro cache

```bash
npx expo start --clear
```

## Build (EAS)

```bash
npm install -g eas-cli
eas login
eas build --platform android --profile preview
```

## Screens

| Screen | File |
|---|---|
| Login / Register | `app/screens/AuthScreen.jsx` |
| Dashboard | `app/screens/DashboardScreen.jsx` |
| Add Entry | `app/screens/AddScreen.jsx` |
| History | `app/screens/HistoryScreen.jsx` |
| Categories | `app/screens/CategoriesScreen.jsx` |
| Household | `app/screens/HouseholdScreen.jsx` |
| Account | `app/screens/AccountScreen.jsx` |

## Project Structure

```
├── App.jsx               # Root — auth gate, navigation
├── index.js              # Entry point
├── app/screens/          # Screen components
├── src/
│   ├── hooks/            # React hooks (auth, entries, household, categories)
│   ├── services/         # API clients
│   ├── utils/            # Theme, default categories
│   └── components/       # Shared UI components
└── assets/               # Images, fonts
```

## Environment Variables

| Variable | Description |
|---|---|
| `EXPO_PUBLIC_API_BASE_URL` | Base URL of PersonalFinanceAPI (e.g. `http://192.168.1.100:8080`) |

Never use `localhost` — on a physical device it refers to the phone itself, not your computer.
```

- [ ] **Step 5: Initialise git repo**

```bash
cd ~/Documents/personalFinance/PersonalFinanceMobile
git init
git add .
git commit -m "feat: initial mobile repo split from personalFinance monorepo"
```

- [ ] **Step 6: Verify**

```bash
cd ~/Documents/personalFinance/PersonalFinanceMobile
npm install
npx expo export --platform android --output-dir /tmp/expo-verify-build 2>&1 | tail -5
```

Expected: no errors about missing source files. (A full EAS build is not required for verification.)

---

## Task 4: Push, verify, and delete monorepo scaffolding

- [ ] **Step 1: Confirm all three repos exist with commits**

```bash
for repo in LocalStack PersonalFinanceAPI PersonalFinanceMobile; do
  echo "=== $repo ==="
  git -C ~/Documents/personalFinance/$repo log --oneline
  echo ""
done
```

Expected: one commit in each repo.

- [ ] **Step 2: Push all three repos to GitHub**

```bash
# LocalStack
cd ~/Documents/personalFinance/LocalStack
git remote add origin git@github.com:stanislavlas/LocalStack.git
git push -u origin main

# PersonalFinanceAPI
cd ~/Documents/personalFinance/PersonalFinanceAPI
git remote add origin git@github.com:stanislavlas/PersonalFinanceAPI.git
git push -u origin main

# PersonalFinanceMobile
cd ~/Documents/personalFinance/PersonalFinanceMobile
git remote add origin git@github.com:stanislavlas/PersonalFinanceMobile.git
git push -u origin main
```

- [ ] **Step 3: Confirm all three repos are visible on GitHub**

Open in browser and verify files are present:
- https://github.com/stanislavlas/LocalStack
- https://github.com/stanislavlas/PersonalFinanceAPI
- https://github.com/stanislavlas/PersonalFinanceMobile

- [ ] **Step 4: Delete monorepo scaffolding**

At this point all files have been moved out. Delete what remains of the old monorepo structure:

```bash
cd ~/Documents/personalFinance

# Remove old source directories (now empty or moved)
rm -rf backend/ mobile/ localstack/

# Remove monorepo root files
rm -f docker-compose.yml repository.yaml README.md AGENTS.md .gitattributes
rm -f "Expences.xlsx" "~\$Expences.xlsx" PLAN-excel-import.md

# Remove the monorepo git history
rm -rf .git/

# Remove IDE files
rm -rf .idea/ .claude/
```

- [ ] **Step 5: Verify final structure**

```bash
ls ~/Documents/personalFinance/
```

Expected output:
```
LocalStack/
PersonalFinanceAPI/
PersonalFinanceMobile/
```

- [ ] **Step 6: Note on HA add-on store**

Each repo (`LocalStack`, `PersonalFinanceAPI`) is now its own standalone HA add-on repository. In HA add-on store, add each URL separately:
- `https://github.com/stanislavlas/LocalStack`
- `https://github.com/stanislavlas/PersonalFinanceAPI`
