package com.windhoverlabs.com.video;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import com.windhoverlabs.com.video.PipelineCfg.MMC_EncoderCfg_t;
import com.windhoverlabs.com.video.PipelineCfg.MMC_EntryState;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.Get_format_AVCodecContext_IntPointer;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.annotation.Cast;

public class Encoder extends ComponentBase {

  public static class SHK {
    public long FramesIn;
    public long BytesIn;
    public long BytesOut;
    public long PacketsOut;
    public long PacketsPerFrame;
    public long SizeOfPackets;
    public long Pts;
    public long Dts;
    public long Width;
    public long Height;
    public double BitRate;
    public long EncodeTime;
    public long TicksPerFrame;
    public long[] Errors = new long[8];
    public long FrameQuality;
    public int GlobalQuality;
    public long RcMaxRate;
    public long RcMinRate;
    public int RcBufferSize;
    public double PacketRate;
  }

  private MMC_EncoderCfg_t config;
  private AVCodecContext context;
  private int packetsSinceFrame;
  private AVCodec codec;
  private SHK hk = new SHK();
  private long streamStartTime;
  private long pts;

  public Encoder() {
    // Constructor logic if needed
  }

  @Override
  public void finalize() {
    // Destructor logic
  }

  public EReturnCode setConfig(MMC_EncoderCfg_t inConfig) {
    this.config = inConfig;
    return EReturnCode.OK;
  }

  public EReturnCode initialize(AVStream inStream, AVBufferRef hwAccelDeviceContext) {
    if (config == null || config.Name == null || config.Name.isEmpty()) {
      System.err.println("Config is not set or name is empty.");
      return EReturnCode.FAILED_INITIALIZATION;
    }

    codec = avcodec_find_encoder_by_name(config.Name);
    if (codec == null) {
      System.err.println("Encoder not found.");
      return EReturnCode.FAILED_INITIALIZATION;
    }

    context = avcodec_alloc_context3(codec);
    if (context == null) {
      System.err.println("Failed to allocate codec context.");
      return EReturnCode.FAILED_INITIALIZATION;
    }

    context.width(config.Width);
    context.height(config.Height);
    context.time_base(av_make_q(1, config.FramesPerSecond));
    context.pix_fmt(config.PixelFormat);
    context.framerate(av_make_q(config.FramesPerSecond, 1));
    context.bit_rate(config.BitRate);
    context.gop_size(config.GopSize);
    context.max_b_frames(config.MaxBFrames);

    if (hwAccelDeviceContext != null) {
      initializeHwFramesCtx(hwAccelDeviceContext, config.Width, config.Height);
      context.hw_device_ctx(av_buffer_ref(hwAccelDeviceContext));
      //            context.get_format(this::getHwFormat);

      context.get_format(
          new Get_format_AVCodecContext_IntPointer() {
            public int call(AVCodecContext s, @Cast("const AVPixelFormat*") IntPointer fmt) {
              return fmt.get();
            }
          });
    }

    int avRc = avcodec_parameters_from_context(inStream.codecpar(), context);
    if (avRc < 0) {
      System.err.println("Error setting codec parameters.");
      return EReturnCode.FAILED_INITIALIZATION;
    }

    return EReturnCode.OK;
  }

  public boolean isConfigSet() {
    return config != null;
  }

  public boolean isHWAccelerated() {
    return context != null && context.hw_device_ctx() != null;
  }

  public void reset() {
    if (context != null) {
      avcodec_flush_buffers(context);
    }
  }

  public EReturnCode start() {
    if (config == null) {
      return EReturnCode.FAILED_INITIALIZATION;
    }

    streamStartTime = av_gettime();
    int avRc = avcodec_open2(context, codec, new PointerPointer(new AVDictionary()));
    if (avRc < 0) {
      System.err.println("Failed to open codec.");
      avcodec_free_context(context);
      return EReturnCode.FAILED_INITIALIZATION;
    }

    return EReturnCode.OK;
  }

  public int initializeHwFramesCtx(AVBufferRef hwDeviceCtx, int width, int height) {
    AVBufferRef hwFramesCtx = av_hwframe_ctx_alloc(hwDeviceCtx);
    if (hwFramesCtx == null) {
      System.err.println("Failed to allocate hardware frames context.");
      return avutil.AVERROR_ENOMEM();
    }

    //    AVHWFramesContext.
    //  TODO:Not sure if this is the "Java way" of "frames_ctx = (AVHWFramesContext
    // *)hw_frames_ctx.data;"
    BytePointer framesCtxPtr = hwFramesCtx.data();

    AVHWFramesContext framesCtx = new AVHWFramesContext(framesCtxPtr);
    framesCtx.format(AV_PIX_FMT_CUDA);
    framesCtx.sw_format(AV_PIX_FMT_NV12);
    framesCtx.width(width);
    framesCtx.height(height);
    framesCtx.initial_pool_size(10);

    int avRc = av_hwframe_ctx_init(hwFramesCtx);
    if (avRc < 0) {
      System.err.println("Failed to initialize hardware frames context.");
      av_buffer_unref(hwFramesCtx);
      return avRc;
    }

    context.hw_frames_ctx(av_buffer_ref(hwFramesCtx));
    av_buffer_unref(hwFramesCtx);
    return 0;
  }

  private int getHwFormat(AVCodecContext ctx, int[] pixFmts) {
    for (int pixFmt : pixFmts) {
      if (pixFmt == ctx.pix_fmt()) {
        return pixFmt;
      }
    }
    return AV_PIX_FMT_NONE;
  }

  public EReturnCode sendFrame(AVFrame inFrame) {
    if (config == null) {
      return EReturnCode.FAILED_EXECUTE;
    }

    int avRc = avcodec_send_frame(context, inFrame);
    if (avRc < 0) {
      System.err.println("Failed to send frame.");
      return EReturnCode.FAILED_EXECUTE;
    }

    hk.FramesIn++;
    return EReturnCode.OK;
  }

  boolean IsConfigSet() {
    boolean rc = false;

    if (config != null) {
      rc = true;
    }

    return rc;
  }

  public EReturnCode getNextPacket(AVPacket outPacket) {
    EReturnCode rc = EReturnCode.OK;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == config.State) {
        int avRC;

        avRC = avcodec_receive_packet(context, outPacket);

        if (avutil.AVERROR_EAGAIN() == avRC) {
          rc = EReturnCode.OK_QUEUE_EMPTY;
        } else if (avRC < 0) {
          ReportAVError(
              "CEncoder::GetNextPacket",
              "avcodec_receive_packet",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
        } else if (0 == avRC) {
          //	                /* Rescale timestamps for the output stream */
          //	                av_packet_rescale_ts(outPacket, Context.time_base, Stream.time_base);

          /* Get the current time in microseconds since epoch */
          //	                int64_t current_time_us = av_gettime();
          //
          ////	                outPacket.pts = av_rescale_q(outPacket.pts, Stream.time_base,
          // (AVRational){1, 90000}) / 10;
          ////	                outPacket.dts = av_rescale_q(outPacket.dts, Stream.time_base,
          // (AVRational){1, 90000}) / 10;
          ////					outPacket.pts = av_rescale_q(current_time_us, Stream.time_base, (AVRational){1,
          // 90000}) / 10;
          ////					outPacket.dts = av_rescale_q(current_time_us, Stream.time_base, (AVRational){1,
          // 90000}) / 10;
          //					outPacket.pts = av_rescale_q(current_time_us, (AVRational){1, 1000000},
          // Stream.time_base) / 3;
          //					outPacket.dts = av_rescale_q(current_time_us, (AVRational){1, 1000000},
          // Stream.time_base) / 3;
          //					outPacket.pts = av_rescale_q(current_time_us, Stream.time_base, (AVRational){1,
          // 1000000}) / 10;
          //					outPacket.dts = av_rescale_q(current_time_us, Stream.time_base, (AVRational){1,
          // 1000000}) / 10;

          //	                /* Convert the current time to the stream's time_base */
          //	                int64_t pts = av_rescale_q(current_time_us, (AVRational){1, 1000000},
          // Stream.time_base);
          //
          //	                /* Set the packet's PTS and DTS, since this is always going to be real
          // time. */
          //	                outPacket.pts = pts;
          //	                outPacket.dts = pts;

          //	                static int64_t last_pts = 0;
          //	                if (outPacket.pts <= last_pts)
          //	                {
          //	                	outPacket.pts = last_pts + 1;
          //	                }
          //	                last_pts = outPacket.pts;
          //
          //	                static int64_t last_dts = 0;
          //	                if (outPacket.dts <= last_dts)
          //	                {
          //	                	outPacket.dts = last_dts + 1;
          //	                }
          //	                last_dts = outPacket.dts;

          outPacket.dts(pts);
          outPacket.pts(pts);
          pts = pts + 1000;

          //	                int64_t current_time_us = av_gettime() - StreamStartTime;
          //	                outPacket.pts = av_rescale_q(current_time_us, (AVRational){1, 1000000},
          // (AVRational){1, 90000});

          //	                outPacket.stream_index = Stream.index;

          ++packetsSinceFrame;

          hk.PacketsPerFrame = packetsSinceFrame;
          hk.BitRate = context.bit_rate();
          ++hk.PacketsOut;
          for (int i = 0; i < 8; ++i) {

            hk.Errors[i] = context.error(i);
          }
          hk.GlobalQuality = context.global_quality();

          hk.RcMaxRate = context.rc_max_rate();
          hk.RcMinRate = context.rc_min_rate();
          hk.RcBufferSize = context.rc_buffer_size();
        }
      }
    }

    return rc;
  }

  public AVCodec getCodec() {
    return codec;
  }
}
