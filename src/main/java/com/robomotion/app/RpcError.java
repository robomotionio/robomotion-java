package com.robomotion.app;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RpcError extends Exception {
	private String code;
	private String message;
	
	public RpcError() {
	}
	
	public RpcError(String message) {
		super(message);
		this.message = message;
	}
	
	public RpcError(String code, String message) {
		super(message);
		this.message = message;
		this.code = code;
	}
	
	public RpcError(String message, Throwable cause) {
		super(message, cause);
		this.message = message;
	}
	
	public RpcError(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
		this.message = message;
	}
	
	public String Serialize() {
		Map<String, String> err = new HashMap<String, String>() {{
			put("code", code);
			put("message", message);
		}};
		return new String(Runtime.Serialize(err), StandardCharsets.UTF_8);
	}
}
