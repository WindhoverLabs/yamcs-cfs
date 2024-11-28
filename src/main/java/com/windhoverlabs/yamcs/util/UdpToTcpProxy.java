package com.windhoverlabs.yamcs.util;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.logging.Log;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.AbstractThreadedTcDataLink;
import org.yamcs.tctm.Link.Status;
import org.yamcs.xtce.Parameter;
import org.yamcs.yarch.TupleDefinition;

// public class UdpToTcpProxy extends AbstractThreadedTcDataLink implements SystemParametersProducer
// {
public class UdpToTcpProxy extends AbstractThreadedTcDataLink {

  private Log log;
  protected YConfiguration config;
  protected String linkName;
  protected AtomicBoolean disabled = new AtomicBoolean(false);

  private int udpPort;
  private int tcpPort;
  private Parameter udpPortParam;
  private Parameter tcpPortParam;

  private DatagramSocket udpSocket;
  private ServerSocket tcpServerSocket;

  private Thread udpListenerThread;
  private Thread tcpServerThread;
  private ThreadPoolExecutor clientHandlerExecutor;

  private static TupleDefinition gftdef = StandardTupleDefinitions.PARAMETER.copy();

  private Set<Socket> tcpClients = Collections.synchronizedSet(new HashSet<>());

  private CommandHistoryPublisher commandHistoryPublisher;

  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);

    this.log = new Log(getClass(), instance);
    this.config = config;

    /* Validate the configuration that the user passed us. */
    try {
      config = getSpec().validate(config);
    } catch (ValidationException e) {
      log.error("Failed configuration validation.", e);
      notifyFailed(e);
    }

    this.linkName = name;

    // Read configuration
    this.udpPort = config.getInt("udpPort");
    this.tcpPort = config.getInt("tcpPort");
  }

  @Override
  protected void doStart() {
    log.info("Starting UdpToTcpProxy: " + getName());

    try {
      // Initialize UDP socket
      udpSocket = new DatagramSocket(udpPort);
      udpListenerThread = new Thread(this::udpListener);
      udpListenerThread.setName("UdpListenerThread");
      udpListenerThread.start();
      log.info("UDP listener started on port " + udpPort);

      // Initialize TCP server
      tcpServerSocket = new ServerSocket();
      tcpServerSocket.bind(new InetSocketAddress(tcpPort));
      tcpServerThread = new Thread(this::tcpServer);
      tcpServerThread.setName("TcpServerThread");
      tcpServerThread.start();
      log.info("TCP server started on port " + tcpPort);

      // Executor for handling client connections
      clientHandlerExecutor =
          (ThreadPoolExecutor)
              Executors.newCachedThreadPool(
                  new ThreadFactory() {
                    private int count = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                      return new Thread(r, "TcpClientHandler-" + count++);
                    }
                  });

      super.doStart();
    } catch (IOException e) {
      log.error("Failed to start UdpToTcpProxy: " + e.getMessage());
      notifyFailed(e);
    }
  }

  @Override
  protected void doStop() {
    log.info("Stopping UdpToTcpProxy: " + getName());

    disabled.set(true);

    // Close UDP socket
    if (udpSocket != null && !udpSocket.isClosed()) {
      udpSocket.close();
    }

    // Close TCP server socket
    try {
      if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
        tcpServerSocket.close();
      }
    } catch (IOException e) {
      log.warn("Error closing TCP server socket", e);
    }

    // Close all client sockets
    synchronized (tcpClients) {
      for (Socket client : tcpClients) {
        try {
          client.close();
        } catch (IOException e) {
          log.warn("Error closing client socket", e);
        }
      }
      tcpClients.clear();
    }

    // Shutdown executor
    if (clientHandlerExecutor != null && !clientHandlerExecutor.isShutdown()) {
      clientHandlerExecutor.shutdownNow();
    }

    super.doStop();
  }

  private void udpListener() {
    byte[] buffer = new byte[65535];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

    while (!disabled.get() && !udpSocket.isClosed()) {
      try {
        udpSocket.receive(packet);
        int length = packet.getLength();
        byte[] data = new byte[length];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, length);

        /* Update PV telemetry */
        TupleDefinition tdef = gftdef.copy();
        // pushTuple(tdef, cols);

        // Forward data to all connected TCP clients
        forwardToTcpClients(data);
      } catch (SocketException e) {
        if (!disabled.get()) {
          log.error("UDP socket error", e);
        }
        break;
      } catch (IOException e) {
        log.error("Error receiving UDP packet", e);
      }
    }
  }

  private void tcpServer() {
    while (!disabled.get() && !tcpServerSocket.isClosed()) {
      try {
        Socket clientSocket = tcpServerSocket.accept();
        tcpClients.add(clientSocket);
        log.info("New TCP client connected: " + clientSocket.getRemoteSocketAddress());

        // Handle client disconnection asynchronously
        clientHandlerExecutor.submit(() -> handleClient(clientSocket));
      } catch (SocketException e) {
        if (!disabled.get()) {
          log.error("TCP server socket error", e);
        }
        break;
      } catch (IOException e) {
        log.error("Error accepting TCP client connection", e);
      }
    }
  }

  private void handleClient(Socket clientSocket) {
    try {
      while (!clientSocket.isClosed()) {
        if (clientSocket.getInputStream().read() == -1) {
          break;
        }
      }
    } catch (IOException e) {
      log.warn("Client connection error", e);
    } finally {
      try {
        clientSocket.close();
      } catch (IOException e) {
        log.warn("Error closing client socket", e);
      }
      tcpClients.remove(clientSocket);
      log.info("TCP client disconnected: " + clientSocket.getRemoteSocketAddress());
    }
  }

  private void forwardToTcpClients(byte[] data) {
    synchronized (tcpClients) {
      for (Socket client : tcpClients) {
        try {
          client.getOutputStream().write(data);
          client.getOutputStream().flush();
        } catch (IOException e) {
          log.warn("Error sending data to client: " + client.getRemoteSocketAddress(), e);
          try {
            client.close();
          } catch (IOException ex) {
            log.warn("Error closing client socket", ex);
          }
          tcpClients.remove(client);
        }
      }
    }
  }

  //  @Override
  //  public YConfiguration getConfig() {
  //    return config;
  //  }
  //
  //  @Override
  //  public String getName() {
  //    return linkName;
  //  }
  //
  //  @Override
  //  public void resetCounters() {
  //    // TODO
  //  }
  //
  //  @Override
  //  public long getDataInCount() {
  //    // TODO
  //    return 0;
  //  }
  //
  //  @Override
  //  public long getDataOutCount() {
  //    // TODO
  //    return 0;
  //  }

  //  @Override
  //  public boolean isDisabled() {
  //    return disabled.get();
  //  }

  //  @Override
  //  public void disable() {
  //    boolean b = disabled.getAndSet(true);
  //    if (!b) {
  //      try {
  //        /* TODO */
  //        // doDisable();
  //      } catch (Exception e) {
  //        disabled.set(false);
  //        log.warn("Failed to disable link", e);
  //      }
  //    }
  //  }
  //
  //  @Override
  //  public void enable() {
  //    boolean b = disabled.getAndSet(false);
  //    if (b) {
  //      try {
  //        /* TODO */
  //        // doEnable();
  //      } catch (Exception e) {
  //        disabled.set(true);
  //        log.warn("Failed to enable link", e);
  //      }
  //    }
  //  }

  @Override
  public Status getLinkStatus() {
    if (isDisabled()) {
      return Status.DISABLED;
    }
    if (state() == State.FAILED) {
      return Status.FAILED;
    }

    return Status.OK;
  }

  @Override
  public Spec getSpec() {
    Spec spec = getDefaultSpec();
    spec.addOption("udpPort", Spec.OptionType.INTEGER).withRequired(true);
    spec.addOption("tcpPort", Spec.OptionType.INTEGER).withRequired(true);
    return spec;
  }

  @Override
  public void setupSystemParameters(SystemParametersService sysParamService) {
    super.setupSystemParameters(sysParamService);

    udpPortParam =
        sysParamService.createSystemParameter(
            linkName + "/udpPort", Type.UINT64, "The current UDP port the plugin is listening to.");

    tcpPortParam =
        sysParamService.createSystemParameter(
            linkName + "/tcpPort", Type.UINT64, "The current TCP port the plugin is listening to.");
  }

  @Override
  public List<ParameterValue> getSystemParameters(long gentime) {
    ArrayList<ParameterValue> list = new ArrayList<>();

    list.add(org.yamcs.parameter.SystemParametersService.getPV(udpPortParam, gentime, udpPort));

    list.add(org.yamcs.parameter.SystemParametersService.getPV(tcpPortParam, gentime, tcpPort));

    try {
      super.collectSystemParameters(gentime, list);
    } catch (Exception e) {
      log.error("Exception caught when collecting link system parameters", e);
    }

    return list;
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  public void uplinkCommand(PreparedCommand pc) throws IOException {
    log.info("Received command.");
    dataOut(1, pc.getBinary().length);
    ackCommand(pc.getCommandId());
  }

  @Override
  protected void startUp() throws Exception {
    // TODO Auto-generated method stub
  }

  @Override
  protected void shutDown() throws Exception {
    // TODO Auto-generated method stub
  }

  @Override
  public String getDetailedStatus() {
    return String.format("OK");
  }
}
