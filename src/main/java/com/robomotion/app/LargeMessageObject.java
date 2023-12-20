package com.robomotion.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;

import org.apache.commons.codec.binary.Base32;
import org.slf4j.helpers.Util;

import java.nio.file.*;
import com.google.gson.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;
class Constants {
    public static final int LMO_MAGIC = 0x1343B7E;
    public static final int LMO_LIMIT = 110; // 256kb
    public static final int LMO_VERSION = 0x01;
    public static final int LMO_HEAD = 10;

    public static String newId() {
        byte[] bytes = new byte[16];
        new Random().nextBytes(bytes);
        Base32 base32 = new Base32();
        String id = base32.encodeAsString(bytes);
        id = id.substring(0, 26);
        return id;
    }
}

class LargeMessageObject {
    private int magic;
    private int version;
    private String id;
    private String head;
    private int size;
    private Object data;

    public int getMagic() {
        return magic;
    }

    public void setMagic(int magic) {
        this.magic = magic;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHead() {
        return head;
    }

    public void setHead(String head) {
        this.head = head;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public Map<String, Object> toDictionary() {
        Map<String, Object> dict = new HashMap<>();
        dict.put("magic", this.magic);
        dict.put("version", this.version);
        dict.put("id", this.id);
        dict.put("head", this.head);
        dict.put("size", this.size);
        return dict;
    }

    public static Object serializeLMO(Object value) throws RuntimeNotInitializedException {
        if (!Capability.isLMOCapable()) {
            return null;
        }

        try {
            String res = new ObjectMapper().writeValueAsString(value);
            byte[] data = res.getBytes(StandardCharsets.UTF_8);

            int dataLen = data.length;
            if (dataLen < Constants.LMO_LIMIT) {
                return null;
            }

            String id = Constants.newId().toLowerCase();
            String head = new String(Arrays.copyOfRange(data, 0, Constants.LMO_HEAD), StandardCharsets.UTF_8);

            LargeMessageObject lmo = new LargeMessageObject();
            lmo.setMagic(Constants.LMO_MAGIC);
            lmo.setVersion(Constants.LMO_VERSION);
            lmo.setId(id);
            lmo.setHead(head);
            lmo.setSize(dataLen);
            lmo.setData(value);
            
            String robotID = Runtime.GetRobotID();
            String tempPath = Utils.File.GetTempPath();
            String dir = Paths.get(tempPath, "robots", robotID).toString();
            new File(dir).mkdirs();
            
            String filePath = Paths.get(dir, id + ".lmo").toString();            
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(new File(filePath), lmo);
            lmo.setData(null);

            return lmo.toDictionary();
        } catch (Exception ex) {
        }

        return null;
    }

    public static <T> T deserializeLMO(String id) throws IOException, RuntimeNotInitializedException {
        String robotID = Runtime.GetRobotID();
        String tempPath = Utils.File.GetTempPath();
        String dir = Paths.get(tempPath, "robots", robotID).toString();
        String filePath = Paths.get(dir, id + ".lmo").toString();

        File file = new File(filePath);

        String content = new String(Files.readAllBytes(file.toPath()));
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(content);
        JsonNode deserializedData = rootNode.get("data");
        return (T)objectMapper.treeToValue(deserializedData, Object.class);
        
    }
    
    public static void PrintMap(Map<String,Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            System.out.println(entry.getKey() + ":" + entry.getValue().toString());
        }
    }

    public static byte[] PackMessageBytes(byte[] inMsg) throws RuntimeNotInitializedException {
        if (!Capability.isLMOCapable() || inMsg.length < Constants.LMO_LIMIT)
        {   
            return inMsg;
        }
        
        Map<String, Object> msg = new HashMap<String, Object>();
        msg = Runtime.Deserialize(inMsg, msg.getClass());         
        if (msg != null) {
            msg = PackMessage(msg);
            return  Runtime.Serialize(msg);
        }
        return inMsg;
    }

    public static Map<String, Object> PackMessage(Map<String, Object> msg) throws RuntimeNotInitializedException {
        if (!Capability.isLMOCapable())
        {   
            return msg;
        }
        Map<String, Object> temp = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : msg.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            Object lmo = serializeLMO(value);
            
            if (lmo != null){
                temp.put(key, lmo);
            }else{
                temp.put(key, value);
            }
        }
        return temp;
    }
    public static byte[] UnpackMessageBytes(byte[] inMsg) throws RuntimeNotInitializedException, ParseException, IOException {
        if (!Capability.isLMOCapable())
        {   
            return inMsg;
        }
        Map<String,Object> msg = UnpackMessage(inMsg);
        return Runtime.Serialize(msg);          
         
    }


    public static Map<String, Object> UnpackMessage(byte[] inMsg) throws RuntimeNotInitializedException, ParseException, IOException{
        if (!Capability.isLMOCapable())
        {
            return null;
        }
        Map<String, Object> msg = new HashMap<>();
        Map<String, Object> deserializedMsg = new HashMap<String, Object>();
		deserializedMsg = Runtime.Deserialize(inMsg, deserializedMsg.getClass());
        
        for (Map.Entry<String, Object> entry : deserializedMsg.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if(isLMO(value)){
                if (value instanceof Map<?, ?>) {
                Map<?, ?> mapVal = (Map<?, ?>) value;
                if (mapVal.containsKey("id")) {
                    
                    Object id = mapVal.get("id");
                    Object result = deserializeLMO((String)id);  
                    if (result != null) {
                        msg.put(key,result);
                        continue;
                    }         
                   
                }
            }
            }
            msg.put(key, value);
        }
        
        return msg;
    }

    public static boolean isLMO(Object value) throws RuntimeNotInitializedException {
        if (!Capability.isLMOCapable()) {
            return false;
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> mapVal = (Map<?, ?>) value;
            if (mapVal.containsKey("magic")) {
                
                Object magicValObj = mapVal.get("magic");
                
                if (magicValObj instanceof Integer) {
                    return  (Integer) magicValObj == Constants.LMO_MAGIC;
                } else if (magicValObj instanceof Double) {
                    return ((Double) magicValObj) == Constants.LMO_MAGIC;
                }
            }
        }

        try {
            String json = value.toString();
            JsonElement rootElement = JsonParser.parseString(json);
            if (rootElement.isJsonObject() && rootElement.getAsJsonObject().has("magic")) {
                return rootElement.getAsJsonObject().get("magic").getAsLong() == Constants.LMO_MAGIC;
            }
        } catch (JsonSyntaxException e) {
            return false;
        }

        return false;
    }
    
}
