package com.robomotion.app;

import java.util.Map;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

import com.google.protobuf.ByteString;
import com.robomotion.app.NodeGrpc.NodeImplBase;

public class NodeServer extends NodeImplBase 
{
	@Override
	public void init(InitRequest request, StreamObserver<Empty> responseObserver)
	{
		try {
			final ManagedChannel channel = ManagedChannelBuilder.forTarget("127.0.0.1:"+ request.getPort())
			        .usePlaintext()
			        .build();

			RuntimeHelperGrpc.RuntimeHelperBlockingStub stub = RuntimeHelperGrpc.newBlockingStub(channel);
			Runtime.SetClient(stub);

			// Fetch robot capabilities and LMO store path asynchronously.
			// Must NOT call GetRobotInfo() synchronously here — the host is
			// waiting for Init() to return before it can handle callbacks,
			// so a synchronous gRPC call back to the host would deadlock.
			new Thread(new Runnable() {
			    public void run() {
					try {
						Map<String, Object> info = Runtime.GetRobotInfo();
						Object capsObj = info.get("capabilities");
						if (capsObj instanceof Number) {
							Runtime.SetRobotCapabilities(((Number) capsObj).longValue());
						}
						Object storePath = info.get("lmo_store_path");
						if (storePath != null && !storePath.toString().isEmpty()) {
							LMO.init(storePath.toString());
						}
					} catch (Exception ex) {
						System.err.println("lmo: init during startup: " + ex.getMessage());
					}
					Runtime.CheckRunnerConn(channel);
			    }
			}).start();
			
			responseObserver.onNext(Empty.newBuilder().build());
			responseObserver.onCompleted();
		}
		catch (Exception e) {
			RpcError err;
			if (e instanceof RpcError) err = (RpcError)e;
			else err = new RpcError("Err.Unknown", e.toString());
			responseObserver.onError(Status.UNKNOWN
					.withDescription(err.Serialize())
					.withCause(err)
					.asRuntimeException());
		}
	}
	
	@Override
	public void onCreate(OnCreateRequest request, StreamObserver<OnCreateResponse> responseObserver)
	{
		try {
			Runtime.activeNodes++;
			
			byte[] config = request.getConfig().toByteArray();
			Runtime.Factories().get(request.getName()).OnCreate(config);

			OnCreateResponse response = OnCreateResponse.newBuilder().build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
		catch (Exception e) {
			RpcError err;
			if (e instanceof RpcError) err = (RpcError)e;
			else err = new RpcError("Err.Unknown", e.toString());
			responseObserver.onError(Status.UNKNOWN
					.withDescription(err.Serialize())
					.withCause(err)
					.asRuntimeException());
		}
	}
	
	@Override
	public void onMessage(OnMessageRequest request, StreamObserver<OnMessageResponse> responseObserver)
	{
		byte[] data = Runtime.Decompress(request.getInMessage().toByteArray());
		data = LMO.resolveAll(data);
		Node node = Runtime.Nodes().get(request.getGuid());

		try {
			Context ctx = new Message(data);
			node.OnMessage(ctx);

			byte[] outMessage = Runtime.IsLMOCapable() ? LMO.pack(ctx.getRaw()) : ctx.getRaw();
			OnMessageResponse response = OnMessageResponse.newBuilder().setOutMessage(ByteString.copyFrom(outMessage)).build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
		}
		catch (Exception e) {
			if (!node.continueOnError) {
				RpcError err;
				if (e instanceof RpcError) err = (RpcError)e;
				else err = new RpcError("Err.Unknown", e.toString());
				responseObserver.onError(Status.UNKNOWN
						.withDescription(err.Serialize())
						.withCause(err)
						.asRuntimeException());
			} else {
				OnMessageResponse response = OnMessageResponse.newBuilder().setOutMessage(ByteString.copyFrom(data)).build();
				responseObserver.onNext(response);
				responseObserver.onCompleted();
			}
		}
	}
	
	@Override
	public void getCapabilities(Empty request, StreamObserver<GetCapabilitiesResponse> responseObserver)
	{
		GetCapabilitiesResponse response = GetCapabilitiesResponse.newBuilder()
				.setCapabilities(Runtime.packageCapabilities)
				.build();
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}

	@Override
	public void onClose(OnCloseRequest request, StreamObserver<OnCloseResponse> responseObserver)
	{
		try {
			Runtime.Nodes().get(request.getGuid()).OnClose();
			
			OnCloseResponse response = OnCloseResponse.newBuilder().build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
			Runtime.activeNodes--;
			if (Runtime.activeNodes == 0 && !Runtime.sessionMode) App.latch.countDown();
		}
		catch (Exception e) {
			RpcError err;
			if (e instanceof RpcError) err = (RpcError)e;
			else err = new RpcError("Err.Unknown", e.toString());
			responseObserver.onError(Status.UNKNOWN
					.withDescription(err.Serialize())
					.withCause(err)
					.asRuntimeException());
		}
	}
}
