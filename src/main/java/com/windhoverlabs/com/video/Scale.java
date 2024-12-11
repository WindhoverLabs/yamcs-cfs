package com.windhoverlabs.com.video;

import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_EntryState;
import com.windhoverlabs.com.video.MMC_PipelineCfg.MMC_ScaleCfg_t;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.swscale.SwsFilter;

public class Scale extends ComponentBase {

  MMC_ScaleCfg_t Config;
  SwsContext Context;

  int LastWidth = 0;
  int LastHeight = 0;
  int LastFormat = 0;

  public Scale() {
    // TODO Auto-generated constructor stub

  }

  public EReturnCode SetConfig(MMC_ScaleCfg_t inConfig) {
    EReturnCode rc = EReturnCode.OK;

    Config = inConfig;

    return rc;
  }

  public EReturnCode Initialize() {
    EReturnCode rc = EReturnCode.OK;

    super.Initialize();

    //		TODO:Add CustomFilter classes
    //		rc = InitCustom();

    /* There really is no initialization here.  We create the context at runtime
     * because the function to create it requires the source image resolution
     * and format.  To allow these to change up stream dynamically, we create
     * the context at runtime.
     */

    return rc;
  }

  EReturnCode IsHardwareAccelerated(AVFrame Frame, boolean Accelerated) {
    EReturnCode rc = EReturnCode.OK;

    if (Frame == null || Frame.isNull()) {
      ReportError(
          "CScale::IsHardwareAccelerated",
          "Frame",
          Thread.currentThread().getStackTrace()[0].getLineNumber(),
          "Frame* was null.");
      rc = EReturnCode.INVALID_PARAM;
    }
    //	    if (Accelerated == null)
    //	    {
    //			ReportError("CScale::IsHardwareAccelerated", "Accelerated",
    // Thread.currentThread().getStackTrace()[0].getLineNumber(), "Accelerated* was null.");
    //	        rc = EReturnCode.INVALID_PARAM;
    //	    }

    Accelerated = false;

    /* Check for hardware frames context */
    if (Frame.hw_frames_ctx() != null) {
      System.out.println("Frame is hardware accelerated.\n");
      Accelerated = true;
    }

    return rc;
  }

  public EReturnCode ScaleFrame(AVFrame inFrame, AVFrame outFrame) {
    EReturnCode rc = EReturnCode.OK;
    boolean accelerated = false;
    AVFrame cpuFrame = null;
    int avRC = 0;

    if (IsConfigSet() == false) {
      /* TODO */
    } else {
      if (MMC_EntryState.ACTIVE == Config.State) {
        int swsRC = 0;

        /* Check to see if this frame is inside hardware (hardware
         * accelerated, rather than in CPU memory.
         */
        IsHardwareAccelerated(inFrame, accelerated);
        if (accelerated) {
          cpuFrame = avutil.av_frame_alloc();

          /* Allocate CPU frame with the same properties */
          cpuFrame.format(avutil.AV_PIX_FMT_YUV420P);
          cpuFrame.width(inFrame.width());
          cpuFrame.height(inFrame.height());
          avRC = avutil.av_frame_get_buffer(cpuFrame, 32);
          if (avRC < 0) {
            ReportError(
                "CScale::Scale",
                "av_frame_get_buffer",
                Thread.currentThread().getStackTrace()[0].getLineNumber(),
                "Failed to get CPU frame buffer.");
            rc = EReturnCode.FAILED_EXECUTE;
          }

          /* Transfer data from GPU to CPU */
          avRC = avutil.av_hwframe_transfer_data(cpuFrame, inFrame, 0);
          if (avRC < 0) {
            ReportError(
                "CScale::Scale",
                "av_hwframe_transfer_data",
                Thread.currentThread().getStackTrace()[0].getLineNumber(),
                "Failed to transfer frame from GPU to CPU.");
            rc = EReturnCode.FAILED_EXECUTE;
          }
        } else {
          cpuFrame = inFrame;
        }

        /* Check to see if the resolution or format has changed since the last
         * time.
         */
        if ((LastWidth != cpuFrame.width())
            || (LastHeight != cpuFrame.height())
            || (LastFormat != cpuFrame.format())) {
          /* It has changed. Create a new context with the new resolution
           * and format.  But before we create a new one, we should
           * free up the old one, if an old one exists. If the resolution
           * or format changed at runtime, we might have already created
           * a Context.  Check if the Context is not null.
           */
          if (Context != null && !Context.isNull()) {
            /* It is not null.  We should free the context before
             * we create a new one.
             */
            swscale.sws_freeContext(Context);
          }

          /* Zero out the pointer just to be sure. */
          Context.setNull();
          ;
        }

        /* Check if the context is null.  If it is, we need to create a new
         * context.
         */
        if (Context.isNull()) {
          /* It is null.  We are going to create a new context with the
           * resolution and format of the frame we just received, and the
           * "destination" resolution and format as defined by the configuration
           * table.
           *
           * But first, try to get the custom filter.
           */
          SwsFilter srcFilter = null;
          SwsFilter dstFilter = null;

          //					TODO:Need to provide a way to add custom filter classes in Java
          //					rc = GetCustomFilter(Config.SrcFilterIndex, srcFilter);
          //					if(rc != OK)
          //					{
          //						ReportError("CScale::Scale", "GetCustomFilter",
          // Thread.currentThread().getStackTrace()[0].getLineNumber(), "Failed to get custom src
          // filter. Using default.");
          //						srcFilter = null;
          //					}
          //
          //					rc = GetCustomFilter(Config.DestFilterIndex, dstFilter);
          //					if(rc != EReturnCode.OK)
          //					{
          //						ReportError("CScale::Scale", "GetCustomFilter",
          // Thread.currentThread().getStackTrace()[0].getLineNumber(), "Failed to get custom dst
          // filter. Using default.");
          //						dstFilter = null;
          //					}

          Context =
              swscale.sws_getContext(
                  cpuFrame.width(),
                  cpuFrame.height(),
                  cpuFrame.format(),
                  Config.Width,
                  Config.Height,
                  Config.PixelFormat,
                  Config.Flags,
                  srcFilter,
                  dstFilter,
                  (double[]) null);
          if (Context == null) {
            ReportError(
                "CScale::Scale",
                "sws_getContext",
                Thread.currentThread().getStackTrace()[0].getLineNumber(),
                "Failed to get scaler context.");
            rc = EReturnCode.FAILED_EXECUTE;
          }
        }

        /* Save off the resolution and format of this image so we can check
         * if it changed on the next invocation.
         */
        LastWidth = cpuFrame.width();
        LastHeight = cpuFrame.height();
        LastFormat = cpuFrame.format();

        /* Since we are creating a new frame, copy the properties from the original
         * frame to start with. */
        avRC = avutil.av_frame_copy_props(outFrame, cpuFrame);
        if (avRC < 0) {
          ReportAVError(
              "CScale::Scale",
              "av_frame_copy_props",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
        }

        /* Now change the properties that we know we are about to change. */
        outFrame.width(Config.Width);
        outFrame.height(Config.Height);
        outFrame.format(Config.PixelFormat);

        /* For now, free the data and allocate a new space.  In the future
         * we can change this to only allocate when the resolution or format
         * of the new frame changes.  Technically, we don't have to free it on
         * the first invocation since it wouldn't have been allocated.  We
         * are going to assume that calling it on unallocated space will have
         * no effect.  We can change this later.
         */
        avutil.av_freep(outFrame.data());
        avRC =
            avutil.av_image_alloc(
                outFrame.data(),
                outFrame.linesize(),
                Config.Width,
                Config.Height,
                Config.PixelFormat,
                32);
        if (avRC < 0) {
          ReportAVError(
              "CScale::Scale",
              "av_image_alloc",
              avRC,
              Thread.currentThread().getStackTrace()[0].getLineNumber());
          rc = EReturnCode.FAILED_EXECUTE;
        }

        /* Now we can finally call the function to rescale the frame. */
        swsRC =
            swscale.sws_scale(
                Context,
                cpuFrame.data(),
                cpuFrame.linesize(),
                0,
                cpuFrame.height(),
                outFrame.data(),
                outFrame.linesize());
        if (swsRC < 0) {
          ReportError(
              "CScale::Scale",
              "sws_getContext",
              Thread.currentThread().getStackTrace()[0].getLineNumber(),
              "Failed to get scaler context.");
          rc = EReturnCode.FAILED_EXECUTE;
        }
      } else {
        /* This component is not active so this is just a pass thru.  Unreferencing
         * the outFrame releases the count on the frame that the outFrame was
         * referencing before. Then we reference the inFrame to the outFrame.  This
         * basically copies some of the inFrame to the outFrame.  Everything except
         * the pointers to things like buffers.  Those pointers are just updated to
         * point to the actual buffers owned by inFrame.
         */
        UnreferenceFrame(outFrame);
        ReferenceFrame(outFrame, inFrame);
      }
    }

    if (accelerated) {
      if (cpuFrame != null) {
        avutil.av_frame_free(cpuFrame);
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
}
