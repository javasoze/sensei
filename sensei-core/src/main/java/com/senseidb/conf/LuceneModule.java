package com.senseidb.conf;

import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Similarity;

import com.google.inject.AbstractModule;
import com.senseidb.search.node.SenseiQueryBuilderFactory;
import com.senseidb.search.node.impl.DefaultJsonQueryBuilderFactory;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.DefaultSimilarity;
import org.apache.lucene.util.Version;

public class LuceneModule extends AbstractModule implements SenseiConfParams{

  private Analyzer analyzer;
  private Similarity similarity;
  private SenseiQueryBuilderFactory queryBuilderFactory;
  
  public LuceneModule(SenseiConfiguration senseiConf){    
    analyzer = senseiConf.pluginRegistry.getBeanByFullPrefix(SENSEI_INDEX_ANALYZER, Analyzer.class);
    if (analyzer == null) {
      analyzer = new StandardAnalyzer(Version.LUCENE_35);
    }
    // Similarity from configuration:
    similarity = senseiConf.pluginRegistry.getBeanByFullPrefix(SENSEI_INDEX_SIMILARITY, Similarity.class);
    if (similarity == null) {
      similarity = new DefaultSimilarity();
    }
    
    queryBuilderFactory = senseiConf.pluginRegistry.getBeanByFullPrefix(SENSEI_QUERY_BUILDER_FACTORY, SenseiQueryBuilderFactory.class);
    if (queryBuilderFactory == null){
      QueryParser queryParser = new QueryParser(Version.LUCENE_35,"contents", analyzer);
      queryBuilderFactory = new DefaultJsonQueryBuilderFactory(queryParser);
    }
  }
  
  @Override
  protected void configure() {
    bind(Analyzer.class).toInstance(analyzer);
    bind(Similarity.class).toInstance(similarity);
    bind(SenseiQueryBuilderFactory.class).toInstance(queryBuilderFactory);
  }

}
