/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.qjournal;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.namenode.EditLogOutputStream;
import org.apache.hadoop.hdfs.server.namenode.FSEditLogOp;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.log4j.Level;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Stubber;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * True unit tests for QuorumJournalManager
 */
public class TestQuorumJournalManagerUnit {
  static {
    ((Log4JLogger)QuorumJournalManager.LOG).getLogger().setLevel(Level.ALL);
  }
  private Configuration conf = new Configuration();
  private List<AsyncLogger> spyLoggers;
  private QuorumJournalManager qjm;
  
  @Before
  public void setup() {
    spyLoggers = ImmutableList.of(
        mockLogger(),
        mockLogger(),
        mockLogger());

    qjm = new QuorumJournalManager() {
      @Override
      protected List<AsyncLogger> createLoggers() {
        return spyLoggers;
      }
    };
    qjm.setConf(conf);
  }
  
  private AsyncLogger mockLogger() {
    return Mockito.mock(AsyncLogger.class);
  }
  
  private static <V> Stubber futureReturns(V value) {
    ListenableFuture<V> ret = Futures.immediateFuture(value);
    return Mockito.doReturn(ret);
  }
  
  private static Stubber futureThrows(Throwable t) {
    ListenableFuture<?> ret = Futures.immediateFailedFuture(t);
    return Mockito.doReturn(ret);
  }


  @Test
  public void testAllLoggersStartOk() throws Exception {
    futureReturns(null).when(spyLoggers.get(0)).startLogSegment(Mockito.anyLong());
    futureReturns(null).when(spyLoggers.get(1)).startLogSegment(Mockito.anyLong());
    futureReturns(null).when(spyLoggers.get(2)).startLogSegment(Mockito.anyLong());
    qjm.startLogSegment(1);
  }
  
  @Test
  public void testQuorumOfLoggersStartOk() throws Exception {
    futureReturns(null).when(spyLoggers.get(0)).startLogSegment(Mockito.anyLong());
    futureReturns(null).when(spyLoggers.get(1)).startLogSegment(Mockito.anyLong());
    futureThrows(new IOException("logger failed"))
      .when(spyLoggers.get(2)).startLogSegment(Mockito.anyLong());
    qjm.startLogSegment(1);
  }
  
  @Test
  public void testQuorumOfLoggersFail() throws Exception {
    futureReturns(null).when(spyLoggers.get(0)).startLogSegment(Mockito.anyLong());
    futureThrows(new IOException("logger failed"))
    .when(spyLoggers.get(1)).startLogSegment(Mockito.anyLong());
    futureThrows(new IOException("logger failed"))
      .when(spyLoggers.get(2)).startLogSegment(Mockito.anyLong());
    try {
      qjm.startLogSegment(1);
      fail("Did not throw when quorum failed");
    } catch (QuorumException qe) {
      GenericTestUtils.assertExceptionContains("logger failed", qe);
    }
  }
  
  @Test
  public void testWriteEdits() throws Exception {
    EditLogOutputStream stm = createLogSegment();
    writeOp(stm, 1);
    writeOp(stm, 2);
    
    stm.setReadyToFlush();
    writeOp(stm, 3);
    
    // The flush should log txn 1-2
    futureReturns(null).when(spyLoggers.get(0)).sendEdits(
        eq(1L), eq(2), Mockito.<byte[]>any());
    futureReturns(null).when(spyLoggers.get(1)).sendEdits(
        eq(1L), eq(2), Mockito.<byte[]>any());
    futureReturns(null).when(spyLoggers.get(2)).sendEdits(
        eq(1L), eq(2), Mockito.<byte[]>any());
    stm.flush();

    // Another flush should now log txn #3
    stm.setReadyToFlush();
    futureReturns(null).when(spyLoggers.get(0)).sendEdits(
        eq(3L), eq(1), Mockito.<byte[]>any());
    futureReturns(null).when(spyLoggers.get(1)).sendEdits(
        eq(3L), eq(1), Mockito.<byte[]>any());
    futureReturns(null).when(spyLoggers.get(2)).sendEdits(
        eq(3L), eq(1), Mockito.<byte[]>any());
    stm.flush();
  }
  
  @Test
  public void testWriteEditsOneSlow() throws Exception {
    EditLogOutputStream stm = createLogSegment();
    writeOp(stm, 1);
    stm.setReadyToFlush();
    
    // Make the first two logs respond immediately
    futureReturns(null).when(spyLoggers.get(0)).sendEdits(
        eq(1L), eq(1), Mockito.<byte[]>any());
    futureReturns(null).when(spyLoggers.get(1)).sendEdits(
        eq(1L), eq(1), Mockito.<byte[]>any());
    
    // And the third log not respond
    SettableFuture<Void> slowLog = SettableFuture.<Void>create();
    Mockito.doReturn(slowLog).when(spyLoggers.get(2)).sendEdits(
        eq(1L), eq(1), Mockito.<byte[]>any());
    stm.flush();
  }

  private EditLogOutputStream createLogSegment() throws IOException {
    futureReturns(null).when(spyLoggers.get(0)).startLogSegment(Mockito.anyLong());
    futureReturns(null).when(spyLoggers.get(1)).startLogSegment(Mockito.anyLong());
    futureReturns(null).when(spyLoggers.get(2)).startLogSegment(Mockito.anyLong());
    EditLogOutputStream stm = qjm.startLogSegment(1);
    return stm;
  }

  static void writeOp(EditLogOutputStream stm, long txid) throws IOException {
    FSEditLogOp op = NameNodeAdapter.createMkdirOp("tx " + txid);
    op.setTransactionId(txid);
    stm.write(op);
  }
}
