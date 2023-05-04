package com.robomotion.app;

public interface Context {
	public String GetID();

	public void Set(String key, Object value);

	public <T> T Get(String key, Class<T> cls);

	public byte[] GetRaw();

	public void SetRaw(byte[] data);

	public boolean IsEmpty();
}
