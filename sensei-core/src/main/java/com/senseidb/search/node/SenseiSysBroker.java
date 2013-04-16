package com.senseidb.search.node;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.log4j.Logger;

import zu.core.cluster.ZuCluster;
import zu.core.cluster.ZuClusterEventListener;

import com.senseidb.search.req.SenseiRequest;
import com.senseidb.search.req.SenseiSystemInfo;
import com.senseidb.svc.impl.SysSenseiCoreServiceImpl;
import com.twitter.finagle.Service;

public class SenseiSysBroker extends AbstractConsistentHashBroker<SenseiRequest, SenseiSystemInfo> implements ZuClusterEventListener
{
  private final static Logger logger = Logger.getLogger(SenseiSysBroker.class);
  private final static long TIMEOUT_MILLIS = 8000L;
  private long _timeoutMillis = TIMEOUT_MILLIS;
  private final Comparator<String> _versionComparator;
  private final boolean allowPartialMerge;
	private Map<InetSocketAddress, Service<SenseiRequest, SenseiSystemInfo>> _nodeAddressToService;

  public SenseiSysBroker(ZuCluster clusterClient, Comparator<String> versionComparator, boolean allowPartialMerge)
  {
    super(clusterClient, SysSenseiCoreServiceImpl.JAVA_SERIALIZER);
    _versionComparator = versionComparator;
    this.allowPartialMerge = allowPartialMerge;
    clusterClient.addClusterEventListener(this);
  }

  @Override
  public SenseiSystemInfo mergeResults(SenseiRequest request, List<SenseiSystemInfo> resultList)
  {
    SenseiSystemInfo result = new SenseiSystemInfo();
    if (resultList == null)
      return result;

    for (SenseiSystemInfo res : resultList)
    {
      result.setNumDocs(result.getNumDocs()+res.getNumDocs());
      result.setSchema(res.getSchema());
      if (result.getLastModified() < res.getLastModified())
        result.setLastModified(res.getLastModified());
      if (result.getVersion() == null || _versionComparator.compare(result.getVersion(), res.getVersion()) < 0)
        result.setVersion(res.getVersion());
      if (res.getFacetInfos() != null)
        result.setFacetInfos(res.getFacetInfos());
      if (res.getClusterInfo() != null) {
        if (result.getClusterInfo() != null)
          result.getClusterInfo().addAll(res.getClusterInfo());
        else
          result.setClusterInfo(res.getClusterInfo());
      }
    }

    return result;
  }

	@Override
	protected List<SenseiSystemInfo> doCall(final SenseiRequest req) throws ExecutionException {

		Map<Service<SenseiRequest, SenseiSystemInfo>, SenseiRequest> serviceToRequest = new HashMap<Service<SenseiRequest, SenseiSystemInfo>, SenseiRequest>();
		for (Service<SenseiRequest, SenseiSystemInfo> service : _nodeAddressToService.values()) {
			serviceToRequest.put(service, req);
		}

		return executeRequestsInParallel(serviceToRequest, _timeoutMillis);
	}

	@Override
  public SenseiSystemInfo getEmptyResultInstance()
  {
    return new SenseiSystemInfo();
  }

  @Override
  public boolean allowPartialMerge() {
    return allowPartialMerge;
  }
  
	@Override
	protected String getMessageType() {
		return SysSenseiCoreServiceImpl.MESSAGE_TYPE_NAME;
	}

	@Override
	public void clusterChanged(Map<Integer, List<InetSocketAddress>> clusterView) {
		logger.info("clusterChanged(): Received new clusterView from zu " + clusterView);
		Set<InetSocketAddress> nodes = getNodesAddresses(clusterView);
		Map<InetSocketAddress, Service<SenseiRequest, SenseiSystemInfo>> addressToService = new HashMap<InetSocketAddress, Service<SenseiRequest, SenseiSystemInfo>>();
		for (InetSocketAddress nodeAddress : nodes) {
			Service<SenseiRequest, SenseiSystemInfo> service = serviceDecorator.decorate(nodeAddress);
			addressToService.put(nodeAddress, service);
		}
		_nodeAddressToService = addressToService;
	}

	@Override
	public void nodesRemoved(Set<InetSocketAddress> removedNodes) {
	}

}

