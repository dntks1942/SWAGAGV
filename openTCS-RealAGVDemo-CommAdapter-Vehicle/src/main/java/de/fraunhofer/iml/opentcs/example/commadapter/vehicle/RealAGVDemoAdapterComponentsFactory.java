/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import de.fraunhofer.iml.opentcs.example.common.telegrams.RequestResponseMatcher;
import de.fraunhofer.iml.opentcs.example.common.telegrams.StateRequesterTask;
import de.fraunhofer.iml.opentcs.example.common.telegrams.TelegramSender;
import java.awt.event.ActionListener;
import org.opentcs.data.model.Vehicle;

/**
 * A factory for various instances specific to the comm adapter.
 *
 * @author Martin Grzenia (Fraunhofer IML)
 */
public interface RealAGVDemoAdapterComponentsFactory {

  /**
   * Creates a new RealAGVDemoCommAdapter for the given vehicle.
   *
   * @param vehicle The vehicle
   * @return A new RealAGVDemoCommAdapter for the given vehicle
   */
  RealAGVDemoCommAdapter createRealAGVDemoCommAdapter(Vehicle vehicle);

  /**
   * Creates a new {@link RequestResponseMatcher}.
   *
   * @param telegramSender Sends telegrams/requests.
   * @return The created {@link RequestResponseMatcher}.
   */
  RequestResponseMatcher createRequestResponseMatcher(TelegramSender telegramSender);

  /**
   * Creates a new {@link StateRequesterTask}.
   *
   * @param stateRequestAction The actual action to be performed to enqueue requests.
   * @return The created {@link StateRequesterTask}.
   */
  StateRequesterTask createStateRequesterTask(ActionListener stateRequestAction);
}
