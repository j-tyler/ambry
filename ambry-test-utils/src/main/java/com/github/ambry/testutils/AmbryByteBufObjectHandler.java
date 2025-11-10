// Copyright (C) 2025. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use
// this file except in compliance with the License. You may obtain a copy of the
// License at  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied.

package com.github.ambry.testutils;

import com.example.bytebuf.tracker.ObjectTrackerHandler;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Custom ObjectTrackerHandler for Ambry that tracks both raw ByteBuf objects
 * and Ambry wrapper classes that contain ByteBufs.
 *
 * This handler extracts ByteBufs from wrapper objects using:
 * 1. Known public getter methods (getBlob(), content(), etc.)
 * 2. Reflection to access private fields as fallback
 *
 * Tracked wrapper classes:
 * - com.github.ambry.protocol.* (PutRequest, GetResponse, etc.)
 * - com.github.ambry.messageformat.BlobData
 * - com.github.ambry.network.* (SocketServerRequest, NettyServerRequest, ResponseInfo)
 * - com.github.ambry.router.* (EncryptJob, DecryptJob, etc.)
 * - com.github.ambry.rest.NettyResponseChannel$Chunk
 * - com.github.ambry.utils.NettyByteBufDataInputStream
 */
public class AmbryByteBufObjectHandler implements ObjectTrackerHandler {

  @Override
  public boolean shouldTrack(Object obj) {
    if (obj == null) {
      return false;
    }

    // Track raw ByteBuf objects
    if (obj instanceof ByteBuf) {
      return true;
    }

    String className = obj.getClass().getName();

    // Track all protocol messages
    if (className.startsWith("com.github.ambry.protocol.")) {
      return true;
    }

    // Track specific wrapper classes
    return className.equals("com.github.ambry.messageformat.BlobData")
        || className.equals("com.github.ambry.network.SocketServerRequest")
        || className.equals("com.github.ambry.network.NettyServerRequest")
        || className.equals("com.github.ambry.network.ResponseInfo")
        || className.equals("com.github.ambry.router.EncryptJob")
        || className.equals("com.github.ambry.router.EncryptJob$EncryptJobResult")
        || className.equals("com.github.ambry.router.DecryptJob")
        || className.equals("com.github.ambry.rest.NettyResponseChannel$Chunk")
        || className.equals("com.github.ambry.utils.NettyByteBufDataInputStream");
  }

  @Override
  public int getMetric(Object obj) {
    if (obj == null) {
      return 0;
    }

    // Direct ByteBuf - return refCnt
    if (obj instanceof ByteBuf) {
      return ((ByteBuf) obj).refCnt();
    }

    // Extract ByteBuf from wrapper and return its refCnt
    ByteBuf extractedBuf = extractByteBuf(obj);
    if (extractedBuf != null) {
      return extractedBuf.refCnt();
    }

    return 0;
  }

  @Override
  public String getObjectType() {
    return "ByteBuf/Wrapper";
  }

  /**
   * Extract ByteBuf from wrapper object using known getter methods or reflection.
   */
  private ByteBuf extractByteBuf(Object obj) {
    if (obj == null) {
      return null;
    }

    Class<?> clazz = obj.getClass();
    String className = clazz.getName();

    try {
      // Try known getter methods first (faster than reflection)

      // PutRequest: getBlob()
      if (className.equals("com.github.ambry.protocol.PutRequest")) {
        Method method = clazz.getMethod("getBlob");
        return (ByteBuf) method.invoke(obj);
      }

      // BlobData: content()
      if (className.equals("com.github.ambry.messageformat.BlobData")) {
        Method method = clazz.getMethod("content");
        return (ByteBuf) method.invoke(obj);
      }

      // ResponseInfo: getContent() returns Send, need to extract from Send
      // For now, use reflection on 'content' field
      if (className.equals("com.github.ambry.network.ResponseInfo")) {
        return extractByteBufViaReflection(obj, "content");
      }

      // NettyResponseChannel.Chunk: has 'buffer' field
      if (className.equals("com.github.ambry.rest.NettyResponseChannel$Chunk")) {
        return extractByteBufViaReflection(obj, "buffer");
      }

      // NettyByteBufDataInputStream: has 'buffer' field
      if (className.equals("com.github.ambry.utils.NettyByteBufDataInputStream")) {
        return extractByteBufViaReflection(obj, "buffer");
      }

      // EncryptJob: has 'blobContentToEncrypt' field
      if (className.equals("com.github.ambry.router.EncryptJob")) {
        return extractByteBufViaReflection(obj, "blobContentToEncrypt");
      }

      // EncryptJob.EncryptJobResult: has 'encryptedBlobContent' field
      if (className.equals("com.github.ambry.router.EncryptJob$EncryptJobResult")) {
        return extractByteBufViaReflection(obj, "encryptedBlobContent");
      }

      // DecryptJob: has 'encryptedBlobContent' field
      if (className.equals("com.github.ambry.router.DecryptJob")) {
        return extractByteBufViaReflection(obj, "encryptedBlobContent");
      }

      // SocketServerRequest and NettyServerRequest: both have 'content' field
      if (className.equals("com.github.ambry.network.SocketServerRequest")
          || className.equals("com.github.ambry.network.NettyServerRequest")) {
        return extractByteBufViaReflection(obj, "content");
      }

      // For other protocol classes, try common field names
      if (className.startsWith("com.github.ambry.protocol.")) {
        // Try common field names in order
        ByteBuf buf = extractByteBufViaReflection(obj, "blob");
        if (buf != null) return buf;

        buf = extractByteBufViaReflection(obj, "content");
        if (buf != null) return buf;

        buf = extractByteBufViaReflection(obj, "buffer");
        if (buf != null) return buf;

        buf = extractByteBufViaReflection(obj, "compositeSendContent");
        if (buf != null) return buf;
      }

    } catch (Exception e) {
      // Silently fail - this is called very frequently during tracking
    }

    return null;
  }

  /**
   * Extract ByteBuf from object using reflection on a specific field name.
   */
  private ByteBuf extractByteBufViaReflection(Object obj, String fieldName) {
    try {
      Class<?> clazz = obj.getClass();
      Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      Object value = field.get(obj);
      if (value instanceof ByteBuf) {
        return (ByteBuf) value;
      }
    } catch (Exception e) {
      // Field doesn't exist or isn't accessible - this is expected for some classes
    }
    return null;
  }
}
