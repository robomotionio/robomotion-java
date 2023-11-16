package com.robomotion.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.google.protobuf.Value;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

public class Runtime {
	private static RuntimeHelperGrpc.RuntimeHelperBlockingStub client;
	private static Map<String, NodeFactory> factories = new HashMap<String, NodeFactory>();
	private static Map<String, Node> nodes = new HashMap<String, Node>();
	public static int activeNodes = 0;
	public static Boolean started = false;
	private static List<Class<?>> handlers;

	public static void SetClient(RuntimeHelperGrpc.RuntimeHelperBlockingStub cli) {
		client = cli;
	}

	public static RuntimeHelperGrpc.RuntimeHelperBlockingStub GetClient() {
		return client;
	}

	public static void CheckRunnerConn(ManagedChannel ch) {
		while (true) {
			try {
				ConnectivityState state = ch.getState(true);

				switch (state) {
					case CONNECTING:
					case IDLE:
					case READY:
						break;

					default:
						App.latch.countDown();
						return;
				}
				Thread.sleep(1000);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static void CreateNode(String name, NodeFactory factory) {
		factories.put(name, factory);
	}

	public static Map<String, NodeFactory> Factories() {
		return factories;
	}

	public static void AddNode(String guid, Node node) {
		nodes.put(guid, node);
	}

	public static Map<String, Node> Nodes() {
		return nodes;
	}

	public static void RegisterNodes(Class<?>... handlers) {
		Runtime.handlers = List.of(handlers);
	}

	public static List<Class<?>> RegisteredNodes() {
		return Runtime.handlers;
	}

	public static byte[] Compress(byte[] data) {
		try {
			OutputStream stream = new ByteArrayOutputStream();
			GZIPOutputStream gos = new GZIPOutputStream(stream);
			gos.write(data);
			gos.close();

			return stream.toString().getBytes();
		} catch (Exception e) {
			// TODO: handle exception
		}
		return null;
	}

	public static byte[] Decompress(byte[] data) {
		try {
			InputStream stream = new ByteArrayInputStream(data);
			GZIPInputStream gis = new GZIPInputStream(stream);
			OutputStream os = new ByteArrayOutputStream();

			byte[] buffer = new byte[1024];
			int len;
			while ((len = gis.read(buffer)) != -1) {
				os.write(buffer, 0, len);
			}

			stream.close();
			gis.close();

			return os.toString().getBytes();
		} catch (Exception e) {
			// TODO: handle exception
		}
		return null;
	}

	public static byte[] Serialize(Object object) {
		try {
			return (new ObjectMapper()).writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			return null;
		}
	}

	public static <T> T Deserialize(byte[] data, Class<T> classOfT) {
		Gson g = new Gson();
		return g.fromJson(new String(data, StandardCharsets.UTF_8), classOfT);
	}

	public static void Close() throws RuntimeNotInitializedException {
		if (client == null)
			throw new RuntimeNotInitializedException();

		Empty request = Empty.newBuilder().build();
		client.close(request);
	}

	public static <T> T GetVariable(Variable variable, Context ctx) throws RuntimeNotInitializedException, IOException {
		if (variable.scope.compareTo("Custom") == 0)
			return (T) variable.name;
		if (variable.scope.compareTo("Message") == 0) {

			T val = (T) ctx.Get(variable.name, null);
			System.out.println("the val is : ");
			System.out.println(val);
			System.out.println("type: " + (val instanceof HashMap));
			if (val instanceof HashMap ) {
				Map<String, Object> obj = (HashMap<String, Object>) val;
				Object isDataCut = obj.get("is_data_cut");
				System.out.println("the is data cut");
				System.out.println(isDataCut);
				if (isDataCut instanceof Boolean && (Boolean)isDataCut) {
					Object robomotion_capnp_id = obj.get("robomotion_capnp_id");
					System.out.println("robomotion_capnp_id: " + robomotion_capnp_id);
					System.out.println("is instance " + robomotion_capnp_id instanceof String);
					if (robomotion_capnp_id instanceof String  && ((String)robomotion_capnp_id).startsWith(RobomotionCapnp.ROBOMOTION_CAPNP_PREFIX)) {
						return (T) RobomotionCapnp.readFromFile((String)robomotion_capnp_id);
					}
				}
				
			} 

			return (T) ctx.Get(variable.name, null);
		}

		if (client == null)
			throw new RuntimeNotInitializedException();

		com.robomotion.app.Variable var = com.robomotion.app.Variable.newBuilder().setScope(variable.scope)
				.setName(variable.name).setPayload(ByteString.copyFrom(ctx.GetRaw())).build();

		GetVariableRequest request = GetVariableRequest.newBuilder().setVariable(var).build();

		GetVariableResponse response = client.getVariable(request);
		Struct st = new Struct(response.getValue());
		return (T) st.Parse();
	}

	public static <T> void SetVariable(Variable variable, Context ctx, T value) throws RuntimeNotInitializedException, IOException {
		if (variable.scope.compareTo("Message") == 0) {
			 
			value = (T) com.robomotion.app.RobomotionCapnp.writeAddressBook(value, GetRobotInfo());
			ctx.Set(variable.name, value);
		}

		if (client == null)
			throw new RuntimeNotInitializedException();

		Value val = Struct.ToValue(value);
		com.google.protobuf.Struct st = com.google.protobuf.Struct.newBuilder().putFields("value", val).build();

		com.robomotion.app.Variable var = com.robomotion.app.Variable.newBuilder().setScope(variable.scope)
				.setName(variable.name).build();

		SetVariableRequest request = SetVariableRequest.newBuilder().setVariable(var).setValue(st).build();

		client.setVariable(request);
	}

	public static Map<String, Object> GetRobotInfo() throws RuntimeNotInitializedException {
		if (client == null)
			throw new RuntimeNotInitializedException();

		Empty request = Empty.newBuilder().build();
		GetRobotInfoResponse response = client.getRobotInfo(request);

		Struct st = new Struct(response.getRobot());
		return (Map<String, Object>) st.Parse();
	}

	public static String GetRobotVersion() throws RuntimeNotInitializedException {
		Map<String, Object> info = GetRobotInfo();
		return info.get("version").toString();
	}

	public static class Variable<T> {
		public String scope;
		public String name;

		public Variable(String scope, String name) {
			this.scope = scope;
			this.name = name;
		}
	}

	public static class InVariable<T> extends Variable<T> {
		public InVariable(String scope, String name) {
			super(scope, name);
		}

		public T Get(Context ctx) throws RuntimeNotInitializedException, IOException {
			return Runtime.GetVariable(this, ctx);
		}
	}

	public static class OutVariable<T> extends Variable<T> {

		public OutVariable(String scope, String name) {
			super(scope, name);
		}

		public void Set(Context ctx, T value) throws RuntimeNotInitializedException, IOException {
			Runtime.SetVariable(this, ctx, value);
		}
	}

	public static class OptVariable<T> extends Variable<T> {
		public OptVariable(String scope, String name) {
			super(scope, name);
		}

		public T Get(Context ctx) throws RuntimeNotInitializedException, IOException {
			return Runtime.GetVariable(this, ctx);
		}
	}

	public static class Credential {
		public String scope;
		public Object name;

		@Deprecated
		public String vaultId;
		@Deprecated
		public String itemId;

		public Credential(String scope, Object name, String vaultId, String itemId) {
			this.scope = scope;
			this.name = name;
			this.vaultId = vaultId;
			this.itemId = itemId;
		}

		public Map<String, Object> Get(Context ctx) throws RuntimeNotInitializedException, IOException {
			if (client == null)
				throw new RuntimeNotInitializedException();

			_credential creds;
			if (this.vaultId != null && this.itemId != null) {
				creds = new _credential(this.vaultId, this.itemId);
			} else {
				Object cr = this.name;
				if (this.scope.compareTo("Message") == 0) {
					InVariable<Object> v = new InVariable<Object>(this.scope, this.name.toString());
					cr = v.Get(ctx);
				}

				Map<String, Object> crMap = (Map<String, Object>) cr;
				creds = new _credential((String) crMap.get("vaultId"), (String) crMap.get("itemId"));
			}

			GetVaultItemRequest request = GetVaultItemRequest.newBuilder().setItemId(creds.itemId)
					.setVaultId(creds.vaultId).build();

			GetVaultItemResponse response = client.getVaultItem(request);
			Struct st = new Struct(response.getItem());
			return (Map<String, Object>) st.Parse();
		}

		public Map<String, Object> Set(Context ctx, byte[] data) throws RuntimeNotInitializedException, IOException {
			if (client == null)
				throw new RuntimeNotInitializedException();

			_credential creds;
			if (this.vaultId != null && this.itemId != null) {
				creds = new _credential(this.vaultId, this.itemId);
			} else {
				Object cr = this.name;
				if (this.scope.compareTo("Message") == 0) {
					InVariable<Object> v = new InVariable<Object>(this.scope, this.name.toString());
					cr = v.Get(ctx);
				}

				Map<String, Object> crMap = (Map<String, Object>) cr;
				creds = new _credential((String) crMap.get("vaultId"), (String) crMap.get("itemId"));
			}

			SetVaultItemRequest request = SetVaultItemRequest.newBuilder().setVaultId(creds.vaultId)
					.setItemId(creds.itemId).setData(ByteString.copyFrom(data)).build();

			SetVaultItemResponse response = client.setVaultItem(request);
			Struct st = new Struct(response.getItem());
			return (Map<String, Object>) st.Parse();
		}
	}

	private static class _credential {
		public String vaultId;
		public String itemId;

		public _credential(String vaultId, String itemId) {
			this.vaultId = vaultId;
			this.itemId = itemId;
		}
	}

	public static void AppRequest(byte[] data, int timeout) throws RuntimeNotInitializedException {
		RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
		if (client == null)
			throw new RuntimeNotInitializedException();

		AppRequestRequest request = AppRequestRequest.newBuilder()
				.setRequest(ByteString.copyFrom(data))
				.setTimeout(timeout)
				.build();

		client.appRequest(request);
	}

	public static void DownloadFile(String url, String path) throws RuntimeNotInitializedException {
		RuntimeHelperGrpc.RuntimeHelperBlockingStub client = Runtime.GetClient();
		if (client == null)
			throw new RuntimeNotInitializedException();

		DownloadFileRequest request = DownloadFileRequest.newBuilder()
				.setUrl(url)
				.setPath(path)
				.build();

		client.downloadFile(request);
	}
}
