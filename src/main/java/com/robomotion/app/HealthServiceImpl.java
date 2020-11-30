package com.robomotion.app;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import grpc.health.v1.HealthGrpc.HealthImplBase;
import grpc.health.v1.HealthOuterClass.HealthCheckRequest;
import grpc.health.v1.HealthOuterClass.HealthCheckResponse;
import grpc.health.v1.HealthOuterClass.HealthCheckResponse.ServingStatus;
import io.grpc.stub.StreamObserver;

public class HealthServiceImpl extends HealthImplBase
{
	ReentrantLock lock = new ReentrantLock();
	private Map<String, ServingStatus> statusMap = new HashMap<String, ServingStatus>();
	
	public void ClearAll()
	{
		lock.lock();
		statusMap.clear();
		lock.unlock();
	}
	
	public void ClearStatus(String service)
	{
		lock.lock();
		statusMap.remove(service);
		lock.unlock();
	}
	
	public void SetStatus(String service, ServingStatus status)
	{
		lock.lock();
		statusMap.put(service, status);
		lock.unlock();
	}
	
	@Override
	public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver)
	{
		String service = request.getService();
		ServingStatus status = statusMap.get(service);
		HealthCheckResponse response = HealthCheckResponse.newBuilder().setStatus(status).build();
		
		responseObserver.onNext(response);
		responseObserver.onCompleted();
	}
}
