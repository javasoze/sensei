package com.senseidb.search.node;

import java.io.File;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.management.StandardMBean;

import org.apache.log4j.Logger;
import org.mortbay.jetty.Server;

import proj.zoie.api.DataProvider;
import zu.core.cluster.ZuCluster;
import zu.finagle.server.ZuFinagleServer;
import zu.finagle.server.ZuTransportService;

import com.senseidb.conf.SenseiServerBuilder;
import com.senseidb.jmx.JmxUtil;
import com.senseidb.plugin.SenseiPluginRegistry;
import com.senseidb.search.req.AbstractSenseiRequest;
import com.senseidb.search.req.AbstractSenseiResult;
import com.senseidb.search.req.SenseiRequest;
import com.senseidb.search.req.SenseiResult;
import com.senseidb.search.req.SenseiSystemInfo;
import com.senseidb.svc.impl.AbstractSenseiCoreService;
import com.senseidb.svc.impl.CoreSenseiServiceImpl;
import com.senseidb.svc.impl.SenseiCoreServiceMessageHandler;
import com.senseidb.svc.impl.SysSenseiCoreServiceImpl;
import com.senseidb.util.NetUtil;
import com.twitter.common.zookeeper.ServerSet.UpdateException;


public class SenseiServer {
  private static final Logger logger = Logger.getLogger(SenseiServer.class);

  private static final String AVAILABLE = "available";
  private static final String UNAVAILABLE = "unavailable";
  private static final String DUMMY_OUT_IP = "74.125.224.0";

  private int _id;
  private int _port;
  private int[] _partitions;
  private final SenseiCore _core;
  private final CoreSenseiServiceImpl _innerSvc;
  private final List<AbstractSenseiCoreService<AbstractSenseiRequest, AbstractSenseiResult>> _externalSvc;

  //private Server _adminServer;

  protected volatile boolean _available = false;

  private final SenseiPluginRegistry pluginRegistry;

	private ZuTransportService transportService;
	private ZuFinagleServer server;
	private ZuCluster cluster;

	public SenseiServer(int port, SenseiCore senseiCore,
			List<AbstractSenseiCoreService<AbstractSenseiRequest, AbstractSenseiResult>> externalSvc,
			SenseiPluginRegistry pluginRegistry, ZuTransportService transport, ZuFinagleServer server, ZuCluster cluster) {
		_core = senseiCore;
		this.pluginRegistry = pluginRegistry;
		this.transportService = transport;
		this.server = server;
		this.cluster = cluster;
		_id = senseiCore.getNodeId();
		_port = port;
		_partitions = senseiCore.getPartitions();

		_innerSvc = new CoreSenseiServiceImpl(senseiCore);
		_externalSvc = externalSvc;
	}

private static String help(){
    StringBuffer buffer = new StringBuffer();
    buffer.append("Usage: <conf.dir> [availability]\n");
    buffer.append("====================================\n");
    buffer.append("conf.dir - server configuration directory, required\n");
    buffer.append("availability - \"available\" or \"unavailable\", optional default is \"available\"\n");
    buffer.append("====================================\n");
    return buffer.toString();
  }

  /*
  public Collection<Zoie<BoboIndexReader,?,?>> getZoieSystems(){
    return _core.zoieSystems;
  }
  */

  public DataProvider getDataProvider()
  {
    return _core.getDataProvider();
  }

  public SenseiCore getSenseiCore()
  {
    return _core;
  }

  /*
  public void setAdminServer(Server server)
  {
    _adminServer = server;
  }

  public SenseiNodeInfo getSenseiNodeInfo()
  {
    StringBuffer adminLink = new StringBuffer();
    if (_adminServer != null && _adminServer.getConnectors() != null && _adminServer.getConnectors().length != 0)
    {
      adminLink.append("http://").append(_adminServer.getConnectors()[0].getHost()).append(":")
               .append(_adminServer.getConnectors()[0].getPort());
    }

    return new SenseiNodeInfo(_id, _partitions, _serverNode.getUrl(), adminLink.toString());
  }
  */

  public void shutdown(){
    try {
      logger.info("shutting down node...");
      try
      {        
        _core.shutdown();
        pluginRegistry.stop();
        server.leaveCluster(cluster);
        server.shutdown();
        cluster.shutdown();
        _core.getPluggableSearchEngineManager().close();
      } catch (Exception e)
      {
        logger.warn(e.getMessage());
      }
    } catch (Exception e) {
      logger.error(e.getMessage(),e);
    }
  }

  public void start(boolean available) throws Exception {
    _core.start();
    logger.info("Cluster Id: " + cluster.getClusterId());

		AbstractSenseiCoreService<SenseiRequest, SenseiResult> coreSenseiService = new CoreSenseiServiceImpl(_core);
		AbstractSenseiCoreService<SenseiRequest, SenseiSystemInfo> sysSenseiCoreService = new SysSenseiCoreServiceImpl(_core);   
		SenseiCoreServiceMessageHandler<SenseiRequest, SenseiResult> senseiMsgHandler = new SenseiCoreServiceMessageHandler<SenseiRequest, SenseiResult>(
				coreSenseiService);
		SenseiCoreServiceMessageHandler<SenseiRequest, SenseiSystemInfo> senseiSysMsgHandler = new SenseiCoreServiceMessageHandler<SenseiRequest, SenseiSystemInfo>(
				sysSenseiCoreService);

		transportService.registerHandler(senseiMsgHandler);
		transportService.registerHandler(senseiSysMsgHandler);

		server.start();

    if (_externalSvc!=null){
      for (AbstractSenseiCoreService svc : _externalSvc){
      	transportService.registerHandler(new SenseiCoreServiceMessageHandler(svc));
      }
    }

		setAvailable(available);
    
    SenseiServerAdminMBean senseiAdminMBean = getAdminMBean();
    StandardMBean bean = new StandardMBean(senseiAdminMBean, SenseiServerAdminMBean.class);
    JmxUtil.registerMBean(bean, "name", "sensei-server-"+_id);
  }

	private Set<Integer> getPartitions() {
		Set<Integer> shards = new HashSet<Integer>();
		for (Integer partition : _partitions) {
			shards.add(partition);
		}
		return shards;
	}


  private String getLocalIpAddress() throws SocketException,
      UnknownHostException {
    String addr = NetUtil.getHostAddress();
    return String.format("%s:%d", addr, _port);
  }

	private SenseiServerAdminMBean getAdminMBean()
	{
	  return new SenseiServerAdminMBean(){
	  @Override
    public int getId()
      {
        return _id;
      }
      @Override
      public int getPort()
      {
        return _port;
      }
      @Override
      public String getPartitions()
      {
        StringBuffer sb = new StringBuffer();
        if(_partitions.length > 0) sb.append(String.valueOf(_partitions[0]));
        for(int i = 1; i < _partitions.length; i++)
        {
          sb.append(',');
          sb.append(String.valueOf(_partitions[i]));
        }
        return sb.toString();
      }
      @Override
      public boolean isAvailable()
      {
        return SenseiServer.this.isAvailable();
      }
      @Override
      public void setAvailable(boolean available)
      {
        SenseiServer.this.setAvailable(available);
      }
    };
  }

	public void setAvailable(boolean available) {
		String ipAddr;
		try {
			ipAddr = getLocalIpAddress();
		} catch (Exception e) {
			logger.warn("cannot get local IP address", e);
			ipAddr = "unknown";
		}

		if (available) {
			try {
				logger.info("making available node " + _id + " @port:" + _port + " IP address : " + ipAddr
						+ " for partitions: " + Arrays.toString(_partitions));
				server.joinCluster(cluster, getPartitions());
				_available = true;
			} catch (Exception e) {
				logger.error("error joining cluster", e);
			}
		} else {
			logger.info("making unavailable node " + _id + " @port:" + _port + " IP address : " + ipAddr
					+ " for partitions: " + Arrays.toString(_partitions));
			try {
				server.leaveCluster(cluster);
				_available = false;
			} catch (UpdateException e) {
				logger.error("error leaving cluster", e);
			}
		}
	}

	public boolean isAvailable()
	{
		return _available;
	}

  /*private static void loadJars(File extDir)
  {
    File[] jarfiles = extDir.listFiles(new FilenameFilter(){
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".jar");
        }
    });

    if (jarfiles!=null && jarfiles.length > 0){
    try{
        URL[] jarURLs = new URL[jarfiles.length];
          ClassLoader parentLoader = Thread.currentThread().getContextClassLoader();
          for (int i=0;i<jarfiles.length;++i){
            String jarFile = jarfiles[i].getAbsolutePath();
            logger.info("loading jar: "+jarFile);
            jarURLs[i] = new URL("jar:file://" + jarFile + "!/");
          }
          URLClassLoader classloader = new URLClassLoader(jarURLs,parentLoader);
          logger.info("url classloader: "+classloader);
          Thread.currentThread().setContextClassLoader(classloader);
    }
    catch(MalformedURLException e){
      logger.error("problem loading extension: "+e.getMessage(),e);
    }
  }
}*/

  public  static void main(String[] args) throws Exception {
    if (args.length<1){
      System.out.println(help());
      System.exit(1);
    }

    File confDir = null;

    try {
      confDir = new File(args[0]);
    }
    catch(Exception e) {
      System.out.println(help());
      System.exit(1);
    }

    boolean available = true;
    for(int i = 1; i < args.length; i++)
    {
      if(args[i] != null)
      {
        if(AVAILABLE.equalsIgnoreCase(args[i]))
        {
          available = true;
        }
        if(UNAVAILABLE.equalsIgnoreCase(args[i]))
        {
          available = false;
        }
      }
    }

    /*File extDir = new File(confDir,"ext");

    if (extDir.exists()){
    	logger.info("loading extension jars...");
        loadJars(extDir);
    	logger.info("finished loading extension jars");
    }*/


    SenseiServerBuilder senseiServerBuilder = new SenseiServerBuilder(confDir, null);

    final SenseiServer server = senseiServerBuilder.buildServer();

    final Server jettyServer = senseiServerBuilder.buildHttpRestServer();

    /*final HttpAdaptor httpAdaptor = senseiServerBuilder.buildJMXAdaptor();


    final ObjectName httpAdaptorName = new ObjectName("mx4j:class=mx4j.tools.adaptor.http.HttpAdaptor,id=1");
	 if (httpAdaptor!=null){
		  try{
			server.mbeanServer.registerMBean(httpAdaptor, httpAdaptorName);
			server.mbeanServer.invoke(httpAdaptorName, "start", null, null);
			httpAdaptor.setProcessor(new XSLTProcessor());
		    logger.info("http adaptor started on port: "+httpAdaptor.getPort());
		  }
		  catch(Exception e){
			logger.error(e.getMessage(),e);
		  }
	  }
*/
    Runtime.getRuntime().addShutdownHook(new Thread(){
      @Override
      public void run(){

        try{
          jettyServer.stop();
        } catch (Exception e) {
          logger.error(e.getMessage(),e);
        }
        finally{
          try{
            server.shutdown();
          }
          finally{
            /*try{
               if (httpAdaptor!=null){
                httpAdaptor.stop();
                server.mbeanServer.invoke(httpAdaptorName, "stop", null, null);
                server.mbeanServer.unregisterMBean(httpAdaptorName);
                logger.info("http adaptor shutdown");
              }
             }
             catch(Exception e){
              logger.error(e.getMessage(),e);
             }*/
          }
        }
      }
    });



    server.start(available);
    jettyServer.start();
  }

}
