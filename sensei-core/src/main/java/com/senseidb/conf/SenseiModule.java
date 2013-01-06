package com.senseidb.conf;

import java.util.Iterator;
import java.util.Map;

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
import com.google.inject.Key;
import com.google.inject.Provides;
import com.linkedin.norbert.javacompat.network.PartitionedLoadBalancerFactory;
import com.senseidb.cluster.routing.SenseiPartitionedLoadBalancerFactory;
import com.senseidb.gateway.SenseiGateway;
import com.senseidb.plugin.SenseiPluginRegistry;
import com.senseidb.search.relevance.CustomRelevanceFunction.CustomRelevanceFunctionFactory;
import com.senseidb.search.relevance.ModelStorage;
import com.senseidb.servlet.DefaultSenseiJSONServlet;
import com.senseidb.servlet.SenseiConfigServletContextListener;
import com.senseidb.servlet.SenseiHttpInvokerServiceServlet;

public class SenseiModule extends AbstractModule implements SenseiConfParams{
  
  private static Logger logger = Logger.getLogger(SenseiModule.class);
  static final String SENSEI_CONTEXT_PATH = "sensei";
  
  private final Configuration senseiConf;
  private final Key<SenseiNode> exposedKey;
  private SenseiPluginRegistry pluginRegistry;
  private final SenseiGateway gateway;
  
  public SenseiModule(Configuration confDir, Key<SenseiNode> exposedKey){
    this.senseiConf = confDir;
    this.exposedKey = exposedKey;
    pluginRegistry = SenseiPluginRegistry.build(senseiConf);
    pluginRegistry.start();
    
    processRelevanceFunctionPlugins(pluginRegistry);

    gateway = pluginRegistry.getBeanByFullPrefix(SENSEI_GATEWAY, SenseiGateway.class);

  }
  
  private void processRelevanceFunctionPlugins(SenseiPluginRegistry pluginRegistry)
  {
    Map<String, CustomRelevanceFunctionFactory> map = pluginRegistry.getNamedBeansByType(CustomRelevanceFunctionFactory.class);
    Iterator<String> it = map.keySet().iterator();
    while(it.hasNext())
    {
      String name = it.next();
      CustomRelevanceFunctionFactory crf = map.get(name);
      ModelStorage.injectPreloadedModel(name, crf);
    }
    
  }
  
  @Override
  protected void configure() {
    System.out.println("installing confDir: "+senseiConf);
  }

  @Provides
  public Server buildHttpRestServer() throws Exception{
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
}
