package com.robomotion.app;

public class Node {
	public String guid="";
	public String name="";
	public Float delayBefore=0.0f;
	public Float delayAfter=0.0f;
	public Boolean continueOnError=false;
	public String scope="";

	public void OnCreate() throws Exception {};
	public void OnMessage(Context ctx) throws Exception {};
	public void OnClose() throws Exception {};
}
