/**
 * Copyright (c) Fraunhofer IML
 */
package de.fraunhofer.iml.opentcs.example.commadapter.vehicle;

import com.google.common.primitives.Ints;
import com.google.inject.assistedinject.Assisted;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.comm.VehicleTelegramDecoder;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.comm.VehicleTelegramEncoder;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.exchange.RealAGVDemoProcessModelTO;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.telegrams.OrderRequest;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.telegrams.OrderResponse;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.telegrams.StateRequest;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.telegrams.StateResponse;
import de.fraunhofer.iml.opentcs.example.commadapter.vehicle.telegrams.StateResponse.LoadState;
import de.fraunhofer.iml.opentcs.example.common.dispatching.LoadAction;
import de.fraunhofer.iml.opentcs.example.common.telegrams.BoundedCounter;
import static de.fraunhofer.iml.opentcs.example.common.telegrams.BoundedCounter.UINT16_MAX_VALUE;
import de.fraunhofer.iml.opentcs.example.common.telegrams.Request;
import de.fraunhofer.iml.opentcs.example.common.telegrams.RequestResponseMatcher;
import de.fraunhofer.iml.opentcs.example.common.telegrams.Response;
import de.fraunhofer.iml.opentcs.example.common.telegrams.StateRequesterTask;
import de.fraunhofer.iml.opentcs.example.common.telegrams.Telegram;
import de.fraunhofer.iml.opentcs.example.common.telegrams.TelegramSender;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static java.util.Objects.requireNonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import javax.inject.Inject;
import org.opentcs.contrib.tcp.netty.ConnectionEventListener;
import org.opentcs.contrib.tcp.netty.TcpClientChannelManager;
import org.opentcs.customizations.kernel.KernelExecutor;
import org.opentcs.data.model.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.drivers.vehicle.BasicVehicleCommAdapter;
import org.opentcs.drivers.vehicle.MovementCommand;
import org.opentcs.drivers.vehicle.VehicleProcessModel;
import org.opentcs.drivers.vehicle.management.VehicleProcessModelTO;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//직접 추가한 import
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.LocalTime;

import org.opentcs.data.model.Point;
import org.opentcs.common.LoopbackAdapterConstants;
import org.opentcs.data.ObjectPropConstants;
import org.opentcs.util.CyclicTask;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.concurrent.TimeUnit;
import org.opentcs.data.order.Route.Step;

/**
 * An example implementation for a communication adapter.
 *
 * @author Mats Wilhelm (Fraunhofer IML)
 */
public class RealAGVDemoCommAdapter
    extends BasicVehicleCommAdapter
    implements ConnectionEventListener<Response>,
               TelegramSender {

  /**
   * This class's logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(RealAGVDemoCommAdapter.class);
  /**
   * Maps movement commands from openTCS to the telegrams sent to the attached vehicle.
   */
  private final OrderMapper orderMapper;
  /**
   * The components factory.
   */
  private final RealAGVDemoAdapterComponentsFactory componentsFactory;
  /**
   * The kernel's executor service.
   */
  private final ExecutorService kernelExecutor;
  /**
   * Manages counting the ids for all {@link Request} telegrams.
   */
  private final BoundedCounter globalRequestCounter = new BoundedCounter(0, UINT16_MAX_VALUE);
  /**
   * Maps commands to order IDs so we know which command to report as finished.
   */
  private final Map<MovementCommand, Integer> orderIds = new ConcurrentHashMap<>();
  /**
   * Manages the channel to the vehicle.
   */
  private TcpClientChannelManager<Request, Response> vehicleChannelManager;
  /**
   * Matches requests to responses and holds a queue for pending requests.
   */
  private RequestResponseMatcher requestResponseMatcher;
  /**
   * A task for enqueuing state requests periodically.
   */
  private StateRequesterTask stateRequesterTask;
  
  //직접 추가한 변수
  private boolean initialized;
  private final Vehicle vehicle;
  
  // localhost
  static String ip = "127.0.0.1";
  // 1번째 agv 주소
  static String agvip = "192.168.0.20";
  // 각각의 agv 주소를 주기위해서 (ex> 192.168.0.10 --> 192.168.0.11 -> .....)
  static char totalsuffix = '0' - 1;
  // 각각의 agv에 정보가 localhost가 보낸 port로 온다. port를 구분하기 위해서
  static int totalportorder = 0;
  
  private char suffix;
  private int portorder;
  boolean ipcheck;
  String recv = "test";
  private static final int ADVANCE_TIME = 100;
  private CyclicTask demoTask;
  boolean canChange = true;
  boolean isMeidansha = true;
  int currentLocation = 0;

  /**
   * Creates a new instance.
   *
   * @param vehicle The attached vehicle.
   * @param orderMapper The order mapper for movement commands.
   * @param componentsFactory The components factory.
   * @param kernelExecutor The kernel's executor service.
   */
  // constructor
  @Inject
  public RealAGVDemoCommAdapter(@Assisted Vehicle vehicle,
                            OrderMapper orderMapper,
                            RealAGVDemoAdapterComponentsFactory componentsFactory,
                            @KernelExecutor ExecutorService kernelExecutor) {
    super(new RealAGVDemoProcessModel(vehicle), 3, 2, LoadAction.CHARGE);
    this.orderMapper = requireNonNull(orderMapper, "orderMapper");
    this.componentsFactory = requireNonNull(componentsFactory, "componentsFactory");
    this.kernelExecutor = requireNonNull(kernelExecutor, "kernelExecutor");
    this.vehicle = requireNonNull(vehicle, "vehicle");
    totalsuffix++; // total suffix증가 시켜서 각 adapter마다 다른 주소값 가지도록
    totalportorder++; // port number을 증가 시켜서 각 adapter마다 다른 포트번호를 가지도록
    suffix = totalsuffix; // totalsuffix는 static이기에 suffix에 증가시킨 값 저장
    portorder = totalportorder; // 위와 동일
    
    LOG.info(agvip + suffix);
    LOG.info("port: " + portorder);
  }
  

  @Override
  public void initialize() {
    super.initialize();
    this.requestResponseMatcher = componentsFactory.createRequestResponseMatcher(this);
    this.stateRequesterTask = componentsFactory.createStateRequesterTask(e -> {
      requestResponseMatcher.enqueueRequest(new StateRequest(Telegram.ID_DEFAULT));
    });
    String initialPos
        = vehicle.getProperties().get(LoopbackAdapterConstants.PROPKEY_INITIAL_POSITION);
    if (initialPos == null) {
      @SuppressWarnings("deprecation")
      String deprecatedInitialPos
          = vehicle.getProperties().get(ObjectPropConstants.VEHICLE_INITIAL_POSITION);
      initialPos = deprecatedInitialPos;
    }
    initialized = true;
  }

  @Override
  public void terminate() {
    stateRequesterTask.disable();
    super.terminate();
  }

  public boolean ipIdentify(String address){
      if(address.equals(agvip+(suffix))) {
          return true;
      }
      return false;
  }
  
  @Override
public synchronized void enable() {
    if (isEnabled()) {
      return;
    }
    LOG.info("차량의 접속을 기다립니다....");
    ipcheck = ipIdentify(ip);
    
    // AGV와의 연결을 확인하고 Drive On 메시지를 보냄.
    MessageThread msgThread = new MessageThread();
    Thread testThread = new Thread(msgThread,"MsgThread");
    testThread.start();
    int cnt =0;
    
    //masteron, setposition, idle까지 체크함
    PrepareThread prepareThread = new PrepareThread();
    prepareThread.start();

    
    super.enable();
    demoTask = new DemoTask();
    Thread demoThread = new Thread(demoTask,"demoTask");
    demoThread.start();
    
  }

  @Override
  public synchronized void disable() {
    if (!isEnabled()) {
      return;
    }

    super.disable();
    vehicleChannelManager.terminate();
    vehicleChannelManager = null;
  }

  @Override
  public synchronized void clearCommandQueue() {
    super.clearCommandQueue();
    orderIds.clear();
  }

  @Override
  protected synchronized void connectVehicle() {
    if (vehicleChannelManager == null) {
      //LOG.warn("{}: VehicleChannelManager not present.", getName());
      return;
    }

    vehicleChannelManager.connect(getProcessModel().getVehicleHost(),
                                  getProcessModel().getVehiclePort());
  }

  @Override
  protected synchronized void disconnectVehicle() {
    if (vehicleChannelManager == null) {
      LOG.warn("{}: VehicleChannelManager not present.", getName());
      return;
    }

    vehicleChannelManager.disconnect();
  }

  @Override
  protected synchronized boolean isVehicleConnected() {
    return vehicleChannelManager != null && vehicleChannelManager.isConnected();
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    super.propertyChange(evt);
    if (!(evt.getSource() instanceof RealAGVDemoProcessModel)) {
      return;
    }

    // Handling of events from the vehicle gui panels start here
    if (Objects.equals(evt.getPropertyName(),
                       VehicleProcessModel.Attribute.COMM_ADAPTER_CONNECTED.name())) {
      if (getProcessModel().isCommAdapterConnected()) {
        // Once the connection is established, ensure that logging is enabled/disabled for it as
        // configured by the user.
        vehicleChannelManager.setLoggingEnabled(getProcessModel().isLoggingEnabled());
      }
    }
    if (Objects.equals(evt.getPropertyName(),
                       VehicleProcessModel.Attribute.COMM_ADAPTER_CONNECTED.name())
        || Objects.equals(evt.getPropertyName(),
                          RealAGVDemoProcessModel.Attribute.PERIODIC_STATE_REQUESTS_ENABLED.name())) {
      if (getProcessModel().isCommAdapterConnected()
          && getProcessModel().isPeriodicStateRequestEnabled()) {
        stateRequesterTask.enable();
      }
      else {
        stateRequesterTask.disable();
      }
    }
    if (Objects.equals(evt.getPropertyName(),
                       RealAGVDemoProcessModel.Attribute.PERIOD_STATE_REQUESTS_INTERVAL.name())) {
      stateRequesterTask.setRequestInterval(getProcessModel().getStateRequestInterval());
    }
  }

  @Override
  @Deprecated
  protected List<org.opentcs.drivers.vehicle.VehicleCommAdapterPanel> createAdapterPanels() {
    return new ArrayList<>();
  }

  @Override
  public final RealAGVDemoProcessModel getProcessModel() {
    return (RealAGVDemoProcessModel) super.getProcessModel();
  }

  @Override
  protected VehicleProcessModelTO createCustomTransferableProcessModel() {
    //Add extra information of the vehicle when sending to other software like control center or 
    //plant overview
    return new RealAGVDemoProcessModelTO()
        .setVehicleRef(getProcessModel().getVehicleReference())
        .setCurrentState(getProcessModel().getCurrentState())
        .setPreviousState(getProcessModel().getPreviousState())
        .setLastOrderSent(getProcessModel().getLastOrderSent())
        .setDisconnectingOnVehicleIdle(getProcessModel().isDisconnectingOnVehicleIdle())
        .setLoggingEnabled(getProcessModel().isLoggingEnabled())
        .setReconnectDelay(getProcessModel().getReconnectDelay())
        .setReconnectingOnConnectionLoss(getProcessModel().isReconnectingOnConnectionLoss())
        .setVehicleHost(getProcessModel().getVehicleHost())
        .setVehicleIdle(getProcessModel().isVehicleIdle())
        .setVehicleIdleTimeout(getProcessModel().getVehicleIdleTimeout())
        .setVehiclePort(getProcessModel().getVehiclePort())
        .setPeriodicStateRequestEnabled(getProcessModel().isPeriodicStateRequestEnabled())
        .setStateRequestInterval(getProcessModel().getStateRequestInterval());
  }

  @Override
  public synchronized void sendCommand(MovementCommand cmd)
      throws IllegalArgumentException {
    requireNonNull(cmd, "cmd");

    try {
      OrderRequest telegram = orderMapper.mapToOrder(cmd);
      orderIds.put(cmd, telegram.getOrderId());
      LOG.debug("{}: Enqueuing order request with: order id={}, dest. id={}, dest. action={}",
                getName(),
                telegram.getOrderId(),
                telegram.getDestinationId(),
                telegram.getDestinationAction());

      // Add the telegram to the queue. Telegram will be send later when its the first telegram in 
      // the queue. This ensures that we always wait for a response until we send a new request.
      requestResponseMatcher.enqueueRequest(telegram);
    }
    catch (IllegalArgumentException exc) {
      //LOG.error("{}: Failed to enqueue command {}", getName(), cmd, exc);
    }
  }

  @Override
  public synchronized ExplainedBoolean canProcess(List<String> operations) {
    requireNonNull(operations, "operations");
    boolean canProcess = true;
    String reason = "";
    if (!isEnabled()) {
      canProcess = false;
      reason = "Adapter not enabled";
    }
    if (canProcess && !isVehicleConnected()) {
      canProcess = false;
      reason = "Vehicle does not seem to be connected";
    }
    if (canProcess
        && getProcessModel().getCurrentState().getLoadState() == LoadState.UNKNOWN) {
      canProcess = false;
      reason = "Vehicle's load state is undefined";
    }
    boolean loaded = getProcessModel().getCurrentState().getLoadState() == LoadState.FULL;
    final Iterator<String> opIter = operations.iterator();
    while (canProcess && opIter.hasNext()) {
      final String nextOp = opIter.next();
      // If we're loaded, we cannot load another piece, but could unload.
      if (loaded) {
        if (nextOp.startsWith(LoadAction.LOAD)) {
          canProcess = false;
          reason = "Cannot load when already loaded";
        }
        else if (nextOp.startsWith(LoadAction.UNLOAD)) {
          loaded = false;
        }
        else if (nextOp.startsWith(DriveOrder.Destination.OP_PARK)) {
          canProcess = false;
          reason = "Vehicle shouldn't park while in a loaded state.";
        }
        else if (nextOp.startsWith(LoadAction.CHARGE)) {
          canProcess = false;
          reason = "Vehicle shouldn't charge while in a loaded state.";
        }
      }
      // If we're not loaded, we could load, but not unload.
      else if (nextOp.startsWith(LoadAction.LOAD)) {
        loaded = true;
      }
      else if (nextOp.startsWith(LoadAction.UNLOAD)) {
        canProcess = false;
        reason = "Cannot unload when not loaded";
      }
    }
    canProcess = true;
    return new ExplainedBoolean(canProcess, reason);
  }

  @Override
  public void processMessage(Object message) {
    //Process messages sent from the kernel or a kernel extension
  }

  @Override
  public void onConnect() {
    if (!isEnabled()) {
      return;
    }
    LOG.debug("{}: connected", getName());
    getProcessModel().setCommAdapterConnected(true);
    // Request the vehicle's current state (preparation for the state requester task)
    requestResponseMatcher.enqueueRequest(new StateRequest(Telegram.ID_DEFAULT));
    // Check for resending last request
    requestResponseMatcher.checkForSendingNextRequest();
  }

  @Override
  public void onFailedConnectionAttempt() {
    if (!isEnabled()) {
      return;
    }
    getProcessModel().setCommAdapterConnected(false);
    if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
      vehicleChannelManager.scheduleConnect(getProcessModel().getVehicleHost(),
                                            getProcessModel().getVehiclePort(),
                                            getProcessModel().getReconnectDelay());
    }
  }

  @Override
  public void onDisconnect() {
    LOG.debug("{}: disconnected", getName());
    getProcessModel().setCommAdapterConnected(false);
    getProcessModel().setVehicleIdle(true);
    getProcessModel().setVehicleState(Vehicle.State.UNKNOWN);
    if (isEnabled() && getProcessModel().isReconnectingOnConnectionLoss()) {
      vehicleChannelManager.scheduleConnect(getProcessModel().getVehicleHost(),
                                            getProcessModel().getVehiclePort(),
                                            getProcessModel().getReconnectDelay());
    }
  }

  @Override
  public void onIdle() {
    LOG.debug("{}: idle", getName());
    getProcessModel().setVehicleIdle(true);
    // If we are supposed to reconnect automatically, do so.
    if (isEnabled() && getProcessModel().isDisconnectingOnVehicleIdle()) {
      LOG.debug("{}: Disconnecting on idle timeout...", getName());
      disconnectVehicle();
    }
  }

  @Override
  public synchronized void onIncomingTelegram(Response response) {
    requireNonNull(response, "response");

    // Remember that we have received a sign of life from the vehicle
    getProcessModel().setVehicleIdle(false);

    //Check if the response matches the current request
    if (!requestResponseMatcher.tryMatchWithCurrentRequest(response)) {
      // XXX Either ignore the message or close the connection
      return;
    }

    if (response instanceof StateResponse) {
      onStateResponse((StateResponse) response);
    }
    else {
      LOG.debug("{}: Receiving response: {}", getName(), response);
    }

    //Send the next telegram if one is waiting
    requestResponseMatcher.checkForSendingNextRequest();
  }

  @Override
  public synchronized void sendTelegram(Request telegram) {
    requireNonNull(telegram, "telegram");
    if (!isVehicleConnected()) {
      LOG.debug("{}: Not connected - not sending request '{}'",
                getName(),
                telegram);
      return;
    }

    // Update the request's id
    telegram.updateRequestContent(globalRequestCounter.getAndIncrement());

    vehicleChannelManager.send(telegram);

    // If the telegram is an order, remember it.
    if (telegram instanceof OrderRequest) {
      getProcessModel().setLastOrderSent((OrderRequest) telegram);
    }

    // If we just sent a state request, restart the state requester task to schedule the next
    // state request
    if (telegram instanceof StateRequest
        && getProcessModel().isPeriodicStateRequestEnabled()) {
      stateRequesterTask.restart();
    }
  }

  public RequestResponseMatcher getRequestResponseMatcher() {
    return requestResponseMatcher;
  }

  private void onStateResponse(StateResponse stateResponse) {
    requireNonNull(stateResponse, "stateResponse");

    final StateResponse previousState = getProcessModel().getCurrentState();
    final StateResponse currentState = stateResponse;

    kernelExecutor.submit(() -> {
      // Update the vehicle's current state and remember the old one.
      getProcessModel().setPreviousState(previousState);
      getProcessModel().setCurrentState(currentState);

      checkForVehiclePositionUpdate(previousState, currentState);
      checkForVehicleStateUpdate(previousState, currentState);
      checkOrderFinished(previousState, currentState);

      // XXX Process further state updates extracted from the telegram here.
    });
  }

  private void checkForVehiclePositionUpdate(StateResponse previousState,
                                             StateResponse currentState) {
    if (previousState.getPositionId() == currentState.getPositionId()) {
      return;
    }
    // Map the reported position ID to a point name.
    String currentPosition = String.valueOf(currentState.getPositionId());
    LOG.debug("{}: Vehicle is now at point {}", getName(), currentPosition);
    // Update the position with the rest of the system, but only if it's not zero (unknown).
    if (currentState.getPositionId() != 0) {
      getProcessModel().setVehiclePosition(currentPosition);
    }
  }

  private void checkForVehicleStateUpdate(StateResponse previousState,
                                          StateResponse currentState) {
    if (previousState.getOperationState() == currentState.getOperationState()) {
      return;
    }
    getProcessModel().setVehicleState(translateVehicleState(currentState.getOperationState()));
  }

  private void checkOrderFinished(StateResponse previousState, StateResponse currentState) {
    if (currentState.getLastFinishedOrderId() == 0) {
      return;
    }
    // If the last finished order ID hasn't changed, don't bother.
    if (previousState.getLastFinishedOrderId() == currentState.getLastFinishedOrderId()) {
      return;
    }
    // Check if the new finished order ID is in the queue of sent orders.
    // If yes, report all orders up to that one as finished.
    if (!orderIds.containsValue(currentState.getLastFinishedOrderId())) {
      LOG.debug("{}: Ignored finished order ID {} (reported by vehicle, not found in sent queue).",
                getName(),
                currentState.getLastFinishedOrderId());
      return;
    }

    Iterator<MovementCommand> cmdIter = getSentQueue().iterator();
    boolean finishedAll = false;
    while (!finishedAll && cmdIter.hasNext()) {
      MovementCommand cmd = cmdIter.next();
      cmdIter.remove();
      int orderId = orderIds.remove(cmd);
      if (orderId == currentState.getLastFinishedOrderId()) {
        finishedAll = true;
      }

      LOG.debug("{}: Reporting command with order ID {} as executed: {}", getName(), orderId, cmd);
      getProcessModel().commandExecuted(cmd);
    }
  }

  /**
   * Map the vehicle's operation states to the kernel's vehicle states.
   *
   * @param operationState The vehicle's current operation state.
   */
  private Vehicle.State translateVehicleState(StateResponse.OperationState operationState) {
    switch (operationState) {
      case IDLE:
        return Vehicle.State.IDLE;
      case MOVING:
      case ACTING:
        return Vehicle.State.EXECUTING;
      case CHARGING:
        return Vehicle.State.CHARGING;
      case ERROR:
        return Vehicle.State.ERROR;
      default:
        return Vehicle.State.UNKNOWN;
    }
  }

  /**
   * Returns the channel handlers responsible for writing and reading from the byte stream.
   *
   * @return The channel handlers responsible for writing and reading from the byte stream
   */
  private List<ChannelHandler> getChannelHandlers() {
    return Arrays.asList(new LengthFieldBasedFrameDecoder(getMaxTelegramLength(), 1, 1, 2, 0),
                         new VehicleTelegramDecoder(this),
                         new VehicleTelegramEncoder());
  }

  private int getMaxTelegramLength() {
    return Ints.max(OrderResponse.TELEGRAM_LENGTH,
                    StateResponse.TELEGRAM_LENGTH);
  }
  
  // 초기 설정을 위한 값 전송 (acs -> agv)
  public class PrepareThread extends Thread {
      public void run() {
          int cnt=0;
          while(true){
                    if(cnt>5){
                        try{
                            InetAddress ia = InetAddress.getByName(agvip + (suffix));
                            DatagramSocket ds = new DatagramSocket();
                            byte[] masterbyte = new java.math.BigInteger("0E00000008000100D20100000000",16).toByteArray();
                            DatagramPacket dp = new DatagramPacket(masterbyte,masterbyte.length,ia,3000);
                            ds.send(dp);
                            LOG.info("master on 메세지를 보냅니다.");
                            cnt++;
                        }
                        catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                    else {
                        LOG.info("agv를 확인할 수 없어 master 메세지를 보낼 수 없습니다.");
                        cnt++;
                    }
                    try{
                        Thread.sleep(2000);
                    }
                    catch(InterruptedException e) {
                        e.printStackTrace();
                    }       
          }
          //setposThread(Position)
          while(true){
              ReadMessage rdmsg =new ReadMessage(recv);
              //LOG.info("AGV에서 온 메시지 : " + recv);

              if(rdmsg.getStateMasterOn() && rdmsg.getStateTapeOn()) {
                  getProcessModel().setVehiclePosition(("Point-000" + portorder));
                  
                  try{
                      InetAddress ia = InetAddress.getByName(agvip + (suffix));
                      DatagramSocket ds = new DatagramSocket();
                      byte[] stepStrobeOnByte = new java.math.BigInteger("0E000000E8000000DC0000000000",16).toByteArray();
                      byte[] stepChangeByte = new java.math.BigInteger("0E000000E8000000DC0000000000",16).toByteArray();
                      byte[] stepStrobeOffByte = new java.math.BigInteger("0E00000008000000DC0000000000",16).toByteArray();
                      DatagramPacket dp = new DatagramPacket(stepStrobeOnByte,stepStrobeOnByte.length,ia,3000);
                      DatagramPacket dp2 = new DatagramPacket(stepChangeByte,stepChangeByte.length,ia,3000);
                      DatagramPacket dp3 = new DatagramPacket(stepStrobeOffByte,stepStrobeOffByte.length,ia,3000);
                      ds.send(dp);
                      try {
                          Thread.sleep(100);
                      }
                      catch (InterruptedException e) {
                          // TODO Auto-generated catch block
                          e.printStackTrace();
                      }
                      ds.send(dp2);
                      try {
                          Thread.sleep(100);
                      }
                      catch (InterruptedException e) {
                          // TODO Auto-generated catch block
                          e.printStackTrace();
                      }
                      ds.send(dp3);
                      LOG.info("step을 0으로 초기화 합니다.");
                  }
                  catch (IOException e) {
                      e.printStackTrace();
                  }   
                  break;
              }
              else{
                  LOG.info("초기 위치를 정할 수 없습니다.");
              }
              try {
                  Thread.sleep(2000);
              }
              catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
              }
          }
          
          //idleThread
          while(true){
              ReadMessage rdmsg =new ReadMessage(recv);
              //LOG.info("AGV에서 온 메시지 : " + recv);
              if(rdmsg.getStateDriveOn()){ //DongWook
                  LOG.info("Drive On");
                  getProcessModel().setVehicleState(Vehicle.State.IDLE);
                  break;
              }
              else {
                  LOG.info("Drive Off");
              }
              try {
                  Thread.sleep(2000);
              }
              catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
              }
          }
      }
  }
  
  // agv에서 온 message를 받는 thread
  public class MessageThread extends Thread {
      public void run() {
              try {
                  // localhost가 data를 보내는 port로 socket연결
                  DatagramSocket ds = new DatagramSocket(4000 + portorder);
                  while(true){
                       byte[] data = new byte[14];
                       //byte[] data2 = new byte[14];
                       DatagramPacket dp = new DatagramPacket(data,data.length);
                       //DatagramPacket dp2 = new DatagramPacket(data2,data2.length);
                       LOG.info("agv 주소: " + agvip + suffix);
                       LOG.info("수신할 포트 : " + (4000 + portorder));
                       ds.receive(dp); // 14byte짜리 data를 지정된 port로 부터 receive
                       //ds.receive(dp2);
                       ip = dp.getAddress().getHostAddress(); // ip는 localhost일껄??
                       String temp = byteArrayToHexaString(dp.getData()); // dp로 받은 datafmf hexa 형태로 저장
                       if(temp != null) {
                           recv = temp;
                       }
                       ReadMessage rdmsg = new ReadMessage(recv);
                       currentLocation = rdmsg.getStepNum();
                       
                       ipcheck = ipIdentify(ip); // ipcheck false일텐디?
                       LOG.info("AGV에서 온 메시지 : " + recv);
                  }    
              }
              catch(IOException e) {
                  e.printStackTrace();
              }  
      }
      // 
      public String byteArrayToHexaString(byte[] bytes) {
          StringBuilder builder = new StringBuilder();
          for(byte data : bytes) {
              builder.append(String.format("%02X ", data));
          }
          if(builder.charAt(0)  == 'D') { // 1101xxxxxx...이면 null? 왜???
              return null;
          }
          return builder.toString();
      }
  }
  
  // agv에서 읽은 data 해석
public class ReadMessage{
    private String[] msg;
    private ToBinary data1L1;
    private ToBinary data1L2;
    private ToBinary data1H1;
    private ToBinary data1H2;
    private ToBinary data2H1;
    private ToBinary data2H2;
    private ToBinary data4L1;
    private ToBinary data4L2;

    public ReadMessage(String recv) {
//    msg[i] 0,1 datasizeL    3,4 datasizeH    6,7, 9,10 dataExtends
//       12,13 data1L        15,16 data1H    18,19 data2L    21,22 data2H    24,25 data3L    27,28 data3H
//       30,31 data4L        33,34 data4H    36,37 data5L    39,40 data5H
        msg = recv.split("");

        data1L1 = new ToBinary(msg[12]);
        data1L2 = new ToBinary(msg[13]);
        data1H1 = new ToBinary(msg[15]);
        data1H2 = new ToBinary(msg[16]);
        data2H1 = new ToBinary(msg[21]);
        data2H2 = new ToBinary(msg[22]);
        data4L1 = new ToBinary(msg[30]);
        data4L2 = new ToBinary(msg[31]);
    }
    
    public int getBattery() {
        String data = data4L1.hexTobin()+data4L2.hexTobin();
        int bat = Integer.parseInt(data, 2);
        int battery;
        if(bat >= 250) {
            battery = 100;
        }
        else {
            battery = (int)bat* 100/250;
        }
        return battery;
    }
    
    public boolean getStateMasterOn() {
        String data = data1H1.hexTobin()+data1H2.hexTobin();
        if(isMeidansha) {
            return true;
        }
        return data.charAt(7) == '1';
    }
    
    public boolean getStateTapeOn() {
        if(isMeidansha) {
            return true;
        }
        String data = data1H1.hexTobin()+data1H2.hexTobin();
        return data.charAt(6) == '1';
    }
    
    public boolean getStateDriveOn() {
        if(isMeidansha) {
            return true;
        }
        String data = data1H1.hexTobin()+data1H2.hexTobin();
        return data.charAt(5) == '1';
    }
    
    public int getStepNum() {
        String data = data2H1.hexTobin()+data2H2.hexTobin();
        int stepNum = Integer.parseInt(data, 2);
        return stepNum;
    }
    
    public boolean getStateStart() {
        String data = data1L1.hexTobin() + data1L2.hexTobin();
        if(isMeidansha) {
            data = data1H1.hexTobin() + data1H2.hexTobin();
            return data.charAt(7) == '1';
        }
        return data.charAt(6) == '1';
    }
}

// hexa형태의 string을 binary로 string으로 변형시키는 class
public class ToBinary {
    public ToBinary(String s) {
        hex = s;
    }

    private String hex;
    private String bin; //hex num
    private String[] hex2bin = {"0000","0001","0010","0011","0100","0101","0110","0111","1000","1001","1010","1011","1100",
            "1101","1110","1111",}; //binary num
    
    public String hexTobin() {
        //  System.out.println(hex + " hex");
        for(int i=0;i<hex.length();i++) {
            switch(hex.charAt(i)) {
         case '0': bin = hex2bin[0]; break;
         case '1': bin = hex2bin[1]; break;
         case '2': bin = hex2bin[2]; break;
         case '3': bin = hex2bin[3]; break;
         case '4': bin = hex2bin[4]; break;
         case '5': bin = hex2bin[5]; break;
         case '6': bin = hex2bin[6]; break;
         case '7': bin = hex2bin[7]; break;
         case '8': bin = hex2bin[8]; break;
         case '9': bin = hex2bin[9]; break;
         case 'A': bin = hex2bin[10]; break;
         case 'B': bin = hex2bin[11]; break;
         case 'C': bin = hex2bin[12]; break;
         case 'D': bin = hex2bin[13]; break;
         case 'E': bin = hex2bin[14]; break;
         case 'F': bin = hex2bin[15]; break;
         default: bin = " "; break;
         }
        } return bin;
    }
}

 // demotask 실제 작업을 실행(acs에서 agv로 message 전달)
private class DemoTask extends CyclicTask {
      //private int simAdvanceTime;   
      private Point nextPoint;
      private Point currentPoint;
      //private Point previousPoint;
      //private double curAngle;
      //private double nextAngle;
      //private String finalDest = "1";
      private int battery;
      private int beforeStepNum = 0; // 이전 step을 저장
      
      
      private DemoTask() {
          super(600); // cyclictask(600) --> 0.6초 있다가 반복하도록 설정
          //curAngle = 90;
      }
      
@Override
 protected void runActualTask() {
    final MovementCommand curCommand;
    synchronized(RealAGVDemoCommAdapter.this) {
        curCommand = getSentQueue().peek(); // sentqueue의 front를 가져온다
        if(curCommand == null) {
            Uninterruptibles.sleepUninterruptibly(ADVANCE_TIME, TimeUnit.MILLISECONDS);
        } // 커맨드가 null일 때.
        else { // 커맨드가 있을 때.
            LOG.info("current Location : {} ",curCommand.getFinalDestinationLocation().getName());
            if(canChange){ // load를 할 수 있는 상태이면
                canChange = false; // unload없는 추가적인 load를 방지하기 위해서
                if("Location-0001".equals(curCommand.getFinalDestinationLocation().getName())){
                    goLocat1(); // location 1으로 가면 goLocat1()
                }
                else if("Location-0002".equals(curCommand.getFinalDestinationLocation().getName())){
                    goLocat2(); // location 2으로 가면 goLocat2()
                }
                else if("Location-0003".equals(curCommand.getFinalDestinationLocation().getName())) {
                    goLocat3(); // location 3으로 가면 goLocat3()
                }
                else if("Location-0004".equals(curCommand.getFinalDestinationLocation().getName())) {
                    goLocat4(); // location 4으로 가면 goLocat4()
                }
            }
            
            final Step curStep = curCommand.getStep();
            currentPoint = curStep.getSourcePoint();
            nextPoint = curStep.getDestinationPoint();
            /*  picar code
            int msg;
            // LRF 계산부분
            
            long curPx = currentPoint.getPosition().getX();
            long curPy= currentPoint.getPosition().getY();
            long nxtPx= nextPoint.getPosition().getX();
            long nxtPy= nextPoint.getPosition().getY();
            long curXd = nxtPx-curPx;
            long curYd = nxtPy-curPy;
            nextAngle = Math.atan2(curYd, curXd);
            nextAngle = Math.toDegrees(nextAngle);
            double angleDiff = nextAngle-curAngle; //각도 구하기 
            
            if((angleDiff>80 && angleDiff<100) || (angleDiff>-280 && angleDiff<-260)) {
                msg = 2; //left
            }
            else if((angleDiff<-80 && angleDiff>-100) || (angleDiff>260 && angleDiff<280)) {
                msg = 1; //right
            }
            else {
                msg = 0; //forward
            }
            
            //원래 이 다음에 메시지 전송 부분이었음
            if(msg == 2){
                LOG.info("차량 {} 에 좌회전 명령을 보냅니다.",getProcessModel().getName());
            }
            else if(msg ==1){
                LOG.info("차량 {} 에 우회전 명령을 보냅니다.",getProcessModel().getName());
            }
            else {
                LOG.info("차량 {} 에 직진 명령을 보냅니다.",getProcessModel().getName());
            }
            //그 다음에 메시지 수신 부분이었음
            */
            
            // 지속적으로 recv를 가져와서 내용을 읽어서 agv의 상태를 acs에 전달
            int beforeLocation = currentLocation;
            while(true) {
                ReadMessage rdmsg = new ReadMessage(recv);
                battery = rdmsg.getBattery();
                
                if(isMeidansha) {
                    if(beforeLocation != currentLocation) {
                        LOG.info("차량 {} 이 {} 에 도착하였습니다.",getProcessModel().getName(),curStep.getDestinationPoint().getName());
                        beforeLocation = currentLocation;
                        break;
                    }
                }
                else {
                    int snum = rdmsg.getStepNum();
                    if(snum == beforeStepNum+1) {
                        beforeStepNum++;
                        LOG.info("차량 {} 이 {} 에 도착하였습니다.",getProcessModel().getName(),curStep.getDestinationPoint().getName());
                        break;
                        // 지금은 맵이 단순해서 직선같은것도 프로그램을 바꾸면서 가기때문에 스텝이 0과 1밖에 없어서
                        // 굳이 beforeStepNum을 증가시키지 않아도 됨. 나중에 맵이 바뀌게 되면 증가시키시면서 업데이트 해야함.
                    }
                }
                // 대기하는 지점을 설정해주었다. (모든 point에서 자동으로 대기 지점을 찾을 수 있도록 변경 필요)
                if("Point-0004".equals(curStep.getSourcePoint().getName()) || "Point-0005".equals(curStep.getSourcePoint().getName())) {
                    goCommand();
                }
            }
         // movement 업데이트 부분
            if(curStep.getPath() == null) {
                LOG.info("path가 존재하지 않습니다.");
                return;
            }
            String destPoint = curStep.getDestinationPoint().getName();
            getProcessModel().setVehicleState(Vehicle.State.EXECUTING);
            getProcessModel().setVehiclePosition(destPoint);
            getProcessModel().setVehicleEnergyLevel(battery);
            
         // operation 체크 부분
            if(!curCommand.isWithoutOperation()) {
                if(curCommand.getOperation().equals("Load cargo")){
                     LOG.info("차량 {} 이 짐을 싣는 중입니다....",getProcessModel().getName());
                     //setInit();
                     //start 명령을 받을 때 까지 대기
                     while(true){
                         ReadMessage rdmsg = new ReadMessage(recv);
                         if(rdmsg.getStateStart()){
                             break;
                         }
                     }
                }
                else if(curCommand.getOperation().equals("Unload cargo")) {
                    LOG.info("차량 {} 이 짐을 내리는 중입니다...",getProcessModel().getName());
                    try {
                        TimeUnit.SECONDS.sleep(3);
                        beforeStepNum = 0;
                        //setInit();
                        //load를 할 수 있다.
                        canChange =true;
                    }
                    catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }

         // command  업데이트 부분
            if(!isTerminated()) { 
                if(getSentQueue().size()<=1 && getCommandQueue().isEmpty()) { //여기는 전체명령이 아니라 Location까지 명령이 다끝난것을 의미함.
                    getProcessModel().setVehicleState(Vehicle.State.IDLE);
                }
                synchronized (RealAGVDemoCommAdapter.this) {
                    MovementCommand sentCmd = getSentQueue().poll();
                    
                    if(sentCmd !=null && sentCmd.equals(curCommand)) {
                        getProcessModel().commandExecuted(curCommand);
                        RealAGVDemoCommAdapter.this.notify();
                    }
                }
            }
        }
      }
    } // runActualTask
    
    // 다음노드를 확인하고 해당 노드가 비어있으면 출발시키는 명령어, testmap에서만 가능한 제한적 명령어 --> 수정필요!!
    // nextpoint를 어떻게 가져올 것인지 찾아야함... 
    public void goCommand() {
        while(nextPoint.getOccupyingVehicle() != null) { // next point가 free가 아니면 계속 기다림
            LOG.info("--------------------------{} occupying point {}----------------------",nextPoint.getOccupyingVehicle().getName(), nextPoint.getName());
        }
        try{  //free가 되면
            LOG.info("---------Next Point is {} , Current point is {}---------",nextPoint.getName(), currentPoint.getName());
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            // 출발 명령어
            if(isMeidansha) {
                byte[] startOperation = new java.math.BigInteger("0E00000002000000000000000000",16).toByteArray();
                byte[] initialOperation = new java.math.BigInteger("0E00000000000000000000000000",16).toByteArray();
                DatagramPacket dp = new DatagramPacket(startOperation,startOperation.length,ia,3000);
                DatagramPacket dp2 = new DatagramPacket(initialOperation,initialOperation.length,ia,3000);
                ds.send(dp);
                Thread.sleep(100);
                ds.send(dp2);
            }
            else {
                byte[] goCommand = new java.math.BigInteger("0E0000000A000100D20000000000",16).toByteArray(); // AGV 기동 명령
                DatagramPacket dp = new DatagramPacket(goCommand,goCommand.length,ia,3000);
                ds.send(dp);
            }
            Thread.sleep(100);
        }
        catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void goLocat1() {
        try{
            LocalTime now = LocalTime.now();
            LOG.info("goLocat1: {}",now);
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            if(isMeidansha) {
                byte[] startOperation = new java.math.BigInteger("0E00000002000000000000000000",16).toByteArray();
                byte[] initialOperation = new java.math.BigInteger("0E00000000000000000000000000",16).toByteArray();
                DatagramPacket dp = new DatagramPacket(startOperation,startOperation.length,ia,3000);
                DatagramPacket dp2 = new DatagramPacket(initialOperation,initialOperation.length,ia,3000);
                ds.send(dp);
                Thread.sleep(100);
                ds.send(dp2);
            }
            else {
                byte[] programStepStrobeOnByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
                byte[] programStepChangeByte = new java.math.BigInteger("0E00000068000100D20000000000",16).toByteArray();
                byte[] programStepStrobeOffByte = new java.math.BigInteger("0E00000008000100D20000000000",16).toByteArray();
                DatagramPacket dp = new DatagramPacket(programStepStrobeOnByte,programStepStrobeOnByte.length,ia,3000);
                DatagramPacket dp2 = new DatagramPacket(programStepChangeByte,programStepChangeByte.length,ia,3000);
                DatagramPacket dp3 = new DatagramPacket(programStepStrobeOffByte,programStepStrobeOffByte.length,ia,3000);
                try {
                    ds.send(dp);
                    Thread.sleep(100);
                    ds.send(dp2);
                    Thread.sleep(100);
                    ds.send(dp3);
                } 
                catch (IOException e) {
                     e.printStackTrace();
                }
            }
        }
        catch (InterruptedException e) {

           e.printStackTrace();
       }
       catch(IOException e) {
           e.printStackTrace();
       }
    }
    public void goLocat2() {
        try{
            LocalTime now = LocalTime.now();
            LOG.info("goLocat2: {}",now);
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            byte[] programStepStrobeOnByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
            byte[] programSTepChangeByte = new java.math.BigInteger("0E00000068000100D20000000000",16).toByteArray();
            byte[] programStepStrobeOffByte = new java.math.BigInteger("0E00000008000100D20000000000",16).toByteArray();
            DatagramPacket dp = new DatagramPacket(programStepStrobeOnByte,programStepStrobeOnByte.length,ia,3000);
            DatagramPacket dp2 = new DatagramPacket(programSTepChangeByte,programSTepChangeByte.length,ia,3000);
            DatagramPacket dp3 = new DatagramPacket(programStepStrobeOffByte,programStepStrobeOffByte.length,ia,3000);
            ds.send(dp);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp2);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp3);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void goLocat3() {
        try{
            LocalTime now = LocalTime.now();
            LOG.info("goLocat3: {}",now);
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            if(isMeidansha) {
                byte[] startOperation = new java.math.BigInteger("0E00000002000000000000000000",16).toByteArray();
                byte[] initialOperation = new java.math.BigInteger("0E00000000000000000000000000",16).toByteArray();
                DatagramPacket dp = new DatagramPacket(startOperation,startOperation.length,ia,3000);
                DatagramPacket dp2 = new DatagramPacket(initialOperation,initialOperation.length,ia,3000);
                ds.send(dp);
                Thread.sleep(1000);
                ds.send(dp2);
            }
            else {
                byte[] programStepStrobeOnByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
                byte[] programSTepChangeByte = new java.math.BigInteger("0E00000068000200D20000000000",16).toByteArray();
                byte[] programStepStrobeOffByte = new java.math.BigInteger("0E00000008000200D20000000000",16).toByteArray();
                DatagramPacket dp = new DatagramPacket(programStepStrobeOnByte,programStepStrobeOnByte.length,ia,3000);
                DatagramPacket dp2 = new DatagramPacket(programSTepChangeByte,programSTepChangeByte.length,ia,3000);
                DatagramPacket dp3 = new DatagramPacket(programStepStrobeOffByte,programStepStrobeOffByte.length,ia,3000);
                ds.send(dp);
                Thread.sleep(100);
                ds.send(dp2);
                Thread.sleep(100);
                ds.send(dp3);
            }
        }
        catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void goLocat4() {  // location4로 가는 함수 -> agv에 location4로 가는 명령어를 보낸다.
        try{
            LocalTime now = LocalTime.now();
            LOG.info("goLocat4: {}",now);
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            byte[] programStepStrobeOnByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
            byte[] programSTepChangeByte = new java.math.BigInteger("0E00000068000200D20000000000",16).toByteArray();
            byte[] programStepStrobeOffByte = new java.math.BigInteger("0E00000008000200D20000000000",16).toByteArray();
            DatagramPacket dp = new DatagramPacket(programStepStrobeOnByte,programStepStrobeOnByte.length,ia,3000);
            DatagramPacket dp2 = new DatagramPacket(programSTepChangeByte,programSTepChangeByte.length,ia,3000);
            DatagramPacket dp3 = new DatagramPacket(programStepStrobeOffByte,programStepStrobeOffByte.length,ia,3000);
            ds.send(dp);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp2);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp3);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void setInit() {  
        try{
            InetAddress ia = InetAddress.getByName(agvip + (suffix));
            DatagramSocket ds = new DatagramSocket();
            byte[] programStepStrobeOnByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
            byte[] programSTepChangeByte = new java.math.BigInteger("0E00000068000000D20000000000",16).toByteArray();
            byte[] programStepStrobeOffByte = new java.math.BigInteger("0E00000008000000D20000000000",16).toByteArray();
            DatagramPacket dp = new DatagramPacket(programStepStrobeOnByte,programStepStrobeOnByte.length,ia,3000);
            DatagramPacket dp2 = new DatagramPacket(programSTepChangeByte,programSTepChangeByte.length,ia,3000);
            DatagramPacket dp3 = new DatagramPacket(programStepStrobeOffByte,programStepStrobeOffByte.length,ia,3000);
            ds.send(dp);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp2);
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            ds.send(dp3);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
  }//CyclicTask
}//Class
