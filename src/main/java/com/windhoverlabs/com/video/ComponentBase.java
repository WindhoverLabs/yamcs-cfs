package com.windhoverlabs.com.video;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVClass;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVOption;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;

public class ComponentBase {

  final String MMC_TRUNCATION_TEXT = "...";
  final String MMC_CONTINUATION_TEXT = ", ";

  int ChannelID = 0xFFFFFFFF;
  int PipelineID = 0xFFFFFFFF;

  AVPacket PassThruPacket;
  AVFrame[] PassThruFrame = new AVFrame[VideoConstants.MAX_INPUT_PIPELINES];

  public ComponentBase() {
    ChannelID = 0;
    PipelineID = 0;
    PassThruPacket = null;
    for (int i = 0; i < VideoConstants.MAX_INPUT_PIPELINES; ++i) {
      PassThruFrame[i] = null;
    }
  }

  void SetChannelID(int inChannelID) {
    ChannelID = inChannelID;
  }

  int GetChannelID() {
    return ChannelID;
  }

  void SetPipelineID(int inPipelineID) {
    PipelineID = inPipelineID;
  }

  int GetPipelineID() {
    return PipelineID;
  }

  void AppendToBuffer(String Buffer, int BufferSize, String TruncationMarker, String... Format) {
    // TODO:This function is probably not necessary in Java...
    // TODO:Make "Buffer" a "String[]/ArrayList<String>", since that would be the "buffer" in Java.
    //    va_list args;
    //    va_start(args, Format);
    //    int_t truncation_marker_length = strlen(TruncationMarker);
    //
    //    /* Find the current length of the buffer content */
    //    int_t current_length = strnlen(Buffer, BufferSize);
    //
    //    /* Calculate remaining space in the buffer (leave room for null
    //     * terminator) */
    //    int_t remaining_size = BufferSize - current_length;
    //
    //    /* Append formatted string to the buffer using vsnprintf */
    //	int written = vsnprintf(Buffer + current_length, remaining_size, Format, args);
    //
    //	/* Check if truncation occurred. */
    //	if (written < 0 || (int_t)written >= remaining_size)
    //	{
    //		/* Replace last character(s) with the truncation marker if
    //		 * truncated. */
    //		memcpy(
    //			&Buffer[BufferSize - truncation_marker_length],
    //			TruncationMarker,
    //			truncation_marker_length );
    //	}
    //
    //    va_end(args);
  }

  void ReportAVError(String ComponentName, String FunctionName, int RC, int LineNum) {
    byte[] err_buf = new byte[128];
    avutil.av_strerror(RC, err_buf, err_buf.length);
    System.err.println(
        String.format(
            "%u:%u:%s %s failed. err=%s  line=%u\n",
            PipelineID, ChannelID, ComponentName, FunctionName, err_buf, LineNum));
  }

  void ReportMMCError(
      String ComponentName, String FunctionName, EReturnCode RC, int LineNum, String ErrText) {
    System.err.println(
        String.format(
            "%u:%u:%s %s  %s  err=%u  line=%i\n",
            PipelineID, ChannelID, ComponentName, FunctionName, ErrText, RC, LineNum));
  }

  void ReportError(String ComponentName, String FunctionName, int LineNum, String ErrText) {
    System.err.println(
        String.format(
            "%u:%u:%s:%s  %s  line=%u\n",
            PipelineID, ChannelID, ComponentName, FunctionName, ErrText, LineNum));
  }

  EReturnCode ConvertToAVDictionary(
      HashMap<String, String> Params,
      int ParamCount,
      AVDictionary Options,
      AVClass AV_Class,
      String InvalidOptionsBuffer,
      int InvalidOptionsSize) {
    EReturnCode mmcRC = EReturnCode.OK;

    Options.setNull();
    ;

    mmcRC =
        AppendToAVDictionary(
            Params, ParamCount, Options, AV_Class, InvalidOptionsBuffer, InvalidOptionsSize);

    return mmcRC;
  }

  EReturnCode AppendToAVDictionary(
      HashMap<String, String> Params,
      int ParamCount,
      AVDictionary Options,
      AVClass AV_Class,
      String InvalidOptionsBuffer,
      int InvalidOptionsSize) {
    EReturnCode mmcRC = EReturnCode.OK;

    for (Map.Entry<String, String> e : Params.entrySet()) {
      EReturnCode result = EReturnCode.OK;

      if (AV_Class != null && !AV_Class.isNull()) {
        result =
            ValidateOption(
                e.getKey(), e.getValue(), AV_Class, InvalidOptionsBuffer, InvalidOptionsSize);
      }

      if (result != EReturnCode.OK) {
        AppendToBuffer(
            InvalidOptionsBuffer, InvalidOptionsSize, MMC_TRUNCATION_TEXT, MMC_CONTINUATION_TEXT);
        mmcRC = result;
      }
    }

    TrimTrailingContinuation(MMC_CONTINUATION_TEXT, InvalidOptionsBuffer, InvalidOptionsSize);

    /* Did we find any errors? */
    if (EReturnCode.OK == mmcRC) {
      /* No we did not.  Go ahead and create the dictionary. */
      for (Map.Entry<String, String> e : Params.entrySet()) {

        avutil.av_dict_set(Options, e.getKey(), e.getValue(), 0);
      }
    }

    return mmcRC;
  }

  EReturnCode SetOptions(
      HashMap<String, String> Params,
      BytePointer OptionsAddress,
      AVClass AV_Class,
      String InvalidOptionsBuffer,
      int InvalidOptionsSize) {
    EReturnCode mmcRC = EReturnCode.OK;

    int ParamCount = Params.size();

    for (Map.Entry<String, String> e : Params.entrySet()) {
      EReturnCode result;

      result =
          ValidateOption(
              e.getKey(), e.getValue(), AV_Class, InvalidOptionsBuffer, InvalidOptionsSize);

      if (result != EReturnCode.OK) {
        AppendToBuffer(InvalidOptionsBuffer, InvalidOptionsSize, MMC_TRUNCATION_TEXT, ",");
        mmcRC = result;
      }
    }

    TrimTrailingContinuation(MMC_CONTINUATION_TEXT, InvalidOptionsBuffer, InvalidOptionsSize);

    /* Did we encounter any errors? */
    if (EReturnCode.OK == mmcRC) {
      /* No we did not.  Go ahead and set the parameters. */
      for (Map.Entry<String, String> e : Params.entrySet()) {
        int ret;

        ret = avutil.av_opt_set(OptionsAddress, e.getKey(), e.getValue(), 0);
        if (ret < 0) {
          byte[] err_buf = new byte[128];
          avutil.av_strerror(ret, err_buf, err_buf.length);
          System.err.println(
              String.format("Could not set '%s' option: %s\n", e.getKey(), new String(err_buf)));
        }
      }
    }

    return mmcRC;
  }

  void PrintAVDictionary(AVDictionary Options) {
    AVDictionaryEntry entry = null;

    System.out.println("AVDictionary contents:\n");

    entry = avutil.av_dict_get(Options, "", entry, avutil.AV_DICT_IGNORE_SUFFIX);

    while (entry != null && !entry.isNull()) {
      System.out.println(String.format("%s : %s\n", entry.key(), entry.value()));
      entry = avutil.av_dict_get(Options, "", entry, avutil.AV_DICT_IGNORE_SUFFIX);
    }
  }

  EReturnCode GetUnusedOptions(
      AVDictionary Options, String InvalidOptionsBuffer, int InvalidOptionsSize) {
    EReturnCode mmcRC = EReturnCode.OK;

    if (Options != null && Options.isNull()) {
      AVDictionaryEntry entry = null;

      entry = avutil.av_dict_get(Options, "", entry, avutil.AV_DICT_IGNORE_SUFFIX);

      while (entry != null && !entry.isNull()) {
        AppendToBuffer(
            InvalidOptionsBuffer,
            InvalidOptionsSize,
            MMC_TRUNCATION_TEXT,
            " {%s,%s}",
            entry.key().getString(),
            entry.value().getString());

        mmcRC = EReturnCode.UNUSED_PARAM;

        entry = avutil.av_dict_get(Options, "", entry, avutil.AV_DICT_IGNORE_SUFFIX);
      }

      TrimTrailingContinuation(MMC_CONTINUATION_TEXT, InvalidOptionsBuffer, InvalidOptionsSize);
    }

    return mmcRC;
  }

  EReturnCode ValidateOption(
      String Key,
      String Value,
      AVClass AV_Class,
      String InvalidOptionsBuffer,
      int InvalidOptionsSize) {
    EReturnCode rc = EReturnCode.OK;

    /* Try to find the option */
    AVOption opt = avutil.av_opt_find(AV_Class, Key, null, 0, avutil.AV_OPT_SEARCH_CHILDREN);

    /* Did we find the option? */
    if (opt != null) {
      /* No.  Set the return code to indicate there is at least one
       * invalid parameter, and add the parameter name to the Invalid
       * Options Buffer.
       */
      AppendToBuffer(InvalidOptionsBuffer, InvalidOptionsSize, MMC_TRUNCATION_TEXT, "%s(NF)", Key);
      rc = EReturnCode.UNUSED_PARAM;
    } else {
      /* Yes.  Now lets try to validate the value as best we can. */
      switch (opt.type()) {
        case avutil.AV_OPT_TYPE_INT:
        case avutil.AV_OPT_TYPE_INT64:
        case avutil.AV_OPT_TYPE_DURATION:
          {
            /* Get the value and check for minimum and maximum. */
            long iValue = Long.parseLong(Value);
            if (iValue < opt.min() || iValue > opt.max()) {
              /* The value is out of range. Set the return code to
               * indicate there is at least one invalid parameter, and
               * add the parameter name to the Invalid Options
               * Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s(OOR %f<=%s<=%f)",
                  Key,
                  Double.toString(opt.min()),
                  Value,
                  Double.toString(opt.max()));
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_DOUBLE:
        case avutil.AV_OPT_TYPE_FLOAT:
          {
            /* Get the value and check for minimum and maximum. */
            double dValue = Double.parseDouble(Value);
            if (dValue < opt.min() || dValue > opt.max()) {
              /* The value is out of range. Set the return code to
               * indicate there is at least one invalid parameter, and
               * add the parameter name to the Invalid Options
               * Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s(OOR %f<=%s<=%f)",
                  Key,
                  Double.toString(opt.min()),
                  Value,
                  Double.toString(opt.max()));
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_BOOL:
          {
            /* Check that the value is in the correct boolean form. */
            if ("0".equals(Value)
                || "1".equals(Value)
                || "false".equals(Value)
                || "true".equals(Value)
                || "False".equals(Value)
                || "True".equals(Value)
                || "FALSE".equals(Value)
                || "TRUE".equals(Value)) {
              /* This is a valid entry. Do nothing. */
            } else {
              /* The value is not in the correct boolean format. Set
               * the return code to indicate there is at least one
               * invalid parameter, and add the parameter name to the
               * Invalid Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (IB %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_RATIONAL:
          {
            AVRational rat = null;

            /* Check that the value is valid. */
            if (avutil.av_parse_ratio(rat, Value, Integer.MAX_VALUE, 0, null) < 0) {
              /* The value is not in a rational number. Set the return
               * code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (NRN %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_IMAGE_SIZE:
          {
            IntBuffer widthBuffer = IntBuffer.allocate(1);
            IntBuffer heightBuffer = IntBuffer.allocate(1);

            /* Check that the value is valid. */
            if (avutil.av_parse_video_size(widthBuffer, heightBuffer, Value) < 0) {
              /* The value is not in a valid image size. Set the
               * return code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (IIS %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_PIXEL_FMT:
          {
            /* Check that the value is valid. */
            int fmt = avutil.av_get_pix_fmt(Value);
            if (fmt == avutil.AV_PIX_FMT_NONE) {
              /* The value is not in a valid pixel format. Set the
               * return code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (IPF %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_SAMPLE_FMT:
          {
            /* Check that the value is valid. */
            int fmt = avutil.av_get_sample_fmt(Value);
            if (fmt == avutil.AV_SAMPLE_FMT_NONE) {
              /* The value is not in a valid sample format. Set the
               * return code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (ISF %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_VIDEO_RATE:
          {
            AVRational rate = null;
            /* Check that the value is valid. */
            if (avutil.av_parse_video_rate(rate, Value) < 0) {
              /* The value is not in a valid video rate. Set the
               * return code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (IVR %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

        case avutil.AV_OPT_TYPE_COLOR:
          {
            byte[] rgba = new byte[4];

            /* Check that the value is valid. */
            if (avutil.av_parse_color(rgba, Value, -1, null) < 0) {
              /* The value is not in a valid color. Set the return
               * code to indicate there is at least one invalid
               * parameter, and add the parameter name to the Invalid
               * Options Buffer. */
              AppendToBuffer(
                  InvalidOptionsBuffer,
                  InvalidOptionsSize,
                  MMC_TRUNCATION_TEXT,
                  "%s (IC %s)",
                  Key,
                  Value);
              rc = EReturnCode.INVALID_PARAM;
            }

            break;
          }

          //			TODO:Not available in this version. I think it is the same as
          // AV_OPT_TYPE_CHANNEL_LAYOUT..
          //			case avutil.AV_OPT_TYPE_CHLAYOUT:
          //			{
          //				/* Check that the value is valid. */
          //				int64_t layout = av_get_channel_layout(Value);
          //				if (layout == 0)
          //				{
          //					/* The value is not in a valid channel layout. Set the
          //					 * return code to indicate there is at least one invalid
          //					 * parameter, and add the parameter name to the Invalid
          //					 * Options Buffer. */
          //					AppendToBuffer(
          //							InvalidOptionsBuffer,
          //							InvalidOptionsSize,
          //							MMC_TRUNCATION_TEXT,
          //							"%s (ICL %s)",
          //							Key,
          //							Value);
          //					rc = EReturnCode.INVALID_PARAM;
          //				}
          //
          //				break;
          //			}

        default:
          {
            /* This is not a data type that can be validated. Just
             * ignore. */
          }
      }
    }

    return rc;
  }

  void TrimTrailingContinuation(String ContinuationMarker, String Buffer, int BufferSize) {
    //	TODO:Probably not needed in Java
    //    /* Check if Buffer and ContinuationMarker are non-null */
    //    if (!Buffer || !ContinuationMarker)
    //    {
    //        return;
    //    }
    //
    //    /* Calculate the lengths of Buffer and ContinuationMarker. */
    //    int buffer_len = strnlen(Buffer, BufferSize);
    //    int marker_len = strlen(ContinuationMarker);
    //
    //    /* Check if the end of Buffer matches ContinuationMarker */
    //	if (strncmp(Buffer + buffer_len - marker_len, ContinuationMarker, marker_len) == 0)
    //	{
    //		/* Trim the ContinuationMarker by setting the end of the Buffer to null terminator */
    //		Buffer[buffer_len - marker_len] = '\0';
    //	}
  }

  boolean IsPacketReferenced(AVPacket inPacket) {
    boolean rc = true;

    if (inPacket == null || inPacket.data().isNull()) {
      rc = false;
    } else if (inPacket.size() <= 0) {
      rc = false;
    }

    return rc;
  }

  boolean IsFrameReferenced(AVFrame inFrame) {
    /* Check if all data pointers are NULL */
    for (int i = 0; i < AVFrame.AV_NUM_DATA_POINTERS; i++) {
      if (inFrame != null && inFrame.data(i) != null && !inFrame.data(i).isNull()) {
        return true;
      }
    }

    return false;
  }

  EReturnCode ReferencePacket(AVPacket inOutDstPacket, AVPacket inSrcPacket) {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    avRC = avcodec.av_packet_ref(inOutDstPacket, inSrcPacket);
    if (avRC < 0) {
      ReportAVError(
          "CComponentBase::ReferencePacket",
          "av_packet_ref",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
      rc = EReturnCode.FAILED_EXECUTE;
    }

    return rc;
  }

  EReturnCode UnreferencePacket(AVPacket inPacket) {
    EReturnCode rc = EReturnCode.OK;

    avcodec.av_packet_unref(inPacket);

    return rc;
  }

  EReturnCode ReferenceFrame(AVFrame inOutDstFrame, AVFrame inSrcFrame) {
    EReturnCode rc = EReturnCode.OK;
    int avRC;

    avRC = avutil.av_frame_ref(inOutDstFrame, inSrcFrame);
    if (avRC < 0) {
      ReportAVError(
          "CComponentBase::ReferenceFrame",
          "av_frame_ref",
          avRC,
          Thread.currentThread().getStackTrace()[0].getLineNumber());
      rc = EReturnCode.FAILED_EXECUTE;
    }

    return rc;
  }

  EReturnCode UnreferenceFrame(AVFrame inFrame) {
    EReturnCode rc = EReturnCode.OK;

    avutil.av_frame_unref(inFrame);

    return rc;
  }

  EReturnCode ReferencePassThruPacket(AVPacket inSrcPacket) {
    EReturnCode rc = EReturnCode.OK;

    rc = ReferencePacket(PassThruPacket, inSrcPacket);

    return rc;
  }

  EReturnCode UnreferencePassThruPacket() {
    EReturnCode rc = EReturnCode.OK;

    rc = UnreferencePacket(PassThruPacket);

    return rc;
  }

  boolean IsPassThruPacketReferenced() {
    boolean rc = true;

    rc = IsPacketReferenced(PassThruPacket);

    return rc;
  }

  EReturnCode ReferencePassThruFrame(AVFrame inSrcFrame) {
    EReturnCode rc = EReturnCode.OK;

    rc = ReferencePassThruFrame(0, inSrcFrame);

    return rc;
  }

  EReturnCode ReferencePassThruFrame(int Index, AVFrame inSrcFrame) {
    EReturnCode rc = EReturnCode.OK;

    rc = ReferenceFrame(PassThruFrame[Index], inSrcFrame);

    return rc;
  }

  EReturnCode UnreferencePassThruFrame() {
    EReturnCode rc = EReturnCode.OK;

    rc = UnreferencePassThruFrame(0);

    return rc;
  }

  EReturnCode UnreferencePassThruFrame(int Index) {
    EReturnCode rc = EReturnCode.OK;

    rc = UnreferenceFrame(PassThruFrame[Index]);

    return rc;
  }

  boolean IsPassThruFrameReferenced() {
    boolean rc = true;

    rc = IsPassThruFrameReferenced(0);

    return rc;
  }

  boolean IsPassThruFrameReferenced(int Index) {
    boolean rc = true;

    rc = IsFrameReferenced(PassThruFrame[Index]);

    return rc;
  }

  AVPacket GetPassThruPacket() {
    return PassThruPacket;
  }

  AVFrame GetPassThruFrame() {
    return GetPassThruFrame(0);
  }

  AVFrame GetPassThruFrame(int Index) {
    return PassThruFrame[Index];
  }

  EReturnCode Initialize() {
    PassThruPacket = avcodec.av_packet_alloc();

    EReturnCode rc = EReturnCode.OK;

    for (int i = 0; i < VideoConstants.MAX_INPUT_PIPELINES; ++i) {
      PassThruFrame[i] = avutil.av_frame_alloc();
      if (PassThruFrame[i] == null || PassThruFrame[i].isNull()) {
        rc = EReturnCode.FAILED_INITIALIZATION;
        System.err.println("Failed to initialize PassThruFrame #" + i);
        break;
      }
    }
    return rc;
  }
}
