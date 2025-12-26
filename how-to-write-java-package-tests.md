# Robomotion Java Package Testing Guide

*Last updated: December 26, 2025*

This guide covers writing **integration tests** for Robomotion packages using the `com.robomotion.testing` package. These tests execute real `OnMessage()` methods with real API calls.

---

## 1. Overview

The `com.robomotion.testing` package provides tools to test Robomotion nodes without needing the full runtime environment:

| Component | Purpose |
|-----------|---------|
| `Quick` | High-level harness that auto-configures nodes and runs `OnMessage()` |
| `Harness` | Low-level harness for full control over variable configuration |
| `CredentialStore` | Mock credential vault that loads from environment variables |
| `MockContext` | Lightweight message context for testing |
| `DotEnv.load()` | Loads environment variables from `.env` files |
| `TestRuntime.initCredentials()` | Initializes the runtime with mock credentials |

**These are integration tests** — they call real APIs with real credentials. The mock is only for the runtime plumbing, not the business logic.

---

## 2. Test File Structure

Follow Java/Maven convention: one test file per source file, plus a shared setup class.

```
my-package/                        # Package root directory
├── pom.xml                        # Maven project file
├── config.json                    # Package metadata
├── credentials.yaml               # Credential definitions
├── icon.png                       # Package icon
├── .env                           # Test credentials (git-ignored!)
├── TESTING.md                     # Requirements to run tests
│
├── src/main/java/                 # Main source directory
│   └── com/example/mypackage/
│       ├── Connect.java           # Connect node
│       ├── GenerateText.java      # GenerateText node
│       └── Embeddings.java        # Embeddings node
│
└── src/test/java/                 # Test source directory
    └── com/example/mypackage/
        ├── TestSetup.java         # Shared setup, credential loading
        ├── ConnectTest.java       # Tests for Connect.java
        ├── GenerateTextTest.java  # Tests for GenerateText.java
        └── EmbeddingsTest.java    # Tests for Embeddings.java
```

**Key Points:**
- Main project and test project follow Maven/Gradle standard layout
- The `.env` file stays at project root
- Use JUnit 5 for testing framework

---

## 3. Maven Dependencies

Add the testing dependencies to your `pom.xml`:

```xml
<dependencies>
    <!-- Robomotion SDK -->
    <dependency>
        <groupId>com.robomotion.app</groupId>
        <artifactId>robomotion</artifactId>
        <version>2.0.1</version>
    </dependency>

    <!-- Testing dependencies -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 4. Basic Setup (TestSetup.java)

Every test package needs shared setup for credential initialization:

```java
package com.example.mypackage;

import com.robomotion.testing.CredentialStore;
import com.robomotion.testing.DotEnv;
import com.robomotion.testing.TestRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

public class TestSetup {

    public static CredentialStore credStore;

    @BeforeAll
    public static void setup() {
        // Initialize credential store
        credStore = new CredentialStore();

        // Load .env file (won't fail if missing)
        DotEnv.load(".env");

        // Load credentials from environment
        // Pattern: PREFIX_API_KEY, PREFIX_KEY, or PREFIX_VALUE → "credential_name"
        credStore.loadFromEnv("GEMINI", "api_key");
        credStore.loadFromEnv("OPENAI", "openai_key");

        // Initialize runtime with mock credentials
        TestRuntime.initCredentials(credStore);
    }

    @AfterAll
    public static void cleanup() {
        TestRuntime.clearCredentials();
    }

    // Helper to check if credentials are available
    public static boolean hasCredentials() {
        return credStore.has("api_key");
    }
}
```

---

## 5. Writing Node Tests

### 5.1 Basic Test Structure

```java
package com.example.mypackage;

import com.robomotion.testing.Quick;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

public class GenerateTextTest extends TestSetup {

    @Test
    public void basicTextGeneration() {
        // Skip if no credentials
        assumeTrue(hasCredentials(), "No API key in environment, skipping");

        // Create node with options set directly
        GenerateText node = new GenerateText();
        node.OptModel = "gemini-2.0-flash-lite";

        // Create Quick harness
        Quick q = new Quick(node);

        // Set credential (field name, vault ID, item ID)
        q.setCredential("OptApiKey", "api_key", "api_key");

        // Set input variables
        q.setCustom("InText", "Say hello in 3 words");

        // Run OnMessage()
        Exception err = q.run();
        assertNull(err, "GenerateText failed: " + (err != null ? err.getMessage() : ""));

        // Check outputs
        Object text = q.getOutput("text");
        assertNotNull(text, "Expected text output to be set");
        assertNotEquals("", text.toString());

        // Log for debugging
        System.out.println("Generated: " + text);
    }
}
```

### 5.2 Quick Harness Methods

| Method | Purpose | Example |
|--------|---------|---------|
| `new Quick(node)` | Create harness for node | `Quick q = new Quick(new MyNode());` |
| `setInput(name, value)` | Set message-scope input | `q.setInput("fileUri", "files/123");` |
| `setCustom(field, value)` | Set custom-scope input | `q.setCustom("InText", "Hello");` |
| `setCredential(field, vaultID, itemID)` | Set credential reference | `q.setCredential("OptApiKey", "api_key", "api_key");` |
| `run()` | Execute `OnCreate()` + `OnMessage()` | `Exception err = q.run();` |
| `getOutput(name)` | Get output value | `Object result = q.getOutput("text");` |
| `getOutputString(name)` | Get string output value | `String result = q.getOutputString("text");` |
| `getOutputInt(name)` | Get integer output value | `long result = q.getOutputInt("count");` |
| `getOutputFloat(name)` | Get float output value | `double result = q.getOutputFloat("score");` |
| `getOutputBool(name)` | Get boolean output value | `boolean result = q.getOutputBool("success");` |

### 5.3 Setting Node Options

Options can be set directly on the node before creating the harness:

```java
GenerateImage node = new GenerateImage();
node.OptModel = "gemini-2.5-flash-image";  // enum option
node.OptNumberOfImages = "1";               // string option
node.OptAspectRatio = "16:9";              // string option

Quick q = new Quick(node);
```

### 5.4 Testing Error Cases

```java
@Test
public void emptyPromptError() {
    assumeTrue(hasCredentials(), "No API key in environment, skipping");

    GenerateText node = new GenerateText();
    node.OptModel = "gemini-2.0-flash-lite";

    Quick q = new Quick(node);
    q.setCredential("OptApiKey", "api_key", "api_key");
    q.setCustom("InText", "");  // Empty prompt

    Exception err = q.run();
    assertNotNull(err, "Expected error for empty prompt");
}

@Test
public void invalidParameter() {
    assumeTrue(hasCredentials(), "No API key in environment, skipping");

    SendChatMessage node = new SendChatMessage();
    node.OptModel = "gemini-2.0-flash-lite";

    Quick q = new Quick(node);
    q.setCredential("OptApiKey", "api_key", "api_key");
    q.setCustom("InText", "Test");
    q.setCustom("OptTemperature", "3.0");  // Invalid: > 2.0

    Exception err = q.run();
    assertNotNull(err, "Expected error for temperature > 2.0");
}
```

---

## 6. Credential Management

### 6.1 The .env File

Create a `.env` file in your project root with your test credentials:

```bash
# .env - Add to .gitignore!
GEMINI_API_KEY=your_api_key_here
```

### 6.2 Loading Credentials

The `loadFromEnv()` method searches for environment variables with these patterns:

```java
credStore.loadFromEnv("GEMINI", "api_key");
// Searches for: GEMINI_API_KEY, GEMINI_KEY, GEMINI_TOKEN, GEMINI_VALUE
// Stores result under name: "api_key"
```

### 6.3 Credential Categories & .env Examples

Each credential category has different fields. Here are `.env` examples for each:

---

#### Category 1: Login (Basic Auth)

Used for: HTTP authentication, FTP, browser automation

**Vault Item Structure:**
```json
{
  "username": "user@example.com",
  "password": "secretpassword",
  "server": "https://api.example.com"
}
```

**.env Format:**
```bash
# Category 1 - Login
SERVICE_USERNAME=user@example.com
SERVICE_PASSWORD=secretpassword
SERVICE_SERVER=https://api.example.com
```

**Test Setup:**
```java
// In TestSetup.java
credStore.setLogin("login_cred",
    System.getenv("SERVICE_USERNAME"),
    System.getenv("SERVICE_PASSWORD"));

// Or with server
credStore.setLogin("login_cred",
    System.getenv("SERVICE_USERNAME"),
    System.getenv("SERVICE_PASSWORD"),
    System.getenv("SERVICE_SERVER"));

// In test
q.setCredential("OptLogin", "login_cred", "login_cred");
```

---

#### Category 4: API Key/Token

Used for: Most API integrations (Gemini, OpenAI, Slack, etc.)

**Vault Item Structure:**
```json
{
  "value": "sk-abc123..."
}
```

**.env Format:**
```bash
# Category 4 - API Key
GEMINI_API_KEY=AIza...your_key_here
OPENAI_API_KEY=sk-...your_key_here
SLACK_API_KEY=xoxb-...your_token_here
GITHUB_TOKEN=ghp_...your_token_here
```

**Test Setup (Simplest):**
```java
// Automatic loading for API keys
credStore.loadFromEnv("GEMINI", "api_key");
// This searches for GEMINI_API_KEY, GEMINI_KEY, or GEMINI_VALUE
// and creates {"value": "..."} structure automatically

// Or manually
credStore.setAPIKey("api_key", System.getenv("GEMINI_API_KEY"));

// In test
q.setCredential("OptApiKey", "api_key", "api_key");
```

---

#### Category 5: Database

Used for: PostgreSQL, MySQL, MongoDB, SQL Server connections

**Vault Item Structure:**
```json
{
  "server": "localhost",
  "port": 5432,
  "database": "mydb",
  "username": "postgres",
  "password": "secret"
}
```

**.env Format:**
```bash
# Category 5 - Database
DB_SERVER=localhost
DB_PORT=5432
DB_DATABASE=testdb
DB_USERNAME=postgres
DB_PASSWORD=secretpassword
```

**Test Setup:**
```java
import com.robomotion.testing.DatabaseCredential;

credStore.setDatabase("db_cred", new DatabaseCredential()
    .setServer(System.getenv("DB_SERVER"))
    .setPort(Integer.parseInt(System.getenv("DB_PORT")))
    .setDatabase(System.getenv("DB_DATABASE"))
    .setUsername(System.getenv("DB_USERNAME"))
    .setPassword(System.getenv("DB_PASSWORD")));
```

---

#### Category 6: Document (OAuth2, Service Accounts, Certificates)

Used for: Google Service Accounts, OAuth tokens, certificates, complex JSON credentials

**Vault Item Structure:**
```json
{
  "filename": "service-account.json",
  "content": "{\"type\":\"service_account\",\"project_id\":\"...\",\"private_key\":\"...\"}"
}
```

**.env Format:**
```bash
# Category 6 - Document/JSON

# Option A: Path to JSON file
GOOGLE_SERVICE_ACCOUNT_FILE=/path/to/service-account.json

# Option B: Inline JSON
GOOGLE_SERVICE_ACCOUNT_JSON={"type":"service_account","project_id":"my-project",...}
```

**Test Setup (Service Account):**
```java
import java.nio.file.Files;
import java.nio.file.Paths;

static void loadServiceAccount(CredentialStore credStore) {
    // Option A: From file
    String path = System.getenv("GOOGLE_SERVICE_ACCOUNT_FILE");
    if (path != null && !path.isEmpty()) {
        try {
            String content = Files.readString(Paths.get(path));
            credStore.setDocument("service_account", "service-account.json", content);
            return;
        } catch (Exception e) {
            // Fall through
        }
    }

    // Option B: From inline JSON
    String json = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON");
    if (json != null && !json.isEmpty()) {
        credStore.setDocument("service_account", "service-account.json", json);
    }
}

// In TestSetup.setup()
loadServiceAccount(credStore);
```

---

### 6.4 Multiple Credentials

For packages using multiple credential types:

```java
@BeforeAll
public static void setup() {
    credStore = new CredentialStore();
    DotEnv.load(".env");

    // API Key credentials
    credStore.loadFromEnv("GEMINI", "gemini_key");
    credStore.loadFromEnv("OPENAI", "openai_key");

    // Database credential
    credStore.setDatabase("postgres", new DatabaseCredential()
        .setServer(getEnvOrDefault("PG_HOST", "localhost"))
        .setPort(Integer.parseInt(getEnvOrDefault("PG_PORT", "5432")))
        .setDatabase(getEnvOrDefault("PG_DATABASE", "testdb"))
        .setUsername(getEnvOrDefault("PG_USER", "postgres"))
        .setPassword(getEnvOrDefault("PG_PASSWORD", "testpass")));

    // Service account credential
    loadServiceAccount(credStore);

    TestRuntime.initCredentials(credStore);
}

private static String getEnvOrDefault(String key, String defaultValue) {
    String value = System.getenv(key);
    return value != null ? value : defaultValue;
}
```

---

## 7. Testing Helper Functions

Use `MockContext` for testing non-node functions:

```java
import com.robomotion.testing.MockContext;

@Test
public void testGetModuleOrCreate() {
    // Setup: create a client
    String connId = addClient("test-key", false);
    try {
        // Create mock context
        MockContext ctx = new MockContext();

        // Test the helper function
        Module module = getModuleOrCreate(ctx, connId, new Credential(), false);
        assertNotNull(module);
        assertEquals("test-key", module.getApiKey());
    } finally {
        delClient(connId);
    }
}

@Test
public void testCalculateSimilarity() {
    float[] a = {1.0f, 0.0f, 0.0f};
    float[] b = {1.0f, 0.0f, 0.0f};

    double sim = calculateSimilarity(a, b, "cosine");
    assertTrue(sim > 0.99);
}
```

---

## 8. Test Categories

### 8.1 What to Test

| Category | Description | Example |
|----------|-------------|---------|
| **Happy Path** | Normal successful operation | Generate text with valid prompt |
| **Error Cases** | Expected failures | Empty input, invalid parameters |
| **Edge Cases** | Boundary conditions | Max length input, special characters |
| **Validation** | Parameter validation | Temperature > 2.0, invalid enum |
| **Connection** | Connect/Disconnect lifecycle | Create and cleanup connections |

### 8.2 Skipping Slow Tests

Mark expensive tests (image generation, video, large files):

```java
@Test
public void basicImageGeneration() {
    assumeTrue(hasCredentials(), "No API key in environment, skipping");

    if (System.getenv("SKIP_SLOW_TESTS") != null) {
        return; // Skip slow test
    }

    // ... expensive test
}
```

Run fast tests only:
```bash
SKIP_SLOW_TESTS=1 mvn test
```

---

## 9. Running Tests

```bash
# Run all tests
mvn test

# Run with verbose output
mvn test -Dtest.verbose=true

# Run specific test class
mvn test -Dtest=GenerateTextTest

# Run specific test method
mvn test -Dtest=GenerateTextTest#basicTextGeneration

# Run with code coverage
mvn test jacoco:report

# Skip slow tests
SKIP_SLOW_TESTS=1 mvn test

# Run with timeout
mvn test -Dsurefire.timeout=300
```

---

## 10. Best Practices

### 10.1 Always Skip Without Credentials

```java
assumeTrue(hasCredentials(), "No API credentials available, skipping");
```

### 10.2 Use Test Organization

```java
public class EmbeddingsTest extends TestSetup {

    @Test
    public void basicEmbedding() { ... }

    @Test
    public void emptyContentError() { ... }

    @Test
    public void withComparisonText() { ... }
}
```

### 10.3 Clean Up Resources

```java
@Test
public void withConnection() {
    String connId = addClient("test-key", false);
    try {
        // ... test code
    } finally {
        delClient(connId);  // Always cleanup
    }
}
```

### 10.4 Log Useful Information

```java
Object text = q.getOutput("text");
System.out.println("Generated text: " + text);

// Log response types for debugging
System.out.println("Response type: " + response.getClass().getName());
```

### 10.5 Test File Naming

| Source File | Test File |
|-------------|-----------|
| `Connect.java` | `ConnectTest.java` |
| `GenerateText.java` | `GenerateTextTest.java` |
| `Common.java` | `CommonTest.java` |
| `Helpers.java` | `HelpersTest.java` |
| (shared setup) | `TestSetup.java` |

---

## 11. TESTING.md Template

Every package should include a `TESTING.md` file describing requirements:

```markdown
# Testing Requirements

## Prerequisites

1. Java 21+
2. Maven 3.8+
3. API credentials (see below)

## Credentials Setup

1. Create `.env` file in the project root directory
2. Add required credentials:

```bash
# Required
SERVICE_API_KEY=your_api_key_here

# Optional (for specific tests)
SERVICE_SECRET=optional_secret
```

## Getting Credentials

1. Go to [Service Console](https://console.example.com)
2. Create a new project or select existing
3. Navigate to API Keys section
4. Create a new API key
5. Copy the key to `.env`

## Running Tests

```bash
# All tests
mvn test

# Fast tests only
SKIP_SLOW_TESTS=1 mvn test

# Specific test
mvn test -Dtest=GenerateTextTest
```

## Test Files Required

- None (all tests use API calls)

## Notes

- Tests make real API calls and may incur costs
- Some tests are slow (image/video generation)
- Rate limits may cause occasional failures
```

---

## 12. Complete Example

Here's a complete test file for reference:

```java
// GenerateTextTest.java
package com.example.mypackage;

import com.robomotion.testing.Quick;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

public class GenerateTextTest extends TestSetup {

    @Test
    public void basicTextGeneration() {
        assumeTrue(hasCredentials(), "No GEMINI_API_KEY in environment, skipping");

        GenerateText node = new GenerateText();
        node.OptModel = "gemini-2.0-flash-lite";

        Quick q = new Quick(node);
        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InText", "Say hello in exactly 3 words");

        Exception err = q.run();
        assertNull(err, "GenerateText failed: " + (err != null ? err.getMessage() : ""));

        Object text = q.getOutput("text");
        assertNotNull(text, "Expected text output to be set");
        assertNotEquals("", text.toString());
        System.out.println("Generated text: " + text);
    }

    @Test
    public void withSystemPrompt() {
        assumeTrue(hasCredentials(), "No GEMINI_API_KEY in environment, skipping");

        GenerateText node = new GenerateText();
        node.OptModel = "gemini-2.0-flash-lite";

        Quick q = new Quick(node);
        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InSystemPrompt", "You are a pirate. Always respond in pirate speak.");
        q.setCustom("InText", "Say hello");

        Exception err = q.run();
        assertNull(err, "GenerateText with system prompt failed");

        Object text = q.getOutput("text");
        assertNotNull(text, "Expected text output to be set");
        System.out.println("Generated text (pirate): " + text);
    }

    @Test
    public void emptyPromptError() {
        assumeTrue(hasCredentials(), "No GEMINI_API_KEY in environment, skipping");

        GenerateText node = new GenerateText();
        node.OptModel = "gemini-2.0-flash-lite";

        Quick q = new Quick(node);
        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InText", "");

        Exception err = q.run();
        assertNotNull(err, "Expected error for empty prompt");
    }

    @Test
    public void jsonMode() {
        assumeTrue(hasCredentials(), "No GEMINI_API_KEY in environment, skipping");

        GenerateText node = new GenerateText();
        node.OptModel = "gemini-2.0-flash-lite";
        node.OptJSONMode = true;

        Quick q = new Quick(node);
        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InText", "Return JSON with name=John and age=30");

        Exception err = q.run();
        assertNull(err, "GenerateText JSON mode failed");

        Object text = q.getOutput("text");
        assertNotNull(text, "Expected text output to be set");
        System.out.println("Generated JSON: " + text);
    }
}
```

---

## 13. Using the Low-Level Harness API

For more control, use the `Harness` class directly:

### 13.1 When to Use Harness

- When you need to configure variables with specific scopes manually
- When dealing with array-type input variables
- When testing complex variable configurations

### 13.2 Basic Harness Usage

```java
import com.robomotion.testing.Harness;

@Test
public void usingHarnessDirectly() {
    assumeTrue(hasCredentials());

    MyNode node = new MyNode();
    Harness h = new Harness(node);

    // Configure variables manually
    h.configureInVariable(node.InData, "Message", "data");
    h.configureOutVariable(node.OutResult, "Message", "result");
    h.configureCustomInput(node.InOption, "some value");

    // Set inputs
    h.withInput("data", "input value");

    // Run
    Exception err = h.run();
    assertNull(err);

    // Get output
    Object result = h.getOutput("result");
    assertNotNull(result);
}
```

### 13.3 Testing Nodes with Array Fields

Some nodes use array-type input variables. The Quick API doesn't work with array fields, so use the Harness API:

```java
static Harness createOperationHarness(boolean[] values, String operationType) {
    Operation node = new Operation();
    node.OptOperationType = operationType;
    node.InValues = new Runtime.InVariable[values.length];

    // Initialize array elements
    for (int i = 0; i < values.length; i++) {
        node.InValues[i] = new Runtime.InVariable<>("Custom", null);
    }

    Harness h = new Harness(node);

    // Configure each InVariable in the array
    for (int i = 0; i < values.length; i++) {
        h.configureCustomInput(node.InValues[i], values[i]);
    }

    // Configure output
    h.configureOutVariable(node.OutResult, "Message", "result");

    return h;
}

@Test
public void testOperationAnd() {
    Harness h = createOperationHarness(new boolean[]{true, true}, "and");

    Exception err = h.run();
    assertNull(err);

    boolean result = h.getOutputBool("result");
    assertTrue(result);
}
```

---

## 14. Test Files and Testdata

### 14.1 Create Files Programmatically (Recommended)

```java
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

static File createTestImage(int width, int height, Color color) throws IOException {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, width, height);
    g.dispose();

    File tmpFile = File.createTempFile("test-image-", ".png");
    ImageIO.write(img, "png", tmpFile);
    return tmpFile;
}

@Test
public void editImage() throws IOException {
    File baseImage = createTestImage(256, 256, Color.BLUE);
    try {
        // Use baseImage.getAbsolutePath() as the file path
        q.setCustom("InImagePath", baseImage.getAbsolutePath());
        // ... test
    } finally {
        baseImage.delete();
    }
}
```

### 14.2 Testdata Directory

Create a `src/test/resources/testdata/` directory for static test files:

```
src/test/resources/
└── testdata/
    ├── sample.png
    ├── sample.pdf
    └── sample.json
```

Helper to get testdata paths:
```java
static String getTestdataPath(String filename) {
    return TestSetup.class.getClassLoader()
        .getResource("testdata/" + filename)
        .getPath();
}
```

---

## 15. Resource Cleanup

When tests create resources, clean them up:

```java
@Test
public void uploadAndCleanupFile() throws IOException {
    assumeTrue(hasCredentials());

    // Create temporary local file
    File tmpFile = File.createTempFile("test-upload-", ".txt");
    Files.writeString(tmpFile.toPath(), "Test content for upload");

    try {
        // Upload file
        FileUpload uploadNode = new FileUpload();
        Quick q = new Quick(uploadNode);
        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InFilePath", tmpFile.getAbsolutePath());

        Exception err = q.run();
        assertNull(err);

        // Get file name for cleanup
        @SuppressWarnings("unchecked")
        Map<String, Object> fileInfo = (Map<String, Object>) q.getOutput("file");
        String fileName = (String) fileInfo.get("name");

        try {
            // ... perform assertions ...
        } finally {
            // Cleanup uploaded file via API
            FileDelete deleteNode = new FileDelete();
            Quick dq = new Quick(deleteNode);
            dq.setCredential("OptApiKey", "api_key", "api_key");
            dq.setCustom("InFileName", fileName);
            dq.run();
        }
    } finally {
        tmpFile.delete();
    }
}
```

---

## 16. Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| `No Token Value` | SetCredential with missing itemID | Use `q.setCredential("OptApiKey", "api_key", "api_key")` — both IDs required |
| `NullPointerException` on variable | Variable not initialized | Ensure node properties are initialized before creating harness |
| Tests skipped | No credentials in .env | Create `.env` with required API keys |
| API errors | Invalid credentials | Verify API key is valid and has required permissions |
| Rate limit errors | Too many API calls | Add delays between tests or use `SKIP_SLOW_TESTS=1` |
| `RuntimeNotInitializedException` | Using Global/Flow scope in test | Only Message and Custom scopes work in tests |

---

## 17. Test Project Template

Here's a template test class with all imports:

```java
package com.example.mypackage;

import com.robomotion.testing.CredentialStore;
import com.robomotion.testing.DotEnv;
import com.robomotion.testing.Harness;
import com.robomotion.testing.MockContext;
import com.robomotion.testing.Quick;
import com.robomotion.testing.TestRuntime;
import com.robomotion.app.Runtime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

public class MyNodeTest {

    private static CredentialStore credStore;

    @BeforeAll
    static void setup() {
        credStore = new CredentialStore();
        DotEnv.load(".env");
        credStore.loadFromEnv("GEMINI", "api_key");
        TestRuntime.initCredentials(credStore);
    }

    @AfterAll
    static void cleanup() {
        TestRuntime.clearCredentials();
    }

    static boolean hasCredentials() {
        return credStore.has("api_key");
    }

    @Test
    void testMyNode() {
        assumeTrue(hasCredentials(), "No credentials, skipping");

        MyNode node = new MyNode();
        Quick q = new Quick(node);

        q.setCredential("OptApiKey", "api_key", "api_key");
        q.setCustom("InText", "Hello");

        Exception err = q.run();
        assertNull(err);

        Object output = q.getOutput("result");
        assertNotNull(output);
    }
}
```

---

This guide covers the essential aspects of testing Robomotion packages with Java. The testing framework provides the same capabilities as the Go and .NET testing packages, enabling comprehensive integration testing of your nodes.
