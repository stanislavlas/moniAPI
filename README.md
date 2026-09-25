# MoniAPI

Kotlin 1.9.25 / Spring Boot 3.3.4 REST API for the Moni app.
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
- LocalStack running — see [LocalStack](https://github.com/stanislavlas/localStack)

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

Install **Moni - Backend** from the add-on store.
Set `jwt_secret` in the Configuration tab (generate with `openssl rand -base64 32`).
Start LocalStack add-on first.

## Package Structure

```
src/main/kotlin/moni/
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
