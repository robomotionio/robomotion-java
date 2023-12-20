package com.robomotion.app;

import java.io.IOException;

import org.json.simple.parser.ParseException;

public interface Context {
	public String GetID();

	public void Set(String key, Object value);

	public <T> T Get(String key, Class<T> cls);

	public byte[] GetRaw(boolean withUnpack) throws RuntimeNotInitializedException, ParseException, IOException;

	public void SetRaw(byte[] data, boolean withPack);

	public boolean IsEmpty();
}
