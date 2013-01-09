package com.senseidb.conf;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.mortbay.jetty.Server;

import com.google.inject.Inject;
import com.linkedin.norbert.javacompat.cluster.ClusterClient;
import com.linkedin.norbert.javacompat.network.NetworkServer;
import com.senseidb.gateway.SenseiGateway;

public class SenseiNode {
  private static Logger logger = Logger.getLogger(SenseiNode.class);
  @Inject ClusterClient clusterClient;
  @Inject NetworkServer networkServer;
  @Inject Server httpServer;
  @Inject SenseiGateway gateway;
  @Inject Configuration senseiConf;
  @Inject Analyzer analyzer;
  
  public void start() throws Exception{
    
    String clusterName = senseiConf.getString(SenseiConfParams.SENSEI_CLUSTER_NAME);
    logger.info("Connecting to cluster: "+clusterName+" ...");
    clusterClient.awaitConnectionUninterruptibly();

    logger.info("Cluster: "+clusterName+" successfully connected ");

    logger.info("Analyzer: "+analyzer);
    
	  httpServer.start();
  }
  
  public void stop() throws Exception{
    httpServer.stop();
    clusterClient.shutdown();
  }
}
