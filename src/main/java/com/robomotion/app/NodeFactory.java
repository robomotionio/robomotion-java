package com.robomotion.app;

public class NodeFactory 
{
	private Class<?> c;
	public NodeFactory(Class<?> c) 
	{
		this.c = c;
	}
	
	public void OnCreate(byte[] config) throws Exception 
	{
		Node node = (Node) Runtime.Deserialize(config, this.c);
		Runtime.AddNode(node.guid, node);
		node.OnCreate();
	}
}