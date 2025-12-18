package com.robomotion.app;

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
			
			new Thread(new Runnable() {
			    public void run() {
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
		Node node = Runtime.Nodes().get(request.getGuid());
		
		try {
			Context ctx = new Message(data);
			node.OnMessage(ctx);

			byte[] outMessage = ctx.getRaw();
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
	public void onClose(OnCloseRequest request, StreamObserver<OnCloseResponse> responseObserver)
	{
		try {
			Runtime.Nodes().get(request.getGuid()).OnClose();
			
			OnCloseResponse response = OnCloseResponse.newBuilder().build();
			responseObserver.onNext(response);
			responseObserver.onCompleted();
			
			Runtime.activeNodes--;
			if (Runtime.activeNodes == 0) App.latch.countDown();
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
