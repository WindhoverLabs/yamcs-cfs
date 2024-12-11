package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_DecoderCfg;
import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_EntryState;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext.Get_format_AVCodecContext_IntPointer;
import org.bytedeco.ffmpeg.avcodec.AVCodecHWConfig;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.annotation.Cast;

public class Decoder extends ComponentBase {

  class SHK {
    int FrameCount;
    int PacketsIn;
    int BytesIn;
    int FramesOut;
    int BytesOut;
    int PacketsPerFrame;
    int SizeOfPackets;
    int Width;
    int Height;
    double BitRate;
    long DecodeTime;
    int TicksPerFrame;
    long[] Errors = new long[8];
    int FrameQuality;
    int GlobalQuality;
    long RcMaxRate;
    long RcMinRate;
    int RcBufferSize;
  }
  ;

  MMC_DecoderCfg Config;
  AVCodecContext Context;
  int PacketsSinceFrame = 0;
  AVFrame HWFrame;
  long StreamStartTime = 0;
  int FramesToSkip = 0;
  long StartTime;
  AVStream Stream;

  SHK Hk;

  EReturnCode GetSkipFrames(int inFramesToSkip) {
    EReturnCode rc = EReturnCode.OK;

    FramesToSkip = 0;

    inFramesToSkip = FramesToSkip;

    return rc;
  }

  boolean IsHWAccelerated() {
    boolean rc = true;

    if (Context.hw_device_ctx() == null) {
      rc = false;
    }

    return rc;
  }

  /// * Callback to get the hardware-accelerated pixel format */
  // static enum AVPixelFormat MMC_GetHwFormat(AVCodecContext *ctx, const enum AVPixelFormat
  // *pix_fmts) {
  //    const enum AVPixelFormat *p;
  //
  //    for (p = pix_fmts; *p != -1; p++) {
  //        if (
  //        	*p == AV_PIX_FMT_VAAPI ||
  //			*p == AV_PIX_FMT_VDPAU ||
  //			*p == AV_PIX_FMT_QSV ||
  //			*p == AV_PIX_FMT_MMAL ||
  //			*p == AV_PIX_FMT_CUDA ||
  //			*p == AV_PIX_FMT_XVMC
  //			) {
  //            return *p;
  //        }
  //    }
  //    fprintf(stderr, "Failed to get a suitable hardware pixel format.\n");
  //    return AV_PIX_FMT_NONE;
  // }

  int MMC_GetHwFormat(AVCodecContext ctx, int pix_fmts) {

    // Check if the codec supports hardware configurations
    for (int i = 0; ; i++) {
      AVCodecHWConfig config = avcodec.avcodec_get_hw_config(ctx.codec(), i);
      if (config == null) {
        System.err.println("Decoder does not support hardware configurations.\n");
        break;
      }

      //        // Check if the configuration matches the desired device type
      //        if (config.device_type == HW_DEVICE_TYPE) {
      // Check for a compatible pixel format in the provided list
      for (int p = pix_fmts; p != -1; p++) {
        if (p == config.pix_fmt()) {
          System.out.println(
              String.format("Selected hardware pixel format: %s\n", avutil.av_get_pix_fmt_name(p)));
          return p; // Return the compatible pixel format
        }
      }
      //        }
    }

    System.out.println("No compatible hardware pixel formats found.\n");
    return avutil.AV_PIX_FMT_NONE; // Return error if no compatible format is found
  }

  EReturnCode SetConfig(MMC_DecoderCfg inConfig) {
    EReturnCode rc = EReturnCode.OK;

    Config = inConfig;

    return rc;
  }

  void Reset() {
    if (Context != null) {
      avcodec.avcodec_flush_buffers(Context);
    }
  }

  EReturnCode Initialize(AVStream inStream, AVBufferRef HWAccelDeviceContext) {
    EReturnCode rc = EReturnCode.OK;

    super.Initialize();

    StreamStartTime = avutil.av_gettime();

    HWFrame = avutil.av_frame_alloc();

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if ((MMC_EntryState.INACTIVE == Config.state) || (MMC_EntryState.ACTIVE == Config.state)) {
        AVCodec codec = null;
        int avRC;
        AVDictionary options = null;
        //			char           invalidOptionsBuffer[MMC_INVALID_OPTIONS_BUFFER_SIZE];
        //
        //			invalidOptionsBuffer[0] = '\0';

        String invalidOptionsBuffer = null;

        Stream = inStream;

        /* Find the decoder for the video stream */
        codec = avcodec.avcodec_find_decoder(inStream.codecpar().codec_id());
        if (codec == null) {
          ReportError(
              "Decoder",
              "avcodec_find_decoder",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              "Unsupported codec.");
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        /* Allocate a codec context for the decoder */
        Context = avcodec.avcodec_alloc_context3(codec);
        if (Context == null) {
          ReportError(
              "Decoder",
              "avcodec_find_decoder",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              "Could not allocate codec context.");
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        Context.err_recognition(Config.errorRecognition);
        Context.error_concealment(Config.errorConcealment);
        Context.flags(Context.flags() | Config.flags);

        /* Copy codec parameters from input stream to codec context */
        avRC = avcodec.avcodec_parameters_to_context(Context, inStream.codecpar());
        if (avRC < 0) {
          ReportAVError(
              "Decoder",
              "avcodec_parameters_to_context",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          avcodec.avcodec_free_context(Context);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        if (HWAccelDeviceContext != null) {
          Context.hw_device_ctx(avutil.av_buffer_ref(HWAccelDeviceContext));
          Context.get_format(
              new Get_format_AVCodecContext_IntPointer() {
                public int call(AVCodecContext s, @Cast("const AVPixelFormat*") IntPointer fmt) {
                  return fmt.get();
                }
              });
        }

        rc =
            ConvertToAVDictionary(
                Config.params,
                VideoConstants.MMC_MAX_CONFIG_PARAMS,
                options,
                Context.av_class(),
                invalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "Initialize",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              invalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        /* This will not be validated.  To validate, we must know the private class
         * which, in this case, would be set at Context.iformat.priv_class.  The
         * problem is "iformat" is not set set until after "avformat_open_input()"
         * is called.
         */
        rc =
            AppendToAVDictionary(
                Config.privateParams,
                VideoConstants.MMC_MAX_CONFIG_PARAMS,
                options,
                null,
                invalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "Initialize",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              invalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        /* Open the codec */
        avRC = avcodec.avcodec_open2(Context, codec, options);
        if (avRC < 0) {
          ReportAVError(
              "Initialize",
              "avcodec_open2",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          avcodec.avcodec_free_context(Context);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        rc =
            GetUnusedOptions(
                options, invalidOptionsBuffer, VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "Initialize",
              "Unused Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              invalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }
        avutil.av_dict_free(options);

        Context.skip_frame(avcodec.AVDISCARD_NONREF);
      }
    }

    StartTime = avutil.av_gettime_relative();

    end_of_function:
    return rc;
  }

  EReturnCode SendPacket(AVPacket inPacket) {
    EReturnCode rc = EReturnCode.OK;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.state) {
        int avRC;

        ++Hk.PacketsIn;
        ++PacketsSinceFrame;
        Hk.BytesIn = inPacket.size();

        avRC = avcodec.avcodec_send_packet(Context, inPacket);
        UnreferencePacket(inPacket);
        if (avRC < 0) {
          ReportAVError(
              "Decoder",
              "avcodec_send_packet",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
          //				goto end_of_function;
        }
      }
    }

    // end_of_function:

    return rc;
  }

  EReturnCode DelayByPts(long pts, AVRational timeBase, long InstartTime) {
    EReturnCode rc = EReturnCode.OK;

    /* First convert the PTS timebase to our clock timebase.  On most, if not all
     * platforms, the clock timebase will be 1/1000000.  So basically, this next
     * function will convert the PTS time to microseconds of clock time.  In other
     * words, this is the clock time that our frame needs to be rendered.
     */
    long playbackTime = avutil.av_rescale_q(pts, timeBase, avutil.av_get_time_base_q());

    /* Get the current time in microseconds. */
    long currentTime = avutil.av_gettime_relative() - InstartTime;

    /* Is the playback time after current time?  If not, we're falling behind
     * anyway.  Hopefully, it will be in the future.
     */
    if (playbackTime > currentTime) {
      /* Yes, playback is in the future.  Let's calculate how long we can slee
       * before we need to wake up and send the packet out for processing.
       */
      long sleepTime = playbackTime - currentTime;

      System.out.println(String.format("Sleeping %li  ", sleepTime));

      //    	/* Nap time.  Go to sleep. */
      long startTime = avutil.av_gettime();
      avutil.av_usleep((int) (sleepTime / 4));
      long stopTime = avutil.av_gettime();
      System.out.println(String.format("%li\n", stopTime - startTime));
    } else {
      /* Let the caller know we are starting to get congested and
       * we are overrunning our deadline.
       */
      rc = EReturnCode.OK_CONGESTED;

      System.out.println(String.format("Behind %li ms\n", currentTime - playbackTime));
    }

    return rc;
  }

  EReturnCode GetNextFrame(AVFrame outFrame) {
    EReturnCode rc = EReturnCode.OK;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.state) {
        int avRC;
        long startTime;
        long stopTime;

        startTime = avutil.av_gettime();
        avRC = avcodec.avcodec_receive_frame(Context, outFrame);
        stopTime = avutil.av_gettime();

        if (avutil.AVERROR_EAGAIN() == avRC) {
          rc = EReturnCode.OK_QUEUE_EMPTY;
        } else if (avRC < 0) {
          ReportAVError(
              "Decoder",
              "avcodec_receive_frame",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
        } else if (0 == avRC) {
          int skipFrames = 0;

          GetSkipFrames(skipFrames);
          int modPacket = Hk.FrameCount % (skipFrames + 1);
          if (modPacket != 0) {
            // printf("*** PTS: %li   PKT_PTS: %li\n", outFrame.pts, outFrame.pkt_pts);

            Hk.PacketsPerFrame = PacketsSinceFrame;
            Hk.BitRate = Context.bit_rate();
            Hk.SizeOfPackets = outFrame.pkt_size();
            Hk.Width = outFrame.width();
            Hk.Height = outFrame.height();
            Hk.DecodeTime = stopTime - startTime;
            ++Hk.FramesOut;
            PacketsSinceFrame = 0;
            Hk.TicksPerFrame = Context.ticks_per_frame();
            for (int i = 0; i < 8; ++i) {
              Hk.Errors[i] = Context.error(i);
            }
            Hk.GlobalQuality = Context.global_quality();
            Hk.FrameQuality = outFrame.quality();
            Hk.RcMaxRate = Context.rc_max_rate();
            Hk.RcMinRate = Context.rc_min_rate();
            Hk.RcBufferSize = Context.rc_buffer_size();

            //					rc = DelayByPts(outFrame.pts, Stream.time_base, StartTime);
            //					if(rc != OK)
            //					{
            //						goto end_of_function;
            //					}
          } else {
            // printf("PTS: %li   PKT_PTS\n", outFrame.pts, outFrame.pkt_pts);
            rc = EReturnCode.OK_FRAME_SKIPPED;
          }

          ++Hk.FrameCount;
        }
      }
    }

    end_of_function:
    return rc;
  }

  boolean IsConfigSet() {
    boolean rc = false;

    if (Config != null) {
      rc = true;
    }

    return rc;
  }
}
