// Copyright (c) 2013-2014 Sandstorm Development Group, Inc. and contributors
// Licensed under the MIT License:
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package com.robomotion.app;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.codec.binary.Hex;
import org.capnproto.StructList;
import com.robomotion.app.Robomotion.NodeMessage;
import com.google.gson.Gson;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.commons.codec.binary.Hex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RobomotionCapnp {
    static int CAPNP_LIMIT = 1;
    static String ROBOMOTION_CAPNP_PREFIX = "robomotion-capnp";
    public static Object writeAddressBook(Object value, Map<String, Object> robotInfo) throws java.io.IOException {
        System.out.println(value);
        byte[] data = RobomotionCapnp.Serialize(value.toString());
        String str = "{\"name\":\"latif\",\"surname\":\"uluman\"}";
        data = str.getBytes();
        
        if(data.length < RobomotionCapnp.CAPNP_LIMIT) {
            System.out.println("limiti gecemedi");
            return value;
        }
        String robotID = robotInfo.get("id").toString();
        String cacheDir = robotInfo.get("cache_dir").toString();
        Path dir = Path.of(cacheDir, "temp", "robots", robotID);
        System.out.println("path: " + dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return null; // Handle the error as needed
        }
        System.out.println("try pass");
        Path path = Files.createTempFile(dir, "robomotion-capnp", null);        
        org.capnproto.MessageBuilder message = new org.capnproto.MessageBuilder();
        NodeMessage.Builder capnp = message.initRoot(NodeMessage.factory);
        
        capnp.setContent(data);

        FileOutputStream fileOutputStream = new FileOutputStream(path.toString());
        org.capnproto.SerializePacked.writeToUnbuffered(
            (fileOutputStream).getChannel(),
            message);
            String result = ROBOMOTION_CAPNP_PREFIX + Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
        return result;
    }

    public static Object readFromFile(String id) throws java.io.IOException {

        id = id.replaceFirst(ROBOMOTION_CAPNP_PREFIX, "");
        
        try {
            byte[] bytes = Hex.decodeHex(id.toCharArray());
            String filePath = new String(bytes, "UTF-8");
            System.out.println("the path is " + filePath);
            filePath = "/home/latif/.config/robomotion/cache/temp/robots/4dc76b1b-7e8c-4345-8a41-9d7d37dbc299/table/robomotion-capnp1060402068";
            Path path = Paths.get(filePath);
            FileInputStream fileInputStream = new FileInputStream(path.toFile());
            FileChannel channel = fileInputStream.getChannel();

            org.capnproto.MessageReader message = org.capnproto.SerializePacked.readFromUnbuffered(channel);
            fileInputStream.close();
            System.out.println("1111111");
            System.out.println(message);
            
            NodeMessage.Reader nodeMessage = message.getRoot(NodeMessage.factory);
            System.out.println("2222222222");
            byte[] data = nodeMessage.getContent().toArray();
            System.out.println("3333333333");
            Object obj = RobomotionCapnp.Deserialize(data, Object.class);
            System.out.println("the obj is " + obj.toString());
            return obj;
            
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
        
        
        
        }


    public static byte[] Serialize(Object object) {
		try {
			return (new ObjectMapper()).writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			return null;
		}
	}
    
    public static <T> T Deserialize(byte[] data, Class<T> classOfT) {
    Gson g = new Gson();
    return g.fromJson(new String(data, StandardCharsets.UTF_8), classOfT);
	}
}

//java -cp runtime/target/classes:examples/target/classes org.capnproto.examples.AddressbookMain read