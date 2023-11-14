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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import com.robomotion.app.Addressbook.NodeMessage;
import com.google.gson.Gson;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.commons.codec.binary.Hex;
public class AddressbookMain {
    static int CAPNP_LIMIT = 1;
    static String ROBOMOTION_CAPNP_PREFIX = "robomotion-capnp";
    public static Object writeAddressBook(Object value, Map<String, Object> robotInfo) throws java.io.IOException {
        System.out.println(value);
        byte[] data = AddressbookMain.Serialize(value);
        if(data.length > AddressbookMain.CAPNP_LIMIT) {
            return value;
        }
        String robotID = robotInfo.get("id").toString();
        String cacheDir = robotInfo.get("cache_dir").toString();
        Path dir = Path.of(cacheDir, "temp", "robots", robotID);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return null; // Handle the error as needed
        }
        Path path = Files.createTempFile(dir, "robomotion-capnp", null);        
        org.capnproto.MessageBuilder message = new org.capnproto.MessageBuilder();
        NodeMessage.Builder addressbook = message.initRoot(NodeMessage.factory);
        
        addressbook.setContent(data);

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
            String path = new String(bytes, "UTF-8");
            System.out.println("the path is " + path);
            
            FileInputStream fileInputStream = new FileInputStream(path);
            System.out.println("000000000000000");
            org.capnproto.MessageReader message = org.capnproto.SerializePacked.readFromUnbuffered((fileInputStream).getChannel());
            fileInputStream.close();
            System.out.println("1111111");
            System.out.println(message);
            NodeMessage.Reader nodeMessage = message.getRoot(NodeMessage.factory);
            System.out.println("2222222222");
            byte[] data = nodeMessage.getContent().toArray();
            System.out.println("3333333333");
            Object obj = AddressbookMain.Deserialize(data, Object.class);
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