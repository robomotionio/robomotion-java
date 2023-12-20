package com.robomotion.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.json.simple.parser.ParseException;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

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
		DocumentContext docCtx = JsonPath.parse(new String(data, StandardCharsets.UTF_8));
		JsonPath jsonPath = JsonPath.compile("$." + key);
		return (T) docCtx.read(jsonPath);
	}

	public byte[] GetRaw(boolean withUnpack) throws RuntimeNotInitializedException, ParseException, IOException  {
		if (withUnpack) {			
			return LargeMessageObject.UnpackMessageBytes(this.data);
		}
		
		return this.data;
	}

	public void SetRaw(byte[] data, boolean withPack) throws RuntimeNotInitializedException, IOException {
		if(withPack) {		
			this.data = LargeMessageObject.PackMessageBytes(data);			
		}else{
			this.data = data;
		}
	}

	public boolean IsEmpty() {
		return this.data == null || this.data.length == 0;
	}
}
