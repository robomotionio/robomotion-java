package com.robomotion.app;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import com.robomotion.app.RunnerGrpc.RunnerBlockingStub;
import com.robomotion.app.RunnerProto.AttachRequest;
import com.robomotion.app.RunnerProto.DetachRequest;
import com.robomotion.app.RunnerProto.Null;
import com.robomotion.app.RunnerProto.RobotNameResponse;

import org.apache.commons.lang3.SystemUtils;
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
		if (cfgData == null) {
			System.out.println("Failed to serialize attach config");
			System.exit(1);
		}

		try {
			attachedTo = GetRPCAddr();
		} catch (Exception e) {
			System.out.println(e.toString());
			System.exit(0);
		}

		if (attachedTo.isEmpty()) {
			System.out.println("empty gRPC address");
			System.exit(0);
		}

		System.out.println(String.format("Attached to %s", attachedTo));

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(attachedTo).usePlaintext().build();
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

		final ManagedChannel channel = ManagedChannelBuilder.forTarget(attachedTo).usePlaintext().build();
		DebugGrpc.DebugBlockingStub stub = DebugGrpc.newBlockingStub(channel);
		DetachRequest request = DetachRequest.newBuilder().setNamespace(ns).build();
		stub.detach(request);
		channel.shutdownNow();
	}

	private static String GetRPCAddr() throws Exception {
		List<SockTabEntry> tabs = GetNetStatPorts(State.LISTENING, "robomotion-runner");
		tabs = FilterTabs(tabs);

		switch (tabs.size()) {
			case 0:
				return "";
			case 1:
				return tabs.get(0).localAddress;
			default:
				return SelectTab(tabs);
		}
	}

	private static List<SockTabEntry> FilterTabs(List<SockTabEntry> tabs) {
		List<SockTabEntry> filtered = new ArrayList<>();

		for (SockTabEntry sockTabEntry : tabs) {
			String addr = sockTabEntry.localAddress;
			try {
				sockTabEntry.robotName = GetRobotName(addr);
				filtered.add(sockTabEntry);
			} catch (Exception e) {
				System.out.println(e.getMessage());
			}
		}

		return filtered;
	}

	private static String SelectTab(List<SockTabEntry> tabs) {
		int count = tabs.size();

		String robots = "";
		for (int i = 0; i < count; i++) {
			robots += String.format("%d) %s\n", i + 1, tabs.get(i).robotName);
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
		final ManagedChannel channel = ManagedChannelBuilder.forTarget(addr).usePlaintext().build();
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

	private static String[] statesWin = {
			"UNKNOWN", "", "LISTENING", "SYN_SENT", "SYN_RECV", "ESTABLISHED", "FIN_WAIT1", "FIN_WAIT2", "CLOSE_WAIT",
			"CLOSING", "LAST_ACK", "TIME_WAIT", "DELETE_TCB",
	};

	private static String[] statesUnix = {
			"UNKNOWN", "CLOSED", "LISTEN", "SYN_SENT", "SYN_RECV", "ESTABLISHED", "FIN_WAIT1", "FIN_WAIT2",
			"CLOSE_WAIT",
			"CLOSING", "LAST_ACK", "TIME_WAIT", "DELETE_TCB",
	};

	public static List<SockTabEntry> GetNetStatPorts(State state, String processName) throws Exception {
		if (SystemUtils.IS_OS_WINDOWS) {
			return getNetStatPortsWin(state, processName);
		} else if (SystemUtils.IS_OS_UNIX) {
			return getNetStatPortsUnix(state, processName);
		} else if (SystemUtils.IS_OS_MAC) {
			return getNetStatPortsDarwin(state, processName);
		}

		throw new Exception("OS is not supported");
	}

	private static List<SockTabEntry> getNetStatPortsWin(State state, String processName) {
		List<SockTabEntry> tabs = new ArrayList<SockTabEntry>();

		try {
			Process process = java.lang.Runtime.getRuntime()
					.exec(new String[] { "netstat", "-a", "-n", "-o", "-p", "tcp" });
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
				List<ProcessInfo> procs = processList.stream().filter(p -> p.getPid().compareTo(pid) == 0)
						.collect(Collectors.toList());

				if (tokens.length > 4 && tokens[1].compareTo("TCP") == 0 &&
						tokens[4].compareTo(statesWin[state.ordinal()]) == 0 &&
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

	private static List<SockTabEntry> getNetStatPortsUnix(State state, String processName) {
		List<SockTabEntry> tabs = new ArrayList<SockTabEntry>();

		try {
			Process process = java.lang.Runtime.getRuntime()
					.exec(new String[] { "netstat", "-a", "-n", "-o", "-p", "tcp" });
			Scanner sc = new Scanner(process.getInputStream(), "IBM850");
			sc.useDelimiter("\\A");
			String content = sc.next();
			sc.close();

			JProcesses jp = JProcesses.get();
			jp.fastMode(true);

			if (processName.length() > 15) {
				processName = processName.substring(0, 15);
			}

			List<ProcessInfo> processList = jp.listProcesses(processName);
			String[] rows = content.split("\n");

			for (String row : rows) {
				if (row.isEmpty())
					continue;

				String[] tokens = row.split("\\s+");
				if (tokens.length <= 7)
					continue;

				String[] pidStr = tokens[6].split("/");
				if (pidStr.length == 0) {
					continue;
				}

				String pid = pidStr[0];
				List<ProcessInfo> procs = processList.stream().filter(p -> p.getPid().compareTo(pid) == 0)
						.collect(Collectors.toList());

				if (tokens[0].compareTo("tcp") == 0 &&
						tokens[5].compareTo(statesUnix[state.ordinal()]) == 0 &&
						procs.size() > 0) {

					String localAddr = tokens[3];
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

	private static List<SockTabEntry> getNetStatPortsDarwin(State state, String processName) {
		List<SockTabEntry> tabs = new ArrayList<SockTabEntry>();

		try {
			Process process = java.lang.Runtime.getRuntime()
					.exec(new String[] { "netstat", "-a", "-n", "-v", "-p", "tcp" });
			Scanner sc = new Scanner(process.getInputStream(), "IBM850");
			sc.useDelimiter("\\A");
			String content = sc.next();
			sc.close();

			JProcesses jp = JProcesses.get();
			jp.fastMode(true);

			if (processName.length() > 15) {
				processName = processName.substring(0, 15);
			}

			List<ProcessInfo> processList = jp.listProcesses(processName);
			String[] rows = content.split("\n");

			for (String row : rows) {
				if (row.isEmpty())
					continue;

				String[] tokens = row.split("\\s+");
				if (tokens.length <= 10)
					continue;

				String pid = tokens[8];
				List<ProcessInfo> procs = processList.stream().filter(p -> p.getPid().compareTo(pid) == 0)
						.collect(Collectors.toList());

				if (tokens[0].compareTo("tcp4") == 0 &&
						tokens[5].compareTo(statesUnix[state.ordinal()]) == 0 &&
						procs.size() > 0) {

					String localAddr = tokens[3];
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
		public String robotName;
	}
}
