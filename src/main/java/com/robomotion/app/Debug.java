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
		String addr = System.getenv("ATTACH_TO");
		if (addr == null) {
			System.out.println("Please specify ATTACH_TO environment variable to attach");
			System.exit(0);
		}

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr).usePlaintext(true).build();

		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);

		AttachRequest request = AttachRequest.newBuilder().setConfig(ByteString.copyFrom(cfgData)).build();

		stub.attach(request);

		channel.shutdownNow();
	}

	public static void Detach(String ns) {
		String addr = System.getenv("ATTACH_TO");
		if (addr == "") {
			System.out.println("ATTACH_TO environment variable is null");
			System.exit(0);
		}

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr).usePlaintext(true).build();

		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);

		DetachRequest request = DetachRequest.newBuilder().setNamespace(ns).build();

		stub.detach(request);

		channel.shutdownNow();
	}
}
