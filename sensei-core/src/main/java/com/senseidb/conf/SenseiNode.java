package com.senseidb.conf;

import org.mortbay.jetty.Server;

import com.google.inject.Inject;
import com.linkedin.norbert.javacompat.cluster.ClusterClient;
import com.linkedin.norbert.javacompat.network.NetworkServer;

public class SenseiNode {
  @Inject ClusterClient clusterClient;
  @Inject NetworkServer networkServer;
  @Inject Server httpServer;
}
