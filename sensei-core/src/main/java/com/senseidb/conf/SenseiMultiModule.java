package com.senseidb.conf;

import java.util.LinkedList;
import java.util.List;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;

import scala.actors.threadpool.Arrays;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

public class SenseiMultiModule extends AbstractModule {

  private final List<Configuration> confDirs;
  private final List<Key<SenseiNode>> keys;
  
  public SenseiMultiModule(List<Configuration> confDirs){
    this.confDirs = confDirs;
    this.keys = new LinkedList<Key<SenseiNode>>();
  }
  
  private List<Key<SenseiNode>> getKeys(){
    return keys;
  }
  
  private Key<SenseiNode> configureNode(final Configuration conf,int count){
    final Key<SenseiNode> keyToExpose = Key.get(SenseiNode.class, Names.named(conf.toString()+"-"+count));
    keys.add(keyToExpose);
    install(new PrivateModule() {
      @Override public void configure() {

        // Your private bindings go here, including the binding for MyInterface.
        // You can install other modules here as well!

        SenseiModule senseiModule = new SenseiModule(conf,keyToExpose);
        install(senseiModule);

        // expose the MyInterface binding with the unique key
        bind(keyToExpose).to(SenseiNode.class);
        expose(keyToExpose);
      }
    });
    
    return keyToExpose;
  }
  
  @Override
  protected void configure() {

    int count = 0;
    for (Configuration confDir : confDirs){
      Key<SenseiNode> keyToExpose = configureNode(confDir, count++);

      // add the exposed unique key to the multibinding
      Multibinder.newSetBinder(binder(), SenseiNode.class).addBinding().to(keyToExpose);
    }
  }
  
  public static void main(String[] args) throws Exception{
    String node1ConfPath = "/Users/jwang/github/sensei/example/cars/conf/sensei.properties";
    
    List<Configuration> confDirs = Arrays.asList(new Configuration[]{new PropertiesConfiguration(node1ConfPath)});
    
    SenseiMultiModule senseiMultiModule = new SenseiMultiModule(confDirs);
    Injector injector = Guice.createInjector(senseiMultiModule);
    
    Provider<SenseiNode> senseiNodeProvider = injector.getProvider(senseiMultiModule.getKeys().get(0));
    SenseiNode node = senseiNodeProvider.get();
  }

}
