/**
 * Robomotion Java SDK Testing Framework.
 * <p>
 * This package provides utilities for unit testing Robomotion nodes without needing
 * the full runtime environment, GRPC, or NATS infrastructure.
 * <p>
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link com.robomotion.testing.Quick} - High-level harness that auto-configures nodes</li>
 *   <li>{@link com.robomotion.testing.Harness} - Low-level harness for full control</li>
 *   <li>{@link com.robomotion.testing.MockContext} - Test implementation of Context</li>
 *   <li>{@link com.robomotion.testing.CredentialStore} - Mock credential vault</li>
 *   <li>{@link com.robomotion.testing.DotEnv} - Load environment variables from .env files</li>
 *   <li>{@link com.robomotion.testing.TestRuntime} - Initialize mock credentials</li>
 * </ul>
 * <p>
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // In your test class
 * public class MyNodeTest {
 *     private static CredentialStore credStore;
 *
 *     @BeforeAll
 *     static void setup() {
 *         // Load .env file
 *         DotEnv.load(".env");
 *
 *         // Initialize credential store
 *         credStore = new CredentialStore();
 *         credStore.loadFromEnv("GEMINI", "api_key");
 *
 *         // Initialize runtime with mock credentials
 *         TestRuntime.initCredentials(credStore);
 *     }
 *
 *     @AfterAll
 *     static void cleanup() {
 *         TestRuntime.clearCredentials();
 *     }
 *
 *     @Test
 *     void testGenerateText() {
 *         // Skip if no credentials
 *         if (!credStore.has("api_key")) {
 *             return;
 *         }
 *
 *         // Create node with options set directly
 *         GenerateTextNode node = new GenerateTextNode();
 *         node.OptModel = "gemini-2.0-flash-lite";
 *
 *         // Create Quick harness
 *         Quick q = new Quick(node);
 *         q.setCredential("OptApiKey", "api_key", "api_key");
 *         q.setCustom("InText", "Say hello in 3 words");
 *
 *         // Run OnMessage
 *         Exception err = q.run();
 *         assertNull(err);
 *
 *         // Check outputs
 *         Object text = q.getOutput("text");
 *         assertNotNull(text);
 *         System.out.println("Generated: " + text);
 *     }
 * }
 * }</pre>
 *
 * @see com.robomotion.testing.Quick
 * @see com.robomotion.testing.Harness
 * @see com.robomotion.testing.TestRuntime
 */
package com.robomotion.testing;
