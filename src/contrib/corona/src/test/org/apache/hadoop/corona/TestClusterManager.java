package org.apache.hadoop.corona;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;
import static org.junit.Assert.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.mapred.ResourceTracker;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.CoronaJobTracker;
import org.apache.hadoop.net.Node;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;

public class TestClusterManager extends TestCase {
  final static Log LOG = LogFactory.getLog(TestClusterManager.class);

  public final static String sessionHost = "localhost";
  public static int getSessionPort(int i) {
    return (7000 + i);
  }

  final static String M = ResourceTracker.RESOURCE_TYPE_MAP;
  final static String R = ResourceTracker.RESOURCE_TYPE_REDUCE;

  private Configuration conf;
  private ClusterManagerTestable cm;

  private ClusterNodeInfo nodes [];
  private int numNodes;

  private SessionInfo sessionInfos [];
  private int numSessions;

  private String handles [];
  private Session sessions [];

  protected TopologyCache topologyCache;

  protected void setUp() throws IOException {
    conf = new Configuration();
    conf.setClass("topology.node.switch.mapping.impl",
                  org.apache.hadoop.net.IPv4AddressTruncationMapping.class,
                  org.apache.hadoop.net.DNSToSwitchMapping.class);
    conf.set(CoronaConf.CPU_TO_RESOURCE_PARTITIONING, TstUtils.std_cpu_to_resource_partitioning);

    topologyCache = new TopologyCache(conf);
    cm = new ClusterManagerTestable(conf);

    numNodes = 100;
    nodes = new ClusterNodeInfo[numNodes];
    for (int i=0; i<numNodes; i++) {
      nodes[i] = new ClusterNodeInfo(TstUtils.getNodeHost(i),
                                     new InetAddress(TstUtils.getNodeHost(i),
                                                     TstUtils.getNodePort(i)),
                                     TstUtils.std_spec);
      nodes[i].setUsed(TstUtils.free_spec);
    }

    numSessions = 5;
    sessionInfos = new SessionInfo [numSessions];
    handles = new String [numSessions];
    sessions =  new Session [numSessions];

    for (int i =0; i<numSessions; i++) {
      sessionInfos[i] = new SessionInfo(new InetAddress(sessionHost, getSessionPort(i)),
                                        "s_" + i, "hadoop");
      sessionInfos[i].setPriority(SessionPriority.NORMAL);
    }
  }

  private void addSomeNodes(int count) throws TException {
    for (int i=0; i<count; i++) {
      cm.nodeHeartbeat(nodes[i]);
    }
  }

  private void addAllNodes() throws TException {
    addSomeNodes(this.numNodes);
  }

  private List<Integer> createIdList(int low, int high) {
    ArrayList<Integer> ret = new ArrayList<Integer> (high - low + 1);
    for (int i = low; i<=high; i++)
      ret.add(i);
    return ret;
  }

  public static void reliableSleep(int ms) {
    long start, now;
    start = now = System.currentTimeMillis();
    do {
      try {
        Thread.sleep (ms - (now - start));
      } catch (InterruptedException e) {
        System.out.println("Test caught interrupted exception");
      }
      now =  System.currentTimeMillis();
    } while ((now - start) < ms);
  }

  public void testCpuConf() throws Throwable {
    try {
      CoronaConf cConf = new CoronaConf(conf);
      Map<Integer, Map<String, Integer>> m = cConf.getCpuToResourcePartitioning();
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }
  }

  public void testBadResourceType() throws Throwable {
    LOG.info("Starting testBadResourceType");
    try {
      handles[0] = cm.sessionStart(sessionInfos[0]).handle;
      List<ResourceRequest> reqs = TstUtils.createRequests(1, 1, 0);
      for(ResourceRequest req: reqs) 
        req.type = "ILLEGAL";
      try {
        cm.requestResource(handles[0], reqs);
      } catch(TApplicationException e) {
        // this is expected
        return;
      }

      // should not come here
      assertEquals(false, true);

    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }
    LOG.info("Ended testBadResourceType");
  }

  public void testMissingResourceType() throws Throwable {
    LOG.info("Starting testMissingResourceType");

    try {
      // we start a new cluster manager that does not have any map slot allocation
      conf.set(CoronaConf.CPU_TO_RESOURCE_PARTITIONING, "{\"1\":{\"M\":0, \"R\":1}}");
      cm = new ClusterManagerTestable(conf);

      addAllNodes();

      // create a request for a map slot
      handles[0] = cm.sessionStart(sessionInfos[0]).handle;
      sessions[0] = cm.getSessionManager().getSession(handles[0]);
      cm.requestResource(handles[0], TstUtils.createRequests(1, 1, 0));
      reliableSleep(1000);

      // this request will go unfulfilled
      synchronized(sessions[0]) {
        assertEquals(sessions[0].getPendingRequestCount(), 1);
      }

    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }

    LOG.info("Ended testMissingResourceType");
  }

  public void testFCFS1() throws Throwable {
    LOG.info("Starting testFCFS1");

    try {
      for (int i=0; i<numSessions; i++) {
        handles[i] = cm.sessionStart(sessionInfos[i]).handle;
        sessions[i] = cm.getSessionManager().getSession(handles[i]);
        reliableSleep(500);
      }

      int [] distribution = {800, 600, 200, 200, 200};
      for (int i=0; i<numSessions; i++) {
        cm.requestResource(handles[i], TstUtils.createRequests(distribution[i], this.numNodes));
        synchronized(sessions[i]) {
          assertEquals(sessions[i].getRequestCountForType(M),
                       distribution[i]*3/4);
          assertEquals((sessions[i].getRequestCountForType(M) +
                        sessions[i].getRequestCountForType(R)),
                       distribution[i]);
          assertEquals(sessions[i].getGrantedRequestForType(M).size(), 0);
          assertEquals(sessions[i].getGrantedRequestForType(R).size(), 0);
        }
      }

      // add the nodes after the requests to make sure that this is handled well by CM
      addAllNodes();

      reliableSleep(100);
      int [] origDistribution = distribution;
      distribution = new int [] {0, 250, 150, 150, 150};
      for (int i=0; i<numSessions; i++) {
        synchronized(sessions[i]) {
          assertEquals(sessions[i].getPendingRequestCount(), distribution[i]);

          assertEquals((sessions[i].getPendingRequestForType(M).size() +
                        sessions[i].getPendingRequestForType(R).size()),
                       distribution[i]);
          assertEquals(sessions[i].getGrantedRequestForType(M).size() +
                       sessions[i].getGrantedRequestForType(R).size(), 
                       origDistribution[i] - distribution[i]);
        }
      }

      LOG.info("\tVerified Session#1 allocation");

      cm.sessionEnd(handles[0], SessionStatus.SUCCESSFUL);
      reliableSleep(1000);
      distribution = new int [] {0, 0, 0, 0, 100};
      for (int i=1; i<numSessions; i++) {
        synchronized(sessions[i]) {
          assertEquals(sessions[i].getPendingRequestCount(), distribution[i]);
          assertEquals(sessions[i].getGrantedRequestForType(M).size() +
                       sessions[i].getGrantedRequestForType(R).size(), 
                       origDistribution[i] - distribution[i]);
        }
      }

      Collection<RetiredSession> retiredSessions = cm.getSessionManager().getRetiredSessions();
      synchronized(retiredSessions) {
        assertEquals(retiredSessions.size(), 1);
        int i=0;
        for(RetiredSession s: retiredSessions) {
          assertEquals(s.sessionId, sessions[i].sessionId);
          assertEquals(s.status, SessionStatus.SUCCESSFUL);
          i++;
        }
      }

      LOG.info("\tVerified Session#2 and Session#3 allocation after Session#1 release");

      cm.releaseResource(handles[1], createIdList(0, 199));
      reliableSleep(1000);
      distribution = new int [] {0, 0, 0, 0, 0};
      for (int i=0; i<numSessions; i++) {
        synchronized(sessions[i]) {
          assertEquals(sessions[i].getPendingRequestCount(), distribution[i]);
        }
      }

      LOG.info("\tVerified all sessions scheduled");

      for (int i=1; i<numSessions-1; i++) {
        cm.sessionEnd(handles[i], SessionStatus.SUCCESSFUL);
      }

      synchronized(retiredSessions) {
        assertEquals(retiredSessions.size(), numSessions - 1);
        int i=0;
        for(RetiredSession s: retiredSessions) {
          assertEquals(s.sessionId, sessions[i].sessionId);
          i++;
        }
      }


      // there's only one session running right now 
      // if we remove all the nodes - then this session
      // should have all the requests back in pending state

      for (int i=0; i<numNodes; i++) {
        cm.nodeTimeout(nodes[i].name);
      }

      synchronized(sessions[numSessions-1]) {
        assertEquals(sessions[numSessions-1].getPendingRequestCount(), 200);
      }
      cm.sessionEnd(handles[numSessions-1], SessionStatus.SUCCESSFUL);

      LOG.info("\tVerified node death handled correctly");

    } catch (InvalidSessionHandle e) {
      LOG.error("Bad Session Handle");
      assertEquals("Bad Session Handle", null);
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }

    LOG.info("Finished testFCFS1");
  }

  public void testFCFS_locality() throws Throwable {
    LOG.info("Starting testFCFS_locality");
    try {
      addAllNodes();

      for (int i=0; i<numSessions; i++) {
        handles[i] = cm.sessionStart(sessionInfos[i]).handle;
        sessions[i] = cm.getSessionManager().getSession(handles[i]);
        reliableSleep(500);
      }

      Collection<ResourceGrant> grants;
      String h0 = TstUtils.getNodeHost(0);

      // create a request that takes up all the slots on one node
      cm.requestResource(handles[0], TstUtils.createRequests(1, TstUtils.numCpuPerNode, 0));
      reliableSleep(1000);

      // verify all slots granted and all are node local
      assertEquals(sessions[0].getPendingRequestCount(), 0);
      synchronized(sessions[0]) {
        grants = sessions[0].getGrantedRequests();
      }

      assertEquals(grants.size(), TstUtils.numCpuPerNode);
      for (ResourceGrant grant: grants) {
        assertEquals(grant.address.host, h0);
      }

      // create another request that wants slots on node #0 only
      cm.requestResource(handles[1], TstUtils.createRequests(1, TstUtils.numCpuPerNode, 0));
      reliableSleep(1000);

      // verify all slots granted and all are rack local
      assertEquals(sessions[1].getPendingRequestCount(), 0);
      synchronized(sessions[1]) {
        grants = sessions[1].getGrantedRequests();
      }

      assertEquals(grants.size(), TstUtils.numCpuPerNode);

      Node wantRack = topologyCache.getNode(h0).getParent();
      for (ResourceGrant grant: grants) {
        if(grant.address.host.equals(h0))
           assertEquals("Data Locality on fully subscribed host", null);

        Node gotRack = topologyCache.getNode(grant.address.host).getParent();
        assertEquals(wantRack, gotRack);
      }

      // free up all the requests on node #0
      cm.releaseResource(handles[0], createIdList(0, TstUtils.numCpuPerNode-1));

      // create a request that takes up all the slots on one node
      cm.requestResource(handles[2], TstUtils.createRequests(1, TstUtils.numCpuPerNode, 0));
      reliableSleep(1000);

      // verify all slots granted and all are node local
      assertEquals(sessions[2].getPendingRequestCount(), 0);
      synchronized(sessions[2]) {
        grants = sessions[2].getGrantedRequests();
      }

      assertEquals(grants.size(), TstUtils.numCpuPerNode);
      for (ResourceGrant grant: grants) {
        assertEquals(grant.address.host, h0);
      }

    } catch (InvalidSessionHandle e) {
      assertEquals("Bad Session Handle", null);
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }

    LOG.info("Finished testFCFS_locality");
  }

  public void testSessionExpiry() throws Throwable {
    LOG.info("Starting testSessionExpiry");
    try {
      // start a cluster manager with short session expiry interval
      conf.setInt(CoronaConf.SESSION_EXPIRY_INTERVAL, 2000);
      cm = new ClusterManagerTestable(conf);
      int nodeCount = 10;

      addSomeNodes(nodeCount);

      int i=0;
      handles[i] = cm.sessionStart(sessionInfos[i]).handle;
      sessions[i] = cm.getSessionManager().getSession(handles[i]);
      reliableSleep(50);

      cm.requestResource(handles[i], TstUtils.createRequests(nodeCount,
                                                             nodeCount*TstUtils.numCpuPerNode,
                                                             0));
      reliableSleep(100);

      // we should have a session with requests granted
      synchronized(sessions[i]) {
        assertEquals(sessions[i].getPendingRequestCount(), 0);
        assertEquals((sessions[i].getPendingRequestForType(M).size() +
                      sessions[i].getPendingRequestForType(R).size()),
                     0);
        assertEquals(sessions[i].getGrantedRequestForType(M).size() +
                     sessions[i].getGrantedRequestForType(R).size(), 
                     nodeCount*TstUtils.numCpuPerNode);
      }

      // sleep for longer than session expiry interval
      reliableSleep(4000);

      // session should now be deleted and resources available to 
      assertEquals(sessions[i].deleted, true);


      i=1;
      handles[i] = cm.sessionStart(sessionInfos[i]).handle;
      sessions[i] = cm.getSessionManager().getSession(handles[i]);
      reliableSleep(50);

      cm.requestResource(handles[i], TstUtils.createRequests(nodeCount,
                                                             nodeCount*TstUtils.numCpuPerNode,
                                                             0));
      reliableSleep(100);

      // we should have a session with requests granted
      synchronized(sessions[i]) {
        assertEquals(sessions[i].getPendingRequestCount(), 0);
        assertEquals((sessions[i].getPendingRequestForType(M).size() +
                      sessions[i].getPendingRequestForType(R).size()),
                     0);
        assertEquals(sessions[i].getGrantedRequestForType(M).size() +
                     sessions[i].getGrantedRequestForType(R).size(), 
                     nodeCount*TstUtils.numCpuPerNode);
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw t;
    }
    LOG.info("Ending testSessionExpiry");
  }

}
