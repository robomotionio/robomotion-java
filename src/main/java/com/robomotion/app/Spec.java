package com.robomotion.app;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;
import com.robomotion.app.FieldAnnotations.ECategory;
import com.robomotion.app.Runtime.InVariable;
import com.robomotion.app.Runtime.OutVariable;
import com.robomotion.app.Runtime.OptVariable;
import com.robomotion.app.Runtime.Credential;

public class Spec {
	private static class JObject extends HashMap<String, Object> {
		public JObject() {
			super();
		}
	}

	private static class JArray extends ArrayList<Object> {
		public JArray() {
			super();
		}
	}

	public static void GenerateSpec(final String pluginName, final String version)
			throws ClassNotFoundException, IOException {

		List<Class<?>> classes = App.GetNodeTypes();

		JObject pspec = new JObject();
		pspec.put("name", pluginName);
		pspec.put("version", version);

		final JArray nodes = new JArray();

		for (Class<?> c : classes) {
			JObject node = new JObject();
			node.put("name", GetTitle(c));
			node.put("color", GetColor(c));
			node.put("icon", GetIcon(c));
			node.put("id", GetNamespace(c));
			node.put("inputs", GetInputCount(c));
			node.put("outputs", GetOutputCount(c));

			String editor = GetEditor(c);
			if (editor != "")
				node.put("editor", editor);

			final JArray properties = new JArray();
			List<Field> inputs = GetInputs(c);
			List<Field> inputVars = GetInputVars(c);

			if (inputs.size() + inputVars.size() > 0) {
				JObject property = new JObject();

				JObject pSchema = new JObject();
				JObject pUISchema = new JObject();
				JObject formData = new JObject();

				pSchema.put("title", "Input");
				pSchema.put("type", "object");

				JObject inProperties = new JObject();
				JArray uiOrder = new JArray();

				for (Field input : inputVars) {
					JObject inObject = new JObject();
					inObject.put("type", "object");
					inObject.put("title", GetTitle(input));
					inObject.put("properties", new JObject() {
						{
							put("scope", new JObject() {
								{
									put("type", "string");
								}
							});
							put("name", new JObject() {
								{
									put("type", "string");
								}
							});
						}
					});

					String varType = ((ParameterizedType) input.getGenericType()).getActualTypeArguments()[0]
							.toString();
					String[] parts = varType.split("\\.");
					inObject.put("variableType", parts[parts.length - 1]);

					if (CustomScope(input))
						inObject.put("customScope", true);
					if (MessageScope(input))
						inObject.put("messageScope", true);
					if (MessageOnly(input))
						inObject.put("messageOnly", true);

					String description = GetDescription(input);
					if (description != "")
						inObject.put("description", description);

					String name = LowerFirstLetter(input.getName());
					final String format = GetFormat(input);
					if (format != "") {
						inObject.put("format", format);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", format);
							}
						});
					} else {
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "variable");
							}
						});
					}

					formData.put(name, GetDefault(input));
					inProperties.put(name, inObject);
					uiOrder.add(name);
				}

				for (Field input : inputs) {
					JObject inObject = new JObject();
					String[] parts = input.getType().toString().split("\\.");

					inObject.put("type", parts[parts.length - 1].toLowerCase());
					inObject.put("title", GetTitle(input));

					String name = LowerFirstLetter(input.getName());
					String description = GetDescription(input);
					if (description != "") {
						inObject.put("description", description);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "input");
							}
						});
					}

					if (IsHidden(input)) {
						pUISchema.put(name, new JObject() {
							{
								put("ui:widget", "hidden");
							}
						});
					}

					final String format = GetFormat(input);
					if (format != "") {
						inObject.put("format", format);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", format);
							}
						});
					}

					formData.put(name, GetDefault(input));
					inProperties.put(name, inObject);
					uiOrder.add(name);
				}

				pSchema.put("properties", inProperties);
				pUISchema.put("ui:order", uiOrder);

				property.put("schema", pSchema);
				property.put("uiSchema", pUISchema);
				property.put("formData", formData);

				properties.add(property);
			}

			List<Field> outputs = GetOutputs(c);
			List<Field> outputVars = GetOutputVars(c);

			if (outputs.size() + outputVars.size() > 0) {
				JObject property = new JObject();

				JObject pSchema = new JObject();
				JObject pUISchema = new JObject();
				JObject formData = new JObject();

				pSchema.put("title", "Output");
				pSchema.put("type", "object");

				JObject outProperties = new JObject();
				JArray uiOrder = new JArray();

				for (Field output : outputVars) {
					JObject outObject = new JObject();
					outObject.put("type", "object");
					outObject.put("title", GetTitle(output));
					outObject.put("properties", new JObject() {
						{
							put("scope", new JObject() {
								{
									put("type", "string");
								}
							});
							put("name", new JObject() {
								{
									put("type", "string");
								}
							});
						}
					});

					String varType = ((ParameterizedType) output.getGenericType()).getActualTypeArguments()[0]
							.toString();
					String[] parts = varType.split("\\.");
					outObject.put("variableType", parts[parts.length - 1]);

					if (CustomScope(output))
						outObject.put("customScope", true);
					if (MessageScope(output))
						outObject.put("messageScope", true);
					if (MessageOnly(output))
						outObject.put("messageOnly", true);

					String description = GetDescription(output);
					if (description != "")
						outObject.put("description", description);

					String name = LowerFirstLetter(output.getName());

					formData.put(name, GetDefault(output));
					outProperties.put(name, outObject);
					uiOrder.add(name);

					pUISchema.put(name, new JObject() {
						{
							put("ui:field", "variable");
						}
					});
				}

				for (Field output : outputs) {
					JObject outObject = new JObject();
					String[] parts = output.getType().toString().split("\\.");

					outObject.put("type", parts[parts.length - 1].toLowerCase());
					outObject.put("title", GetTitle(output));

					String name = LowerFirstLetter(output.getName());
					String description = GetDescription(output);
					if (description != "") {
						outObject.put("description", description);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "input");
							}
						});
					}

					if (IsHidden(output)) {
						pUISchema.put(name, new JObject() {
							{
								put("ui:widget", "hidden");
							}
						});
					}

					formData.put(name, GetDefault(output));
					outProperties.put(name, outObject);
					uiOrder.add(name);
				}

				pSchema.put("properties", outProperties);
				pUISchema.put("ui:order", uiOrder);

				property.put("schema", pSchema);
				property.put("uiSchema", pUISchema);
				property.put("formData", formData);

				properties.add(property);
			}

			List<Field> options = GetOptions(c);
			List<Field> optionVars = GetOptionVars(c);

			if (options.size() + optionVars.size() > 0) {
				JObject property = new JObject();

				JObject pSchema = new JObject();
				JObject pUISchema = new JObject();
				JObject formData = new JObject();

				pSchema.put("title", "Options");
				pSchema.put("type", "object");

				JObject optProperties = new JObject();
				JArray uiOrder = new JArray();

				for (Field option : optionVars) {
					JObject optObject = new JObject();
					optObject.put("type", "object");
					optObject.put("title", GetTitle(option));
					optObject.put("properties", new JObject() {
						{
							put("scope", new JObject() {
								{
									put("type", "string");
								}
							});
							put("name", new JObject() {
								{
									put("type", "string");
								}
							});
						}
					});

					String varType = ((ParameterizedType) option.getGenericType()).getActualTypeArguments()[0]
							.toString();
					String[] parts = varType.split("\\.");
					optObject.put("variableType", parts[parts.length - 1]);

					if (CustomScope(option))
						optObject.put("customScope", true);
					if (MessageScope(option))
						optObject.put("messageScope", true);
					if (MessageOnly(option))
						optObject.put("messageOnly", true);

					String description = GetDescription(option);
					if (description != "")
						optObject.put("description", description);

					String name = LowerFirstLetter(option.getName());
					final String format = GetFormat(option);
					if (format != "") {
						optObject.put("format", format);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", format);
							}
						});
					} else {
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "variable");
							}
						});
					}

					formData.put(name, GetDefault(option));
					optProperties.put(name, optObject);
					uiOrder.add(name);
				}

				for (Field option : options) {
					JObject optObject = new JObject();

					optObject.put("type", GetType(option).toLowerCase());
					optObject.put("title", GetTitle(option));

					ECategory category = GetCategory(option);
					if (category != ECategory.Null)
						optObject.put("category", category.getCategory());

					Object[] enums = GetEnum(option);
					if (enums != null) {
						Object[] enumNames = GetEnumNames(option);
						optObject.put("enum", enums);
						optObject.put("enumNames", enumNames);
					}

					String name = LowerFirstLetter(option.getName());
					String description = GetDescription(option);
					if (description != "") {
						optObject.put("description", description);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "input");
							}
						});
					}

					final String format = GetFormat(option);
					if (format != "") {
						optObject.put("format", format);
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", format);
							}
						});
					}

					if (Credential.class.isAssignableFrom(option.getType())) {
						optObject.put("subtitle", GetTitle(option));
						optObject.put("properties", new JObject() {
							{
								put("scope", new JObject() {
									{
										put("type", "string");
									}
								});
								put("name", new JObject() {
									{
										put("type", "object");
										put("properties", new JObject() {
											{
												put("vaultId", new JObject() {
													{
														put("type", "string");
													}
												});
												put("itemId", new JObject() {
													{
														put("type", "string");
													}
												});
											}
										});
									}
								});
							}
						});
						formData.put(name, new JObject() {
							{
								put("scope", "Custom");
								put("name", new JObject() {
									{
										put("vaultId", "_");
										put("itemId", "_");
									}
								});
							}
						});
						pUISchema.put(name, new JObject() {
							{
								put("ui:field", "vault");
							}
						});
					}

					if (IsHidden(option)) {
						pUISchema.put(name, new JObject() {
							{
								put("ui:widget", "hidden");
							}
						});
					}

					optProperties.put(name, optObject);
					uiOrder.add(name);

					Object def = GetDefault(option);
					if (def != null)
						formData.put(name, def);
				}

				pSchema.put("properties", optProperties);
				pUISchema.put("ui:order", uiOrder);

				property.put("schema", pSchema);
				property.put("uiSchema", pUISchema);
				property.put("formData", formData);

				properties.add(property);
			}

			node.put("properties", properties);
			nodes.add(node);
		}

		pspec.put("nodes", nodes);

		String json = new ObjectMapper().setSerializationInclusion(Include.NON_NULL)
				.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
				.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).writeValueAsString(pspec);

		System.out.println(json);
	}

	public static String LowerFirstLetter(String name) {
		if (name.length() < 2)
			return name.toLowerCase();
		return String.format("%s%s", name.substring(0, 1).toLowerCase(), name.substring(1));
	}

	public static String GetVariableType(Field f) {
		Map<Class<?>, String> types = new HashMap<Class<?>, String>() {
			{
				put(boolean.class, "Boolean");
			}
			{
				put(byte.class, "Integer");
			}
			{
				put(short.class, "Integer");
			}
			{
				put(int.class, "Integer");
			}
			{
				put(long.class, "Integer");
			}
			{
				put(double.class, "Double");
			}
			{
				put(float.class, "Double");
			}
			{
				put(Array.class, "Array");
			}
		};

		if (types.containsKey(f.getType())) {
			return types.get(f.getType());
		}

		return "String";
	}

	public static String GetType(Field f) {
		Map<Class<?>, String> types = new HashMap<Class<?>, String>() {
			{
				put(String.class, "string");
				put(boolean.class, "boolean");
			}
			{
				put(byte.class, "number");
			}
			{
				put(short.class, "number");
			}
			{
				put(int.class, "number");
			}
			{
				put(long.class, "number");
			}
			{
				put(double.class, "number");
			}
			{
				put(float.class, "number");
			}
		};

		if (types.containsKey(f.getType())) {
			return types.get(f.getType());
		}

		return "object";
	}

	public static String UpperFirstLetter(String text) {
		if (text.length() < 2)
			return text.toUpperCase();
		return String.format("%s%s", text.substring(0, 1).toUpperCase(), text.substring(1));
	}

	public static String GetTitle(Class<?> c) {
		NodeAnnotations.Title annotation = c.getAnnotation(NodeAnnotations.Title.class);
		return annotation == null ? c.getName() : annotation.title();
	}

	public static String GetColor(Class<?> c) {
		NodeAnnotations.Color annotation = c.getAnnotation(NodeAnnotations.Color.class);
		return annotation == null ? "#000" : annotation.color();
	}

	public static String GetIcon(Class<?> c) {
		NodeAnnotations.Icon annotation = c.getAnnotation(NodeAnnotations.Icon.class);
		return annotation == null ? "" : annotation.icon();
	}

	public static String GetEditor(Class<?> c) {
		NodeAnnotations.Editor annotation = c.getAnnotation(NodeAnnotations.Editor.class);
		return annotation == null ? "" : annotation.editor();
	}

	public static String GetNamespace(Class<?> c) {
		NodeAnnotations.Name annotation = c.getAnnotation(NodeAnnotations.Name.class);
		return annotation == null ? "" : annotation.name();
	}

	public static int GetInputCount(Class<?> c) {
		NodeAnnotations.Inputs annotation = c.getAnnotation(NodeAnnotations.Inputs.class);
		return annotation == null ? 0 : annotation.inputs();
	}

	public static int GetOutputCount(Class<?> c) {
		NodeAnnotations.Outputs annotation = c.getAnnotation(NodeAnnotations.Outputs.class);
		return annotation == null ? 0 : annotation.outputs();
	}

	public static List<Field> GetInputs(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			if (IsInput(f) && !(f.getGenericType() instanceof ParameterizedType)) {
				fields.add(f);
			}
		}

		return fields;
	}

	public static List<Field> GetInputVars(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			java.lang.reflect.Type t = f.getGenericType();
			if (t instanceof ParameterizedType) {
				ParameterizedType pT = (ParameterizedType) t;
				java.lang.reflect.Type[] genericArgs = pT.getActualTypeArguments();
				if (genericArgs.length > 0 && InVariable.class.isAssignableFrom((Class<?>) pT.getRawType())) {
					fields.add(f);
				}
			}
		}

		return fields;
	}

	public static List<Field> GetOutputs(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			if (IsOutput(f) && !(f.getGenericType() instanceof ParameterizedType)) {
				fields.add(f);
			}
		}

		return fields;
	}

	public static List<Field> GetOutputVars(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			java.lang.reflect.Type t = f.getGenericType();
			if (t instanceof ParameterizedType) {
				ParameterizedType pT = (ParameterizedType) t;
				java.lang.reflect.Type[] genericArgs = pT.getActualTypeArguments();
				if (genericArgs.length > 0 && OutVariable.class.isAssignableFrom((Class<?>) pT.getRawType())) {
					fields.add(f);
				}
			}
		}

		return fields;
	}

	public static List<Field> GetOptions(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			if (IsOption(f) && !(f.getGenericType() instanceof ParameterizedType)) {
				fields.add(f);
			}
		}

		return fields;
	}

	public static List<Field> GetOptionVars(Class<?> c) {
		List<Field> fields = new ArrayList<Field>();
		Field[] all = c.getFields();

		for (Field f : all) {
			java.lang.reflect.Type t = f.getGenericType();
			if (t instanceof ParameterizedType) {
				ParameterizedType pT = (ParameterizedType) t;
				java.lang.reflect.Type[] genericArgs = pT.getActualTypeArguments();
				if (genericArgs.length > 0 && OptVariable.class.isAssignableFrom((Class<?>) pT.getRawType())) {
					fields.add(f);
				}
			}
		}

		return fields;
	}

	public static String GetTitle(Field f) {
		FieldAnnotations.Title annotation = f.getAnnotation(FieldAnnotations.Title.class);
		return annotation == null ? "" : annotation.title();
	}

	public static String GetDescription(Field f) {
		FieldAnnotations.Description annotation = f.getAnnotation(FieldAnnotations.Description.class);
		return annotation == null ? "" : annotation.description();
	}

	public static String GetFormat(Field f) {
		FieldAnnotations.Format annotation = f.getAnnotation(FieldAnnotations.Format.class);
		return annotation == null ? "" : annotation.format();
	}

	public static boolean IsInput(Field f) {
		FieldAnnotations.Input annotation = f.getAnnotation(FieldAnnotations.Input.class);
		return annotation == null ? false : annotation.input();
	}

	public static boolean IsOutput(Field f) {
		FieldAnnotations.Output annotation = f.getAnnotation(FieldAnnotations.Output.class);
		return annotation == null ? false : annotation.output();
	}

	public static boolean IsOption(Field f) {
		FieldAnnotations.Option annotation = f.getAnnotation(FieldAnnotations.Option.class);
		return annotation == null ? false : annotation.option();
	}

	public static boolean CustomScope(Field f) {
		FieldAnnotations.CustomScope annotation = f.getAnnotation(FieldAnnotations.CustomScope.class);
		return annotation == null ? false : annotation.customScope();
	}

	public static boolean MessageScope(Field f) {
		FieldAnnotations.MessageScope annotation = f.getAnnotation(FieldAnnotations.MessageScope.class);
		return annotation == null ? false : annotation.messageScope();
	}

	public static boolean MessageOnly(Field f) {
		FieldAnnotations.MessageOnly annotation = f.getAnnotation(FieldAnnotations.MessageOnly.class);
		return annotation == null ? false : annotation.messageOnly();
	}

	public static boolean IsHidden(Field f) {
		FieldAnnotations.Hidden annotation = f.getAnnotation(FieldAnnotations.Hidden.class);
		return annotation == null ? false : annotation.hidden();
	}

	public static Object GetDefault(Field f) {
		FieldAnnotations.Default annotation = f.getAnnotation(FieldAnnotations.Default.class);
		if (annotation == null)
			return null;

		try {
			String valJson = annotation.value();
			if (valJson.compareTo("") != 0) {
				Gson g = new Gson();
				Object value = g.fromJson(valJson, annotation.cls());
				return value;
			}
		} catch (Exception e) {
		}

		final String scope = annotation.scope();
		final String name = annotation.name();

		if (scope.compareTo("") != 0) {
			return new JObject() {
				{
					put("scope", scope);
					put("name", name);
				}
			};
		}

		return null;
	}

	public static ECategory GetCategory(Field f) {
		FieldAnnotations.Category annotation = f.getAnnotation(FieldAnnotations.Category.class);
		return annotation == null ? ECategory.Null : annotation.category();
	}

	public static Object[] GetEnum(Field f) {
		FieldAnnotations.Enum annotation = f.getAnnotation(FieldAnnotations.Enum.class);
		if (annotation == null)
			return null;

		String enumJson = annotation.enumeration();
		Gson g = new Gson();
		List<Object> enumeration = new ArrayList<Object>();
		enumeration = g.fromJson(enumJson, enumeration.getClass());

		return enumeration.toArray();
	}

	public static Object[] GetEnumNames(Field f) {
		FieldAnnotations.Enum annotation = f.getAnnotation(FieldAnnotations.Enum.class);
		if (annotation == null)
			return null;

		String namesJson = annotation.enumNames();
		Gson g = new Gson();
		List<Object> enumeration = new ArrayList<Object>();
		enumeration = g.fromJson(namesJson, enumeration.getClass());

		return enumeration.toArray();
	}
}
