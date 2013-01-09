package com.senseidb.conf;

import java.io.File;
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

  private final List<SenseiConfiguration> confDirs;
  private final List<Key<SenseiNode>> keys;
  
  public SenseiMultiModule(List<SenseiConfiguration> confDirs){
    this.confDirs = confDirs;
    this.keys = new LinkedList<Key<SenseiNode>>();
  }
  
  private List<Key<SenseiNode>> getKeys(){
    return keys;
  }
  
  private Key<SenseiNode> configureNode(final SenseiConfiguration conf,int count){
    final Key<SenseiNode> keyToExpose = Key.get(SenseiNode.class, Names.named(conf.toString()+"-"+count));
    keys.add(keyToExpose);
    install(new PrivateModule() {
      @Override public void configure() {

        // private bindings go here
        // install other modules here as well!

        SenseiModule senseiModule = new SenseiModule(conf);
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
    for (SenseiConfiguration confDir : confDirs){
      Key<SenseiNode> keyToExpose = configureNode(confDir, count++);

      // add the exposed unique key to the multibinding
      Multibinder.newSetBinder(binder(), SenseiNode.class).addBinding().to(keyToExpose);
    }
  }
  
  public static void main(String[] args) throws Exception{

    File node1ConfPath = new File("/Users/johnwang/github/sensei/example/cars/conf");
    File node2ConfPath = new File("/Users/johnwang/github/sensei/example/cars/conf2");
    
    SenseiConfiguration conf1 = new SenseiConfiguration(node1ConfPath);
    
    
    SenseiConfiguration conf2 = new SenseiConfiguration(node2ConfPath);
    
    List<SenseiConfiguration> confDirs = Arrays.asList(new SenseiConfiguration[]{conf1, conf2});
    
    SenseiMultiModule senseiMultiModule = new SenseiMultiModule(confDirs);
    Injector injector = Guice.createInjector(senseiMultiModule);
    
    Provider<SenseiNode> senseiNodeProvider = injector.getProvider(senseiMultiModule.getKeys().get(0));
    SenseiNode node = senseiNodeProvider.get();
    
    Provider<SenseiNode> senseiNodeProvider2 = injector.getProvider(senseiMultiModule.getKeys().get(1));
    SenseiNode node2 = senseiNodeProvider2.get();
    
    node.start();
    node2.start();
  }

}
