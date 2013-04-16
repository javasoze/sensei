package com.senseidb.search.node;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.SortField;

import proj.zoie.api.indexing.AbstractZoieIndexable;
import zu.core.cluster.ZuCluster;
import zu.core.cluster.ZuClusterEventListener;

import com.browseengine.bobo.api.FacetSpec;
import com.senseidb.conf.SenseiSchema;
import com.senseidb.indexing.DefaultJsonSchemaInterpreter;
import com.senseidb.search.req.ErrorType;
import com.senseidb.search.req.SenseiError;
import com.senseidb.search.req.SenseiHit;
import com.senseidb.search.req.SenseiRequest;
import com.senseidb.search.req.SenseiResult;
import com.senseidb.svc.impl.CoreSenseiServiceImpl;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;


/**
 * This SenseiBroker routes search(browse) request using the routers created by
 * the supplied router factory. It uses scatter-gather handling
 * mechanism to handle distributed search, which does not support request based
 * context sensitive routing.
 */
public class SenseiBroker extends AbstractConsistentHashBroker<SenseiRequest, SenseiResult> implements ZuClusterEventListener
{
  private final static Logger logger = Logger.getLogger(SenseiBroker.class);

	private final boolean allowPartialMerge;
	private Set<InetSocketAddress> nodeAddresses = Collections.emptySet();
	private static Counter numberOfNodesInTheCluster = Metrics.newCounter(new MetricName(SenseiBroker.class,
			"numberOfNodesInTheCluster"));
    private volatile boolean disconnected;

	public SenseiBroker(ZuCluster clusterClient, boolean allowPartialMerge) {
		super(clusterClient, CoreSenseiServiceImpl.JAVA_SERIALIZER);
		this.allowPartialMerge = allowPartialMerge;
	}

  public static void recoverSrcData(SenseiResult res, SenseiHit[] hits, boolean isFetchStoredFields)
  {
    if (hits != null)
    {
      for(SenseiHit hit : hits)
      {
        try
        {
          byte[] dataBytes = hit.getStoredValue();
          if (dataBytes == null || dataBytes.length == 0)
          {
            Document doc = hit.getStoredFields();
            if (doc != null)
            {
              dataBytes = doc.getBinaryValue(AbstractZoieIndexable.DOCUMENT_STORE_FIELD);

              if (dataBytes == null || dataBytes.length == 0)
              {
                dataBytes = doc.getBinaryValue(SenseiSchema.SRC_DATA_COMPRESSED_FIELD_NAME);

                if (dataBytes == null || dataBytes.length == 0)
                {
                  dataBytes = doc.getBinaryValue(SenseiSchema.SRC_DATA_FIELD_NAME);
                  if (dataBytes != null && dataBytes.length > 0)
                  {
                    hit.setSrcData(new String(dataBytes,"UTF-8"));
                    dataBytes = null; // set to null to avoid gunzip.
                  }
                }
                doc.removeFields(SenseiSchema.SRC_DATA_COMPRESSED_FIELD_NAME);
                doc.removeFields(SenseiSchema.SRC_DATA_FIELD_NAME);
              }
            }
          }
          if (dataBytes != null && dataBytes.length > 0)
          {
            byte[] data;
            try
            {
              data = DefaultJsonSchemaInterpreter.decompress(dataBytes);
            }
            catch(Exception ex)
            {
              
              data = dataBytes;
            }
            hit.setSrcData(new String(data, "UTF-8"));
          }
        }
        catch(Exception e)
        {
          logger.error(e.getMessage(),e);
          res.getErrors().add(new SenseiError(e.getMessage(), ErrorType.BrokerGatherError));
        }

        recoverSrcData(res, hit.getSenseiGroupHits(), isFetchStoredFields);

        // Remove stored fields since the user is not requesting:
        if (!isFetchStoredFields)
          hit.setStoredFields(null);
      }
    }
  }

  @Override
  public SenseiResult mergeResults(SenseiRequest request, List<SenseiResult> resultList)
  {
    SenseiResult res = ResultMerger.merge(request, resultList, false);
    
    if (request.isFetchStoredFields() || request.isFetchStoredValue())
      recoverSrcData(res, res.getSenseiHits(), request.isFetchStoredFields());

    return res;
  }

  @Override
  public SenseiResult getEmptyResultInstance()
  {
    return new SenseiResult();
  }
  
  @Override
  public SenseiRequest customizeRequest(SenseiRequest request)
  {    // Rewrite offset and count.
    request.setCount(request.getOffset()+request.getCount());
    request.setOffset(0);

    // Rewrite facet max count.
    Map<String, FacetSpec> facetSpecs = request.getFacetSpecs();
    if (facetSpecs != null) {
      for (Map.Entry<String, FacetSpec> entry : facetSpecs.entrySet()) {
        FacetSpec spec = entry.getValue();
        if (spec != null && spec.getMaxCount() < 50)
          spec.setMaxCount(50);
      }
    }

    // Rewrite fetchStoredFields for zoie store.
    if (!request.isFetchStoredFields())
      request.setFetchStoredFields(request.isFetchStoredValue());

    // Rewrite select list to include sort and group by fields:
    if (request.getSelectSet() != null)
    {
      List<String> selectList = request.getSelectList();
      SortField[] sortFields = request.getSort();
      if (sortFields != null && sortFields.length != 0)
      {
        for (int i = 0; i < sortFields.length; ++i)
        {
          if (sortFields[i].getType() != SortField.SCORE && sortFields[i].getType() != SortField.DOC)
          {
            String field = sortFields[i].getField();
            selectList.add(field);
          }
        }
      }
      String[] groupByFields = request.getGroupBy();
      if (groupByFields != null && groupByFields.length != 0)
      {
        for (int i = 0; i < groupByFields.length; ++i)
        {
          selectList.add(groupByFields[i]);
        }
      }
      String[] distinctFields = request.getDistinct();
      if (distinctFields != null && distinctFields.length != 0)
      {
        for (int i = 0; i < distinctFields.length; ++i)
        {
          selectList.add(distinctFields[i]);
        }
      }
      request.setSelectList(selectList);
    }

    return request;
  }


  @Override
  public boolean allowPartialMerge() {
    return allowPartialMerge;
  }

	public boolean isDisconnected() {
	  return disconnected;
	}

	@Override
	protected String getMessageType() {
		return CoreSenseiServiceImpl.MESSAGE_TYPE_NAME;
	}

	@Override
	public void clusterChanged(Map<Integer, List<InetSocketAddress>> clusterView) {
		logger.info("clusterChanged(): Received new clusterView from zu " + clusterView);
		Set<InetSocketAddress> nodeAddresses = getNodesAddresses(clusterView);
		this.nodeAddresses = nodeAddresses;
	}

	public void updateNumberOfNodesMetric() {
		synchronized (SenseiBroker.class) {
			numberOfNodesInTheCluster.clear();
			numberOfNodesInTheCluster.inc(nodeAddresses.size());
		}
	}

	@Override
	public void nodesRemoved(Set<InetSocketAddress> nodesRemoved) {
	}

}
