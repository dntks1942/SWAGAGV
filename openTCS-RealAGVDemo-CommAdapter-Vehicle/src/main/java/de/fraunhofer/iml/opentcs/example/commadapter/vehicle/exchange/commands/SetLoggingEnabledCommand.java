/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.commands;

import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.RealAGVDemoCommAdapter;
import org.opentcs.drivers.vehicle.AdapterCommand;
import org.opentcs.drivers.vehicle.VehicleCommAdapter;

/**
 * A command to enable/disable logging.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public class SetLoggingEnabledCommand
    implements AdapterCommand {

  /**
   * The new logging state.
   */
  private final boolean enabled;

  /**
   * Creates a new instance.
   *
   * @param enabled The new logging state.
   */
  public SetLoggingEnabledCommand(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void execute(VehicleCommAdapter adapter) {
    if (!(adapter instanceof RealAGVDemoCommAdapter)) {
      return;
    }

    RealAGVDemoCommAdapter exampleAdapter = (RealAGVDemoCommAdapter) adapter;
    exampleAdapter.getProcessModel().setLoggingEnabled(enabled);
  }
}
