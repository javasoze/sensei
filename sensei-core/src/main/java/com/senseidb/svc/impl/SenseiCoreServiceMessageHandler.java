package com.senseidb.svc.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import zu.finagle.serialize.ZuSerializer;
import zu.finagle.server.ZuTransportService.RequestHandler;

import com.senseidb.metrics.MetricsConstants;
import com.senseidb.search.req.AbstractSenseiRequest;
import com.senseidb.search.req.AbstractSenseiResult;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.Timer;

public final class SenseiCoreServiceMessageHandler<REQUEST extends AbstractSenseiRequest, RESULT extends AbstractSenseiResult>
		implements RequestHandler<REQUEST, RESULT> {
	private static final Logger logger = Logger.getLogger(SenseiCoreServiceMessageHandler.class);
    private final AbstractSenseiCoreService<REQUEST, RESULT> _svc;

    private static Timer TotalSearchTimer = null;
    static{
  	  // register jmx monitoring for timers
  	  try{
  	    MetricName metricName = new MetricName(MetricsConstants.Domain,"timer","total-search-time","node");
  	    TotalSearchTimer = Metrics.newTimer(metricName, TimeUnit.MILLISECONDS,TimeUnit.SECONDS);
  	  }
	    catch(Exception e){
		    logger.error(e.getMessage(),e);
	    }
    }
    
    public SenseiCoreServiceMessageHandler(AbstractSenseiCoreService<REQUEST, RESULT> svc){
		  _svc = svc;
	  }

	@Override
	public RESULT handleRequest(final REQUEST request) {
		try {
      return TotalSearchTimer.time(new Callable<RESULT>(){

        @Override
        public RESULT call() throws Exception {
            return _svc.execute(request);
        }
    });
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getName() {
		return _svc.getMessageTypeName();
	}

	@Override
	public ZuSerializer<REQUEST, RESULT> getSerializer() {
		return _svc.getSerializer();
	}
}
