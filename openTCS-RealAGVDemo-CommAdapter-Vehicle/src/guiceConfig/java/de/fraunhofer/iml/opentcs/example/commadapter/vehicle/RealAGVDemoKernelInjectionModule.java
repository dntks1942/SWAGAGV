/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import org.opentcs.customizations.kernel.KernelInjectionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealAGVDemoKernelInjectionModule
    extends KernelInjectionModule {
  
  private static final Logger LOG = LoggerFactory.getLogger(RealAGVDemoKernelInjectionModule.class);

  @Override
  protected void configure() {
    
    RealAGVDemoCommAdapterConfiguration configuration
        = getConfigBindingProvider().get(RealAGVDemoCommAdapterConfiguration.PREFIX,
                                         RealAGVDemoCommAdapterConfiguration.class);
    
    if (!configuration.enable()) {
      LOG.info("RealAGVDemo communication adapter disabled by configuration.");
      return;
    }
    
    install(new FactoryModuleBuilder().build(RealAGVDemoAdapterComponentsFactory.class));
    vehicleCommAdaptersBinder().addBinding().to(RealAGVDemoCommAdapterFactory.class);
  }
}
