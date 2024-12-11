package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_EntryState;
import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_InputPipelineCfg_t;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avfilter.AVFilterContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;

public class InputPipeline {

  MMC_InputPipelineCfg_t Config;
  InputFormat InputFormat;
  CCustomPacketProcessor CustomPacketProcessor;
  Decoder Decoder;
  Scale Scaler;
  CCustomFrameProcessor CustomFrameProcessor;
  int ChannelID = 0xFFFFFFFF;
  int PipelineID = 0xFFFFFFFF;
  AVPacket Packet;
  AVPacket CustomizedPacket;
  AVFrame Frame;
  AVFrame CustomizedFrame;
  AVFrame ScaledFrame;
  FilterGraph FilterGraph;
  AVStream Stream;
  String FilterBufferSrcName;
  AVFilterContext FilterBufferSrcContext;

  EReturnCode ReadPacket(AVPacket Packet) {
    EReturnCode rc;

    rc = InputFormat.GetPacket(Packet);

    return rc;
  }

  EReturnCode SetConfig(MMC_InputPipelineCfg_t inConfig) {
    EReturnCode rc = EReturnCode.OK;

    Config = inConfig;

    rc = InputFormat.SetConfig(inConfig.InputFormatCfg);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = CustomPacketProcessor.setConfig(inConfig.CustomPacketProcessorCfg);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = Decoder.SetConfig(inConfig.DecoderCfg);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = Scaler.SetConfig(inConfig.ScaleCfg);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = CustomFrameProcessor.setConfig(inConfig.CustomFrameProcessorCfg);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    //	end_of_function:

    return rc;
  }

  EReturnCode Initialize(FilterGraph inFilterGraph, AVBufferRef HWAccelDeviceContext) {
    EReturnCode rc = EReturnCode.OK;

    Packet = avcodec.av_packet_alloc();
    CustomizedPacket = avcodec.av_packet_alloc();
    Frame = avutil.av_frame_alloc();
    CustomizedFrame = avutil.av_frame_alloc();
    ScaledFrame = avutil.av_frame_alloc();

    FilterGraph = inFilterGraph;

    rc = InputFormat.Initialize();
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    Stream = InputFormat.GetStream();

    rc = CustomPacketProcessor.Initialize();
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = Decoder.Initialize(Stream, HWAccelDeviceContext);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = Scaler.Initialize();
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    rc = CustomFrameProcessor.Initialize();
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    FilterBufferSrcName = String.format("src%d", PipelineID);

    rc =
        FilterGraph.CreateBufferSrc(
            FilterBufferSrcName, Config.FilterBufferSrcArgs, FilterBufferSrcContext);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			//goto end_of_function;
    }

    end_of_function:
    return rc;
  }

  EReturnCode Restart() {
    EReturnCode rc = EReturnCode.OK;

    if (Config == null) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.State) {
        Decoder.Reset();

        rc = InputFormat.Restart();
        if (rc != EReturnCode.OK) {
          //					//goto end_of_function;
        }
      }
    }

    //	end_of_function:

    return rc;
  }

  EReturnCode ReadFrame() {
    EReturnCode rc = EReturnCode.OK;

    if (Config == null) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.State) {
        rc = InputFormat.GetPacket(Packet);
        if (rc != EReturnCode.OK) {
          /* TODO */
          // goto end_of_function;
        }

        rc = CustomPacketProcessor.ProcessPacket(Packet, CustomizedPacket);
        if (rc != EReturnCode.OK) {
          /* TODO */
          // goto end_of_function;
        }

        rc = Decoder.SendPacket(CustomizedPacket);
        if (rc != EReturnCode.OK) {
          /* TODO */
          // goto end_of_function;
        }

        rc = Decoder.GetNextFrame(Frame);
        while ((EReturnCode.OK == rc)
            || (EReturnCode.OK_FRAME_SKIPPED == rc)
            || (EReturnCode.OK_CONGESTED == rc)) {
          if ((EReturnCode.OK == rc) || (EReturnCode.OK_CONGESTED == rc)) {
            if (EReturnCode.OK_CONGESTED == rc) {
              InputFormat.SetDropNonKeyPackets(true);
            } else {
              InputFormat.SetDropNonKeyPackets(false);
            }

            rc = Scaler.ScaleFrame(Frame, ScaledFrame);
            if (rc != EReturnCode.OK) {
              /* TODO */
              //							//goto end_of_function;
            }

            /* Queue the message into the FilterGraph */
            FilterGraph.AddFrame(FilterBufferSrcContext, ScaledFrame);
          }

          rc = Decoder.GetNextFrame(Frame);
        }
      } else {

      }
    }

    if ((EReturnCode.OK_QUEUE_EMPTY == rc) || (EReturnCode.OK_CONGESTED == rc)) {
      rc = EReturnCode.OK;
    }

    return rc;
  }

  void SetPipelineID(int inPipelineID) {
    PipelineID = inPipelineID;

    InputFormat.SetPipelineID(PipelineID);
    CustomPacketProcessor.SetPipelineID(PipelineID);
    Decoder.SetPipelineID(PipelineID);
    Scaler.SetPipelineID(PipelineID);
    CustomFrameProcessor.SetPipelineID(PipelineID);
  }

  int GetPipelineID() {
    return PipelineID;
  }

  void SetChannelID(int inChannelID) {
    ChannelID = inChannelID;

    InputFormat.SetChannelID(ChannelID);
    CustomPacketProcessor.SetChannelID(ChannelID);
    Decoder.SetChannelID(ChannelID);
    Scaler.SetChannelID(ChannelID);
    CustomFrameProcessor.SetChannelID(ChannelID);
  }

  int GetChannelD() {
    return ChannelID;
  }

  AVStream GetStream() {
    return Stream;
  }
}
