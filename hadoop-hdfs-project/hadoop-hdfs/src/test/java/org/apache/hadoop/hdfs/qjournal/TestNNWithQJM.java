package org.apache.hadoop.hdfs.qjournal;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doNothing;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestNNWithQJM {
  Configuration conf = new HdfsConfiguration();
  private MiniJournalCluster mjc;
  private Path TEST_PATH = new Path("/test-dir");
  private Path TEST_PATH_2 = new Path("/test-dir");

  @Before
  public void startJNs() throws Exception {
    mjc = new MiniJournalCluster.Builder(conf).build();
    mjc.start();
  }
  
  @After
  public void stopJNs() throws Exception {
    if (mjc != null) {
      mjc.shutdown();
    }
  }
  
  @Test
  public void testLogAndRestart() throws IOException {
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        MiniDFSCluster.getBaseDirectory() + "/TestNNWithQJM/image");
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
        "qjournal://localhost/myjournal");
    mjc.setupClientConfigs(conf);
    
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(0)
      .manageNameDfsDirs(false)
      .build();
    try {
      cluster.getFileSystem().mkdirs(TEST_PATH);
      
      // Restart the NN and make sure the edit was persisted
      // and loaded again
      cluster.restartNameNode();
      
      assertTrue(cluster.getFileSystem().exists(TEST_PATH));
      cluster.getFileSystem().mkdirs(TEST_PATH_2);
      
      // Restart the NN again and make sure both edits are persisted.
      cluster.restartNameNode();
      assertTrue(cluster.getFileSystem().exists(TEST_PATH));
      assertTrue(cluster.getFileSystem().exists(TEST_PATH_2));
    } finally {
      cluster.shutdown();
    }
  }
  
  @Test
  public void testNewNamenodeTakesOverWriter() throws Exception {
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        MiniDFSCluster.getBaseDirectory() + "/TestNNWithQJM/image-nn1");
    conf.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
        "qjournal://localhost/myjournal");
    mjc.setupClientConfigs(conf);
    
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf)
      .numDataNodes(0)
      .manageNameDfsDirs(false)
      .build();
    Runtime spyRuntime = NameNodeAdapter.spyOnEditLogRuntime(
        cluster.getNameNode());
    doNothing().when(spyRuntime).exit(anyInt());

    try {
      cluster.getFileSystem().mkdirs(TEST_PATH);
      
      // Start a second NN pointed to the same quorum
      Configuration conf2 = new Configuration();
      conf2.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
          MiniDFSCluster.getBaseDirectory() + "/TestNNWithQJM/image-nn2");
      conf2.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
          "qjournal://localhost/myjournal");
      mjc.setupClientConfigs(conf2);
      MiniDFSCluster cluster2 = new MiniDFSCluster.Builder(conf2)
        .numDataNodes(0)
        .manageNameDfsDirs(false)
        .build();
      
      // Check that the new cluster sees the edits made on the old cluster
      try {
        assertTrue(cluster2.getFileSystem().exists(TEST_PATH));
      } finally {
        cluster2.shutdown();
      }
      
      // Check that, if we try to write to the old NN
      // that it aborts.
      Mockito.verify(spyRuntime, Mockito.times(0)).exit(Mockito.anyInt());
      cluster.getFileSystem().mkdirs(new Path("/x"));
      Mockito.verify(spyRuntime, Mockito.times(1)).exit(Mockito.anyInt());
      
    } finally {
      cluster.shutdown();
    }
  }

}
