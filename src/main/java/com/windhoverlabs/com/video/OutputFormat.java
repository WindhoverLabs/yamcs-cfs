package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.Encoder.SHK;
import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_EntryState;
import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_OutputFormatCfg_t;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;

public class OutputFormat extends ComponentBase {
  MMC_OutputFormatCfg_t Config;
  AVFormatContext Context;
  AVCodecParameters CodecParameters;
  AVStream Stream;
  AVStream InputStream;
  SHK Hk;

  EReturnCode SetConfig(MMC_OutputFormatCfg_t inConfig) {
    EReturnCode rc = EReturnCode.OK;

    Config = inConfig;

    return rc;
  }

  EReturnCode Restart() {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    //		/* Reset demuxer state */
    //		avRC = avformat_flush(Context);
    //		if (avRC < 0)
    //		{
    //			ReportAVError("COutputFormat::Restart", "avformat_flush", avRC,
    // Thread.currentThread().getStackTrace()[0].getLineNumber());
    //			rc = FAILED_EXECUTE;
    //			goto end_of_function;
    //		}

    avRC = avformat.av_interleaved_write_frame(Context, null);
    if (avRC < 0) {
      ReportAVError(
          "COutputFormat::Restart",
          "av_interleaved_write_frame",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
      rc = EReturnCode.FAILED_EXECUTE;
    }

    //		/* Reset demuxer state */
    //		avRC = avformat_flush(Context);
    //		if (avRC < 0)
    //		{
    //			ReportAVError("COutputFormat::Restart", "avformat_flush", avRC,
    // Thread.currentThread().getStackTrace()[0].getLineNumber());
    //			rc = FAILED_EXECUTE;
    //			goto end_of_function;
    //		}

    return rc;
  }

  boolean IsConfigSet() {
    boolean rc = false;

    if (Config != null) {
      rc = true;
    }

    return rc;
  }

  EReturnCode Initialize(AVStream inInputStream) {
    EReturnCode rc = EReturnCode.OK;

    super.Initialize();

    InputStream = inInputStream;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if ((MMC_EntryState.INACTIVE == Config.State) || (MMC_EntryState.ACTIVE == Config.State)) {
        int avRC;

        //	char InvalidOptionsBuffer[MMC_INVALID_OPTIONS_BUFFER_SIZE];
        //	InvalidOptionsBuffer[0] = '\0';

        avRC = avformat.avformat_alloc_output_context2(Context, null, Config.Name, Config.URL);
        if (avRC < 0) {
          ReportAVError(
              "OutputFormat",
              "avformat_alloc_output_context2",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_INITIALIZATION;
        }

        /* Add video stream to output */
        Stream = avformat.avformat_new_stream(Context, null);
        if (Stream != null) {
          ReportError(
              "COutputFormat::Initialize",
              "avformat_new_stream",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              "Failed allocating output stream.");
          rc = EReturnCode.FAILED_INITIALIZATION;
        }

        /* Required for some containers */
        Stream.codecpar().codec_tag(0);
      }
    }

    return rc;
  }

  EReturnCode Start() {
    EReturnCode rc = EReturnCode.OK;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if ((MMC_EntryState.INACTIVE == Config.State) || (MMC_EntryState.ACTIVE == Config.State)) {
        int avRC;
        AVDictionary options = null;
        //				char          InvalidOptionsBuffer[MMC_INVALID_OPTIONS_BUFFER_SIZE];

        String InvalidOptionsBuffer = new String();

        //				InvalidOptionsBuffer[0] = '\0';

        rc =
            ConvertToAVDictionary(
                Config.Params,
                VideoConstants.MMC_MAX_CONFIG_PARAMS,
                options,
                Context.av_class(),
                InvalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "COutputFormat::Start",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              InvalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
        }

        rc =
            AppendToAVDictionary(
                Config.PrivateParams,
                VideoConstants.MMC_MAX_CONFIG_PARAMS,
                options,
                null,
                InvalidOptionsBuffer,
                VideoConstants.MMC_INVALID_OPTIONS_BUFFER_SIZE);
        if (rc != EReturnCode.OK) {
          ReportError(
              "COutputFormat::Start",
              "Params",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              InvalidOptionsBuffer);
          rc = EReturnCode.FAILED_INITIALIZATION;
        }

        /* Open output */
        if ((Context.oformat().flags() & avformat.AVFMT_NOFILE) != 0) {
          // NOTE:This pattern seems to fix it as per
          // https://github.com/bytedeco/javacpp-presets/issues/408#issuecomment-291711924
          AVIOContext pb = new AVIOContext(null);
          avRC = avformat.avio_open2(pb, Config.URL, avformat.AVIO_FLAG_WRITE, null, options);
          if (avRC < 0) {
            ReportAVError(
                "OutputFormat",
                "avio_open2",
                avRC,
                Thread.currentThread().getStackTrace()[0].getLineNumber());
            rc = EReturnCode.FAILED_INITIALIZATION;
          }
          Context.pb(pb);
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
        }
        avutil.av_dict_free(options);

        /* Write header */
        avRC = avformat.avformat_write_header(Context, new AVDictionary());
        if (avRC < 0) {
          ReportAVError(
              "OutputFormat",
              "avformat_write_header",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_INITIALIZATION;
        }
      }
    }

    return rc;
  }

  AVStream GetStream() {
    return Stream;
  }

  EReturnCode SendPacket(AVPacket InPacket) {
    EReturnCode rc = EReturnCode.OK;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.State) {
        int avRC;

        //				int64_t scaled_pts = av_rescale_q(InPacket.pts, Stream.time_base, AV_TIME_BASE_Q);
        //				InPacket.pts = scaled_pts;

        avRC = avformat.av_interleaved_write_frame(Context, InPacket);
        if (avRC < 0) {
          ReportAVError(
              "COutputFormat::SendPacket",
              "av_interleaved_write_frame",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
        } else {
          ++Hk.PacketsOut;
          Hk.BytesOut += InPacket.size();
          Hk.PacketRate = ((double) Hk.PacketsOut) / (Context.duration() / avutil.AV_TIME_BASE);
          Hk.BitRate = ((double) Hk.BytesOut * 8) / (Context.duration() / avutil.AV_TIME_BASE);
          Hk.Pts = InPacket.pts();
          Hk.Dts = InPacket.dts();
        }
      }
    }

    return rc;
  }
}
