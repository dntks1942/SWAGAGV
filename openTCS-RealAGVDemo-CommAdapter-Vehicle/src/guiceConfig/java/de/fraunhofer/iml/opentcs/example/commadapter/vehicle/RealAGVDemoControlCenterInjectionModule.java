/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.AdapterPanelComponentsFactory;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.RealAGVDemoCommAdapterPanelFactory;
import org.opentcs.customizations.controlcenter.ControlCenterInjectionModule;

/**
 * A custom Guice module for project-specific configuration.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class RealAGVDemoControlCenterInjectionModule
    extends ControlCenterInjectionModule {

  @Override
  protected void configure() {
    install(new FactoryModuleBuilder().build(AdapterPanelComponentsFactory.class));

    commAdapterPanelFactoryBinder().addBinding().to(RealAGVDemoCommAdapterPanelFactory.class);
  }
}
