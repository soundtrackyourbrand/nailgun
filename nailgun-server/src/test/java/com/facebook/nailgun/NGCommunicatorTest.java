/*

Copyright 2017-present Facebook, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

*/

package com.facebook.nailgun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NGCommunicatorTest {
  // Mocks
  private Socket socket;
  private InputStream istream;
  private OutputStream ostream;

  @BeforeEach
  private void before() throws IOException {
    socket = mock(Socket.class);

    istream = mock(InputStream.class);
    ostream = mock(OutputStream.class);

    when(socket.getInputStream()).thenReturn(istream);
    when(socket.getOutputStream()).thenReturn(ostream);
  }

  @Test
  void canRun() throws IOException {
    NGCommunicator comm = new NGCommunicator(socket, 0);
    assertNotNull(comm);
  }

  @Test
  void canReadCommand() throws IOException {
    String command = "some_command";
    byte[] commandBin = command.getBytes(StandardCharsets.UTF_8);
    byte[] payload;
    try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream)) {
      stream.writeInt(commandBin.length);
      stream.writeByte(NGConstants.CHUNKTYPE_COMMAND);
      stream.write(commandBin);
      stream.flush();
      payload = byteStream.toByteArray();
    }

    ByteArrayInputStream istream = new ByteArrayInputStream(payload);
    when(socket.getInputStream()).thenReturn(istream);

    NGCommunicator comm = new NGCommunicator(socket, 0);
    CommandContext context = comm.readCommandContext();
    assertEquals(command, context.getCommand());
  }

  private static byte[] chunk(byte chunkType, String payload) throws IOException {
    byte[] payloadBin = payload.getBytes(StandardCharsets.UTF_8);
    try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(byteStream)) {
      stream.writeInt(payloadBin.length);
      stream.writeByte(chunkType);
      stream.write(payloadBin);
      stream.flush();
      return byteStream.toByteArray();
    }
  }

  private static class TimesOutAfterStream extends InputStream {
    final CountDownLatch timedOutLatch = new CountDownLatch(1);

    private final byte[] prefix;
    private int pos = 0;

    TimesOutAfterStream(byte[] prefix) {
      this.prefix = prefix;
    }

    @Override
    public int read() throws IOException {
      if (pos < prefix.length) {
        return prefix[pos++] & 0xff;
      }
      timedOutLatch.countDown();
      throw new SocketTimeoutException("Read timed out");
    }
  }

  private static class CapturingHandler extends Handler {
    final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private Level levelOfTimeoutRecord(byte[] beforeTimeout) throws Exception {
    Logger logger = Logger.getLogger(NGCommunicator.class.getName());
    CapturingHandler handler = new CapturingHandler();
    handler.setLevel(Level.ALL);
    Level previousLevel = logger.getLevel();
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);

    TimesOutAfterStream stream = new TimesOutAfterStream(beforeTimeout);
    when(socket.getInputStream()).thenReturn(stream);

    NGCommunicator comm = new NGCommunicator(socket, 100);
    try {
      comm.readCommandContext();
      assertTrue(stream.timedOutLatch.await(5, TimeUnit.SECONDS), "the read should have timed out");
      for (int i = 0; i < 100; i++) {
        for (LogRecord record : handler.records) {
          if (record.getMessage().startsWith("Nailgun client socket timed out")) {
            return record.getLevel();
          }
        }
        Thread.sleep(20);
      }
      return null;
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      comm.close();
    }
  }

  @Test
  void socketTimeoutIsNotWarnedAboutWhenClientNeverHeartbeated() throws Exception {
    assertEquals(
        Level.FINE, levelOfTimeoutRecord(chunk(NGConstants.CHUNKTYPE_COMMAND, "some_command")));
  }

  @Test
  void socketTimeoutIsWarnedAboutWhenClientHadHeartbeated() throws Exception {
    ByteArrayOutputStream prefix = new ByteArrayOutputStream();
    prefix.write(chunk(NGConstants.CHUNKTYPE_COMMAND, "some_command"));
    prefix.write(chunk(NGConstants.CHUNKTYPE_HEARTBEAT, ""));

    assertEquals(Level.WARNING, levelOfTimeoutRecord(prefix.toByteArray()));
  }

  @Test
  void canWriteData() throws IOException {
    NGCommunicator comm = new NGCommunicator(socket, 0);

    byte[] data = {0x01, 0x02, 0x03};
    comm.send(NGConstants.CHUNKTYPE_STDOUT, data, 0, data.length);

    verify(ostream).write(data, 0, data.length);
  }
}
