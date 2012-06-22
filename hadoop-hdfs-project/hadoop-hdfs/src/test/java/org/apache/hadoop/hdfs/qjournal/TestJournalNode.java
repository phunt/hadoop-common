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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.NewEpochResponseProto;
import org.apache.hadoop.hdfs.qjournal.protocol.QJournalProtocolProtos.PaxosPrepareResponseProto;
import org.apache.hadoop.hdfs.server.namenode.NNStorage;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;


public class TestJournalNode {
  private static final NamespaceInfo FAKE_NSINFO = new NamespaceInfo(
      12345, "mycluster", "my-bp", 0L, 0);
  private static final String JID = "test-journalid";

  private JournalNode jn;
  private Journal journal; 
  private Configuration conf = new Configuration();
  private IPCLoggerChannel ch;

  static {
    // Avoid an error when we double-initialize JvmMetrics
    DefaultMetricsSystem.setMiniClusterMode(true);
  }
  
  @Before
  public void setup() throws Exception {
    conf.set(JournalNodeRpcServer.DFS_JOURNALNODE_RPC_ADDRESS_KEY,
        "0.0.0.0:0");
    jn = new JournalNode();
    jn.setConf(conf);
    jn.start();
    journal = jn.getOrCreateJournal(JID);
    // TODO: this should not have to be done explicitly in the unit
    // test, really, I dont think..
    journal.format();
    
    ch = new IPCLoggerChannel(conf, JID, jn.getBoundIpcAddress());
  }
  
  @After
  public void teardown() throws Exception {
    jn.stop(0);
  }
  
  @Test
  public void testJournal() throws Exception {
    IPCLoggerChannel ch = new IPCLoggerChannel(
        conf, JID, jn.getBoundIpcAddress());
    ch.newEpoch(FAKE_NSINFO, 1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1, 1, "hello".getBytes(Charsets.UTF_8)).get();
  }
  
  
  @Test
  public void testReturnsSegmentInfoAtEpochTransition() throws Exception {
    ch.newEpoch(FAKE_NSINFO, 1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1, 2, QJMTestUtil.createTxnData(1, 2)).get();
    
    // Switch to a new epoch without closing earlier segment
    NewEpochResponseProto response = ch.newEpoch(
        FAKE_NSINFO, 2).get();
    ch.setEpoch(2);
    assertEquals(1, response.getCurSegmentTxId());
    
    ch.finalizeLogSegment(1, 2).get();
    
    // Switch to a new epoch after just closing the earlier segment.
    response = ch.newEpoch(FAKE_NSINFO, 3).get();
    ch.setEpoch(3);
    assertEquals(1, response.getCurSegmentTxId());
    
    // Start a segment but don't write anything, check newEpoch segment info
    ch.startLogSegment(3).get();
    response = ch.newEpoch(FAKE_NSINFO, 4).get();
    ch.setEpoch(4);
    assertEquals(3, response.getCurSegmentTxId());
  }
  
  @Test
  public void testHttpServer() throws Exception {
    InetSocketAddress addr = jn.getBoundHttpAddress();
    assertTrue(addr.getPort() > 0);
    
    String urlRoot = "http://localhost:" + addr.getPort();
    
    String pageContents = DFSTestUtil.urlGet(new URL(urlRoot + "/jmx"));
    assertTrue("Bad contents: " + pageContents,
        pageContents.contains(
            "Hadoop:service=JournalNode,name=JvmMetrics"));
    
    // Create some edits on server side
    byte[] EDITS_DATA = QJMTestUtil.createTxnData(1, 3);
    IPCLoggerChannel ch = new IPCLoggerChannel(
        conf, JID, jn.getBoundIpcAddress());
    ch.newEpoch(FAKE_NSINFO, 1).get();
    ch.setEpoch(1);
    ch.startLogSegment(1).get();
    ch.sendEdits(1, 3, EDITS_DATA).get();
    ch.finalizeLogSegment(1, 3).get();

    // Attempt to retrieve via HTTP, ensure we get the data back
    // including the header we expected
    byte[] retrievedViaHttp = DFSTestUtil.urlGetBytes(new URL(urlRoot +
        "/getimage?filename=" + NNStorage.getFinalizedEditsFileName(1, 3) +
        "&jid=" + JID));
    byte[] expected = Bytes.concat(
            Ints.toByteArray(HdfsConstants.LAYOUT_VERSION),
            EDITS_DATA);

    assertArrayEquals(expected, retrievedViaHttp);
    
    // Attempt to fetch a non-existent file, check that we get an
    // error status code
    URL badUrl = new URL(urlRoot + "/getimage?filename=xxxDoesNotExist&jid=" + JID);
    HttpURLConnection connection = (HttpURLConnection)badUrl.openConnection();
    try {
      assertEquals(500, connection.getResponseCode());
    } finally {
      connection.disconnect();
    }
  }

  /**
   * Test that the JournalNode performs correctly as a Paxos
   * <em>Acceptor</em> process.
   * 
   * @throws Exception
   */
  @Test
  public void testPaxosAcceptorBehavior() throws Exception {
    // We need to run newEpoch() first, or else we have no way to distinguish
    // different proposals for the same decision.
    try {
      ch.paxosPrepare(1L).get();
      fail("Did not throw IllegalState when trying to run paxos without an epoch");
    } catch (ExecutionException ise) {
      GenericTestUtils.assertExceptionContains("bad epoch", ise);
    }
    
    ch.newEpoch(FAKE_NSINFO, 1).get();
    ch.setEpoch(1);
    
    // prepare() with no previously accepted value and no logs present
    PaxosPrepareResponseProto prep = ch.paxosPrepare(1L).get();
    System.err.println("Prep: " + prep);
    assertFalse(prep.hasAcceptedRecovery());
    assertFalse(prep.hasSegmentInfo());
    
    // Make a log segment, and prepare again -- this time should see the
    // segment existing.
    ch.startLogSegment(1L).get();
    ch.sendEdits(1L, 1, QJMTestUtil.createTxnData(1, 1)).get();

    prep = ch.paxosPrepare(1L).get();
    System.err.println("Prep: " + prep);
    assertFalse(prep.hasAcceptedRecovery());
    assertTrue(prep.hasSegmentInfo());
    
    // accept() should save the accepted value in persistent storage
    // TODO: should be able to accept without a URL here
    ch.paxosAccept(prep.getSegmentInfo(), new URL("file:///dev/null")).get();

    // So another prepare() call from a new epoch would return this value
    ch.newEpoch(FAKE_NSINFO, 2);
    ch.setEpoch(2);
    prep = ch.paxosPrepare(1L).get();
    assertEquals(1L, prep.getAcceptedRecovery().getAcceptedInEpoch());
    assertEquals(1L, prep.getAcceptedRecovery().getEndTxId());
    
    // A prepare() or accept() call from an earlier epoch should now be rejected
    ch.setEpoch(1);
    try {
      ch.paxosPrepare(1L).get();
      fail("prepare from earlier epoch not rejected");
    } catch (ExecutionException ioe) {
      GenericTestUtils.assertExceptionContains(
          "epoch 1 is less than the last promised epoch 2",
          ioe);
    }
    try {
      ch.paxosAccept(prep.getSegmentInfo(), new URL("file:///dev/null")).get();
      fail("accept from earlier epoch not rejected");
    } catch (ExecutionException ioe) {
      GenericTestUtils.assertExceptionContains(
          "epoch 1 is less than the last promised epoch 2",
          ioe);
    }
  }
  
  // TODO:
  // - add test that checks formatting behavior
  // - add test that checks rejects newEpoch if nsinfo doesn't match
  
}
