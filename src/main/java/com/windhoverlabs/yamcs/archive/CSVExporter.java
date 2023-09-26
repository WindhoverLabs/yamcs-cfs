package com.windhoverlabs.yamcs.archive;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.api.HttpBody;
import org.yamcs.api.Observer;
import org.yamcs.archive.ReplayOptions;
import org.yamcs.client.YamcsClient;
import org.yamcs.client.archive.ArchiveClient;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.MediaType;
import org.yamcs.http.api.ManagementApi;
import org.yamcs.http.api.ParameterReplayListener;
import org.yamcs.http.api.ReplayFactory;
// import org.yamcs.http.api.StreamArchiveApi.CsvParameterStreamer;
import org.yamcs.mdb.XtceDbFactory;
import org.yamcs.parameter.ParameterValueWithId;
import org.yamcs.protobuf.Archive.ExportParameterValuesRequest;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.ParameterReplayRequest;
import org.yamcs.security.ObjectPrivilegeType;
import org.yamcs.security.User;
import org.yamcs.utils.ParameterFormatter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

public class CSVExporter extends AbstractYamcsService {

  private static class CsvParameterStreamer extends ParameterReplayListener {

    Observer<HttpBody> observer;
    List<NamedObjectId> ids;
    boolean addRaw;
    boolean addMonitoring;
    int recordCount = 0;
    char columnDelimiter = '\t';

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
          ParameterFormatter formatter = new ParameterFormatter(writer, ids, columnDelimiter)) {
        formatter.setWriteHeader(recordCount == 0);
        formatter.setPrintRaw(addRaw);
        formatter.setPrintMonitoring(addMonitoring);
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
  private String start;
  private String stop;

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config)
      throws InitException {
    yamcsClient = YamcsClient.newBuilder("http://localhost", 8090).build();
    archiveClient = yamcsClient.createArchiveClient(yamcsInstance);

    realtime =
        this.config.getBoolean(
            "realtime", false); // might be useful for "always" writing to a CSV file...
    start = this.config.getString("start");
    stop = this.config.getString("stop");
  }

  @Override
  protected void doStart() {
    //		archiveClient
    // TODO Auto-generated method stub
    this.export(
        YamcsServer.getServer().getSecurityStore().getDirectory().getUser("admin"),
        ExportParameterValuesRequest.newBuilder().build(),
        null);
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
      //        ParameterWithId paramWithId = MdbApi.verifyParameterWithId(ctx, mdb, id);
      NamedObjectId.newBuilder().build();
      ids.add(NamedObjectId.newBuilder().build());
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
    observer.setCancelHandler(l::requestReplayAbortion);
    ReplayFactory.replay(instance, user, repl, l);
  }
}
