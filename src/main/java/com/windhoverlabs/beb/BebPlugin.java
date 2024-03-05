package com.windhoverlabs.beb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.yamcs.Experimental;
import org.yamcs.Plugin;
import org.yamcs.PluginException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.http.Binding;
import org.yamcs.http.HttpServer;
import org.yamcs.logging.Log;
import org.yamcs.management.ManagementListener;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.YamcsInstance.InstanceState;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

public class BebPlugin implements Plugin {

  static final String CONFIG_SECTION = "yamcs-web";
  static final String CONFIG_DISPLAY_BUCKET = "displayBucket";
  static final String CONFIG_STACK_BUCKET = "stackBucket";

  /**
   * Allows access to the Admin Area.
   *
   * <p>Remark that certain pages in the Admin Area may require further privileges.
   */
  public static final SystemPrivilege PRIV_ADMIN = new SystemPrivilege("web.AccessAdminArea");

  private Log log = new Log(getClass());

  private WebFileDeployer deployer;
  private BebHandler bebHandler;

  // We need these outside of deployer because we cannot predict the order in which
  // plugins are loaded.
  private List<Path> extraStaticRoots = new ArrayList<>();
  private Map<String, Map<String, Object>> extraConfigs = new HashMap<>();

  @Override
  public void onLoad(YConfiguration config) throws PluginException {
    YamcsServer yamcs = YamcsServer.getServer();
    yamcs.getSecurityStore().addSystemPrivilege(PRIV_ADMIN);

    HttpServer httpServer = yamcs.getGlobalService(HttpServer.class);
    String contextPath = httpServer.getContextPath();

    extraStaticRoots.add(
        Path.of(
            "/home/lgomez/projects/beb_integration/squeaky-weasel/software/airliner/build/venus_aero/sassie/sitl_commander_workspace/beb"));
    //        Don't care about buckets, for now
    //        createBuckets(config);

    try {
      deployer = new WebFileDeployer(config, contextPath, extraStaticRoots, extraConfigs);
      setupRoutes(config, deployer);
    } catch (IOException e) {
      throw new PluginException("Could not deploy website", e);
    }

    // Print these log statements via a ready listener because it is more helpful
    // if they appear at the end of the boot log.
    yamcs.addReadyListener(
        () -> {
          for (Binding binding : httpServer.getBindings()) {
            log.info("Beb deployed at {}{}", binding, contextPath);
          }
        });
  }

  @Experimental
  public void addExtension(String id, Map<String, Object> config, Path staticRoot) {
    extraConfigs.put(id, config);
    extraStaticRoots.add(staticRoot);
    if (deployer != null) { // Trigger deploy, if deployer is already available
      deployer.setExtraSources(extraStaticRoots, extraConfigs);
      bebHandler.setStaticRoots(
          Stream.concat(Stream.of(deployer.getDirectory()), deployer.getExtraStaticRoots().stream())
              .collect(Collectors.toList()));
    }
  }

  private void createBuckets(YConfiguration config) throws PluginException {
    String displayBucketName = config.getString(CONFIG_DISPLAY_BUCKET);
    createBucketIfNotExists(displayBucketName);

    String stackBucketName = config.getString(CONFIG_STACK_BUCKET);
    createBucketIfNotExists(stackBucketName);

    // Buckets can be overriden at instance level. If so, create those
    // buckets here. It is this WebPlugin that owns them.
    ManagementService.getInstance()
        .addManagementListener(
            new ManagementListener() {
              @Override
              public void instanceStateChanged(YamcsServerInstance ysi) {
                if (ysi.state() == InstanceState.STARTING) {
                  YConfiguration instanceConfig = ysi.getConfig().getConfigOrEmpty(CONFIG_SECTION);
                  if (instanceConfig.containsKey(CONFIG_DISPLAY_BUCKET)) {
                    String bucketName = instanceConfig.getString(CONFIG_DISPLAY_BUCKET);
                    try {
                      createBucketIfNotExists(bucketName);
                    } catch (PluginException e) {
                      log.error(
                          "Could not create display bucket for instance '" + ysi.getName() + "'",
                          e);
                    }
                  }
                  if (instanceConfig.containsKey(CONFIG_STACK_BUCKET)) {
                    String bucketName = instanceConfig.getString(CONFIG_STACK_BUCKET);
                    try {
                      createBucketIfNotExists(bucketName);
                    } catch (PluginException e) {
                      log.error(
                          "Could not create stack bucket for instance '" + ysi.getName() + "'", e);
                    }
                  }
                }
              }
            });
  }

  private Bucket createBucketIfNotExists(String bucketName) throws PluginException {
    YarchDatabaseInstance yarch = YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE);
    try {
      Bucket bucket = yarch.getBucket(bucketName);
      if (bucket == null) {
        bucket = yarch.createBucket(bucketName);
      }
      return bucket;
    } catch (IOException e) {
      throw new PluginException("Could not create '" + bucketName + "' bucket", e);
    }
  }

  /** Add routes used by Web UI. */
  private void setupRoutes(YConfiguration config, WebFileDeployer deployer) throws PluginException {
    HttpServer httpServer = YamcsServer.getServer().getGlobalService(HttpServer.class);

    bebHandler =
        new BebHandler(config, httpServer, deployer.getDirectory(), deployer.getExtraStaticRoots());
    httpServer.addRoute("beb", () -> bebHandler);

    //        Not needed by beb, for now
    // Additional API Routes
    //        try (InputStream in = getClass().getResourceAsStream("/yamcs-web.protobin")) {
    //            httpServer.getProtobufRegistry().importDefinitions(in);
    //        } catch (IOException e) {
    //            throw new PluginException(e);
    //        }
    //        httpServer.addApi(new BebApi());
  }
}
