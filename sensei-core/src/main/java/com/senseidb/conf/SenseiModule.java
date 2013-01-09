package com.senseidb.conf;

import org.apache.commons.configuration.Configuration;
import org.apache.log4j.Logger;
import org.jolokia.http.AgentServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.jetty.webapp.WebAppContext;
import org.mortbay.servlet.GzipFilter;
import org.mortbay.thread.QueuedThreadPool;

import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.linkedin.norbert.javacompat.cluster.ClusterClient;
import com.linkedin.norbert.javacompat.cluster.ZooKeeperClusterClient;
import com.linkedin.norbert.javacompat.network.NettyNetworkServer;
import com.linkedin.norbert.javacompat.network.NetworkServer;
import com.linkedin.norbert.javacompat.network.NetworkServerConfig;
import com.linkedin.norbert.javacompat.network.PartitionedLoadBalancerFactory;
import com.senseidb.cluster.routing.SenseiPartitionedLoadBalancerFactory;
import com.senseidb.gateway.SenseiGateway;
import com.senseidb.plugin.SenseiPluginRegistry;
import com.senseidb.servlet.DefaultSenseiJSONServlet;
import com.senseidb.servlet.SenseiConfigServletContextListener;
import com.senseidb.servlet.SenseiHttpInvokerServiceServlet;

public class SenseiModule extends AbstractModule implements SenseiConfParams,Provider<SenseiNode>{
  
  private static Logger logger = Logger.getLogger(SenseiModule.class);
  static final String SENSEI_CONTEXT_PATH = "sensei";
  
  private final SenseiConfiguration conf;
  
  private SenseiNode senseiNode;
  
  public SenseiModule(SenseiConfiguration conf){
    this.conf = conf;
  }
  
  @Override
  protected void configure() {
    bind(SenseiConfiguration.class).toInstance(conf);
    install(conf);
    senseiNode = new SenseiNode();
  }
  
  @Provides 
  public SenseiGateway provideSenseiGateway() throws Exception{
    return conf.pluginRegistry.getBeanByFullPrefix(SENSEI_GATEWAY, SenseiGateway.class);
  }
  
  static{
    try{
      org.mortbay.log.Log.setLog(new org.mortbay.log.Slf4jLog());
    }
    catch(Throwable t){
      logger.error(t.getMessage(),t);
    }
  }

  @Provides
  public Server buildHttpRestServer(SenseiGateway gateway) throws Exception{
    Configuration senseiConf = conf.senseiConf;
    SenseiPluginRegistry pluginRegistry = conf.pluginRegistry;
    int port = senseiConf.getInt(SERVER_BROKER_PORT);

    String webappPath = senseiConf.getString(SERVER_BROKER_WEBAPP_PATH,"sensei-core/src/main/webapp");


    Server server = new Server();

    QueuedThreadPool threadPool = new QueuedThreadPool();
    threadPool.setName("Sensei Broker(jetty) threads");
    threadPool.setMinThreads(senseiConf.getInt(SERVER_BROKER_MINTHREAD,20));
    threadPool.setMaxThreads(senseiConf.getInt(SERVER_BROKER_MAXTHREAD,50));
    threadPool.setMaxIdleTimeMs(senseiConf.getInt(SERVER_BROKER_MAXWAIT,2000));
    //threadPool.start();
    server.setThreadPool(threadPool);

    logger.info("request threadpool started.");
    SelectChannelConnector connector = new SelectChannelConnector();
    connector.setPort(port);
    server.addConnector(connector);

    DefaultSenseiJSONServlet senseiServlet = new DefaultSenseiJSONServlet();
    ServletHolder senseiServletHolder = new ServletHolder(senseiServlet);

    SenseiHttpInvokerServiceServlet springServlet = new SenseiHttpInvokerServiceServlet();
    ServletHolder springServletHolder = new ServletHolder(springServlet);

    AgentServlet jmxServlet = new AgentServlet();
    ServletHolder jmxServletHolder = new ServletHolder(jmxServlet);

    WebAppContext senseiApp = new WebAppContext();
    senseiApp.addFilter(GzipFilter.class,"/"+SENSEI_CONTEXT_PATH+"/*",1);

    //HashMap<String, String> initParam = new HashMap<String, String>();
    //if (_senseiConfFile != null) {
    //logger.info("Broker Configuration file: "+_senseiConfFile.getAbsolutePath());
    //initParam.put("config.file", _senseiConfFile.getAbsolutePath());
    //}
    //senseiApp.setInitParams(initParam);
    senseiApp.setAttribute("sensei.search.configuration", senseiConf);
    senseiApp.setAttribute(SenseiConfigServletContextListener.SENSEI_CONF_PLUGIN_REGISTRY, pluginRegistry);
    senseiApp.setAttribute("sensei.search.version.comparator", gateway.getVersionComparator());

    PartitionedLoadBalancerFactory<String> routerFactory = pluginRegistry.getBeanByFullPrefix(SenseiConfParams.SERVER_SEARCH_ROUTER_FACTORY, PartitionedLoadBalancerFactory.class);
    if (routerFactory == null) {
      routerFactory = new SenseiPartitionedLoadBalancerFactory(50);
    }

    senseiApp.setAttribute("sensei.search.router.factory", routerFactory);
    senseiApp.addEventListener(new SenseiConfigServletContextListener());
    senseiApp.addServlet(senseiServletHolder,"/"+SENSEI_CONTEXT_PATH+"/*");
    senseiApp.setResourceBase(webappPath);
    senseiApp.addServlet(springServletHolder,"/sensei-rpc/SenseiSpringRPCService");
    senseiApp.addServlet(jmxServletHolder,"/admin/jmx/*");

    server.setHandler(senseiApp);
    server.setStopAtShutdown(true);

    return server;
  }
  
  @Provides
  public ClusterClient buildClusterClient()
  {
    Configuration senseiConf = conf.senseiConf;
    String clusterName = senseiConf.getString(SENSEI_CLUSTER_NAME);
    String clusterClientName = senseiConf.getString(SENSEI_CLUSTER_CLIENT_NAME,clusterName);
    String zkUrl = senseiConf.getString(SENSEI_CLUSTER_URL);
    int zkTimeout = senseiConf.getInt(SENSEI_CLUSTER_TIMEOUT, 300000);
    ClusterClient clusterClient =  new ZooKeeperClusterClient(clusterClientName, clusterName, zkUrl, zkTimeout);
    logger.info("cluster client: "+clusterName+" constructed");
    return clusterClient;
  }

  @Provides
  private NetworkServer buildNetworkServer(ClusterClient clusterClient){
    Configuration senseiConf = conf.senseiConf;
    NetworkServerConfig networkConfig = new NetworkServerConfig();
    networkConfig.setClusterClient(clusterClient);

    networkConfig.setRequestThreadCorePoolSize(senseiConf.getInt(SERVER_REQ_THREAD_POOL_SIZE, 20));
    networkConfig.setRequestThreadMaxPoolSize(senseiConf.getInt(SERVER_REQ_THREAD_POOL_MAXSIZE,70));
    networkConfig.setRequestThreadKeepAliveTimeSecs(senseiConf.getInt(SERVER_REQ_THREAD_POOL_KEEPALIVE,300));
    return new NettyNetworkServer(networkConfig);
  }

  @Override
  public SenseiNode get() {
    return senseiNode;
  }
}
