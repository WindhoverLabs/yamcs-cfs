package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.PipelineCfg.MMC_EntryState;
import com.windhoverlabs.com.video.PipelineCfg.MMC_InputFormatCfg;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;

public class InputFormat extends ComponentBase {

  MMC_InputFormatCfg Config;
  AVFormatContext Context;
  AVStream Stream;
  AVCodecParameters CodecParameters;

  int VideoStreamIndex;
  int PacketSkipCount = 0;
  int PacketsToSkip = 0;

  SHK Hk;

  long StartTime;
  //	long               PtsOffset = 0;
  //	long               DtsOffset = 0;
  long NextPts = 0;
  long PreviousPts = 0;
  long PreviousDts = 0;
  long PreviousDuration = 0;
  long PreviousAdjustedPts = 0;
  long PreviousAdjustedDts = 0;
  float TimeScale = 1.0f;
  boolean PacketReceived = false;
  boolean DropNonKeyPackets = false;
  long NonKeyPacketDuration = 0;

  class SHK {
    long PacketCount;
    long SkippedPackets;
    int PacketsIn;
    double PacketRateIn;
    double BitRateIn;
    int BytesIn;
    int PacketsOut;
    double PacketRateOut;
    double BitRateOut;
    int BytesOut;
    int PacketsIgnored;
    long Pts;
    long Dts;
  }
  ;

  EReturnCode Restart() {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    rc = RestartInputSource();

    /* Reset demuxer state */
    avRC = avformat.avformat_flush(Context);
    if (avRC < 0) {
      ReportAVError(
          "CInputFormat::Restart",
          "avformat_flush",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
      rc = EReturnCode.FAILED_EXECUTE;
    }

    /* Reset PacketsReceived back to zero so the jam won't mess up our CollectTimingData()
     * function.
     */
    PacketReceived = false;

    Hk.PacketCount = 0;

    return rc;
  }

  EReturnCode JumpToPts(long Pts) {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    // Seek to the desired PTS
    long tsInStreamTimebase =
        avutil.av_rescale_q(Pts, avutil.av_get_time_base_q(), Stream.time_base());
    avRC = avformat.av_seek_frame(Context, VideoStreamIndex, tsInStreamTimebase, 0);
    if (avRC < 0) {
      ReportAVError(
          "CInputFormat::JumpToPts",
          "av_seek_frame",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
      rc = EReturnCode.FAILED_EXECUTE;
    }

    StartTime += Pts;

    return rc;
  }

  void DelayByPts(long pts, AVRational timeBase, long startTime) {
    /* First convert the PTS timebase to our clock timebase.  On most, if not all
     * platforms, the clock timebase will be 1/1000000.  So basically, this next
     * function will convert the PTS time to microseconds of clock time.  In other
     * words, this is the clock time that our packet needs to be rendered.
     */
    long playbackTime = avutil.av_rescale_q(pts, timeBase, avutil.av_get_time_base_q());

    /* Get the current time in microseconds. */
    long currentTime = avutil.av_gettime_relative() - startTime;

    /* Is the playback time after current time?  If not, we're falling behind
     * anyway.  Hopefully, it will be in the future.
     */
    if (playbackTime > currentTime) {
      /* Yes, playback is in the future.  Let's calculate how long we can slee
       * before we need to wake up and send the packet out for processing.
       */
      long sleepTime = playbackTime - currentTime;

      /* Nap time.  Go to sleep. */
      avutil.av_usleep((int) sleepTime);
    } else {
      String.format("1 Falling behind %i\n", currentTime - playbackTime);
    }
  }

  EReturnCode GetSkipPackets(int inPacketsToSkip) {
    EReturnCode rc = EReturnCode.OK;

    PacketsToSkip = 0;

    inPacketsToSkip = PacketsToSkip;

    return rc;
  }

  EReturnCode CollectTimingData(AVPacket newPacket) {
    EReturnCode rc = EReturnCode.OK;

    if (PacketReceived) {
      /* Don't do anything if we're dropping non key packets */
      if (DropNonKeyPackets) {
        /* This is not the first packet, so we can start calculating metrics.
         * First annotate that we've received a packet. */
        /* Now check for time standing still. */

        long deltaPts = newPacket.pts() - PreviousPts;
        long deltaDts = newPacket.dts() - PreviousDts;
        long deltaDuration = newPacket.duration() - PreviousDuration;

        /* Is the PTS of the new packet static? */
        if (0 == deltaPts) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(String.format("PTS is static at %ld,\n", deltaPts));
        }

        /* Is the DTS of the new packet static? */
        if (0 == deltaDts) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(String.format("DTS is static at %ld,\n", deltaDts));
        }

        /* Now lets check for discontinuities. */
        /* Is the PTS of the new packet outside of bounds? */
        if (Math.abs(deltaPts - (newPacket.duration() * (PacketsToSkip + 1)))
            > VideoConstants.PTS_DISCONTINUITY_THRESHOLD) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(
              String.format(
                  "PTS jumped from %ld to %ld (%ld),\n", PreviousPts, newPacket.pts(), deltaPts));
        }

        /* Is the DTS of the new packet outside of bounds? */
        if (Math.abs(deltaDts - (newPacket.duration() * (PacketsToSkip + 1)))
            > VideoConstants.DTS_DISCONTINUITY_THRESHOLD) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(
              String.format(
                  "DTS jumped from %ld to %ld (%ld),\n", PreviousDts, newPacket.dts(), deltaDts));
        }

        /* Is the duration of the new packet outside of bounds? */
        if (Math.abs(deltaDuration) > VideoConstants.DURATION_DISCONTINUITY_THRESHOLD) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(
              String.format(
                  "Duration jumped from %ld to %ld (%ld),\n",
                  PreviousDuration, newPacket.duration(), deltaDuration));
        }

        /* Now lets check for time going backwards. */
        /* Did the PTS of the new packet go backwards in time? */
        if (deltaPts < 0) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(
              String.format(
                  "PTS reverted from %ld to %ld (%ld),\n", PreviousPts, newPacket.pts(), deltaPts));
        }

        /* Did the DTS of the new packet go backwards in time? */
        if (deltaDts < 0) {
          /* Yes it is. Note this. */
          /* TODO */
          System.out.println(
              String.format(
                  "DTS reverted from %ld to %ld (%ld),\n", PreviousDts, newPacket.dts(), deltaDts));
        }
      }
    }

    PacketReceived = true;

    PreviousPts = newPacket.pts();
    PreviousDts = newPacket.dts();
    PreviousDuration = newPacket.duration();

    return rc;
  }

  EReturnCode SetNewTiming(AVPacket newPacket) {
    EReturnCode rc = EReturnCode.OK;

    /* Collect some metrics before we make adjustments. */
    CollectTimingData(newPacket);

    /* First, adjust the new packet by adding the offset and advancing it by its
     * self reported duration. */
    newPacket.pts(PreviousAdjustedPts + newPacket.duration());
    newPacket.dts(PreviousAdjustedDts + newPacket.duration());

    newPacket.pts(newPacket.pts() + NonKeyPacketDuration);
    newPacket.dts(newPacket.dts() + NonKeyPacketDuration);
    NonKeyPacketDuration = 0;

    /* Now estimate the next PTS.  This is required by the rollover response algorithm.
     * We assume the next frame will take the same duration as this frame, so just
     * take the new PTS and add in the duration to estimate what the next PTS will be.
     */
    NextPts = newPacket.pts() + newPacket.duration();

    /* Finally, save off the values we just calculated. */
    PreviousAdjustedPts = newPacket.pts();
    PreviousAdjustedDts = newPacket.dts();

    return rc;
  }

  EReturnCode SetConfig(MMC_InputFormatCfg inConfig) {
    EReturnCode rc = EReturnCode.OK;

    Config = inConfig;

    return rc;
  }

  EReturnCode Initialize() {
    EReturnCode rc = EReturnCode.OK;

    super.Initialize();
    PacketReceived = false;

    //	memset(&Hk, 0, sizeof(Hk));

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if ((MMC_EntryState.INACTIVE == Config.state) || (MMC_EntryState.ACTIVE == Config.state)) {
        int avRC = 0;
        AVDictionary options = null;
        //			char         InvalidOptionsBuffer[MMC_INVALID_OPTIONS_BUFFER_SIZE];

        String InvalidOptionsBuffer = null;
        //			InvalidOptionsBuffer[0] = '\0';

        Context = avformat.avformat_alloc_context();
        if (Context != null) {
          super.ReportError(
              "InputFormat",
              "avformat_alloc_context",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              "Could not allocate AVInputFormat context.");
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        rc =
            super.ConvertToAVDictionary(
                Config.params,
                VideoConstants.MMC_MAX_CONFIG_PARAMS,
                options,
                Context.av_class(),
                InvalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "InputFormat",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              InvalidOptionsBuffer);
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
                InvalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "InputFormat",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              InvalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }

        /* Open the URL. */
        avRC = avformat.avformat_open_input(Context, Config.url, null, options);
        if (avRC < 0) {
          ReportAVError(
              "InputFormat",
              "avformat_open_input",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }
        rc =
            GetUnusedOptions(
                options, InvalidOptionsBuffer, VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "COutputFormat::Start",
              "Unused Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              InvalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }
        avutil.av_dict_free(options);

        for (int i = 0; i < Context.nb_streams(); i++) {
          AVStream stream = Context.streams(i);
          CodecParameters = stream.codecpar();
          AVCodec codec = avcodec.avcodec_find_decoder(CodecParameters.codec_id());

          if (codec == null) {
            continue; // Skip setting options for this stream
          }

          if (CodecParameters.codec_type() == avutil.AVMEDIA_TYPE_VIDEO) {
            rc =
                ConvertToAVDictionary(
                    Config.streamParams,
                    VideoConstants.MMC_MAX_CONFIG_PARAMS,
                    options,
                    codec.priv_class(),
                    InvalidOptionsBuffer,
                    VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
            if (rc != EReturnCode.OK) {
              ReportError(
                  "InputFormat",
                  "StreamParams",
                  Thread.currentThread().getStackTrace()[0].getLineNumber(),
                  InvalidOptionsBuffer);
              rc = EReturnCode.FAILED_INITIALIZATION;
              //						goto end_of_function;
            }
          }

          break;
        }

        /* Retrieve stream information */
        avRC = avformat.avformat_find_stream_info(Context, options);
        avutil.av_dict_free(options);
        if (avRC < 0) {
          ReportAVError(
              "InputFormat",
              "avformat_find_stream_info",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
        }

        /* Find the first video stream */
        VideoStreamIndex =
            avformat.av_find_best_stream(
                Context, avutil.AVMEDIA_TYPE_VIDEO, -1, -1, new AVCodec(), 0);
        if (VideoStreamIndex < 0) {
          ReportAVError(
              "CInputFormat::Initialize",
              "av_find_best_stream",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          System.err.println("No video stream found\n");
          rc = EReturnCode.FAILED_INITIALIZATION;
          //				goto end_of_function;
        }
        Stream = Context.streams(VideoStreamIndex);

        StartTime = avutil.av_gettime_relative();

        JumpToPts(7000000);
      }
    }

    return rc;
  }

  EReturnCode RestartInputSource() {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    if ((Context.flags() & avformat.AVFMTCTX_UNSEEKABLE) != 0) {
      /* Seek back to the beginning of the file */
      avRC = avformat.av_seek_frame(Context, -1, 0, avformat.AVSEEK_FLAG_BACKWARD);
      if (avRC < 0) {
        ReportAVError(
            "CInputFormat::GetPacket",
            "av_seek_frame",
            avRC,
            Thread.currentThread().getStackTrace()[0].getLineNumber());
        rc = EReturnCode.FAILED_EXECUTE;
      }
    }

    return rc;
  }

  void SetDropNonKeyPackets(boolean input) {
    if (input != DropNonKeyPackets) {
      if (false == input) {
        PacketReceived = false;
      }
    }

    DropNonKeyPackets = input;
  }

  EReturnCode GetPacket(AVPacket inOutPacket) {
    EReturnCode rc = EReturnCode.OK;
    boolean contProcessing = true;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.state) {
        int avRC;

        while (contProcessing) {
          avRC = avformat.av_read_frame(Context, inOutPacket);

          if (inOutPacket.stream_index() == VideoStreamIndex) {
            ++Hk.PacketsIn;
            Hk.BytesIn += inOutPacket.size();
            if (avutil.AVERROR_EOF == avRC) {
              rc = EReturnCode.OK_EOF;
              contProcessing = false;
            } else if (avRC < 0) {
              ReportAVError(
                  "InputFormat",
                  "av_read_frame",
                  avRC,
                  Thread.currentThread().getStackTrace()[0].getLineNumber());
              rc = EReturnCode.FAILED_EXECUTE;
              contProcessing = false;
            } else {
              if (DropNonKeyPackets && ((inOutPacket.flags() & avcodec.AV_PKT_FLAG_KEY) != 0)) {
                /* TODO */
                ++Hk.SkippedPackets;
                NonKeyPacketDuration += inOutPacket.duration();
              } else {
                int skipPackets = 0;
                SetDropNonKeyPackets(false);
                GetSkipPackets(skipPackets);
                int modPacket = (int) (Hk.PacketCount % (skipPackets + 1));
                if (modPacket != 0) {
                  SetNewTiming(inOutPacket);

                  DelayByPts(inOutPacket.pts(), Stream.time_base(), StartTime);

                  Hk.PacketRateIn =
                      ((double) Hk.PacketsIn) / (Context.duration() / avutil.AV_TIME_BASE);
                  Hk.BitRateIn =
                      ((double) Hk.BytesIn * 8) / (Context.duration() / avutil.AV_TIME_BASE);

                  ++Hk.PacketsOut;
                  Hk.BytesOut += inOutPacket.size();
                  Hk.Pts = inOutPacket.pts();
                  Hk.Dts = inOutPacket.dts();
                  Hk.PacketRateOut =
                      ((double) Hk.PacketsOut) / (Context.duration() / avutil.AV_TIME_BASE);
                  Hk.BitRateOut =
                      ((double) Hk.BytesOut * 8) / (Context.duration() / avutil.AV_TIME_BASE);

                  contProcessing = false;
                }
                ++Hk.PacketCount;
              }
            }
          } else {
            ++Hk.PacketsIgnored;
            avcodec.av_packet_unref(inOutPacket);
          }
        }
      }
    }

    return rc;
  }

  boolean IsConfigSet() {
    boolean rc = false;

    if (Config != null) {
      rc = true;
    }

    return rc;
  }

  AVStream GetStream() {
    return Stream;
  }

  EReturnCode SetPacketSkipCount(int Count) {
    PacketSkipCount = Count;

    return EReturnCode.OK;
  }

  long GetPacketCount() {
    return Hk.PacketCount;
  }
}
