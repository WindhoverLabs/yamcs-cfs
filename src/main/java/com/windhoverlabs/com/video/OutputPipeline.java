package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.PipelineCfg.MMC_EntryState;
import com.windhoverlabs.com.video.PipelineCfg.MMC_OutputPipelineCfg_t;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avfilter.AVFilterContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;

public class OutputPipeline {

  private Encoder encoder = new Encoder();
  private OutputFormat outputFormat = new OutputFormat();
  private Scale scaler = new Scale();
  private CustomFrameProcessor customFrameProcessor = new CustomFrameProcessor();

  MMC_OutputPipelineCfg_t config;
  Scale Scaler;
  CCustomFrameProcessor
      CustomFrameProcessor; // We might be able to use the YAMCS processor API instead here
  CCustomPacketProcessor CustomPacketProcessor;
  OutputFormat OutputFormat;
  Encoder Encoder;
  int ChannelID = 0xFFFFFFFF;
  int PipelineID = 0xFFFFFFFF;
  AVPacket Packet;
  AVPacket CustomizedPacket;
  AVFrame Frame;
  AVFrame CustomizedFrame;
  AVFrame ScaledFrame;
  FilterGraph FilterGraph;
  AVStream OutputStream;
  AVStream InputStream;
  String FilterBufferSinkName;
  AVFilterContext FilterBufferSinkContext;

  public OutputPipeline() {
    // TODO: Auto-generated constructor stub
  }

  @Override
  protected void finalize() throws Throwable {
    // TODO: Auto-generated destructor stub
    super.finalize();
  }

  public EReturnCode restart() {
    EReturnCode rc = EReturnCode.OK;

    if (config == null) {
      // TODO: Handle null configuration case
    } else {
      if (config.State == PipelineCfg.MMC_EntryState.ACTIVE) {
        encoder.reset();

        rc = outputFormat.Restart();
        if (rc != EReturnCode.OK) {
          return rc;
        }
      }
    }

    return rc;
  }

  public EReturnCode setConfig(MMC_OutputPipelineCfg_t inConfig) {
    EReturnCode rc = EReturnCode.OK;

    this.config = inConfig;

    rc = scaler.SetConfig(inConfig.ScaleCfg);
    if (rc != EReturnCode.OK) {
      // TODO: Handle scaler configuration failure
      return rc;
    }

    rc = customFrameProcessor.setConfig(inConfig.CustomFrameProcessorCfg);
    if (rc != EReturnCode.OK) {
      // TODO: Handle custom frame processor configuration failure
      return rc;
    }

    rc = encoder.setConfig(inConfig.EncoderCfg);
    if (rc != EReturnCode.OK) {
      // TODO: Handle encoder configuration failure
      return rc;
    }

    rc = outputFormat.SetConfig(inConfig.OutputFormatCfg);
    if (rc != EReturnCode.OK) {
      // TODO: Handle output format configuration failure
      return rc;
    }

    return rc;
  }

  EReturnCode Initialize(
      boolean PacketLevelRemux,
      FilterGraph inFilterGraph,
      AVBufferRef HWAccelDeviceContext,
      AVStream inInputStream) {
    EReturnCode rc = EReturnCode.OK;

    Packet = avcodec.av_packet_alloc();
    CustomizedPacket = avcodec.av_packet_alloc();
    Frame = avutil.av_frame_alloc();
    ScaledFrame = avutil.av_frame_alloc();
    CustomizedFrame = avutil.av_frame_alloc();

    InputStream = inInputStream;

    /* We initialize this before the Encoder so we can get the Codec
     * parameters.
     */
    rc = OutputFormat.Initialize(InputStream);
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			goto end_of_function;
    }

    OutputStream = OutputFormat.GetStream();

    //		TODO:Need to find a way to add CustomPacketProcessor classes to Java
    //		rc = CustomPacketProcessor.Initialize();
    //		if(rc != OK)
    //		{
    //			/* TODO */
    ////			goto end_of_function;
    //		}

    if (PacketLevelRemux) {
      AVStream outputStream = OutputFormat.GetStream();
      avcodec.avcodec_parameters_copy(outputStream.codecpar(), InputStream.codecpar());
    } else {
      //			TODO:Add Filter Graph
      //			FilterGraph = inFilterGraph;
      //
      //			snprintf(FilterBufferSinkName, sizeof(FilterBufferSinkName), "sink%d", PipelineID);
      //			rc = FilterGraph.CreateBufferSink(FilterBufferSinkName, Config.FilterBufferSinkArgs,
      // &FilterBufferSinkContext);
      if (rc != EReturnCode.OK) {
        /* TODO */
        //				goto end_of_function;
      }

      rc = Scaler.Initialize();
      if (rc != EReturnCode.OK) {
        /* TODO */
        //				goto end_of_function;
      }

      //			TODO:Add CustomFrameProcessor
      //			rc = CustomFrameProcessor.Initialize();
      //			if(rc != EReturnCode.OK)
      //			{
      //				/* TODO */
      ////				goto end_of_function;
      //			}

      rc = Encoder.initialize(OutputStream, HWAccelDeviceContext);
      if (rc != EReturnCode.OK) {
        /* TODO */
        //				goto end_of_function;
      }

      rc = Encoder.start();
      if (rc != EReturnCode.OK) {
        /* TODO */
        //				goto end_of_function;
      }
    }

    rc = OutputFormat.Start();
    if (rc != EReturnCode.OK) {
      /* TODO */
      //			goto end_of_function;
    }

    return rc;
  }

  void SetPipelineID(int inPipelineID) {
    PipelineID = inPipelineID;

    Scaler.SetPipelineID(PipelineID);
    //  	TODO:Add CustomFrameProcessor
    //  	CustomFrameProcessor.SetPipelineID(PipelineID);
    Encoder.SetPipelineID(PipelineID);
    //  	CustomPacketProcessor.SetPipelineID(PipelineID);
    OutputFormat.SetPipelineID(PipelineID);
  }

  EReturnCode SendFrame() {
    EReturnCode rc = EReturnCode.OK;

    if (config == null) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == config.State) {

        //			rc = FilterGraph.GetFrame(FilterBufferSinkContext, Frame);
        //			if(rc != OK)
        //			{
        //				/* TODO */
        ////				goto end_of_function;
        //			}
        while (EReturnCode.OK == rc) {
          rc = Scaler.ScaleFrame(Frame, ScaledFrame);
          if (rc != EReturnCode.OK) {
            /* TODO */
            //					goto end_of_function;
          }
          // TODO:Add CustomFrameProcessor
          //				rc = CustomFrameProcessor.ProcessFrame(ScaledFrame, CustomizedFrame);
          if (rc != EReturnCode.OK) {
            /* TODO */
            //					goto end_of_function;
          }

          rc = Encoder.sendFrame(CustomizedFrame);
          if (rc != EReturnCode.OK) {
            /* TODO */
            //					goto end_of_function;
          }

          rc = Encoder.getNextPacket(Packet);
          while (rc == EReturnCode.OK) {
            //					TODO:Add CustomPacketProcessor
            //					rc = CustomPacketProcessor.ProcessPacket(Packet, CustomizedPacket);
            //					if(rc != EReturnCode.OK)
            //					{
            //						/* TODO */
            ////						goto end_of_function;
            //					}

            rc = OutputFormat.SendPacket(CustomizedPacket);
            if (rc != EReturnCode.OK) {
              /* TODO */
              //						goto end_of_function;
            }

            rc = Encoder.getNextPacket(Packet);
          }

          rc = FilterGraph.getFrame(FilterBufferSinkContext, Frame);
        }
      }
    }

    // end_of_function:

    return rc;
  }

  EReturnCode SendPacket(AVPacket Packet) {
    return OutputFormat.SendPacket(Packet);
  }

  // Placeholder for CustomFrameProcessor class
  static class CustomFrameProcessor {
    public EReturnCode setConfig(
        PipelineCfg.MMC_CustomFrameProcessorCfg_t customFrameProcessorCfg) {
      // TODO: Set custom frame processor configuration logic
      return EReturnCode.OK;
    }
  }
}
