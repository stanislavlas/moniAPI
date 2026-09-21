# Notifications Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users configure a recurring local push notification that reminds them to log expenses, with frequency (daily/weekly/monthly/N days) and time-of-day stored in the backend user record and applied on the device.

**Architecture:** Notification preferences (`notificationsEnabled`, `notificationFrequency`, `notificationTime`) are added to the backend `User` model and synced to the mobile via the existing `PATCH /api/user` endpoint. The mobile app reads these preferences on startup and after any update, then schedules (or cancels) a repeating local notification using `expo-notifications`. No push server is needed — all scheduling happens on-device.

**Tech Stack:** Kotlin / Spring Boot (backend), `expo-notifications` (mobile), `expo-local-authentication` already installed, `AsyncStorage` for local cache, Android only.

---

## File Map

### Backend — modified files
| File | Change |
|------|--------|
| `backend/src/main/kotlin/personalFinance/models/internal/User.kt` | Add `notificationsEnabled`, `notificationFrequency`, `notificationTime` fields |
| `backend/src/main/kotlin/personalFinance/models/api/User.kt` | Expose new fields in the API response |
| `backend/src/main/kotlin/personalFinance/user/UserController.kt` | Accept new fields in `UpdateUserRequest` |
| `backend/src/main/kotlin/personalFinance/user/UserService.kt` | Pass new fields through to `updateUser` |
| `backend/src/main/kotlin/personalFinance/dataStore/IDataStoreClient.kt` | Add `notificationsEnabled`, `notificationFrequency`, `notificationTime` to `updateUser` signature |
| `backend/src/main/kotlin/personalFinance/dataStore/DynamoClient.kt` | Persist new fields in `updateUser` |

### Backend — new files
| File | Purpose |
|------|---------|
| `backend/src/test/kotlin/personalFinance/user/UserServiceTest.kt` | Unit tests for notification preference update logic |

### Mobile — new files
| File | Purpose |
|------|---------|
| `mobile/src/services/notifications.js` | Schedule / cancel local notifications; read/write prefs from AsyncStorage |

### Mobile — modified files
| File | Change |
|------|--------|
| `mobile/package.json` | Add `expo-notifications` dependency |
| `mobile/app.json` | Register `expo-notifications` plugin |
| `mobile/src/services/auth.js` | `updateProfile` already handles arbitrary patch — no changes needed |
| `mobile/app/screens/AccountScreen.jsx` | Add Notifications settings card in the Settings section |
| `mobile/App.jsx` | Call `applyNotificationPreferences(user)` after login and after profile update |

---

## Task 1: Install expo-notifications

**Files:**
- Modify: `mobile/package.json`
- Modify: `mobile/app.json`

- [ ] **Step 1: Install the package**

```bash
cd mobile && npx expo install expo-notifications
```

Expected: `expo-notifications` added to `dependencies` in `package.json`.

- [ ] **Step 2: Register the plugin in app.json**

In `mobile/app.json`, add `"expo-notifications"` to the `"plugins"` array:

```json
"plugins": [
  "expo-asset",
  "expo-secure-store",
  "expo-status-bar",
  "expo-notifications",
  [
    "expo-splash-screen",
    {
      "image": "./assets/splash.png",
      "resizeMode": "contain",
      "backgroundColor": "#1D9E75"
    }
  ]
]
```

- [ ] **Step 3: Verify no build error**

```bash
cd mobile && npx expo start --clear 2>&1 | head -20
```

Expected: Metro starts without `expo-notifications not found` errors. Press `Ctrl+C` to stop.

- [ ] **Step 4: Commit**

```bash
cd mobile && git add package.json package-lock.json app.json
git commit -m "chore: install expo-notifications"
```

---

## Task 2: Backend — extend User model with notification preferences

**Files:**
- Modify: `backend/src/main/kotlin/personalFinance/models/internal/User.kt`
- Modify: `backend/src/main/kotlin/personalFinance/models/api/User.kt`

- [ ] **Step 1: Add fields to internal User**

Replace the body of `backend/src/main/kotlin/personalFinance/models/internal/User.kt`:

```kotlin
package personalFinance.models.internal

import personalFinance.models.Currency
import personalFinance.models.api.User
import java.util.UUID

data class User(
    val currency: Currency,
    val email: String,
    val name: String,
    val password: String,
    val userId: UUID,
    val householdId: UUID? = null,
    val householdRole: MemberRole? = null,
    val emailVerified: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notificationFrequency: String = "daily",   // "daily" | "weekly" | "monthly" | "custom"
    val notificationCustomDays: Int = 1,            // used when frequency == "custom"
    val notificationTime: String = "20:00",         // "HH:mm" in local device time
) {
    fun toApi() = User(
        currency = this.currency,
        email = this.email,
        name = this.name,
        userId = this.userId,
        notificationsEnabled = this.notificationsEnabled,
        notificationFrequency = this.notificationFrequency,
        notificationCustomDays = this.notificationCustomDays,
        notificationTime = this.notificationTime,
    )
}
```

- [ ] **Step 2: Add fields to API User**

Replace `backend/src/main/kotlin/personalFinance/models/api/User.kt`:

```kotlin
package personalFinance.models.api

import personalFinance.models.Currency
import java.util.*

data class AuthUserResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: User,
)

data class RegistrationPendingResponse(
    val pendingVerification: Boolean = true,
    val userId: UUID,
    val message: String,
)

data class User(
    val currency: Currency,
    val email: String,
    val name: String,
    val userId: UUID,
    val notificationsEnabled: Boolean = false,
    val notificationFrequency: String = "daily",
    val notificationCustomDays: Int = 1,
    val notificationTime: String = "20:00",
)
```

- [ ] **Step 3: Build to verify compilation**

```bash
cd backend && ./gradlew build -x test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd backend && git add src/main/kotlin/personalFinance/models/
git commit -m "feat(backend): add notification preference fields to User model"
```

---

## Task 3: Backend — wire preferences through controller / service / datastore

**Files:**
- Modify: `backend/src/main/kotlin/personalFinance/user/UserController.kt`
- Modify: `backend/src/main/kotlin/personalFinance/user/UserService.kt`
- Modify: `backend/src/main/kotlin/personalFinance/dataStore/IDataStoreClient.kt`
- Modify: `backend/src/main/kotlin/personalFinance/dataStore/DynamoClient.kt`

- [ ] **Step 1: Update UpdateUserRequest and controller**

Replace `backend/src/main/kotlin/personalFinance/user/UserController.kt`:

```kotlin
package personalFinance.user

import org.springframework.web.bind.annotation.*
import personalFinance.auth.JwtAuth
import personalFinance.common.getUserId
import personalFinance.models.api.User

@RestController
@RequestMapping("/api/user")
class UserController(
    private val jwtAuth: JwtAuth,
    private val userService: UserService,
) {

    @GetMapping("")
    fun getUser(@RequestHeader("Authorization") authHeader: String): User {
        return userService.getUser(authHeader.getUserId(jwtAuth)).toApi()
    }

    @PatchMapping("")
    fun updateUser(
        @RequestHeader("Authorization") authHeader: String,
        @RequestBody request: UpdateUserRequest,
    ): User {
        return userService.updateUser(
            userId                  = authHeader.getUserId(jwtAuth),
            name                    = request.name,
            currency                = request.currency,
            email                   = request.email,
            currentPassword         = request.currentPassword,
            notificationsEnabled    = request.notificationsEnabled,
            notificationFrequency   = request.notificationFrequency,
            notificationCustomDays  = request.notificationCustomDays,
            notificationTime        = request.notificationTime,
        ).toApi()
    }
}

data class UpdateUserRequest(
    val name: String? = null,
    val currency: String? = null,
    val email: String? = null,
    val currentPassword: String? = null,
    val notificationsEnabled: Boolean? = null,
    val notificationFrequency: String? = null,
    val notificationCustomDays: Int? = null,
    val notificationTime: String? = null,
)
```

- [ ] **Step 2: Update UserService**

Replace `backend/src/main/kotlin/personalFinance/user/UserService.kt`:

```kotlin
package personalFinance.user

import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Lazy
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import personalFinance.currency.CurrencyConversionService
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.internal.User
import java.util.*

@Service
class UserService(
    private val dataStoreClient: IDataStoreClient,
    private val currencyConversionService: CurrencyConversionService,
    @Lazy private val passwordEncoder: PasswordEncoder,
) {

    fun getUser(userId: UUID): User {
        return runBlocking { dataStoreClient.getUserById(userId = userId) }
    }

    fun updateUser(
        userId: UUID,
        name: String? = null,
        currency: String? = null,
        email: String? = null,
        currentPassword: String? = null,
        notificationsEnabled: Boolean? = null,
        notificationFrequency: String? = null,
        notificationCustomDays: Int? = null,
        notificationTime: String? = null,
    ): User {
        if (currency != null && !currencyConversionService.isValidCurrency(currency)) {
            throw IllegalArgumentException("Unknown currency code: $currency")
        }

        if (notificationFrequency != null) {
            val valid = setOf("daily", "weekly", "monthly", "custom")
            if (notificationFrequency !in valid) {
                throw IllegalArgumentException("Invalid notificationFrequency: $notificationFrequency")
            }
        }

        if (notificationTime != null) {
            val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (!timeRegex.matches(notificationTime)) {
                throw IllegalArgumentException("notificationTime must be HH:mm format")
            }
        }

        if (email != null) {
            if (currentPassword == null) {
                throw IllegalArgumentException("Current password is required to change email")
            }
            val user = runBlocking { dataStoreClient.getUserById(userId) }
            if (!passwordEncoder.matches(currentPassword, user.password)) {
                throw IllegalArgumentException("Incorrect password")
            }
            val normalizedEmail = email.trim().lowercase()
            val existing = runBlocking { dataStoreClient.getUserByEmail(normalizedEmail) }
            if (existing != null && existing.userId != userId) {
                throw IllegalArgumentException("Email address is already in use")
            }
            return runBlocking {
                dataStoreClient.updateUser(
                    userId, name, currency, normalizedEmail,
                    notificationsEnabled, notificationFrequency, notificationCustomDays, notificationTime,
                )
            }
        }

        return runBlocking {
            dataStoreClient.updateUser(
                userId, name, currency, null,
                notificationsEnabled, notificationFrequency, notificationCustomDays, notificationTime,
            )
        }
    }
}
```

- [ ] **Step 3: Update IDataStoreClient**

Replace `backend/src/main/kotlin/personalFinance/dataStore/IDataStoreClient.kt`:

```kotlin
package personalFinance.dataStore

import personalFinance.models.internal.User
import java.util.*

interface IDataStoreClient {
    suspend fun putUser(user: User)
    suspend fun getUserByEmail(email: String): User?
    suspend fun getUserById(userId: UUID): User
    suspend fun deleteUser(userId: UUID)
    suspend fun updateUser(
        userId: UUID,
        name: String? = null,
        currency: String? = null,
        email: String? = null,
        notificationsEnabled: Boolean? = null,
        notificationFrequency: String? = null,
        notificationCustomDays: Int? = null,
        notificationTime: String? = null,
    ): User
    suspend fun updateUserPassword(userId: UUID, encodedPassword: String): User
}
```

- [ ] **Step 4: Update DynamoClient**

Replace the `updateUser` function in `backend/src/main/kotlin/personalFinance/dataStore/DynamoClient.kt`:

```kotlin
override suspend fun updateUser(
    userId: UUID,
    name: String?,
    currency: String?,
    email: String?,
    notificationsEnabled: Boolean?,
    notificationFrequency: String?,
    notificationCustomDays: Int?,
    notificationTime: String?,
): personalFinance.models.internal.User {
    val existing = getUserById(userId)
    val updated = existing.copy(
        name                   = name                   ?: existing.name,
        currency               = currency               ?: existing.currency,
        email                  = email                  ?: existing.email,
        notificationsEnabled   = notificationsEnabled   ?: existing.notificationsEnabled,
        notificationFrequency  = notificationFrequency  ?: existing.notificationFrequency,
        notificationCustomDays = notificationCustomDays ?: existing.notificationCustomDays,
        notificationTime       = notificationTime       ?: existing.notificationTime,
    )
    putUser(updated)
    return updated
}
```

- [ ] **Step 5: Build and run tests**

```bash
cd backend && ./gradlew build 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd backend && git add src/
git commit -m "feat(backend): wire notification preferences through controller/service/datastore"
```

---

## Task 4: Backend — unit tests for UserService notification validation

**Files:**
- Create: `backend/src/test/kotlin/personalFinance/user/UserServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/kotlin/personalFinance/user/UserServiceTest.kt`:

```kotlin
package personalFinance.user

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import personalFinance.currency.CurrencyConversionService
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.internal.User
import java.util.*

class UserServiceTest {

    private val dataStoreClient           = mockk<IDataStoreClient>()
    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val passwordEncoder           = mockk<PasswordEncoder>()

    private val service = UserService(dataStoreClient, currencyConversionService, passwordEncoder)

    private val userId = UUID.randomUUID()

    private fun baseUser() = User(
        userId   = userId,
        name     = "Alice",
        email    = "alice@example.com",
        password = "hashed",
        currency = "EUR",
    )

    @Test
    fun `updateUser saves valid notification preferences`() {
        coEvery { dataStoreClient.getUserById(userId) } returns baseUser()
        coEvery {
            dataStoreClient.updateUser(userId, null, null, null, true, "daily", null, "08:30")
        } returns baseUser().copy(notificationsEnabled = true, notificationFrequency = "daily", notificationTime = "08:30")

        val result = service.updateUser(
            userId               = userId,
            notificationsEnabled = true,
            notificationFrequency = "daily",
            notificationTime     = "08:30",
        )

        assertEquals(true, result.notificationsEnabled)
        assertEquals("daily", result.notificationFrequency)
        assertEquals("08:30", result.notificationTime)
    }

    @Test
    fun `updateUser rejects invalid frequency`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationFrequency = "hourly")
        }
        assertTrue(ex.message!!.contains("Invalid notificationFrequency"))
    }

    @Test
    fun `updateUser rejects malformed notificationTime`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationTime = "25:00")
        }
        assertTrue(ex.message!!.contains("HH:mm"))
    }

    @Test
    fun `updateUser rejects notificationTime without colon`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationTime = "2000")
        }
        assertTrue(ex.message!!.contains("HH:mm"))
    }

    @Test
    fun `updateUser accepts custom frequency with customDays`() {
        coEvery { dataStoreClient.getUserById(userId) } returns baseUser()
        coEvery {
            dataStoreClient.updateUser(userId, null, null, null, true, "custom", 3, "20:00")
        } returns baseUser().copy(notificationsEnabled = true, notificationFrequency = "custom", notificationCustomDays = 3)

        val result = service.updateUser(
            userId                  = userId,
            notificationsEnabled    = true,
            notificationFrequency   = "custom",
            notificationCustomDays  = 3,
            notificationTime        = "20:00",
        )

        assertEquals("custom", result.notificationFrequency)
        assertEquals(3, result.notificationCustomDays)
    }
}
```

- [ ] **Step 2: Run tests — expect them to pass (logic already implemented)**

```bash
cd backend && ./gradlew test --tests "personalFinance.user.UserServiceTest" 2>&1 | tail -20
```

Expected: `5 tests completed, 0 failed`.

- [ ] **Step 3: Commit**

```bash
cd backend && git add src/test/
git commit -m "test(backend): UserService notification preference validation"
```

---

## Task 5: Mobile — notifications service

**Files:**
- Create: `mobile/src/services/notifications.js`

This service handles:
1. Requesting Android notification permission
2. Scheduling (or cancelling) a repeating local notification based on user prefs
3. Persisting the last-applied prefs to AsyncStorage so we can skip redundant reschedules

- [ ] **Step 1: Create the service**

Create `mobile/src/services/notifications.js`:

```javascript
import * as Notifications from "expo-notifications";
import AsyncStorage from "@react-native-async-storage/async-storage";

const KEY_NOTIF_PREFS = "budget_notification_prefs";
const NOTIF_IDENTIFIER = "budget_expense_reminder";

// Configure how notifications appear when the app is in the foreground
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

/**
 * Request Android notification permission.
 * Returns true if granted, false otherwise.
 */
export async function requestNotificationPermission() {
  const { status } = await Notifications.requestPermissionsAsync();
  return status === "granted";
}

/**
 * Cancel all scheduled expense-reminder notifications.
 */
export async function cancelNotifications() {
  await Notifications.cancelAllScheduledNotificationsAsync();
  await AsyncStorage.removeItem(KEY_NOTIF_PREFS);
}

/**
 * Compute the trigger config for expo-notifications from user prefs.
 *
 * @param {string} time      - "HH:mm"
 * @param {string} frequency - "daily" | "weekly" | "monthly" | "custom"
 * @param {number} customDays
 * @returns {object} trigger object for scheduleNotificationAsync
 */
function buildTrigger(time, frequency, customDays) {
  const [hour, minute] = time.split(":").map(Number);

  if (frequency === "daily") {
    return { hour, minute, repeats: true };
  }

  if (frequency === "weekly") {
    // Fire every 7 days — use seconds-based interval (7 days in seconds)
    return { seconds: 7 * 24 * 60 * 60, repeats: true };
  }

  if (frequency === "monthly") {
    // Approximately 30 days
    return { seconds: 30 * 24 * 60 * 60, repeats: true };
  }

  if (frequency === "custom") {
    const days = Math.max(1, customDays || 1);
    return { seconds: days * 24 * 60 * 60, repeats: true };
  }

  // Fallback — daily
  return { hour, minute, repeats: true };
}

/**
 * Schedule the expense-reminder notification based on user preferences.
 * Cancels any existing one first to avoid duplicates.
 *
 * @param {object} prefs
 * @param {boolean} prefs.notificationsEnabled
 * @param {string}  prefs.notificationFrequency  - "daily" | "weekly" | "monthly" | "custom"
 * @param {number}  prefs.notificationCustomDays
 * @param {string}  prefs.notificationTime       - "HH:mm"
 */
export async function applyNotificationPreferences(prefs) {
  const {
    notificationsEnabled,
    notificationFrequency = "daily",
    notificationCustomDays = 1,
    notificationTime = "20:00",
  } = prefs || {};

  // Cancel whatever was scheduled before
  await Notifications.cancelAllScheduledNotificationsAsync();

  if (!notificationsEnabled) {
    await AsyncStorage.removeItem(KEY_NOTIF_PREFS);
    return;
  }

  const granted = await requestNotificationPermission();
  if (!granted) return;

  const trigger = buildTrigger(notificationTime, notificationFrequency, notificationCustomDays);

  await Notifications.scheduleNotificationAsync({
    identifier: NOTIF_IDENTIFIER,
    content: {
      title: "Budget reminder",
      body: "Don't forget to log your expenses!",
    },
    trigger,
  });

  // Persist so App.jsx can avoid redundant reschedules on every render
  await AsyncStorage.setItem(KEY_NOTIF_PREFS, JSON.stringify(prefs));
}
```

- [ ] **Step 2: Commit**

```bash
cd mobile && git add src/services/notifications.js
git commit -m "feat(mobile): add notifications service"
```

---

## Task 6: Mobile — apply preferences on login and profile update

**Files:**
- Modify: `mobile/App.jsx`

We call `applyNotificationPreferences(user)` in two places:
1. When the user object becomes non-null (login / rehydrate)
2. After `handleUpdateProfile` succeeds (preferences were saved to backend, local user updated)

- [ ] **Step 1: Import the service in App.jsx**

At the top of `mobile/App.jsx`, add the import after the existing imports:

```javascript
import { applyNotificationPreferences } from "./src/services/notifications.js";
```

- [ ] **Step 2: Add useEffect to schedule notifications when user changes**

Inside `AppContent`, after the existing `useEffect` blocks (around line 95), add:

```javascript
// Schedule / cancel notifications whenever the authenticated user's prefs change
useEffect(() => {
  if (user) {
    applyNotificationPreferences(user).catch(() => {});
  }
}, [
  user?.notificationsEnabled,
  user?.notificationFrequency,
  user?.notificationCustomDays,
  user?.notificationTime,
]);
```

- [ ] **Step 3: Verify build**

```bash
cd mobile && npx expo start --clear 2>&1 | head -20
```

Expected: Metro starts cleanly. Press `Ctrl+C`.

- [ ] **Step 4: Commit**

```bash
cd mobile && git add App.jsx
git commit -m "feat(mobile): apply notification preferences on user change"
```

---

## Task 7: Mobile — notification settings UI in AccountScreen

**Files:**
- Modify: `mobile/app/screens/AccountScreen.jsx`

Add a new settings card between "Display Currency" and "Server URL". The card has:
- An enable/disable toggle (Switch)
- When enabled, shows frequency picker and time input
- Frequency options: Daily, Weekly, Monthly, Custom
- When "Custom" is selected, shows a number input for days
- Time input: text field accepting "HH:mm"
- A Save button that calls `onUpdateProfile` then shows a flash message

- [ ] **Step 1: Add notification state and handler to AccountScreen**

At the top of `AccountScreen.jsx`, add the import:

```javascript
import { requestNotificationPermission } from "../../src/services/notifications.js";
```

After the existing state declarations (around line 62), add:

```javascript
// Notification preferences
const [notifEnabled, setNotifEnabled]       = useState(user?.notificationsEnabled ?? false);
const [notifFrequency, setNotifFrequency]   = useState(user?.notificationFrequency ?? "daily");
const [notifCustomDays, setNotifCustomDays] = useState(String(user?.notificationCustomDays ?? 1));
const [notifTime, setNotifTime]             = useState(user?.notificationTime ?? "20:00");
```

After `handleSaveServerUrl`, add the save handler:

```javascript
async function handleSaveNotifications() {
  const timeRegex = /^([01]\d|2[0-3]):[0-5]\d$/;
  if (!timeRegex.test(notifTime)) return flash(false, "Time must be in HH:mm format (e.g. 20:00).");
  const customDaysNum = parseInt(notifCustomDays, 10);
  if (notifFrequency === "custom" && (isNaN(customDaysNum) || customDaysNum < 1)) {
    return flash(false, "Custom days must be a number ≥ 1.");
  }

  if (notifEnabled) {
    const granted = await requestNotificationPermission();
    if (!granted) return flash(false, "Notification permission denied. Please enable it in device settings.");
  }

  setLoading(true);
  try {
    await onUpdateProfile({
      notificationsEnabled:   notifEnabled,
      notificationFrequency:  notifFrequency,
      notificationCustomDays: notifFrequency === "custom" ? customDaysNum : (user?.notificationCustomDays ?? 1),
      notificationTime:       notifTime,
    });
    flash(true, notifEnabled ? "Notifications scheduled." : "Notifications disabled.");
    setSection(null);
  } catch (e) {
    flash(false, e.message || "Failed to save notification settings.");
  } finally {
    setLoading(false);
  }
}
```

- [ ] **Step 2: Add the UI card**

In the JSX, find the `{/* Display Currency */}` block and insert the following card **after** the closing `</View>` of the Display Currency card and **before** the `{/* Server URL */}` card:

```jsx
{/* Notifications */}
<View style={[S.card, { marginBottom: 12 }]}>
  <TouchableOpacity style={S.rowBetween} onPress={() => toggleSection("notifications")}>
    <View style={S.row}>
      <Text style={{ fontSize: 18, marginRight: 12 }}>🔔</Text>
      <Text style={S.body}>Expense Reminders</Text>
    </View>
    <View style={S.row}>
      {notifEnabled && section !== "notifications" && (
        <Text style={{ fontSize: 11, color: C.greenDark, marginRight: 8, fontWeight: "600" }}>ON</Text>
      )}
      <Text style={{ color: C.textTertiary, fontSize: 18 }}>{section === "notifications" ? "−" : "+"}</Text>
    </View>
  </TouchableOpacity>
  <Text style={[S.small, { marginLeft: 30, marginTop: 4 }]}>
    {notifEnabled ? `Reminders enabled · ${notifFrequency} at ${notifTime}` : "Remind yourself to log expenses"}
  </Text>

  {section === "notifications" && (
    <View style={{ marginTop: 14 }}>
      {/* Enable toggle */}
      <View style={[S.rowBetween, { marginBottom: 16 }]}>
        <Text style={S.label}>Enable reminders</Text>
        <Switch
          value={notifEnabled}
          onValueChange={setNotifEnabled}
          trackColor={{ false: C.bgTertiary, true: C.greenLight }}
          thumbColor={notifEnabled ? C.green : C.textTertiary}
        />
      </View>

      {notifEnabled && (
        <>
          {/* Frequency selector */}
          <Text style={[S.label, { marginBottom: 8 }]}>Frequency</Text>
          <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8, marginBottom: 14 }}>
            {["daily", "weekly", "monthly", "custom"].map(freq => (
              <TouchableOpacity
                key={freq}
                onPress={() => setNotifFrequency(freq)}
                style={{
                  paddingHorizontal: 14,
                  paddingVertical: 7,
                  borderRadius: 8,
                  borderWidth: 1,
                  borderColor: notifFrequency === freq ? C.green : C.border,
                  backgroundColor: notifFrequency === freq ? C.greenLight : C.bg,
                }}
              >
                <Text style={{
                  fontSize: 13,
                  fontWeight: notifFrequency === freq ? "700" : "400",
                  color: notifFrequency === freq ? C.greenDark : C.text,
                  textTransform: "capitalize",
                }}>
                  {freq}
                </Text>
              </TouchableOpacity>
            ))}
          </View>

          {/* Custom days input */}
          {notifFrequency === "custom" && (
            <View style={{ marginBottom: 14 }}>
              <Text style={[S.label, { marginBottom: 5 }]}>Every N days</Text>
              <TextInput
                style={S.input}
                value={notifCustomDays}
                onChangeText={setNotifCustomDays}
                keyboardType="numeric"
                placeholder="e.g. 3"
                placeholderTextColor={C.textTertiary}
              />
            </View>
          )}

          {/* Time input */}
          <Text style={[S.label, { marginBottom: 5 }]}>Reminder time (HH:mm)</Text>
          <TextInput
            style={S.input}
            value={notifTime}
            onChangeText={setNotifTime}
            placeholder="20:00"
            placeholderTextColor={C.textTertiary}
            keyboardType="numeric"
          />
        </>
      )}

      <TouchableOpacity
        style={[S.btnPrimary, { backgroundColor: C.green, marginTop: 14 }]}
        onPress={handleSaveNotifications}
        disabled={loading}
      >
        {loading ? <ActivityIndicator color="#fff" /> : <Text style={S.btnPrimaryText}>Save</Text>}
      </TouchableOpacity>
    </View>
  )}
</View>
```

- [ ] **Step 3: Verify the app renders the card**

Run the app on an emulator or device and navigate to Account → Settings. Confirm the "Expense Reminders" card appears below "Display Currency". Toggle it on, select a frequency, enter a time, and press Save. Verify the flash message "Notifications scheduled." appears.

- [ ] **Step 4: Commit**

```bash
cd mobile && git add app/screens/AccountScreen.jsx
git commit -m "feat(mobile): notification settings card in AccountScreen"
```

---

## Task 8: Manual end-to-end verification

No automated mobile tests exist (per AGENTS.md). Perform this checklist manually on Android emulator:

- [ ] Start LocalStack: `docker-compose up -d` from project root
- [ ] Start backend: `cd backend && AWS_URL=http://localhost:4567 ./gradlew bootRun`
- [ ] Start mobile: `cd mobile && npx expo start --android`
- [ ] Log in to the app
- [ ] Go to Account → Settings → Expense Reminders
- [ ] Toggle **on**, select **daily**, set time **08:00**, press Save → flash "Notifications scheduled."
- [ ] Switch to **weekly**, press Save → flash still appears
- [ ] Switch to **custom**, enter **3** days, press Save → flash appears
- [ ] Enter invalid time **99:99**, press Save → flash error "Time must be in HH:mm format"
- [ ] Enter custom days **-1**, press Save → flash error "Custom days must be a number ≥ 1"
- [ ] Toggle **off**, press Save → flash "Notifications disabled."
- [ ] Background the app and wait for scheduled time (or test by setting time to 1 minute in the future and waiting) → notification arrives in system tray

- [ ] **Final commit if any fixes were needed during manual testing**

```bash
git add -A && git commit -m "fix: manual test fixes for notifications"
```

---

## Self-Review

### Spec coverage

| Requirement | Covered by |
|-------------|-----------|
| Notifications toggle in settings | Task 7 |
| Daily / Weekly / Monthly / Custom N days | Task 7 (frequency picker) |
| User-configurable time | Task 7 (time input) |
| Preferences stored in backend | Tasks 2–4 |
| Notification fires even when app is closed | expo-notifications scheduleNotificationAsync (Task 5) |
| Preferences applied after login | Task 6 |
| Preferences applied after profile update | Task 6 (useEffect on user fields) |
| Permission request on Android | Task 5 + Task 7 |
| Validation: invalid frequency rejected by backend | Task 4 |
| Validation: malformed time rejected by backend | Task 4 |

### No placeholder checks
All steps contain actual code. No TBD / TODO items.

### Type consistency
- `notificationsEnabled: Boolean`, `notificationFrequency: String`, `notificationCustomDays: Int`, `notificationTime: String` — used consistently across Tasks 2, 3, 4, 5, 6, 7.
- `applyNotificationPreferences(user)` — called in Task 6 with the full user object; reads the four fields in Task 5.
- `handleSaveNotifications` in Task 7 patches exactly the four fields expected by backend Task 3's `UpdateUserRequest`.
