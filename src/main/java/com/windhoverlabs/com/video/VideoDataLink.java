package com.windhoverlabs.com.video;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import java.io.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.javacpp.*;
import org.yamcs.ConfigurationException;
import org.yamcs.StandardTupleDefinitions;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.mdb.Mdb;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.protobuf.Event.EventSeverity;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.BooleanParameterType;
import org.yamcs.xtce.FloatParameterType;
import org.yamcs.xtce.IntegerParameterType;
import org.yamcs.xtce.NameDescription;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.ParameterType;
import org.yamcs.xtce.StringParameterType;
import org.yamcs.xtce.XtceDb;
import org.yamcs.yarch.DataType;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.TupleDefinition;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.protobuf.Db.Event;

/**
 * Receives telemetry packets via UDP. One UDP datagram = one TM packet.
 *
 * <p>Options:
 *
 * <ul>
 *   <li>{@code port} - the UDP port to listen to
 *   <li>{@code maxLength} - the maximum length of the datagram (and thus the TM packet length +
 *       initialBytesToStrip). If a datagram longer than this size will be received, it will be
 *       truncated. Default: 1500 (bytes)
 *   <li>{@code initialBytesToStrip} - if configured, skip that number of bytes from the beginning
 *       of the datagram. Default: 0
 * </ul>
 */
public class VideoDataLink extends AbstractTmDataLink implements Runnable {
  private volatile int invalidDatagramCount = 0;

  private DatagramSocket tmSocket;
  private int port;

  static final int MAX_LENGTH = 1500;
  DatagramPacket datagram;
  int maxLength;
  int initialBytesToStrip;
  int rcvBufferSize;

  Mdb mdb;

  private VariableParam frameParam;

  private Stream videoStream;

  private static TupleDefinition gftdef = StandardTupleDefinitions.PARAMETER.copy();

  ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  /**
   * Creates a new UDP TM Data Link
   *
   * @throws ConfigurationException if port is not defined in the configuration
   */
  @Override
  public void init(String instance, String name, YConfiguration config)
      throws ConfigurationException {
    super.init(instance, name, config);
    port = config.getInt("port");
    maxLength = config.getInt("maxLength", MAX_LENGTH);
    initialBytesToStrip = config.getInt("initialBytesToStrip", 0);
    rcvBufferSize = config.getInt("rcvBufferSize", 0);
    datagram = new DatagramPacket(new byte[maxLength], maxLength);

    this.mdb = YamcsServer.getServer().getInstance(yamcsInstance).getMdb();

    frameParam = VariableParam.getForFullyQualifiedName("/yamcs/pop-os/links/Video/frameData");

    ParameterType ptype = getBasicType(mdb, Type.BINARY);

    //    ParameterType ptype = getBasicType(mdb, Type.FLOAT);

    frameParam.setParameterType(ptype);

    if (mdb.getParameter(frameParam.getQualifiedName()) == null) {
      log.debug("Adding OPCUA object as parameter to mdb:{}", frameParam.getQualifiedName());
      try {
        mdb.addParameter(frameParam, true, true);
      } catch (Exception e) {
        // TODO Auto-generated catch block
        //        internalLogger.info(e.toString());
        //        internalLogger.info("Failed to add PV:" + p.getQualifiedName());
        org.yamcs.yarch.protobuf.Db.Event ev =
            Event.newBuilder()
                .setGenerationTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime())
                .setGenerationTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime())
                .setSource(this.linkName)
                .setType(this.linkName)
                .setMessage("Failed to add PV:" + frameParam.getQualifiedName())
                .setSeverity(EventSeverity.ERROR)
                .build();
        eventProducer.sendEvent(ev);
      }
    } else {
      frameParam = (VariableParam) mdb.getParameter(frameParam.getQualifiedName());
    }

    YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);

    //    this.opcuaStreamName = config.getString("opcuaStream");
    this.videoStream = getStream(ydb, "video_frames_stream");
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      //      try {
      //        //        readVideo();
      //        streamVideoOverRTP();
      //      } catch (IOException e) {
      //        // TODO Auto-generated catch block
      //        e.printStackTrace();
      //      }
      try {
        tmSocket = new DatagramSocket(port);
        if (rcvBufferSize > 0) {
          tmSocket.setReceiveBufferSize(rcvBufferSize);
        }
        Thread thread = new Thread(this);
        thread.setName("UdpTmDataLink-" + linkName);
        thread.start();
      } catch (SocketException e) {
        notifyFailed(e);
      }
    }
    notifyStarted();
  }

  @Override
  public void doStop() {
    if (tmSocket != null) {
      tmSocket.close();
    }
    notifyStopped();
  }

  public void delayByPts(long pts, AVRational timeBase, int startTime) {
    // Convert PTS to microseconds

    AVRational r = new AVRational();
    r.num(1);
    r.den(avutil.AV_TIME_BASE);
    int playbackTime = (int) av_rescale_q(pts, timeBase, r);
    int currentTime = (int) (av_gettime_relative() - startTime);

    if (playbackTime > currentTime) {
      // Sleep until it's time to display/send this frame
      int sleepTime = playbackTime - currentTime;
      System.out.println("Sleeping for " + sleepTime + " us");
      //			Thread.sleep(sleepTime);
      av_usleep(sleepTime);
    }
  }

  private void streamVideoOverRTP() throws IOException {
    System.out.println("Starting video streaming over RTP...");

    av_log_set_level(AV_LOG_DEBUG);

    //    av_log_set_callback();

    avformat_network_init();
    //	avdevice_register_all();

    int ret, v_stream_idx = -1;
    String inputFile = "/home/lgomez/Downloads/217115_small.mp4";
    inputFile =
        "/home/lgomez/Downloads/vecteezy_vancouver-canada-september-16-2023-flight-by-fpv-drone_37202565.mp4";
    String outputURL = "rtp://127.0.0.1:5005";

    //    outputURL =
    //
    // "/home/lgomez/projects/viper_sitl/squeaky-weasel/software/airliner/build/venus_aero/sassie/sitl_commander_workspace/new_video.mp4";

    AVFormatContext inputCtx = avformat_alloc_context();

    AVFormatContext outputCtx = avformat_alloc_context();

    // Open input video file
    System.out.println("Opening input file: " + inputFile);
    if (avformat_open_input(inputCtx, inputFile, null, null) < 0) {
      throw new IOException("Failed to open input file");
    }

    System.out.println("Finding stream info...");
    if (avformat_find_stream_info(inputCtx, (PointerPointer) null) < 0) {
      throw new IOException("Failed to retrieve stream info");
    }

    sws_freeContext();

    // Find video stream
    System.out.println("Searching for video stream...");
    for (int i = 0; i < inputCtx.nb_streams(); i++) {
      if (inputCtx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
        v_stream_idx = i;
        System.out.println("Video stream found at index: " + v_stream_idx);
        break;
      }
    }
    if (v_stream_idx == -1) {
      throw new IOException("Video stream not found");
    }

    AVStream inputStream = inputCtx.streams(v_stream_idx);
    AVCodec codec = avcodec_find_decoder(inputStream.codecpar().codec_id());
    System.out.println("av_codec_is_decoder-->" + av_codec_is_decoder(codec));

    System.out.println("av_codec_is_encoder-->" + av_codec_is_encoder(codec));
    if (codec == null) {
      throw new IOException("Unsupported codec");
    }

    System.out.println("Allocating codec context...");
    AVCodecContext decoderCtx = avcodec_alloc_context3(codec);

    avcodec_parameters_to_context(decoderCtx, inputStream.codecpar());
    avcodec_open2(decoderCtx, codec, (PointerPointer) null);

    // Set up RTP output
    System.out.println("Setting up RTP output: " + outputURL);

    //    if (avformat_alloc_output_context2(outputCtx, null, "rtp_mpegts", outputURL) < 0) {
    //      throw new IOException("Failed to create RTP output context");
    //    }

    //    if (avformat_alloc_output_context2(outputCtx, null, null, outputURL) < 0) {
    //      throw new IOException("Failed to create RTP output context");
    //    }

    if (avformat_alloc_output_context2(outputCtx, null, "rtp_mpegts", outputURL) < 0) {
      throw new IOException("Failed to create RTP output context");
    }

    AVCodec encoderCodec = avcodec_find_encoder_by_name("libx264");

    //  AVCodec encoderCodec = avcodec_find_encoder_by_name("h264");

    if (encoderCodec == null) {
      throw new IOException("h264 codec not found");
    }

    AVStream outputStream = avformat_new_stream(outputCtx, encoderCodec);
    if (outputStream == null) {
      throw new IOException("Failed to create output stream");
    }

    AVRational q = new AVRational();

    q.num(1);
    q.den(30);

    System.out.println("Configuring encoder...");
    AVCodecContext encoderCtx = avcodec_alloc_context3(encoderCodec);
    System.out.println("Configuring encoder2...");
    System.out.println("encoderCodec.id()-->" + encoderCodec.id());
    System.out.println("codec.id()-->" + decoderCtx.width());
    System.out.println("codec.id()-->" + decoderCtx.height());
    System.out.println("inputStream.time_num()-->" + inputStream.time_base().num());
    System.out.println("inputStream.time_den()-->" + inputStream.time_base().den());
    System.out.println("av_inv_q(inputStream.time_base())-->" + av_inv_q(inputStream.time_base()));

    //	Context->width = Config->Width;
    //	Context->height = Config->Height;
    //	Context->time_base = (AVRational){1, (int)Config->FramesPerSecond};
    //	Context->pix_fmt = Config->PixelFormat;
    //	Context->framerate = (AVRational){(int)Config->FramesPerSecond, 1};
    //	Context->bit_rate = Config->BitRate;
    //	Context->gop_size = Config->GopSize;
    //	Context->max_b_frames = Config->MaxBFrames;
    //	Context->gop_size = Config->GopSize;
    //	Context->flags |= Config->Flags;

    encoderCtx.codec_id(encoderCodec.id());
    System.out.println("Configuring encoder3...");
    encoderCtx.codec_type(AVMEDIA_TYPE_VIDEO);
    encoderCtx.pix_fmt(AV_PIX_FMT_YUV420P);
    System.out.println("Configuring encoder4...");
    //    encoderCtx.width(decoderCtx.width());
    //    encoderCtx.height(decoderCtx.height());
    //
    //    encoderCtx.width(640);
    //    encoderCtx.height(480);
    //

    encoderCtx.width(decoderCtx.width());
    encoderCtx.height(decoderCtx.height());

    //    av_opt_set()
    //
    //    av_opt_find()
    //
    //    avcodec.av_packet_ref()
    //
    //    av_frame_ref();
    //
    //    av_packet_alloc()

    System.out.println("Configuring encoder5...");
    encoderCtx.time_base(av_inv_q(inputStream.time_base()));
    //    encoderCtx.time_base(q);
    AVRational fr = new AVRational();

    fr.den(1);
    fr.num(30);
    encoderCtx.framerate(fr);
    encoderCtx.framerate(decoderCtx.framerate());
    System.out.println("Configuring encoder6...");
    encoderCtx.bit_rate(400000);
    encoderCtx.gop_size(30);
    encoderCtx.max_b_frames(0);

    encoderCtx.hw_device_ctx(null);

    AVBufferRef HWAccelDeviceContext = new AVBufferRef(null);
    //    int avRC =
    //        av_hwdevice_ctx_create(
    //            HWAccelDeviceContext, AV_HWDEVICE_TYPE_CUDA, "0", new AVDictionary(null), 0);

    //    int avRC =
    //        av_hwdevice_ctx_create(
    //            HWAccelDeviceContext,
    //            AV_HWDEVICE_TYPE_VAAPI,
    //            new BytePointer(),
    //            new AVDictionary(null),
    //            0);
    //    if (avRC < 0) {
    //      throw new IOException("Failed to create hw device. Error code:" + avRC);
    //    }

    avcodec_parameters_from_context(outputStream.codecpar(), encoderCtx);
    if (avcodec_open2(encoderCtx, encoderCodec, new PointerPointer()) < 0) {
      throw new IOException("Failed to open encoder");
    }

    System.out.println("Configuring encoder7...");

    System.out.println("Configuring encoder8...");

    //    if (avio_open2(outputCtx.pb(), outputURL, AVIO_FLAG_WRITE, null, null) < 0) {
    //      throw new IOException("Failed to open RTP output");
    //    }

    // NOTE:This pattern seems to fix it as per
    // https://github.com/bytedeco/javacpp-presets/issues/408#issuecomment-291711924

    AVIOContext pb = new AVIOContext(null);

    //    if (avio_open(pb, outputURL, AVIO_FLAG_WRITE) < 0) {
    //      throw new IOException("Failed to open RTP output");
    //    }

    avcodec_parameters_copy(outputStream.codecpar(), inputStream.codecpar());

    AVDictionary options = new AVDictionary(null);
    if (avio_open2(pb, outputURL, AVIO_FLAG_WRITE, null, options) < 0) {
      throw new IOException("Failed to open RTP output");
    }
    outputCtx.pb(pb);

    System.out.println("Configuring encoder9...");

    avformat_write_header(outputCtx, new AVDictionary(null));

    System.out.println("RTP streaming setup complete, starting frame processing...");

    AVFrame frame = av_frame_alloc();
    AVPacket packet = new AVPacket();

    //    TODO:Move StartTime assignment to an "Init" method

    long StartTime = av_gettime_relative();

    while (av_read_frame(inputCtx, packet) >= 0) {
      if (packet.stream_index() == v_stream_idx) {
        System.out.println("Decoding frame...");

        // TODO:The commented section will execute when demux mode is on. This needs to be made
        // configurable.
        writeFrame(outputCtx, decoderCtx, outputStream, encoderCtx, frame, packet);
        delayByPts(packet.pts(), inputStream.time_base(), ((int) StartTime));

        av_packet_unref(packet);
      }
      av_packet_unref(packet);
    }

    System.out.println("Finalizing RTP stream...");
    av_write_trailer(outputCtx);

    avcodec_close(decoderCtx);
    avcodec_close(encoderCtx);
    avformat_close_input(inputCtx);
    avio_closep(outputCtx.pb());
    avformat_free_context(outputCtx);

    System.out.println("Streaming finished successfully");
  }

  private EReturnCode RestartInputSource(AVFormatContext Context) {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    //    if (!(Context.flags() & AVFMTCTX_UNSEEKABLE)) {
    //      /* Seek back to the beginning of the file */
    //      avRC = av_seek_frame(Context, -1, 0, AVSEEK_FLAG_BACKWARD);
    //      if (avRC < 0) {
    //        ReportAVError("CInputFormat::GetPacket", "av_seek_frame", avRC, __LINE__);
    //        rc = EReturnCode.FAILED_EXECUTE;
    //        return rc;
    //      }
    //    }

    return rc;
  }

  //  private void CInputFormat::Restart()
  private void Restart(AVFormatContext Context) {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    //    rc = RestartInputSource(Context);
    //
    //    /* Reset demuxer state */
    //    avRC = avformat_flush(Context);
    //    if (avRC < 0) {
    //      ReportAVError("CInputFormat::Restart", "avformat_flush", avRC, __LINE__);
    //      rc = EReturnCode.FAILED_EXECUTE;
    //    }
    //
    //    StartTime = av_gettime_relative();
    //    PtsOffset = NextPts;
    //
    //    end_of_function:
    //    return rc;
  }

  private void writeFrame(
      AVFormatContext outputCtx,
      AVCodecContext decoderCtx,
      AVStream outputStream,
      AVCodecContext encoderCtx,
      AVFrame frame,
      AVPacket packet) {
    int ret;
    if (avcodec_send_packet(decoderCtx, packet) >= 0) {
      ret = avcodec_receive_frame(decoderCtx, frame);
      while (ret >= 0) {
        System.out.println("Encoding frame...");
        ret = avcodec_send_frame(encoderCtx, frame);

        System.out.println("ret for avcodec_send_frame-->" + ret);
        if (ret >= 0) {
          AVPacket outPacket = new AVPacket();
          int recv_packets = avcodec_receive_packet(encoderCtx, outPacket);

          System.out.println("recv_packets-->" + recv_packets);
          while (recv_packets >= 0) {
            System.out.println("Writing encoded packet to RTP stream...");
            outPacket.stream_index(outputStream.index());

            System.out.println("Length of packet:" + outPacket.size());

            byte[] outPacketData = new byte[outPacket.size()];
            System.out.println("Read data:" + outPacket.data().get(outPacketData));

            System.out.println("Length of array:" + outPacketData.length);

            writeFrameToDB(outPacketData);

            av_write_frame(outputCtx, outPacket);
            av_packet_unref(outPacket);

            recv_packets = avcodec_receive_packet(encoderCtx, outPacket);
          }
        }
        System.out.println("ret for avcodec_receive_frame1 -->" + ret);

        ret = avcodec_receive_frame(decoderCtx, frame);
        System.out.println("ret for avcodec_receive_frame2 -->" + ret);
      }
    }

    //        av_write_frame(outputCtx, packet);
  }

  private static ParameterType getOrCreateType(
      XtceDb mdb, String name, Supplier<ParameterType.Builder<?>> supplier) {

    String fqn = XtceDb.YAMCS_SPACESYSTEM_NAME + NameDescription.PATH_SEPARATOR + name;
    ParameterType ptype = mdb.getParameterType(fqn);
    if (ptype != null) {
      return ptype;
    }
    ParameterType.Builder<?> typeb = supplier.get().setName(name);

    ptype = typeb.build();
    ((NameDescription) ptype).setQualifiedName(fqn);

    return ((Mdb) mdb).addSystemParameterType(ptype);
  }

  public static ParameterType getBasicType(XtceDb mdb, Type type) {
    ParameterType pType = null;
    switch (type) {
      case BOOLEAN:
        return getOrCreateType(mdb, "boolean", () -> new BooleanParameterType.Builder());
      case STRING:
        return getOrCreateType(mdb, "string", () -> new StringParameterType.Builder());

      case FLOAT:
        return getOrCreateType(
            mdb, "float32", () -> new FloatParameterType.Builder().setSizeInBits(32));
      case DOUBLE:
        return getOrCreateType(
            mdb, "float64", () -> new FloatParameterType.Builder().setSizeInBits(64));
      case SINT32:
        return getOrCreateType(
            mdb,
            "sint32",
            () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(true));
      case SINT64:
        return getOrCreateType(
            mdb,
            "sint64",
            () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(true));
      case UINT32:
        return getOrCreateType(
            mdb,
            "uint32",
            () -> new IntegerParameterType.Builder().setSizeInBits(32).setSigned(false));
      case UINT64:
        return getOrCreateType(
            mdb,
            "uint64",
            () -> new IntegerParameterType.Builder().setSizeInBits(64).setSigned(false));
      default:
        break;
    }

    return pType;
  }

  private void writeFrameToDB(byte data[]) {
    TupleDefinition tdef = gftdef.copy();

    int numberfRecords = 1;

    List<Object> cols = new ArrayList<>(4 + numberfRecords);

    tdef = gftdef.copy();
    long gentime = timeService.getMissionTime();
    cols.add(gentime);
    cols.add("/yamcs/pop-os/");
    cols.add(0);
    cols.add(gentime);

    tdef.addColumn(frameParam.getQualifiedName(), DataType.PARAMETER_VALUE);

    //    String filePath =
    // "/home/lgomez/projects/viper_sitl/squeaky-weasel/software/airliner/build/venus_aero/sassie/sitl_commander_workspace/hello_world.txt";
    //
    //    try (FileInputStream fis = new FileInputStream(filePath)) {
    //      // Create a byte array large enough to hold the file contents
    //      byte[] fileBytes = new byte[fis.available()];
    //
    //      // Read the bytes into the array
    //      fis.read(fileBytes);
    //
    //      // Print the bytes (optional)
    //      //        for (byte b : fileBytes) {
    //      //            System.out.print(b + " ");
    //
    //      cols.add(getPV(frameParam, gentime, data));
    //
    //      //        }
    //    } catch (IOException e) {
    //      e.printStackTrace();
    //    }

    //    cols.add(getPV(frameParam, gentime, ByteBuffer.allocate(4).putFloat(47.0f).array()));

    //    cols.add(getPV(frameParam, gentime, 47.0 ));

    cols.add(getPV(frameParam, gentime, data));

    pushTuple(tdef, cols);
  }

  private static Stream getStream(YarchDatabaseInstance ydb, String streamName) {
    Stream stream = ydb.getStream(streamName);
    if (stream == null) {
      try {
        ydb.execute("create stream " + streamName + gftdef.getStringDefinition());
      } catch (Exception e) {
        throw new ConfigurationException(e);
      }

      stream = ydb.getStream(streamName);
    }
    return stream;
  }

  private synchronized void pushTuple(TupleDefinition tdef, List<Object> cols) {
    Tuple t;
    t = new Tuple(tdef, cols);
    videoStream.emitTuple(t);
  }

  public ParameterValue getNewPv(Parameter parameter, long time) {
    ParameterValue pv = new ParameterValue(parameter);
    pv.setAcquisitionTime(YamcsServer.getTimeService(yamcsInstance).getMissionTime());
    pv.setGenerationTime(time);
    return pv;
  }

  public ParameterValue getPV(Parameter parameter, long time, double v) {
    ParameterValue pv = getNewPv(parameter, time);
    pv.setEngValue(ValueUtility.getDoubleValue(v));
    pv.setRawValue(ValueUtility.getDoubleValue(v));
    return pv;
  }

  public ParameterValue getPV(Parameter parameter, long time, byte v[]) {
    ParameterValue pv = getNewPv(parameter, time);
    pv.setEngValue(ValueUtility.getBinaryValue(v));
    pv.setRawValue(ValueUtility.getBinaryValue(v));
    return pv;
  }

  static void save_frame(AVFrame pFrame, int width, int height, int f_idx) throws IOException {
    // Open file
    String szFilename = String.format("frame%d_.ppm", f_idx);
    OutputStream pFile = new FileOutputStream(szFilename);

    // Write header
    pFile.write(String.format("P6\n%d %d\n255\n", width, height).getBytes());

    // Write pixel data
    BytePointer data = pFrame.data(0);
    byte[] bytes = new byte[width * 3];
    int l = pFrame.linesize(0);
    for (int y = 0; y < height; y++) {
      data.position(y * l).get(bytes);
      pFile.write(bytes);
    }

    // Close file
    pFile.close();
  }

  /**
   * Concatenates the root with the subsystems and returns a qualified name
   *
   * @param root
   */
  public static String qualifiedName(String root, String... subsystems) {
    if (root.charAt(0) != NameDescription.PATH_SEPARATOR) {
      throw new IllegalArgumentException(
          "root has to start with " + NameDescription.PATH_SEPARATOR);
    }
    StringBuilder sb = new StringBuilder();
    sb.append(root);
    for (String s : subsystems) {
      if (s.charAt(0) != NameDescription.PATH_SEPARATOR) {
        sb.append(NameDescription.PATH_SEPARATOR);
      }
      sb.append(s);
    }
    return sb.toString();
  }

  private void readVideo() throws IOException {
    System.out.println("Read few frame and write to image");
    //      if (args.length < 1) {
    //          System.out.println("Missing input video file");
    //          System.exit(-1);
    //      }
    int ret = -1, i = 0, v_stream_idx = -1;
    String vf_path = "/home/lgomez/Downloads/ginger_man.mp4";
    AVFormatContext fmt_ctx = new AVFormatContext(null);
    AVPacket pkt = new AVPacket();

    ret = avformat_open_input(fmt_ctx, vf_path, null, null);
    if (ret < 0) {
      System.out.printf("Open video file %s failed \n", vf_path);
      throw new IllegalStateException();
    }

    // i dont know but without this function, sws_getContext does not work
    if (avformat_find_stream_info(fmt_ctx, (PointerPointer) null) < 0) {
      System.exit(-1);
    }

    av_dump_format(fmt_ctx, 0, vf_path, 0);

    for (i = 0; i < fmt_ctx.nb_streams(); i++) {
      if (fmt_ctx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
        v_stream_idx = i;
        break;
      }
    }
    if (v_stream_idx == -1) {
      System.out.println("Cannot find video stream");
      throw new IllegalStateException();
    } else {
      System.out.printf(
          "Video stream %d with resolution %dx%d\n",
          v_stream_idx,
          fmt_ctx.streams(i).codecpar().width(),
          fmt_ctx.streams(i).codecpar().height());
    }

    AVCodecContext codec_ctx = avcodec_alloc_context3(null);
    avcodec_parameters_to_context(codec_ctx, fmt_ctx.streams(v_stream_idx).codecpar());

    AVCodec codec = avcodec_find_decoder(codec_ctx.codec_id());
    if (codec == null) {
      System.out.println("Unsupported codec for video file");
      throw new IllegalStateException();
    }
    ret = avcodec_open2(codec_ctx, codec, (PointerPointer) null);
    if (ret < 0) {
      System.out.println("Can not open codec");
      throw new IllegalStateException();
    }

    AVFrame frm = av_frame_alloc();

    // Allocate an AVFrame structure
    AVFrame pFrameRGB = av_frame_alloc();
    if (pFrameRGB == null) {
      System.exit(-1);
    }

    // Determine required buffer size and allocate buffer
    int numBytes =
        av_image_get_buffer_size(AV_PIX_FMT_RGB24, codec_ctx.width(), codec_ctx.height(), 1);
    BytePointer buffer = new BytePointer(av_malloc(numBytes));

    SwsContext sws_ctx =
        sws_getContext(
            codec_ctx.width(),
            codec_ctx.height(),
            codec_ctx.pix_fmt(),
            codec_ctx.width(),
            codec_ctx.height(),
            AV_PIX_FMT_RGB24,
            SWS_BILINEAR,
            null,
            null,
            (DoublePointer) null);

    if (sws_ctx == null) {
      System.out.println("Can not use sws");
      throw new IllegalStateException();
    }

    av_image_fill_arrays(
        pFrameRGB.data(),
        pFrameRGB.linesize(),
        buffer,
        AV_PIX_FMT_RGB24,
        codec_ctx.width(),
        codec_ctx.height(),
        1);

    i = 0;
    int ret1 = -1, ret2 = -1, fi = -1;
    while (av_read_frame(fmt_ctx, pkt) >= 0) {
      if (pkt.stream_index() == v_stream_idx) {
        ret1 = avcodec_send_packet(codec_ctx, pkt);
        ret2 = avcodec_receive_frame(codec_ctx, frm);
        System.out.printf("ret1 %d ret2 %d\n", ret1, ret2);
        // avcodec_decode_video2(codec_ctx, frm, fi, pkt);
      }
      // if not check ret2, error occur [swscaler @ 0x1cb3c40] bad src image pointers
      // ret2 same as fi
      // if (fi && ++i <= 5) {
      if (ret2 >= 0 && ++i <= 100) {
        sws_scale(
            sws_ctx,
            frm.data(),
            frm.linesize(),
            0,
            codec_ctx.height(),
            pFrameRGB.data(),
            pFrameRGB.linesize());

        save_frame(pFrameRGB, codec_ctx.width(), codec_ctx.height(), i);
        // save_frame(frm, codec_ctx.width(), codec_ctx.height(), i);
      }
      av_packet_unref(pkt);
      if (i >= 100) {
        break;
      }
    }

    av_frame_free(frm);

    avcodec_close(codec_ctx);
    avcodec_free_context(codec_ctx);

    avformat_close_input(fmt_ctx);

    streamVideoOverRTP();
    System.out.println("Shutdown");
  }

  @Override
  public void run() {

    //    for (int i = 0; i < 10; i++) {
    //      writeFrameToDB(new byte[64]);
    //    }

    try {
      streamVideoOverRTP();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    while (isRunningAndEnabled()) {

      //      scheduler.scheduleAtFixedRate(
      //          () -> {
      //            writeFrameToDB(new byte[(int) 1e9 ]);
      //          },
      //          1,
      //          1,
      //          TimeUnit.SECONDS);
      TmPacket tmpkt = getNextPacket();
      //      writeFrameToDB(new byte[64]);
      if (tmpkt != null) {
        processPacket(tmpkt);
      }
    }
  }

  /**
   * Called to retrieve the next packet. It blocks in readining on the multicast socket
   *
   * @return anything that looks as a valid packet, just the size is taken into account to decide if
   *     it's valid or not
   */
  public TmPacket getNextPacket() {
    byte[] packet = null;

    while (isRunning()) {
      try {
        tmSocket.receive(datagram);
        int pktLength = datagram.getLength() - initialBytesToStrip;

        if (pktLength <= 0) {
          log.warn(
              "received datagram of size {} <= {} (initialBytesToStrip); ignored.",
              datagram.getLength(),
              initialBytesToStrip);
          invalidDatagramCount++;
          continue;
        }

        updateStats(datagram.getLength());
        packet = new byte[pktLength];
        System.arraycopy(
            datagram.getData(), datagram.getOffset() + initialBytesToStrip, packet, 0, pktLength);
        break;
      } catch (IOException e) {
        if (!isRunning()
            || isDisabled()) { // the shutdown or disable will close the socket and that will
          // generate an exception
          // which we ignore here
          return null;
        }
        log.warn("exception thrown when reading from the UDP socket at port {}", port, e);
      }
    }

    if (packet != null) {
      TmPacket tmPacket = new TmPacket(timeService.getMissionTime(), packet);
      tmPacket.setEarthRceptionTime(timeService.getHresMissionTime());
      return packetPreprocessor.process(tmPacket);
    } else {
      return null;
    }
  }

  /** returns statistics with the number of datagram received and the number of invalid datagrams */
  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return "DISABLED";
    } else {
      return String.format(
          "OK (%s) %nValid datagrams received: %d%nInvalid datagrams received: %d",
          port, packetCount.get(), invalidDatagramCount);
    }
  }

  /** Sets the disabled to true such that getNextPacket ignores the received datagrams */
  @Override
  public void doDisable() {
    if (tmSocket != null) {
      tmSocket.close();
      tmSocket = null;
    }
  }

  /**
   * Sets the disabled to false such that getNextPacket does not ignore the received datagrams
   *
   * @throws SocketException
   */
  @Override
  public void doEnable() throws SocketException {
    tmSocket = new DatagramSocket(port);
    new Thread(this).start();
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }
}
