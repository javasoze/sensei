package com.senseidb.search.node;

import java.io.IOException;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

import proj.zoie.api.ZoieIndexReader;
import proj.zoie.impl.indexing.AbstractIndexReaderDecorator;

import com.browseengine.bobo.api.BoboIndexReader;
import com.browseengine.bobo.facets.FacetHandler;
import com.browseengine.bobo.facets.RuntimeFacetHandlerFactory;

public class SenseiIndexReaderDecorator extends AbstractIndexReaderDecorator<BoboIndexReader> {
	private final List<FacetHandler<?>> _facetHandlers;
	private static final Logger logger = Logger.getLogger(SenseiIndexReaderDecorator.class);
    private final List<RuntimeFacetHandlerFactory<?,?>> _facetHandlerFactories;

	public SenseiIndexReaderDecorator(List<FacetHandler<?>> facetHandlers, List<RuntimeFacetHandlerFactory<?,?>> facetHandlerFactories)
	{
	  _facetHandlers = facetHandlers;
	  _facetHandlerFactories = facetHandlerFactories;
	}
	
	public SenseiIndexReaderDecorator()
	{
		this(null, null);
	}
	
	public List<FacetHandler<?>> getFacetHandlerList(){
		return _facetHandlers;
	}
	
	public List<RuntimeFacetHandlerFactory<?,?>> getFacetHandlerFactories(){
		return _facetHandlerFactories;
	}
	
	public BoboIndexReader decorate(ZoieIndexReader<BoboIndexReader> zoieReader) throws IOException {
		BoboIndexReader boboReader = null;
        if (zoieReader != null){
          
          boboReader = BoboIndexReader.getInstanceAsSubReader(zoieReader,_facetHandlers, _facetHandlerFactories);
          

          Directory dir = zoieReader.directory();
          if (!(dir instanceof RAMDirectory)){
            // index warming
            logger.info("warming new index reader");
            long start = System.currentTimeMillis();
            TermEnum te = null;
            
            try{
              te = zoieReader.terms();
              while (te.next()){
                Term t = te.term();
                TermPositions tp = null;
                try{
                  tp = zoieReader.termPositions(t);
                  while (tp.next()){
                    int f = tp.freq();
                    for (int i=0;i<f;++i){
                      tp.nextPosition();
                    }
                  }
                }
                finally{
                  if (tp != null){
                    tp.close();
                  }
                }
                
              }
            }
            finally{
              if (te != null){
                te.close();
              }
            }
  
            long end = System.currentTimeMillis();
            
            logger.info("finish warming index reader, took: "+((end-start)/1000)+"s");
          }
        }
        return boboReader;
	}
	
	@Override
    public BoboIndexReader redecorate(BoboIndexReader reader, ZoieIndexReader<BoboIndexReader> newReader,boolean withDeletes)
                          throws IOException {
          return reader.copy(newReader);
    }
}

