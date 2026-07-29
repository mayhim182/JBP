# JBP — Backend

Backend for JBP, a two-sided hiring platform: candidates build a profile and apply, recruiters
post jobs and manage a pipeline, admins verify companies and moderate job posts. Optional AI
features read uploaded resumes and suggest profile content.

The web frontend lives in a separate repository (`jbp-web`) and talks to this service over HTTP.

**AI is switched off by default.** Everything below works without an API key; the
[AI features](#ai-features-optional) section explains how to turn it on.

---

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.1 (Web, Data JPA, Security, Validation) |
| Database | MySQL 8+ (H2 in-memory for tests) |
| Auth | JWT (jjwt), stateless |
| Build | Maven — via the bundled wrapper, so no local Maven needed |
| Resume parsing | PDFBox (PDF), Apache POI (DOCX), libphonenumber (phone numbers) |
| AI provider | Google Gemini, through its OpenAI-compatible endpoint |

---

## Prerequisites

- **JDK 21** — `java -version` should report 21
- **MySQL 8 or newer**, running locally on port 3306
- **Nothing else.** Do not install Maven; use `./mvnw`

---

## First-time setup

### 1. Clone

```bash
git clone <repository-url>
cd JBP
```

### 2. Create the MySQL user

The database itself is created automatically — the JDBC URL carries
`createDatabaseIfNotExist=true`. You only need the user:

```sql
CREATE USER 'jbpuser'@'localhost' IDENTIFIED BY '<password from application.properties>';
GRANT ALL PRIVILEGES ON jbpdb.* TO 'jbpuser'@'localhost';
FLUSH PRIVILEGES;
```

Use the exact value of `spring.datasource.password` from
`src/main/resources/application.properties`. Schema is managed by Hibernate
(`ddl-auto=update`), so there are no migrations to run.

### 3. Run

```bash
./mvnw spring-boot:run
```

The service starts on **http://localhost:8080**. On first start it seeds the three roles and a
default admin account, using `app.admin.email` and `app.admin.password` from
`application.properties`.

### 4. Verify

```bash
curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"email":"admin@jbp.com","password":"admin123"}'
```

A JSON response containing `accessToken` means you're up.

---

## Running tests

```bash
./mvnw test
```

Tests use H2 in memory, so **MySQL does not need to be running** and your development data is
never touched. The suite makes **no network calls** — every AI test uses a fake client. One test
is reported as skipped by design; see below.

---

## AI features (optional)

AI adds richer resume parsing: experience, education, projects, headline, location and seniority,
rather than skills alone. Without it, resume upload still works and suggests email, phone and
skills using deterministic rules.

### 1. Get a Gemini API key

1. Go to **https://aistudio.google.com/apikey**
2. **Create API key** and copy it — it's shown once

The free tier is sufficient for development.

### 2. Put the key in your environment — never in a file

```bash
echo 'export GEMINI_API_KEY=your-key-here' >> ~/.zshrc
```

```bash
source ~/.zshrc
```

```bash
[ -n "$GEMINI_API_KEY" ] && echo "key is set" || echo "key is NOT set"
```

**Never commit a key.** `application.properties` reads it via `${GEMINI_API_KEY:}` precisely so it
stays out of the repository. If a key is ever committed, revoke it in AI Studio and issue a new one.

### 3. Run with AI enabled

Pass the switches on the command line rather than editing the committed properties file:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.ai.enabled=true --app.resume.parser=llm"
```

In a deployed environment use environment variables instead — Spring Boot maps them automatically:

```
APP_AI_ENABLED=true
APP_RESUME_PARSER=llm
GEMINI_API_KEY=<the key>
```

Leave `app.ai.enabled=false` and `app.resume.parser=deterministic` in the committed file.
`enabled=true` without a key **deliberately fails startup** with a message telling you what to
fix, so a build without a key can never silently degrade.

### 4. Confirm the key works end to end

```bash
JBP_AI_LIVE_TEST=true ./mvnw test -Dtest=GeminiLiveSmokeTest
```

This is the one test skipped in a normal run. It sends a real prompt through the application's own
wiring and prints `Model replied: OK`. It is gated behind `JBP_AI_LIVE_TEST` so ordinary builds
stay offline and spend no quota.

If the model name has been retired from the free tier you'll see HTTP 429 with `limit: 0` — that
reads like a quota error but means zero allowance. List the models your key can reach:

```bash
curl -s "https://generativelanguage.googleapis.com/v1beta/openai/models" -H "Authorization: Bearer $GEMINI_API_KEY" | grep '"id"'
```

Then update `app.ai.model`. No code changes are ever needed for a model or provider swap.

---

## Configuration reference

All settings live in `src/main/resources/application.properties`.

| Property | Default | Notes |
|---|---|---|
| `server.port` | `8080` | |
| `spring.datasource.*` | local MySQL | database auto-created |
| `jwt.secret` | dev fallback | override with `JWT_SECRET` outside development |
| `jwt.expiration-ms` | `86400000` | 24 hours |
| `app.admin.email` / `.password` | seeded admin | change before any real deployment |
| `app.storage.location` | `uploads` | resume files on local disk; swap for S3 later |
| `app.resume.parser` | `deterministic` | or `llm` |
| `app.ai.enabled` | `false` | master switch and kill switch |
| `app.ai.api-key` | from `GEMINI_API_KEY` | never hard-code |
| `app.ai.base-url` | Gemini OpenAI-compatible endpoint | no trailing slash |
| `app.ai.model` | `gemini-3.5-flash-lite` | pinned, not an alias |
| `app.ai.timeout-millis` | `20000` | raise if prompts grow |
| `app.ai.rate-limit-per-minute` | `12` | kept under the free-tier allowance |
| `app.ai.max-input-tokens` | `3000` | prompts truncated to this |

---

## Project structure

```
com.jbp
├── controller     REST endpoints
├── service        interfaces
├── serviceimpl    implementations
├── repository     Spring Data JPA
├── model          JPA entities
├── dto            request and response payloads
├── mapper         entity <-> DTO
├── config         Spring configuration, seeding
├── security       JWT filter, user details
├── exception      custom exceptions + global handler
└── util           stateless helpers
```

### Conventions

- Interfaces in `service`, implementations in `serviceimpl`
- Other areas call a feature through its **service**, never its repository directly
- Constructor injection throughout; no field injection
- Validation on request DTOs with `@Valid` on the controller

### AI architecture

Two ideas carry the whole AI layer:

- **`ChatCompletionClient`** — one interface, one method. `GeminiChatClient` is the only class that
  knows a provider exists; `RateLimitedChatClient` and `LoggingChatClient` decorate it, and
  `DisabledChatClient` stands in when AI is off. Swapping provider is configuration, not code.
- **`AbstractStructuredAiTask<I, O>`** — a final `execute(I)` running prompt → call → parse →
  validate → fallback. A new AI feature supplies only a system prompt, a response record and a
  fallback value. It never throws: any failure returns the fallback, so an AI problem cannot break
  a user's action.

Model output is accepted whole or discarded whole — never partially applied. Contact details come
from regex and libphonenumber rather than the model, because a phone number must be exact.

Prompt and reply **content is never logged**, only sizes and timings, since prompts carry candidate
data.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Port 8080 was already in use` | An earlier run is still going: `kill $(lsof -ti:8080)` |
| `app.ai.enabled is true but app.ai.api-key is not set` | The key isn't in the environment of the terminal you launched from |
| `Access denied for user 'jbpuser'` | MySQL user missing, or its password doesn't match `application.properties` |
| `./mvnw: no such file or directory` | You're in the wrong directory — run from the folder containing `pom.xml` |
| `permission denied: ./mvnw` | `chmod +x mvnw` |
| HTTP 429 with `limit: 0` | The configured model has no free-tier allowance — pick another, see above |
| Resume suggests only skills | `app.resume.parser` is `deterministic`, or the extracted text was too poor to send |

---

## Known gaps

- The MySQL password sits in `application.properties`. It should follow the `jwt.secret` pattern
  and read from an environment variable with a development fallback.
- DOCX hyperlink targets are not recovered — POI returns display text only, so a LinkedIn address
  written as clickable text is lost. PDF hyperlinks are handled.
- Test coverage is concentrated on the AI layer and resume parsing; the older controllers and
  services have no automated tests.

---

## Related

- `jbp-web` — Next.js frontend
- `MVP_Backlog.md` and `AI_Backlog.md` — stories, acceptance criteria, and the reasoning behind
  decisions in the AI layer. Kept outside this repository; ask the maintainer for a copy before
  changing anything under `serviceimpl` that touches AI, as several non-obvious choices are
  recorded there rather than in code.
