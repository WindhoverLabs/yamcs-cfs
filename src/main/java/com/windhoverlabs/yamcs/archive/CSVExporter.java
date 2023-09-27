package com.windhoverlabs.yamcs.archive;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.api.HttpBody;
import org.yamcs.api.Observer;
import org.yamcs.archive.ReplayOptions;
import org.yamcs.client.Helpers;
import org.yamcs.client.YamcsClient;
import org.yamcs.client.archive.ArchiveClient;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.MediaType;
import org.yamcs.http.api.ManagementApi;
import org.yamcs.http.api.ParameterReplayListener;
import org.yamcs.http.api.ReplayFactory;
import org.yamcs.mdb.XtceDbFactory;
import org.yamcs.parameter.ParameterValueWithId;
import org.yamcs.protobuf.Archive.ExportParameterValuesRequest;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.ParameterReplayRequest;
import org.yamcs.security.ObjectPrivilegeType;
import org.yamcs.security.User;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;
import org.yamcs.yarch.FileSystemBucket;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

public class CSVExporter extends AbstractYamcsService implements Runnable {

  private static class CsvParameterStreamer extends ParameterReplayListener {

    Observer<HttpBody> observer;
    List<NamedObjectId> ids;
    boolean addRaw;
    boolean addMonitoring;
    int recordCount = 0;
    char columnDelimiter = '\t';
    Instant firstInstant;

    CsvParameterStreamer(
        Observer<HttpBody> observer,
        String filename,
        List<NamedObjectId> ids,
        boolean addRaw,
        boolean addMonitoring) {
      this.observer = observer;
      this.ids = ids;
      this.addRaw = addRaw;
      this.addMonitoring = addMonitoring;

      HttpBody metadata =
          HttpBody.newBuilder()
              .setContentType(MediaType.CSV.toString())
              .setFilename(filename)
              .build();

      observer.next(metadata);
    }

    @Override
    protected void onParameterData(List<ParameterValueWithId> params) {

      ByteString.Output data = ByteString.newOutput();
      try (Writer writer = new OutputStreamWriter(data, StandardCharsets.UTF_8);
          DeltaCountedParameterFormatter formatter =
              new DeltaCountedParameterFormatter(writer, ids, columnDelimiter)) {
        if (recordCount == 0) {
          firstInstant =
              Helpers.toInstant(params.get(0).getParameterValue().toGpb().getGenerationTime());
        }
        formatter.setWriteHeader(recordCount == 0);
        formatter.setPrintRaw(addRaw);
        formatter.setPrintMonitoring(addMonitoring);
        formatter.setFirstInstant(firstInstant);
        formatter.writeParameters(params);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      HttpBody body = HttpBody.newBuilder().setData(data.toByteString()).build();
      observer.next(body);
      recordCount++;
    }

    @Override
    public void replayFailed(Throwable t) {
      observer.completeExceptionally(t);
    }

    @Override
    public void replayFinished() {
      observer.complete();
    }
  }

  private YamcsClient yamcsClient;
  private ArchiveClient archiveClient;
  private boolean realtime;
  private Instant start;
  private Instant stop;
  private String bucketName;
  private FileSystemBucket bucket;
  private Thread thread;
  private HashMap<String, FileOutputStream> fisMap = new HashMap<String, FileOutputStream>();
  private Path bucketPath;
  private static final String CSV_NAME_POST_FIX = ".csv";
  private Map<String, List<String>> paramsMap = null;

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config)
      throws InitException {

    //    realtime =
    //        this.config.getBoolean(
    //            "realtime", false); // might be useful for "always" writing to a CSV file...
    //    start = Instant.parse("2023-09-23T23:00:00.000Z");
    start = Instant.parse(config.getString("start"));
    //    stop = Instant.parse("2023-09-24T00:10:00.000Z");
    stop = Instant.parse(config.getString("stop"));

    /* Read in our configuration parameters. */
    bucketName = config.getString("bucket");
    paramsMap = config.getMap("params");

    //    System.out.println("params:" + paramsMap);

    /* Iterate through the bucket names passed to us by the configuration file.  We're going to add the buckets
     * to our internal list so we can process them later. */
    YarchDatabaseInstance yarch = YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE);

    try {
      bucket = (FileSystemBucket) yarch.getBucket(bucketName);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  protected void doStart() {

    thread = new Thread(this);
    thread.start();
    notifyStarted();
  }

  @Override
  protected void doStop() {
    // TODO Auto-generated method stub

  }

  private void export(
      User user, ExportParameterValuesRequest request, Observer<HttpBody> observer) {
    String instance = ManagementApi.verifyInstance(request.getInstance());
    //
    ReplayOptions repl = ReplayOptions.getAfapReplay();
    //
    List<NamedObjectId> ids = new ArrayList<>();
    XtceDb mdb = XtceDbFactory.getInstance(instance);
    String namespace = null;

    if (request.hasStart()) {
      repl.setRangeStart(TimeEncoding.fromProtobufTimestamp(request.getStart()));
    }
    if (request.hasStop()) {
      repl.setRangeStop(TimeEncoding.fromProtobufTimestamp(request.getStop()));
    }
    //    TODO:Get names from conofig/api request
    for (String id : request.getParametersList()) {
      ids.add(NamedObjectId.newBuilder().setName(id).build());
    }
    if (request.hasNamespace()) {
      namespace = request.getNamespace();
    }

    if (ids.isEmpty()) {
      for (Parameter p : mdb.getParameters()) {
        if (!user.hasObjectPrivilege(ObjectPrivilegeType.ReadParameter, p.getQualifiedName())) {
          continue;
        }
        if (namespace != null) {
          String alias = p.getAlias(namespace);
          if (alias != null) {
            ids.add(NamedObjectId.newBuilder().setNamespace(namespace).setName(alias).build());
          }
        } else {
          ids.add(NamedObjectId.newBuilder().setName(p.getQualifiedName()).build());
        }
      }
    }
    repl.setParameterRequest(ParameterReplayRequest.newBuilder().addAllNameFilter(ids).build());

    String filename;
    String dateString = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
    if (ids.size() == 1) {
      NamedObjectId id = ids.get(0);
      String parameterName = id.hasNamespace() ? id.getName() : id.getName().substring(1);
      filename = parameterName.replace('/', '_') + "_export_" + dateString + ".csv";
    } else {
      filename = "parameter_export_" + dateString + ".csv";
    }

    boolean addRaw = false;
    boolean addMonitoring = false;
    for (String extra : request.getExtraList()) {
      if (extra.equals("raw")) {
        addRaw = true;
      } else if (extra.equals("monitoring")) {
        addMonitoring = true;
      } else {
        throw new BadRequestException("Unexpected option for parameter 'extra': " + extra);
      }
    }
    CsvParameterStreamer l =
        new CsvParameterStreamer(observer, filename, ids, addRaw, addMonitoring);
    if (request.hasDelimiter()) {
      switch (request.getDelimiter()) {
        case "TAB":
          l.columnDelimiter = '\t';
          break;
        case "SEMICOLON":
          l.columnDelimiter = ';';
          break;
        case "COMMA":
          l.columnDelimiter = ',';
          break;
        default:
          throw new BadRequestException("Unexpected column delimiter");
      }
    }
    //    observer.setCancelHandler(l::requestReplayAbortion);
    ReplayFactory.replay(instance, user, repl, l);
  }

  @Override
  public void run() {
    exportPV();
  }

  private void exportPV() {

    for (Entry<String, List<String>> params : paramsMap.entrySet()) {
      ArrayList<String> parameters = new ArrayList<String>();
      //      String[] nameTokens = param.split("/");

      FileOutputStream fis = openFile(getNewFilePath(params.getKey()).toAbsolutePath().toString());
      fisMap.put(getNewFilePath(params.getKey()).toAbsolutePath().toString(), fis);
      for (String param : params.getValue()) {
        //        String p = "/cfs/ppd/apps/to/TO_HK_TLM_MID.ChannelInfo[0].MessagesSent";
        parameters.add(param);
      }
      this.export(
          YamcsServer.getServer().getSecurityStore().getDirectory().getUser("admin"),
          ExportParameterValuesRequest.newBuilder()
              .addAllParameters(parameters)
              .setStart(
                  Timestamp.newBuilder()
                      .setNanos(start.getNano())
                      .setSeconds(start.getEpochSecond())
                      .build())
              .setStop(
                  Timestamp.newBuilder()
                      .setNanos(stop.getNano())
                      .setSeconds(stop.getEpochSecond())
                      .build())
              .setInstance("fsw")
              .build(),
          new Observer<HttpBody>() {

            @Override
            public void next(HttpBody message) {
              //              // TODO Auto-generated method stub
              writeToCSV(
                  message.getData().toByteArray(),
                  getNewFilePath(params.getKey()).toAbsolutePath().toString());
            }

            @Override
            public void completeExceptionally(Throwable t) {
              // TODO Auto-generated method stub

            }

            @Override
            public void complete() {
              // TODO Auto-generated method stub
              closeFile(fis);
            }
          });
    }
  }

  private void writeToCSV(byte[] data, String path) {

    writeToFile(data, fisMap.get(path));
  }

  private void writeToFile(byte[] data, FileOutputStream fis) {
    try {
      fis.write(data, 0, data.length);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void closeFile(FileOutputStream fis) {
    try {
      fis.close();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private FileOutputStream openFile(String path) {
    File inputFile = new File(path);
    FileOutputStream fis = null;
    try {
      fis = new FileOutputStream(inputFile);
    } catch (FileNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return fis;
  }

  private Path getNewFilePath(String name) {
    bucketPath = Paths.get(bucket.getBucketRoot().toString()).toAbsolutePath();
    Path filePath = bucketPath.resolve(name + "_" + CSV_NAME_POST_FIX);

    return filePath;
  }
}
