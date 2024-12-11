import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_EncoderCfg_t;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.Get_format_AVCodecContext_IntPointer;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.annotation.Cast;

public class Encoder extends ComponentBase {

  public static class SHK {
    public long FramesIn;
    public long BytesIn;
    public long PacketsOut;
    public long PacketsPerFrame;
    public long SizeOfPackets;
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
  }

  private MMC_EncoderCfg_t config;
  private AVCodecContext context;
  private int packetsSinceFrame;
  private AVCodec codec;
  private SHK hk = new SHK();
  private long streamStartTime;

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
    if (config == null || config.name == null || config.name.isEmpty()) {
      System.err.println("Config is not set or name is empty.");
      return EReturnCode.FAILED_INITIALIZATION;
    }

    codec = avcodec_find_encoder_by_name(config.name);
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
            public int call(AVCodecContext s, @Cast("const AVPixelFormat*") IntPointer fmt) {}
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
    int avRc = avcodec_open2(context, codec, new avutil.AVDictionary());
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
      return AVERROR(ENOMEM);
    }

    AVHWFramesContext framesCtx = (AVHWFramesContext) hwFramesCtx.data();
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

  public EReturnCode getNextPacket(AVPacket outPacket) {
    if (config == null) {
      return EReturnCode.FAILED_EXECUTE;
    }

    int avRc = avcodec_receive_packet(context, outPacket);
    if (avRc == AVERROR(EAGAIN)) {
      return EReturnCode.OK_QUEUE_EMPTY;
    } else if (avRc < 0) {
      System.err.println("Failed to receive packet.");
      return EReturnCode.FAILED_EXECUTE;
    }

    hk.PacketsOut++;
    return EReturnCode.OK;
  }

  public AVCodec getCodec() {
    return codec;
  }

  // Define EReturnCode enum equivalent in Java
  public enum EReturnCode {
    OK,
    FAILED_INITIALIZATION,
    FAILED_EXECUTE,
    OK_QUEUE_EMPTY
  }
}
