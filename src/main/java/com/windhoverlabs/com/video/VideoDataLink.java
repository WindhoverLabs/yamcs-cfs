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
import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swscale.*;
import org.bytedeco.javacpp.*;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.tctm.AbstractTmDataLink;

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
  }

  @Override
  public void doStart() {
    if (!isDisabled()) {
      try {
        readVideo();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
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

  private void streamVideoOverRTP() throws IOException {
    System.out.println("Starting video streaming over RTP...");

    int ret, v_stream_idx = -1;
    String inputFile = "/home/lgomez/Downloads/ginger_man.mp4";
    String outputURL = "rtp://172.16.100.208:1234";

    AVFormatContext inputCtx = avformat_alloc_context();
    AVFormatContext outputCtx = new AVFormatContext(null);

    // Open input video file
    System.out.println("Opening input file: " + inputFile);
    if (avformat_open_input(inputCtx, inputFile, null, null) < 0) {
      throw new IOException("Failed to open input file");
    }

    System.out.println("Finding stream info...");
    if (avformat_find_stream_info(inputCtx, (PointerPointer) null) < 0) {
      throw new IOException("Failed to retrieve stream info");
    }

    av_dump_format(inputCtx, 0, inputFile, 0);

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
    if (codec == null) {
      throw new IOException("Unsupported codec");
    }

    System.out.println("Allocating codec context...");
    AVCodecContext decoderCtx = avcodec_alloc_context3(codec);
    avcodec_parameters_to_context(decoderCtx, inputStream.codecpar());
    avcodec_open2(decoderCtx, codec, (PointerPointer) null);

    // Set up RTP output
    System.out.println("Setting up RTP output: " + outputURL);
    if (avformat_alloc_output_context2(outputCtx, null, "rtp", outputURL) < 0) {
      throw new IOException("Failed to create RTP output context");
    }

    AVStream outputStream = avformat_new_stream(outputCtx, codec);
    if (outputStream == null) {
      throw new IOException("Failed to create output stream");
    }

    System.out.println("Configuring encoder...");
    AVCodecContext encoderCtx = avcodec_alloc_context3(codec);
    System.out.println("Configuring encoder2...");
    encoderCtx.codec_id(codec.id());
    System.out.println("Configuring encoder3...");
    encoderCtx.codec_type(AVMEDIA_TYPE_VIDEO);
    encoderCtx.pix_fmt(AV_PIX_FMT_YUV420P);
    System.out.println("Configuring encoder4...");
    encoderCtx.width(decoderCtx.width());
    encoderCtx.height(decoderCtx.height());
    System.out.println("Configuring encoder5...");
    encoderCtx.time_base(av_inv_q(inputStream.time_base()));
    System.out.println("Configuring encoder6...");
    encoderCtx.bit_rate(400000);

    if (avcodec_open2(encoderCtx, codec, (PointerPointer) null) < 0) {
      throw new IOException("Failed to open encoder");
    }

    System.out.println("Configuring encoder7...");

    avcodec_parameters_from_context(outputStream.codecpar(), encoderCtx);
    System.out.println("Configuring encoder8...");

        if (avio_open2(outputCtx.pb(), outputURL, AVIO_FLAG_WRITE, null, null) < 0) {
          throw new IOException("Failed to open RTP output");
        }

    avformat_write_header(outputCtx, (PointerPointer) null);
    System.out.println("RTP streaming setup complete, starting frame processing...");

    AVFrame frame = av_frame_alloc();
    AVPacket packet = new AVPacket();

    while (av_read_frame(inputCtx, packet) >= 0) {
      if (packet.stream_index() == v_stream_idx) {
        System.out.println("Decoding frame...");
        if (avcodec_send_packet(decoderCtx, packet) >= 0) {
          while (avcodec_receive_frame(decoderCtx, frame) >= 0) {
            System.out.println("Encoding frame...");
            if (avcodec_send_frame(encoderCtx, frame) >= 0) {
              AVPacket outPacket = new AVPacket();
              while (avcodec_receive_packet(encoderCtx, outPacket) >= 0) {
                System.out.println("Writing encoded packet to RTP stream...");
                outPacket.stream_index(outputStream.index());
                av_write_frame(outputCtx, outPacket);
                av_packet_unref(outPacket);
              }
            }
          }
        }
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

    while (isRunningAndEnabled()) {
      TmPacket tmpkt = getNextPacket();
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
