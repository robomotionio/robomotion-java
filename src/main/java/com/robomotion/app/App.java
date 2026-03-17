package com.robomotion.app;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;

import org.json.simple.JSONObject;
import org.json.simple.parser.*;

import grpc.health.v1.HealthOuterClass.HealthCheckResponse.ServingStatus;
import io.grpc.Server;
import io.grpc.ServerBuilder;

public class App {
	public interface Initializer {
		public void Init();
	}

	private static Boolean attached = false;
	private static JSONObject config;
	private static String ns = "";

	@FieldAnnotations.Default(cls = boolean.class)
	public static CountDownLatch latch = new CountDownLatch(1);
	public static Initializer initializer = null;

	public static void Start(String[] args) {
		// Suppress Netty/gRPC warnings on Java 21+.
		// Must be set before any Netty classes are loaded.
		// grpc-netty-shaded relocates property names to io.grpc.netty.shaded.io.netty.*
		System.setProperty("io.grpc.netty.shaded.io.netty.noUnsafe", "true");
		System.setProperty("io.grpc.netty.shaded.io.netty.leakDetection.level", "DISABLED");
		System.setProperty("io.grpc.netty.shaded.io.netty.transport.noNative", "true");

		try {
			if (args.length > 0) { // start with arg
				String arg = args[0];

				// Handle --session-close=<id> or --session-close <id>
				if (arg.startsWith("--session-close")) {
					int eqIdx = arg.indexOf('=');
					String sessionID;
					if (eqIdx >= 0) {
						sessionID = arg.substring(eqIdx + 1);
					} else if (args.length > 1) {
						sessionID = args[1];
					} else {
						System.err.println("--session-close requires a session ID");
						System.exit(1);
						return;
					}
					CLISession.closeSession(sessionID);
					return;
				}

				// Handle --session-daemon <id> (internal: invoked by startDaemonProcess)
				if (arg.equals("--session-daemon")) {
					if (args.length < 2) {
						System.err.println("--session-daemon requires a session ID");
						System.exit(1);
						return;
					}
					String sessionID = args[1];
					java.util.Map<String, String> daemonFlags;
					try {
						daemonFlags = CLI.parseFlags(args, 2);
					} catch (Exception e) {
						daemonFlags = java.util.Map.of();
					}
					long timeout = CLISession.parseSessionTimeout(daemonFlags.get("session-timeout"));
					String vaultID = daemonFlags.get("vault-id");
					String itemID = daemonFlags.get("item-id");
					CLISession.runDaemon(sessionID, timeout, vaultID, itemID);
					return;
				}

				// CLI-specific flags
				if (arg.equals("--list-commands") || arg.equals("--skill-md") ||
					arg.equals("--help") || arg.equals("-h")) {
					CLI.run(args);
					return;
				}

				// CLI command mode: first arg doesn't start with '-'
				if (!arg.startsWith("-")) {
					CLI.run(args);
					return;
				}

				config = ReadConfigFile();

				String name = config.get("name").toString();
				String version = config.get("version").toString();

				if (arg.compareTo("-a") == 0) { // attach
					attached = true;
				} else if (arg.compareTo("-s") == 0) { // generate spec file
					Spec.GenerateSpec(name, version);
					System.exit(0);
				}
			}

			Init();
			if (initializer != null)
				initializer.Init();

			HealthServiceImpl health = new HealthServiceImpl();
			health.SetStatus("plugin", ServingStatus.SERVING);

			Server server = ServerBuilder.forPort(0).addService(health).addService(new NodeServer()).build();

			server.start();

			System.out.printf("1|1|tcp|127.0.0.1:%d|grpc\n", server.getPort());
			System.out.flush();

			if (attached) { // attach
				ns = config.get("namespace").toString();
				Debug.Attach(String.format("127.0.0.1:%d", server.getPort()), ns);
			}

			latch.await();
			// server.awaitTermination();

			if (attached) {
				Debug.Detach(ns);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void Init()
			throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
		List<Class<?>> classes = GetNodeTypes();

		for (Object obj : classes) {
			Class<?> c = (Class<?>) obj;
			NodeAnnotations.Name annotation = c.getAnnotation(NodeAnnotations.Name.class);
			String name = annotation.name();
			NodeFactory factory = new NodeFactory(c);
			Runtime.CreateNode(name, factory);
		}
	}

	public static List<Class<?>> GetNodeTypes() throws IOException, ClassNotFoundException {
		return Runtime.RegisteredNodes();
		/*
		 * ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		 * ClassPath cp = ClassPath.from(classLoader);
		 * 
		 * List<Class<?>> classes = new ArrayList<Class<?>>();
		 * ImmutableList<ClassInfo> cis = cp.getTopLevelClasses().asList();
		 * Runtime.RegisteredNodes();
		 * for (ClassInfo ci : cis) {
		 * if (ci.getName().contains("com.robomotion.") &&
		 * !ci.getName().contains("com.robomotion.app")) {
		 * Class<?> c = Class.forName(ci.getName());
		 * if (Node.class.isAssignableFrom(c)) {
		 * classes.add(c);
		 * }
		 * }
		 * }
		 * 
		 * return classes;
		 */
	}

	static JSONObject ReadConfigFile() throws Exception {
		FileReader reader;
		if (Files.exists(Paths.get("config.json")))
			reader = new FileReader("config.json");
		else if (Files.exists(Paths.get("..", "config.json")))
			reader = new FileReader("../config.json");
		else
			throw new Exception("Config file not found");

		return (JSONObject) new JSONParser().parse(reader);
	}
}