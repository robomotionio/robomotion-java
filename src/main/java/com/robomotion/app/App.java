package com.robomotion.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;

import grpc.health.v1.HealthOuterClass.HealthCheckResponse.ServingStatus;
import io.grpc.Server;
import io.grpc.ServerBuilder;


public class App
{
	public interface Initializer {
		public void Init();
	}

	private static String ns = "";
	
	@FieldAnnotations.Default(cls = boolean.class)
	public static CountDownLatch latch = new CountDownLatch(1);
	public static Initializer initializer = null;
	
    public static void Start(String[] args)
    {
    	try {
    		if (args.length > 2 && args[0].compareTo("-s") == 0) {
    			Spec.GenerateSpec(args[1], args[2]);
    			return;
    		}
    		
    		Init();
    		if (initializer != null) initializer.Init();
    		
    		HealthServiceImpl health = new HealthServiceImpl();
    		health.SetStatus("plugin", ServingStatus.SERVING);
    		
        	Server server = ServerBuilder.forPort(0)
        			.addService(health)
        			.addService(new NodeServer())
        			.build();
        	
			server.start();
			
			System.out.printf("1|1|tcp|127.0.0.1:%d|grpc\n", server.getPort());
			System.out.flush();
			
			if (args.length > 1 && args[0].compareTo("-a") == 0) { // attach
				ns = args[1];
				Debug.Attach(String.format("127.0.0.1:%d", server.getPort()), ns);
			}

			latch.await();
	    	//server.awaitTermination();
			
			if (ns != "") {
				Debug.Detach(ns);
			}
	    	
		} catch (Exception e) {
			e.printStackTrace();
		}
    }
    
    public static void Init() throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException
    {
    	List classes = GetNodeTypes();
    	
    	for (Object obj : classes) {
    		Class<?> c = (Class<?>) obj;
    		NodeAnnotations.Name annotation = c.getAnnotation(NodeAnnotations.Name.class);
    		String name = annotation.name();
    		NodeFactory factory = new NodeFactory(c);
    		Runtime.CreateNode(name, factory);
    	}
    }
    
    public static List<Class<?>> GetNodeTypes() throws IOException, ClassNotFoundException 
    {
    	ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    	ClassPath cp =  ClassPath.from(classLoader);
    	
    	List<Class<?>> classes = new ArrayList<Class<?>>();
    	ImmutableList<ClassInfo> cis = cp.getTopLevelClasses().asList();
    	for (ClassInfo ci : cis) {
    		if (ci.getName().contains("com.robomotion.") && !ci.getName().contains("com.robomotion.app")) {
            	Class<?> c = Class.forName(ci.getName());
        		if (Node.class.isAssignableFrom(c)) {
        			classes.add(c);
        		}
    		}
    	}

    	return classes;
    }
}