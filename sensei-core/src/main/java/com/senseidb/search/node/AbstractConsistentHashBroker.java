package com.senseidb.search.node;


import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.log4j.Logger;

import zu.core.cluster.ZuCluster;
import zu.core.cluster.routing.ConsistentHashRoutingAlgorithm;
import zu.core.cluster.routing.RoutingAlgorithm;
import zu.finagle.client.ZuFinagleServiceDecorator;
import zu.finagle.client.ZuTransportClientProxy;
import zu.finagle.serialize.ZuSerializer;

import com.senseidb.metrics.MetricsConstants;
import com.senseidb.search.req.AbstractSenseiRequest;
import com.senseidb.search.req.AbstractSenseiResult;
import com.senseidb.search.req.ErrorType;
import com.senseidb.search.req.SenseiError;
import com.senseidb.search.req.SenseiRequest;
import com.senseidb.svc.api.SenseiException;
import com.twitter.finagle.Service;
import com.twitter.util.Duration;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;

/**
 * @author "Xiaoyang Gu<xgu@linkedin.com>"
 *
 * @param <REQUEST>
 * @param <RESULT>
 */
public abstract class AbstractConsistentHashBroker<REQUEST extends AbstractSenseiRequest, RESULT extends AbstractSenseiResult>
    extends AbstractSenseiBroker<REQUEST, RESULT>
{
  private final static Logger logger = Logger.getLogger(AbstractConsistentHashBroker.class);

	protected long _timeout = 8000;
  protected final ZuSerializer<REQUEST, RESULT> _serializer;

  private static Timer ScatterTimer = null;
  private static Timer GatherTimer = null;
  private static Timer TotalTimer = null;
  private static Meter SearchCounter = null;
  private static Meter ErrorMeter = null;
  private static Meter EmptyMeter = null;


  protected ZuFinagleServiceDecorator<REQUEST, RESULT> serviceDecorator;
	private RoutingAlgorithm<Service<REQUEST,RESULT>> router;
	private ZuCluster clusterClient;

  
  static{
	  // register metrics monitoring for timers
	  try{
	    MetricName scatterMetricName = new MetricName(MetricsConstants.Domain,"timer","scatter-time","broker");
	    ScatterTimer = Metrics.newTimer(scatterMetricName, TimeUnit.MILLISECONDS,TimeUnit.SECONDS);
	    
	    MetricName gatherMetricName = new MetricName(MetricsConstants.Domain,"timer","gather-time","broker");
	    GatherTimer = Metrics.newTimer(gatherMetricName, TimeUnit.MILLISECONDS,TimeUnit.SECONDS);

	    MetricName totalMetricName = new MetricName(MetricsConstants.Domain,"timer","total-time","broker");
	    TotalTimer = Metrics.newTimer(totalMetricName, TimeUnit.MILLISECONDS,TimeUnit.SECONDS);
	    
	    MetricName searchCounterMetricName = new MetricName(MetricsConstants.Domain,"meter","search-count","broker");
	    SearchCounter = Metrics.newMeter(searchCounterMetricName, "requets", TimeUnit.SECONDS);

	    MetricName errorMetricName = new MetricName(MetricsConstants.Domain,"meter","error-meter","broker");
	    ErrorMeter = Metrics.newMeter(errorMetricName, "errors",TimeUnit.SECONDS);
	    
	    MetricName emptyMetricName = new MetricName(MetricsConstants.Domain,"meter","empty-meter","broker");
	    EmptyMeter = Metrics.newMeter(emptyMetricName, "null-hits", TimeUnit.SECONDS);
	  }
	  catch(Exception e){
		logger.error(e.getMessage(),e);
	  }
  }
  
	/**
	 * @param clusterClient
   * @param serializer
   *          The serializer used to serialize/deserialize request/response pairs
	 */
	public AbstractConsistentHashBroker(ZuCluster clusterClient, ZuSerializer<REQUEST, RESULT> serializer) {
		super();
		this.clusterClient = clusterClient;
		_serializer = serializer;
		ZuTransportClientProxy<REQUEST, RESULT> proxy = new ZuTransportClientProxy<REQUEST, RESULT>(getMessageType(),
				_serializer);
		serviceDecorator = new ZuFinagleServiceDecorator<REQUEST, RESULT>(proxy);
		router = new ConsistentHashRoutingAlgorithm<Service<REQUEST, RESULT>>(serviceDecorator);
		clusterClient.addClusterEventListener(router);
	}

  
	public REQUEST customizeRequest(REQUEST request) {
		return request;
	}

  /**
   * @return an empty result instance. Used when the request cannot be properly
   *         processed or when the true result is empty.
   */
  public abstract RESULT getEmptyResultInstance();

  /**
   * The method that provides the search service.
   * 
   * @param req
   * @return
   * @throws SenseiException
   */
  public RESULT browse(final REQUEST req) throws SenseiException
  {
//    if (_partitions == null){
//      ErrorMeter.mark();
//      throw new SenseiException("Browse called before cluster is connected!");
//    }
    SearchCounter.mark();
    try
    {
      return TotalTimer.time(new Callable<RESULT>(){
    	@Override
  		public RESULT call() throws Exception {
          return doBrowse(req); 	  
    	}
      });
    } 
    catch (Exception e)
    {
      ErrorMeter.mark();
      throw new SenseiException(e.getMessage(), e);
    }
  }

  /**
   * Merge results on the client/broker side. It likely works differently from
   * the one in the search node.
   * 
   * @param request
   *          the original request object
   * @param resultList
   *          the list of results from all the requested partitions.
   * @return one single result instance that is merged from the result list.
   */
  public abstract RESULT mergeResults(REQUEST request, List<RESULT> resultList);

  protected String getRouteParam(REQUEST req) {
    String param = req.getRouteParam();
    if (param == null) {
      return RandomStringUtils.random(4);
    }
    else {
      return param;
    }
  }

  protected RESULT doBrowse(final REQUEST req)
  {
    final long time = System.currentTimeMillis();

    final List<RESULT> resultList = new ArrayList<RESULT>();
   
    try {
      resultList.addAll(ScatterTimer.time(new Callable<List<RESULT>>() {
        @Override
        public List<RESULT> call() throws Exception {
          return doCall(req);
        }
      }));
    } catch (Exception e) {
      ErrorMeter.mark();
      RESULT emptyResult = getEmptyResultInstance();
      logger.error("Error running scatter/gather", e);
      emptyResult.addError(new SenseiError("Error gathering the results" + e.getMessage(), ErrorType.BrokerGatherError));
      return emptyResult;
    }

    if (resultList.size() == 0)
    {
      logger.error("no result received at all return empty result");
      RESULT emptyResult = getEmptyResultInstance();
      emptyResult.addError(new SenseiError("Error gathering the results. " + "no result received at all return empty result", ErrorType.BrokerGatherError));
      EmptyMeter.mark();
      return emptyResult;
    }

    RESULT result = null;
    try {
      result = GatherTimer.time(new Callable<RESULT>() {
        @Override
        public RESULT call() throws Exception {
          return mergeResults(req, resultList);
        }
      });
    } catch (Exception e) {
      result = getEmptyResultInstance();
      logger.error("Error gathering the results", e);
      result.addError(new SenseiError("Error gathering the results" + e.getMessage(), ErrorType.BrokerGatherError));
      ErrorMeter.mark();
    }

    if (logger.isDebugEnabled()){
      logger.debug("remote search took " + (System.currentTimeMillis() - time) + "ms");
    }

    return result;
  }
  
	protected List<RESULT> doCall(REQUEST req) throws ExecutionException {
		Set<Integer> shards = router.getShards();

		Map<Service<REQUEST, RESULT>, REQUEST> serviceToRequest = new HashMap<Service<REQUEST, RESULT>, REQUEST>();

		for (Integer shard : shards) {
			Service<REQUEST, RESULT> service = router.route(getRouteParam(req).getBytes(), shard);

			if (service == null) {
				logger.warn("router returned null as a destination service");
				continue;
			}

			REQUEST requestToNode = serviceToRequest.get(service);
			if (requestToNode == null) {
        // TODO: Cloning is yucky per http://www.artima.com/intv/bloch13.htm
				requestToNode = (REQUEST) (((SenseiRequest) req).clone());
				requestToNode = customizeRequest(requestToNode);
				requestToNode.setPartitions(new HashSet<Integer>());
				serviceToRequest.put(service, requestToNode);
			}
			requestToNode.getPartitions().add(shard);
		}

		return executeRequestsInParallel(serviceToRequest, _timeout);
	}

	protected abstract String getMessageType();
  
  public void shutdown()
  {
    logger.info("shutting down broker...");
  }

  public long getTimeout() {
    return _timeout;
  }

  public void setTimeout(long timeout) {
    this._timeout = timeout;
  }

  /**
   * @return boolean representing whether or not the server can tolerate node failures or timeouts and merge the other
   * results. It's a tradeoff between fault tolerance and accuracy that may be acceptable for some applications
   */
  public abstract boolean allowPartialMerge();

  protected List<RESULT> executeRequestsInParallel(final Map<Service<REQUEST, RESULT>, REQUEST> serviceToRequest, long timeout) {
		final List<Future<RESULT>> futures = new ArrayList<Future<RESULT>>();
		for (Entry<Service<REQUEST, RESULT>, REQUEST> entry : serviceToRequest.entrySet()) {
			futures.add(entry.getKey().apply(entry.getValue()).addEventListener(new FutureEventListener<RESULT>() {
	
				@Override
				public void onFailure(Throwable t) {
					logger.error("Failed to get response", t);
				}
	
				@Override
				public void onSuccess(RESULT result) {
					// do nothing as we wait for all results below
				}
			}));
		}
	
		Future<List<RESULT>> collected = Future.collect(futures);
		List<RESULT> results = collected.apply(Duration.apply(timeout, TimeUnit.MILLISECONDS));
		logger.debug(String.format("There are %d responses", results.size()));
		return results;
	}


	protected static Set<InetSocketAddress> getNodesAddresses(Map<Integer, List<InetSocketAddress>> clusterView) {
		Set<InetSocketAddress> nodes = new HashSet<InetSocketAddress>();
		for (List<InetSocketAddress> inetSocketAddressList : clusterView.values()) {
			nodes.addAll(inetSocketAddressList);
		}
		return nodes;
	}
}
