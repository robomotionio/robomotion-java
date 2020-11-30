package com.robomotion.app;

import java.util.HashMap;
import java.util.Map;

public class Message implements Context {
	private String ID;
	private byte[] data;

	public Message(byte[] data) {
		Map<String, Object> msg = new HashMap<String, Object>();
		msg = Runtime.Deserialize(data, msg.getClass());
		
		this.ID = msg.get("id").toString();
		this.data = data;
	}
	
	public String GetID() {
		return this.ID;
	}

	public void Set(String key, Object value) {
		Map<String, Object> msg = new HashMap<String, Object>();
		msg = Runtime.Deserialize(data, msg.getClass());
		
		msg.put(key, value);
		this.data = Runtime.Serialize(msg);
	}

	public <T> T Get(String key, Class<T> cls) {
		Map<String, Object> msg = new HashMap<String, Object>();
		msg = Runtime.Deserialize(data, msg.getClass());
		
		if (msg.containsKey(key)) {
			Object obj = msg.get(key);
			return cls.cast(obj);
		}
		
		return null;
	}

	public byte[] GetRaw() {
		return this.data;
	}
}
