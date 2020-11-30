package com.robomotion.app;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.ByteString;
import com.robomotion.app.Utils.File;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class Debug {
	public static class AttachConfig {
		public String protocol;
		public String addr;
		public int pid;
		public String namespace;
		
		public AttachConfig(String protocol, String addr, int pid, String namespace) {
			this.protocol = protocol;
			this.addr = addr;
			this.pid = pid;
			this.namespace = namespace;
		}
	}
	
	private static final String ProtocolInvalid = "";
	private static final String ProtocolNetRPC = "netrpc";
	private static final String ProtocolGRPC = "grpc";
	
	public static Debug instance = new Debug();
	
	public static void Attach(String gAddr, String ns) {
		int pid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
		AttachConfig cfg = new AttachConfig(ProtocolGRPC, gAddr, pid, ns);
		
		byte[] cfgData = Runtime.Serialize(cfg);
		String addr = GetRPCAddr();
		if (addr == "") {
			System.out.println("runner RPC address is null");
			System.exit(0);
		}
		
		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr)
		        .usePlaintext(true)
		        .build();
		
		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);
		
		
		AttachRequest request = AttachRequest.newBuilder()
				.setConfig(ByteString.copyFrom(cfgData)).build();
		
		stub.attach(request);

		channel.shutdownNow();
	}
	
	public static void Detach(String ns) {
		String addr = GetRPCAddr();
		if (addr == "") {
			System.out.println("runner RPC address is null");
			System.exit(0);
		}
		
		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr)
		        .usePlaintext(true)
		        .build();
		
		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);
		
		DetachRequest request = DetachRequest.newBuilder()
				.setNamespace(ns).build();
		
		stub.detach(request);

		channel.shutdownNow();
	}
	
	public static String GetRPCAddr() {
		String dir = File.TempDir();
		Path path = Paths.get(dir, "runcfg.json");
		
		try {
			byte[] data = Files.readAllBytes(path);
			Map<String, Object> dataMap = new HashMap<String, Object>();
			dataMap = Runtime.Deserialize(data, dataMap.getClass());
			
			return dataMap.get("listen").toString().replace("[::]", "127.0.0.1");
			
		} catch (IOException e) {
			e.printStackTrace();
			return "";
		}
	}
}
