package com.robomotion.app;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.deser.impl.ExternalTypeHandler.Builder;
import com.google.protobuf.ListValue;
import com.google.protobuf.Value;

public class Struct {
	private com.google.protobuf.Struct struct;
	
	public Struct(com.google.protobuf.Struct struct) 
	{
		this.struct = struct;
	}
	
	public Object Parse() 
	{
		Value value = this.struct.getFieldsMap().get("value");
		return this.convert(value);
	}
	
	private Object convert(Value value)
	{
		switch (value.getKindCase())
		{
		case BOOL_VALUE:
			return value.getBoolValue();
			
		case NULL_VALUE:
			return value.getNullValue();
			
		case NUMBER_VALUE:
			return value.getNumberValue();
			
		case STRING_VALUE:
			return value.getStringValue();
			
		case LIST_VALUE:
			List<Value> values = value.getListValue().getValuesList();
			List<Object> arr = new ArrayList<Object>();
			for (Value val : values) {
				arr.add(this.convert(val));
			}
			
			return arr;
			
		case STRUCT_VALUE:
			Map<String, Value> fields = value.getStructValue().getFieldsMap();
			Map<String, Object> stMap = new HashMap<String, Object>();
			
			for (String key : fields.keySet()) {
				Value val = fields.get(key);
				stMap.put(key, this.convert(val));
			}
			
			return stMap;
			
		default:
			return null;
		}
	}
	
	public static Value ToValue(Object obj) {
		if (obj == null) return Value.newBuilder().setNullValue(null).build();
		else if (obj instanceof Boolean) return Value.newBuilder().setBoolValue((Boolean)obj).build();
		else if (obj instanceof Integer) return Value.newBuilder().setNumberValue((Integer)obj).build();
		else if (obj instanceof Byte) return Value.newBuilder().setNumberValue((Float)obj).build();
		else if (obj instanceof Short) return Value.newBuilder().setNumberValue((Float)obj).build();
		else if (obj instanceof Long) return Value.newBuilder().setNumberValue((Float)obj).build();
		else if (obj instanceof Float) return Value.newBuilder().setNumberValue((Float)obj).build();
		else if (obj instanceof Double) return Value.newBuilder().setNumberValue((Float)obj).build();
		else if (obj instanceof String) return Value.newBuilder().setStringValue((String)obj).build();
		else if (obj instanceof ArrayList<?>) {
			ArrayList<?> arr = (ArrayList<?>) obj;
			com.google.protobuf.ListValue.Builder list = ListValue.newBuilder();

			int index = 0;
			for (Object element : arr) {
				list.setValues(index, ToValue(element));
			}
			
			return Value.newBuilder().setListValue(list.build()).build();
		}
		else if (obj instanceof Class<?>) {
			Field[] fields = ((Class) obj).getFields();
			com.google.protobuf.Struct st = com.google.protobuf.Struct.newBuilder().build();
			
			for (Field field : fields) {
				Object value;
				try {
					value = field.get(obj);
				} catch (IllegalArgumentException e) {
					continue;
				} catch (IllegalAccessException e) {
					continue;
				}
				st.toBuilder().putFields(field.getName(), ToValue(value));
			}
			
			return Value.newBuilder().setStructValue(st).build();
		}
		else if (obj instanceof Map<?, ?>) {
			Map<String,?> map = (Map<String,?>)obj;			
			com.google.protobuf.Struct.Builder st = com.google.protobuf.Struct.newBuilder(); // Use a builder to modify the Struct

			for (String key : map.keySet()) {
				Object value = map.get(key);
				st.putFields(key, ToValue(value)); // Add the field to the Struct
			}
			return Value.newBuilder().setStructValue(st.build()).build();
		}
		
		return Value.newBuilder().setStringValue(String.format("%s", obj)).build();
	}
}
