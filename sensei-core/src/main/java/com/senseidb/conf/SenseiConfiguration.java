package com.senseidb.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.springframework.core.io.Resource;
import org.w3c.dom.Document;

import com.google.inject.AbstractModule;
import com.senseidb.indexing.SenseiIndexPruner;
import com.senseidb.indexing.ShardingStrategy;
import com.senseidb.plugin.SenseiPluginRegistry;
import com.senseidb.search.relevance.CustomRelevanceFunction.CustomRelevanceFunctionFactory;
import com.senseidb.search.relevance.ModelStorage;

public class SenseiConfiguration extends AbstractModule implements SenseiConfParams{

  public static final String SENSEI_PROPERTIES = "sensei.properties";

  public static final String SCHEMA_FILE_XML = "schema.xml";
  public static final String SCHEMA_FILE_JSON = "schema.json";
  
  public Configuration senseiConf;
  private File confDir;
  public SenseiPluginRegistry pluginRegistry;
  public SenseiSchema  senseiSchema;
  private LuceneModule luceneConf;
  private ShardingStrategy strategy;
  private SenseiIndexPruner indexPruner;
  
  public SenseiConfiguration(File confDir, Configuration senseiConf) throws Exception{
    this.confDir = confDir;
    this.senseiConf = senseiConf;
    initialize();
  }
  
  public SenseiConfiguration(File confDir) throws Exception{
    this.confDir = confDir;
    File senseiConfFile = new File(confDir,SENSEI_PROPERTIES);
    if (!senseiConfFile.exists()){
      throw new ConfigurationException("configuration file: "+senseiConfFile.getAbsolutePath()+" does not exist.");
    }
    senseiConf = new PropertiesConfiguration();
    ((PropertiesConfiguration)senseiConf).setDelimiterParsingDisabled(true);
    ((PropertiesConfiguration)senseiConf).load(senseiConfFile);
    initialize();
  }
  
  private void initialize() throws Exception{
    pluginRegistry = SenseiPluginRegistry.build(senseiConf);
    pluginRegistry.start();
    
    processRelevanceFunctionPlugins(pluginRegistry);
    
    JSONObject schemaDoc = loadSchema(confDir);
    senseiSchema = SenseiSchema.build(schemaDoc);
    
    strategy = pluginRegistry.getBeanByFullPrefix(SENSEI_SHARDING_STRATEGY, ShardingStrategy.class);
    if (strategy == null){
      strategy = new ShardingStrategy.FieldModShardingStrategy(senseiSchema.getUidField());
    }
    
    indexPruner = pluginRegistry.getBeanByFullPrefix(SENSEI_INDEX_PRUNER, SenseiIndexPruner.class);
    //if (indexPruner != null){
     // senseiCore.setIndexPruner(indexPruner);
    //}
    
    luceneConf = new LuceneModule(this);
  }
  
  @Override
  protected void configure() {
    bind(Configuration.class).toInstance(senseiConf);
    bind(SenseiSchema.class).toInstance(senseiSchema);
    bind(SenseiPluginRegistry.class).toInstance(pluginRegistry);
    bind(ShardingStrategy.class).toInstance(strategy);
    bind(SenseiIndexPruner.class).toInstance(indexPruner);
    install(luceneConf);
  }
  
  public static JSONObject loadSchema(File confDir) throws Exception{
    File jsonSchema = new File(confDir,SCHEMA_FILE_JSON);
    if (jsonSchema.exists()){
      InputStream is = new FileInputStream(jsonSchema);
      String json = IOUtils.toString( is );
      is.close();
      return new JSONObject(json);
    }
    else{
      File xmlSchema = new File(confDir,SCHEMA_FILE_XML);
      if (!xmlSchema.exists()){
        throw new ConfigurationException("schema not file");
      }
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setIgnoringComments(true);
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document schemaXml = db.parse(xmlSchema);
      schemaXml.getDocumentElement().normalize();
      return SchemaConverter.convert(schemaXml);
    }

  }

  public static JSONObject loadSchema(Resource confDir) throws Exception
  {
    if (confDir.createRelative(SCHEMA_FILE_JSON).exists()){
      String json = IOUtils.toString(confDir.createRelative(SCHEMA_FILE_JSON).getInputStream());
      return new JSONObject(json);
    }
    else{
      if (confDir.createRelative(SCHEMA_FILE_XML).exists()){
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setIgnoringComments(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document schemaXml = db.parse(confDir.createRelative(SCHEMA_FILE_XML).getInputStream());
        schemaXml.getDocumentElement().normalize();
        return SchemaConverter.convert(schemaXml);
      }
      else{
        throw new Exception("no schema found.");
      }
    }
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

}
