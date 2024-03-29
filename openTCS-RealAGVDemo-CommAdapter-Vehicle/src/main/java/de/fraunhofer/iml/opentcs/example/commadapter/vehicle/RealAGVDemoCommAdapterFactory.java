/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.RealAGVDemoCommAdapterDescription;
import de.fraunhofer.iml.opentcs.example.common.VehicleProperties;
import static de.fraunhofer.iml.opentcs.example.common.VehicleProperties.PROPKEY_VEHICLE_HOST;
import static de.fraunhofer.iml.opentcs.example.common.VehicleProperties.PROPKEY_VEHICLE_PORT;
import static java.util.Objects.requireNonNull;
import javax.inject.Inject;
import org.opentcs.data.model.Vehicle;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;
import org.opentcs.drivers.vehicle.VehicleCommAdapterDescription;
import org.opentcs.drivers.vehicle.VehicleCommAdapterFactory;
import static org.opentcs.util.Assertions.checkInRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealAGVDemoCommAdapterFactory
    implements VehicleCommAdapterFactory {

  /**
   * This class's Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(RealAGVDemoCommAdapterFactory.class);

  /**
   * The factory to create components specific to the comm adapter.
   */
  private final RealAGVDemoAdapterComponentsFactory componentsFactory;
  /**
   * This component's initialized flag.
   */
  private boolean initialized;

  /**
   * Creates a new instance.
   *
   * @param componentsFactory The factory to create components specific to the comm adapter.
   */
  @Inject
  public RealAGVDemoCommAdapterFactory(RealAGVDemoAdapterComponentsFactory componentsFactory) {
    this.componentsFactory = requireNonNull(componentsFactory, "componentsFactory");
  }

  @Override
  public void initialize() {
    if (initialized) {
      LOG.debug("Already initialized.");
      return;
    }
    
    initialized = true;
  }

  @Override
  public boolean isInitialized() {
    return initialized;
  }

  @Override
  public void terminate() {
    if (!initialized) {
      LOG.debug("Not initialized.");
      return;
    }
    initialized = false;
  }

  @Override
  public VehicleCommAdapterDescription getDescription() {
    return new RealAGVDemoCommAdapterDescription();
  }

  @Override
  @Deprecated
  public String getAdapterDescription() {
    return getDescription().getDescription();
  }

  @Override
  public boolean providesAdapterFor(Vehicle vehicle) {
    requireNonNull(vehicle, "vehicle");

    if (vehicle.getProperty(PROPKEY_VEHICLE_HOST) == null) {
      return false;
    }

    if (vehicle.getProperty(VehicleProperties.PROPKEY_VEHICLE_PORT) == null) {
      return false;
    }
    try {
      checkInRange(Integer.parseInt(vehicle.getProperty(PROPKEY_VEHICLE_PORT)),
                   1024,
                   65535);
    }
    catch (IllegalArgumentException exc) {
      return false;
    }

    return true;
  }

  @Override
  public VehicleCommAdapter getAdapterFor(Vehicle vehicle) {
    requireNonNull(vehicle, "vehicle");
    if (!providesAdapterFor(vehicle)) {
      return null;
    }

    RealAGVDemoCommAdapter adapter = componentsFactory.createRealAGVDemoCommAdapter(vehicle);
    adapter.getProcessModel().setVehicleHost(vehicle.getProperty(PROPKEY_VEHICLE_HOST));
    adapter.getProcessModel().setVehiclePort(
        Integer.parseInt(vehicle.getProperty(PROPKEY_VEHICLE_PORT))
    );
    LOG.info("팩토리로 어댑터 생성!");
    return adapter;
  }
}
