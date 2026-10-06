# AGENTS.md

## Version bumping

This repo is a Home Assistant add-on. HA detects updates by comparing the `version` field in `config.yaml`.

**Before every commit:**
1. Determine the appropriate version bump based on the change:
   - `PATCH` (x.x.1) — bug fixes, config tweaks, documentation
   - `MAJOR` (2.0.0) — breaking changes
   - `MINOR` (x.1.0) — new features, non-breaking changes
2. Propose the new version to the user: "I'll bump the version from `1.0.0` to `1.0.1` (patch — reason). OK?"
3. Wait for confirmation or override before committing.
4. Include the `config.yaml` version bump in the same commit.

---

## Code structure

```
src/main/kotlin/moni/
  auth/           # JWT generation/validation (JwtAuth), login/register/refresh/password (AuthService, AuthController)
  category/       # Custom and default category CRUD
  common/         # Shared extension functions: getUserId(), getUser(), successResponse()
  config/         # Spring config: security, CORS, DynamoDB, Jackson, GlobalExceptionHandler
  currency/       # CurrencyConversionService — fetches rates from frankfurter.dev, caches with Caffeine
  dashboard/      # DashboardController/Service — summary data for month/year overview screens
  dataStore/      # Repository layer: one file per DynamoDB table (EntryRepository, UserRepository via IDataStoreClient, …)
  entry/          # Entry CRUD (EntryController, EntryService)
  health/         # GET /health liveness probe
  household/      # Household and invitation management
  models/
    internal/     # Domain models used throughout the service (Entry, User, Household, …)
    api/          # API response shapes exposed to clients (User.kt, Dashboard.kt) — never expose internal models directly
    Common.kt     # Shared enums: TransactionType, Amount
  user/           # GET/PATCH /api/user (UserController, UserService)
  verification/   # OTP generation, validation, and brute-force guard (VerificationCodeService)
```

### Controller conventions
- Controllers are thin: parse the request, call a service, return the result. No business logic.
- Extract the authenticated user with `authorization.getUserId(jwtAuth)` (id only) or `authorization.getUser(jwtAuth, dataStoreClient)` (full User object) from `moni.common`.
- Wrap all coroutine calls in `runBlocking { }` — the app uses Spring MVC (blocking), not WebFlux.
- Return `successResponse()` (from `moni.common`) for `DELETE` and other void endpoints instead of `ResponseEntity.ok(mapOf("success" to true))`.
- Request/response data classes live in the same file as their controller.

### Service conventions
- Services own business logic and DynamoDB access via repositories or `IDataStoreClient`.
- Email addresses are always normalised before use: `email.trim().lowercase()`.
- Never call `valueOf()` on `TransactionType` directly — use `TransactionType.fromStringOrUnsupported()` to handle unknown legacy values gracefully.
- Use `Necessity.fromString()` for deserialising necessity values — it accepts both new (NECESSARY/OPTIONAL) and legacy (NEED/WANT) values.

### Error handling
`GlobalExceptionHandler` maps exception types to HTTP status codes automatically:
- `IllegalArgumentException` → 400
- `NoSuchElementException` → 404
- `AuthException` → 401
- `ForbiddenException` → 403
- `MethodArgumentNotValidException` → 400 with field errors

Throw these exception types from services/controllers; do **not** construct `ResponseEntity` manually for error cases.

### Models: internal vs API
- `models/internal/` — full domain objects, may include sensitive fields (hashed password, etc.). Never return these directly from a controller.
- `models/api/` — safe client-facing shapes. Always call `.toApi()` before returning a user or sensitive object.

### Adding a new endpoint
1. Create or update a `*Controller.kt` in the relevant package.
2. Business logic goes in a `*Service.kt` in the same package.
3. If a new DynamoDB table is needed, add a `*Repository.kt` in `dataStore/`.
4. Add a data class for the request body in the controller file; add a response shape in `models/api/` if it's a new resource type.
5. Annotate request body fields with `@field:NotBlank` / `@field:Valid` as appropriate — `GlobalExceptionHandler` will turn validation failures into 400 responses automatically.

### application.properties
- Do **not** modify `application.properties` defaults for infrastructure config (`aws.url`, `aws.region`, etc.).
- All environment-specific values (LocalStack port, AWS credentials, JWT secret, CORS origins) are injected via environment variables at runtime. The defaults in the file are intentional fallbacks for CI/clean checkouts only.
