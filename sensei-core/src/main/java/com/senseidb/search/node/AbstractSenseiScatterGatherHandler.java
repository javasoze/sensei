package com.senseidb.search.node;

import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;

import com.senseidb.search.req.AbstractSenseiRequest;
import com.senseidb.search.req.AbstractSenseiResult;

public abstract class AbstractSenseiScatterGatherHandler<REQUEST extends AbstractSenseiRequest, RESULT extends AbstractSenseiResult>
{

  private final static Logger logger = Logger.getLogger(AbstractSenseiScatterGatherHandler.class);

  private final static long TIMEOUT_MILLIS = 8000L;

  private final REQUEST _request;

  private long _timeoutMillis = TIMEOUT_MILLIS;

  public AbstractSenseiScatterGatherHandler(REQUEST request) {
    _request = request;
  }

  public void setTimeoutMillis(long timeoutMillis)
  {
    _timeoutMillis = timeoutMillis;
  }

  public long getTimeoutMillis()
  {
    return _timeoutMillis;
  }

  /**
   * Merge results on the client/broker side. It likely works differently from
   * the one in the search node.
   * 
   * @param resultList
   *          the list of results from all the requested partitions.
   * @return one single result instance that is merged from the result list.
   */
  public abstract RESULT mergeResults(REQUEST request, List<RESULT> resultList);

  public abstract REQUEST customizeRequest(REQUEST request,  Set<Integer> partitions);


//    @Override
//    public RESULT gatherResponses(ResponseIterator<RESULT> iter) throws Exception {
//        boolean debugmode = logger.isDebugEnabled();
//        int timeOuts = 0;;
//        List<RESULT> boboBrowseList = new ArrayList<RESULT>();
//        while (iter.hasNext())
//        {
//          RESULT result = iter.next(_timeoutMillis > 0 ? _timeoutMillis : Long.MAX_VALUE, TimeUnit.MILLISECONDS);
//          if (result == null)
//          {
//            timeOuts++;
//            logger.error("Request Timed Out");
//          } else
//          {
//            boboBrowseList.add(result);
//          }
//        }
//        RESULT res = mergeResults(_request, boboBrowseList);
//        res.addError(new SenseiError("Request timeout", ErrorType.BrokerTimeout));
//        if (debugmode)
//        {
//          logger.debug("merged results: " + res);
//          logger.debug("Merging the sensei Results for the input senseiRequest");
//        }
//        return res;
//    }
}
