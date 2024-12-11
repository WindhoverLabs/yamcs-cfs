package com.windhoverlabs.com.video;

import static org.bytedeco.ffmpeg.global.avutil.*;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.global.avcodec;

public class Pipeline extends ComponentBase {

  private MMC_PipelineCfg config;
  private AVBufferRef hwAccelDeviceContext;
  //  TODO:It might make more sense to change these arrays to ArrayList(s)
  private InputPipeline[] inputPipelines = new InputPipeline[MAX_INPUT_PIPELINES];
  private FilterGraph filterGraph = new FilterGraph();
  private OutputPipeline[] outputPipelines = new OutputPipeline[MAX_OUTPUT_PIPELINES];

  private int ChannelID = 0xFFFFFFFF;
  private TState State;
  private AVFrame Frame;
  private AVPacket Packet;

  public Pipeline() {
    // TODO: Auto-generated constructor stub
  }

  @Override
  protected void finalize() throws Throwable {
    // TODO: Auto-generated destructor stub
    super.finalize();
  }

  public EReturnCode setConfig(MMC_PipelineCfg config) {
    EReturnCode rc = EReturnCode.OK;

    this.config = config;

    for (int i = 0; i < MAX_INPUT_PIPELINES; i++) {
      inputPipelines[i] = new InputPipeline();
      inputPipelines[i].SetConfig(config.InputPipelineCfg.get(i));
    }

    filterGraph.SetConfig(config.FilterGraphCfg);

    for (int i = 0; i < MAX_OUTPUT_PIPELINES; i++) {
      outputPipelines[i] = new OutputPipeline();
      outputPipelines[i].setConfig(config.OutputPipelineCfg.get(i));
    }

    return rc;
  }

  public void initializeHWAccel() {
    int avRC =
        av_hwdevice_ctx_create(
            hwAccelDeviceContext,
            config.HWAccelDeviceCfg.deviceType,
            config.HWAccelDeviceCfg.deviceID,
            null,
            0);

    if (avRC < 0) {
      ReportAVError(
          "CPipeline::InitializeHardware",
          "av_hwdevice_ctx_create",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
    }
  }

  public EReturnCode Initialize() {
    EReturnCode rc = EReturnCode.OK;

    super.Initialize();

    Frame = av_frame_alloc();
    Packet = avcodec.av_packet_alloc();

    // First set the pipeline IDs, so error reporting will be correct.
    for (int i = 0; i < MAX_INPUT_PIPELINES; i++) {
      inputPipelines[i].SetPipelineID(i);
    }

    for (int i = 0; i < MAX_OUTPUT_PIPELINES; i++) {
      outputPipelines[i].SetPipelineID(i);
    }

    initializeHWAccel();

    if (!config.PacketLevelRemux) {
      FilterGraph.Initialize();
    }

    // Now initialize the pipelines.
    for (int i = 0; i < MAX_INPUT_PIPELINES; i++) {
      inputPipelines[i].Initialize(filterGraph, hwAccelDeviceContext);
    }

    for (int i = 0; i < MAX_OUTPUT_PIPELINES; i++) {
      AVStream inputStream = inputPipelines[i].GetStream();

      outputPipelines[i].Initialize(
          config.PacketLevelRemux, filterGraph, hwAccelDeviceContext, inputStream);
    }

    if (!config.PacketLevelRemux) {
      FilterGraph.Start();
      if (rc != EReturnCode.OK) {
        rc = EReturnCode.FAILED_INITIALIZATION;
        return rc;
      }
    }

    State = TState.ACTIVE;

    return rc;
  }

  public TState GetState() {
    return State;
  }

  EReturnCode Restart(int PipelineID) {
    EReturnCode rc = EReturnCode.OK;

    rc = inputPipelines[PipelineID].Restart();

    if (EReturnCode.OK == rc) {
      rc = outputPipelines[PipelineID].restart();
    }

    return rc;
  }

  EReturnCode Execute() {
    EReturnCode rc = EReturnCode.OK;

    if (TState.ACTIVE == State) {
      for (int i = 0; i < MAX_INPUT_PIPELINES; ++i) {
        if (config.PacketLevelRemux) {

          rc = inputPipelines[i].ReadPacket(Packet);
          if (EReturnCode.OK_EOF == rc) {
            Restart(i);
            continue;
          }

          if (rc != EReturnCode.OK) {
            /* TODO */
            State = TState.INACTIVE;
            //					goto end_of_function;
          }

          rc = outputPipelines[i].SendPacket(Packet);
          if (rc != EReturnCode.OK) {
            /* TODO */
            State = TState.INACTIVE;
            //					goto end_of_function;
          }
        } else {
          rc = inputPipelines[i].ReadFrame();
          if (EReturnCode.OK_EOF == rc) {
            Restart(i);
          } else if (rc != EReturnCode.OK) {
            /* TODO */
            State = TState.INACTIVE;
            //					goto end_of_function;
          }

          rc = outputPipelines[i].SendFrame();
          while (rc == EReturnCode.OK_QUEUE_EMPTY) {
            rc = outputPipelines[i].SendFrame();
          }
          if (rc != EReturnCode.OK) {
            /* TODO */
            State = TState.INACTIVE;
            //					goto end_of_function;
          }
        }

        //			filterGraph.SetFrame(i, inputPipelines[i].GetFrame());
      }

      //		filterGraph.Execute();
      //
      //		for(int i = 0; i < MAX_OUTPUT_PIPELINES; ++i)
      //		{
      //			OutputPipeline[i].SetFrame(FilterGraph.GetFrame(i));
      //			OutputPipeline[i].Execute();
      //		}
    }

    // end_of_function:

    return rc;
  }

  // Constants (replace with actual values)
  private static final int MAX_INPUT_PIPELINES = 4;
  private static final int MAX_OUTPUT_PIPELINES = 4;
}
