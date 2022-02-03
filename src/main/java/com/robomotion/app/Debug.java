package com.robomotion.app;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import com.google.protobuf.ByteString;
import com.robomotion.app.RunnerGrpc.RunnerBlockingStub;
import com.robomotion.app.RunnerProto.AttachRequest;
import com.robomotion.app.RunnerProto.DetachRequest;
import com.robomotion.app.RunnerProto.Null;
import com.robomotion.app.RunnerProto.RobotNameResponse;

import org.jutils.jprocesses.JProcesses;
import org.jutils.jprocesses.model.ProcessInfo;

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
	private static String attachedTo = "";

	public static Debug instance = new Debug();

	public static void Attach(String gAddr, String ns) {
		int pid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
		AttachConfig cfg = new AttachConfig(ProtocolGRPC, gAddr, pid, ns);

		byte[] cfgData = Runtime.Serialize(cfg);
		attachedTo = GetRPCAddr();
		if (attachedTo.isEmpty()) {
			System.out.println("empty gRPC address");
			System.exit(0);
		}

		System.out.println(String.format("Attached to %s", attachedTo));

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(attachedTo).usePlaintext(true).build();
		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);
		AttachRequest request = AttachRequest.newBuilder().setConfig(ByteString.copyFrom(cfgData)).build();
		stub.attach(request);
		channel.shutdownNow();
	}

	public static void Detach(String ns) {
		if (attachedTo == "") {
			System.out.println("empty gRPC address");
			System.exit(0);
		}

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(attachedTo).usePlaintext(true).build();
		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);
		DetachRequest request = DetachRequest.newBuilder().setNamespace(ns).build();
		stub.detach(request);
		channel.shutdownNow();
	}

	private static String GetRPCAddr() {
		List<SockTabEntry> tabs = GetNetStatPorts(State.LISTENING, "robomotion-runner");
		switch (tabs.size()) {
			case 0:
				return "";
			case 1:
				return tabs.get(0).localAddress;
			default:
				return SelectTab(tabs);
		}
	}

	private static String SelectTab(List<SockTabEntry> tabs) {
		int count = tabs.size();

		String robots = "";
		for (int i = 0; i < count; i++) {
			String addr = tabs.get(i).localAddress;
			String name = GetRobotName(addr);
			robots += String.format("%d) %s\n", i + 1, name);
		}

		int selected = 0;
		System.out.printf("\nFound %d robots running on the machine:\n", count);
		System.out.printf(robots);
		System.out.printf("Please select a robot to attach (1-%d): ", count);

		Scanner sc = new Scanner(System.in);
		while (true) {
			selected = sc.nextInt();
			if (selected > 0 && selected <= count) {
				sc.close();
				return tabs.get(selected - 1).localAddress;
			}
		}
	}

	private static String GetRobotName(String addr) {
		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr).usePlaintext(true).build();
		RunnerBlockingStub stub = RunnerGrpc.newBlockingStub(channel);
		Null request = Null.newBuilder().build();
		RobotNameResponse resp = stub.robotName(request);
		channel.shutdownNow();
		return resp.getRobotName();
	}

	public enum State {
		UNKNOWN, CLOSED, LISTENING, SYN_SENT, SYN_RECV, ESTABLISHED, FIN_WAIT1, FIN_WAIT2, CLOSE_WAIT, CLOSING,
		LAST_ACK, TIME_WAIT, DELETE_TCB,
	}

	private static String[] states = {
			"UNKNOWN", "", "LISTENING", "SYN_SENT", "SYN_RECV", "ESTABLISHED", "FIN_WAIT1", "FIN_WAIT2", "CLOSE_WAIT",
			"CLOSING", "LAST_ACK", "TIME_WAIT", "DELETE_TCB",
	};

	public static List<SockTabEntry> GetNetStatPorts(State state, String processName) {
		List<SockTabEntry> tabs = new ArrayList<SockTabEntry>();

		try {
			Process process = java.lang.Runtime.getRuntime().exec(new String[] { "netstat", "-a", "-n", "-o" });
			Scanner sc = new Scanner(process.getInputStream(), "IBM850");
			sc.useDelimiter("\\A");
			String content = sc.next();
			sc.close();

			JProcesses jp = JProcesses.get();
			jp.fastMode(true);
			List<ProcessInfo> processList = jp.listProcesses(processName);
			String[] rows = content.split("\r\n");

			for (String row : rows) {
				if (row.isEmpty())
					continue;

				String[] tokens = row.split("\\s+");
				if (tokens.length <= 5)
					continue;

				String pid = tokens[5];
				List<ProcessInfo> procs = processList.stream().filter(p -> p.getPid().compareTo(pid) == 0).toList();

				if (tokens.length > 4 && tokens[1].compareTo("TCP") == 0 &&
						tokens[4].compareTo(states[state.ordinal()]) == 0 &&
						procs.size() > 0) {

					String localAddr = tokens[2];
					tabs.add(new SockTabEntry() {
						{
							process = procs.get(0);
							localAddress = localAddr;
						}
					});
				}
			}

		} catch (Exception ex) {
			System.out.println(ex.toString());
		}

		return tabs;
	}

	public static class SockTabEntry {
		public ProcessInfo process;
		public String localAddress;
	}
}
