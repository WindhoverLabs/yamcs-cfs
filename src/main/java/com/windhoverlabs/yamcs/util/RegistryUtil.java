package com.windhoverlabs.yamcs.util;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.yamcs.logging.Log;
import org.yaml.snakeyaml.Yaml;

/**
 * Utilities to load configuration, message ids, etc from the cfs registry. While the intent with
 * the registry is to make it format-agnostic, at the moment only YAML is supported.
 *
 * @author lgomez
 */
public class RegistryUtil {

  private static final Log log = new Log(RegistryUtil.class);

  enum MSGType {
    COMMAND,
    TELEMETRY
  }

  /**
   * Load the modules node from the yaml file at yamlPath
   *
   * @param yamlPath
   * @return The loaded yaml. If an error occurs while loading the file, null is returned.
   */
  private static LinkedHashMap<?, ?> loadModulesFromYAML(String yamlPath) {

    Yaml yaml = new Yaml();
    InputStream input = null;
    try {
      input = new FileInputStream(yamlPath);
    } catch (FileNotFoundException e) {
      // TODO Auto-generated catch block
      log.warn("YAML load failed");
    }
    LinkedHashMap<?, ?> registry = (LinkedHashMap<?, ?>) yaml.load(input);
    return (LinkedHashMap<?, ?>) registry.get("modules");
  }

  /**
   * @param modules
   * @param outConfiguration
   */
  private static void getAllConfiguration(
      LinkedHashMap<?, ?> modules, LinkedHashMap<Object, Object> outConfiguration) {
    for (Map.Entry<?, ?> moduleSet : modules.entrySet()) {
      LinkedHashMap<?, ?> module = ((LinkedHashMap<?, ?>) modules.get(moduleSet.getKey()));

      if (module.get("modules") != null) {
        getAllConfiguration((LinkedHashMap<?, ?>) module.get("modules"), outConfiguration);
      }
      if (module.get("config") != null) {
        LinkedHashMap<?, ?> AllConfig = (LinkedHashMap<?, ?>) module.get("config");

        outConfiguration.put(moduleSet.getKey(), AllConfig);
      }
    }
  }

  /**
   * @return All of the configuration from all of the apps/modules stored in the registry in the
   *     following format:
   *     <p>{ cfe_es: { CFE_ES_CDS_MAX_NAME_LENGTH={name=CFE_ES_CDS_MAX_NAME_LENGTH, value=16} },
   *     to: { TO_MAX_MESSAGE_FLOWS={name=TO_MAX_MESSAGE_FLOWS, value=200} } }
   * @throws Exception
   */
  public LinkedHashMap<Object, Object> getAllConfig(String yamlPath) throws Exception {
    LinkedHashMap<Object, Object> outConfigMap = new LinkedHashMap<Object, Object>();

    // Access the registry through the get method for error-checking
    LinkedHashMap<?, ?> wholeRegistry =
        (LinkedHashMap<?, ?>) (LinkedHashMap<?, ?>) loadModulesFromYAML(yamlPath);
    getAllConfiguration(wholeRegistry, outConfigMap);

    return outConfigMap;
  }

  /**
   * @return All of the configuration from the specified app stored in the registry in the following
   *     format:
   *     <p>{ cfe_es: { CFE_ES_CDS_MAX_NAME_LENGTH={name=CFE_ES_CDS_MAX_NAME_LENGTH, value=16} }, }.
   *     If the no configuration exists for the app, null is returned. Not that it is possible for
   *     this function to return
   * @throws Exception
   */
  public static LinkedHashMap<Object, Object> getAllConfigForApp(String yamlPath, String appName) {
    LinkedHashMap<Object, Object> outConfigMap = new LinkedHashMap<Object, Object>();

    LinkedHashMap<?, ?> wholeRegistry =
        (LinkedHashMap<?, ?>) (LinkedHashMap<?, ?>) loadModulesFromYAML(yamlPath);
    getAllConfiguration(wholeRegistry, outConfigMap);

    if (outConfigMap.containsKey(appName)) {
      if (outConfigMap.get(appName) != null) {
        outConfigMap = (LinkedHashMap<Object, Object>) outConfigMap.get(appName);
      } else {
        outConfigMap = null;
      }
    } else {
      outConfigMap = null;
    }

    return outConfigMap;
  }

  private static void getAllTelemetry(
      LinkedHashMap<?, ?> modules, LinkedHashMap<Object, Object> outMsgIds) {
    for (Map.Entry<?, ?> moduleSet : modules.entrySet()) {
      LinkedHashMap<?, ?> module = ((LinkedHashMap<?, ?>) modules.get(moduleSet.getKey()));

      if (module.get("modules") != null) {
        getAllTelemetry((LinkedHashMap<?, ?>) module.get("modules"), outMsgIds);
      }
      if (module.get("telemetry") != null) {
        LinkedHashMap<?, ?> Alltlm = (LinkedHashMap<?, ?>) module.get("telemetry");

        for (Map.Entry<?, ?> tlmSet : Alltlm.entrySet()) {
          LinkedHashMap<Object, Object> tlm =
              (LinkedHashMap<Object, Object>) Alltlm.get(tlmSet.getKey());
          tlm.put("type", MSGType.TELEMETRY);
          tlm.put("macro", tlmSet.getKey());
          tlm.put("app", moduleSet.getKey());

          if (tlm.get("struct") != null) {
            tlm.remove("struct");
          }
          outMsgIds.put(tlmSet.getKey(), tlmSet.getValue());
        }
      }
    }
  }

  private static void getAllTeleCommands(
      LinkedHashMap<?, ?> modules, LinkedHashMap<Object, Object> outCmdMsgIds) {
    for (Map.Entry<?, ?> moduleSet : modules.entrySet()) {
      LinkedHashMap<?, ?> module = ((LinkedHashMap<?, ?>) modules.get(moduleSet.getKey()));

      if (module.get("modules") != null) {
        getAllTeleCommands((LinkedHashMap<?, ?>) module.get("modules"), outCmdMsgIds);
      }
      if (module.get("commands") != null) {
        LinkedHashMap<?, ?> cmds = (LinkedHashMap<?, ?>) module.get("commands");

        for (Map.Entry<?, ?> cmdSet : cmds.entrySet()) {
          LinkedHashMap<Object, Object> cmd =
              (LinkedHashMap<Object, Object>) cmds.get(cmdSet.getKey());

          LinkedHashMap<Object, Object> subCommands =
              (LinkedHashMap<Object, Object>) cmd.get("commands");

          if (subCommands != null) {
            cmd.put("type", MSGType.COMMAND);
            cmd.put("macro", cmdSet.getKey());

            cmd.put("app", moduleSet.getKey());

            for (Map.Entry<?, ?> subCommandSet : subCommands.entrySet()) {
              LinkedHashMap<Object, Object> subCommand =
                  (LinkedHashMap<Object, Object>) subCommands.get(subCommandSet.getKey());

              // Remove the struct node
              if (subCommand.get("struct") != null) {
                subCommand.remove("struct");
              }
            }
            outCmdMsgIds.put(cmdSet.getKey(), cmdSet.getValue());
          }
        }
      }
    }
  }

  /**
   * Get all messages from the registry. This includes commands and telemetry messages.
   *
   * @return A map of messages in the following format: { HK_HK_TLM_MID: { msgID: 2158, type:
   *     TELEMETRY, macro: HK_HK_TLM_MID, app: hk }, HK_SEND_COMBINED_PKT_MID: { msgID: 6256,
   *     commands: {SendCombinedPkt={cc=0}}, type: COMMAND, macro: HK_SEND_COMBINED_PKT_MID, app: hk
   *     } }
   * @throws Exception
   */
  public static LinkedHashMap<Object, Object> getAllMessages(String yamlPath) {
    LinkedHashMap<Object, Object> outCmdMap = new LinkedHashMap<Object, Object>();

    // Access the registry through the get method for error-checking
    LinkedHashMap<?, ?> wholeRegistry = (LinkedHashMap<?, ?>) loadModulesFromYAML(yamlPath);
    getAllTeleCommands(wholeRegistry, outCmdMap);
    getAllTelemetry(wholeRegistry, outCmdMap);

    return outCmdMap;
  }

  /**
   * Get all messages from the registry from a specific app. This includes commands and telemetry
   * messages.
   *
   * @return A map of messages in the following format: { HK_HK_TLM_MID: { msgID: 2158, type:
   *     TELEMETRY, macro: HK_HK_TLM_MID, app: hk }, HK_SEND_COMBINED_PKT_MID: { msgID: 6256,
   *     commands: {SendCombinedPkt={cc=0}}, type: COMMAND, macro: HK_SEND_COMBINED_PKT_MID, app: hk
   *     } }
   * @throws Exception
   */
  public static LinkedHashMap<Object, Object> getAllMessagesForApp(String yamlPath, String appName)
      throws Exception {
    LinkedHashMap<Object, Object> messages = getAllMessages(yamlPath);

    LinkedHashMap<Object, Object> outMesssages = new LinkedHashMap<Object, Object>();

    for (Entry<?, ?> mEntry : messages.entrySet()) {
      LinkedHashMap<Object, Object> msgMap =
          (LinkedHashMap<Object, Object>) messages.get(mEntry.getKey());
      if (appName.equals(msgMap.get("app"))) {

        outMesssages.put(mEntry.getKey(), messages.get(mEntry.getKey()));
      }
    }

    return outMesssages;
  }

  /**
   * Convenience function to map all messages to Macros directly. Very useful for sending messages
   * that have messageIds to clients.
   *
   * @param messages A map that is assumed to be in the format of {@code {
   *     CFE_ES_CMD_MID={msgID=6148, commands={Noop={cc=0}, Reset={cc=1}, Restart={cc=2},
   *     type=COMMAND, macro=CFE_ES_CMD_MID, app=cfe_es}, CFE_ES_HK_TLM_MID={msgID=2063,
   *     type=TELEMETRY, macro=CFE_ES_HK_TLM_MID, app=cfe_es}, CFE_ES_APP_TLM_MID={msgID=2071,
   *     type=TELEMETRY, macro=CFE_ES_APP_TLM_MID, app=cfe_es}, CFE_ES_MEMSTATS_TLM_MID={msgID=2076,
   *     type=TELEMETRY, macro=CFE_ES_MEMSTATS_TLM_MID, app=cfe_es},
   *     CFE_ES_SHELL_TLM_MID={msgID=2075, type=TELEMETRY, macro=CFE_ES_SHELL_TLM_MID, app=cfe_es}}
   *     } }
   * @return A new map in the form of {@code {CFE_ES_CMD_MID=6148, CFE_ES_SEND_HK_MID=6149,
   *     CFE_ES_HK_TLM_MID=2063, CFE_ES_APP_TLM_MID=2071, CFE_ES_MEMSTATS_TLM_MID=2076,
   *     CFE_ES_SHELL_TLM_MID=2075} }
   */
  public static LinkedHashMap<Object, Object> getMacroToMsgIdMap(
      LinkedHashMap<Object, Object> messages) {
    LinkedHashMap<Object, Object> outMap = new LinkedHashMap<Object, Object>();

    for (Entry<?, ?> mEntry : messages.entrySet()) {
      LinkedHashMap<Object, Object> messageMap =
          (LinkedHashMap<Object, Object>) messages.get(mEntry.getKey());
      outMap.put(mEntry.getKey(), messageMap.get("msgID"));
    }

    return outMap;
  }

  /**
   * Convenience function to map all messages to Macros directly. Very useful for sending messages
   * that have messageIds to clients.
   *
   * @param messages A map that is assumed to be in the format of {@code {
   *     CFE_ES_CMD_MID={msgID=6148, commands={Noop={cc=0}, Reset={cc=1}, Restart={cc=2},
   *     type=COMMAND, macro=CFE_ES_CMD_MID, app=cfe_es}, CFE_ES_HK_TLM_MID={msgID=2063,
   *     type=TELEMETRY, macro=CFE_ES_HK_TLM_MID, app=cfe_es}, CFE_ES_APP_TLM_MID={msgID=2071,
   *     type=TELEMETRY, macro=CFE_ES_APP_TLM_MID, app=cfe_es}, CFE_ES_MEMSTATS_TLM_MID={msgID=2076,
   *     type=TELEMETRY, macro=CFE_ES_MEMSTATS_TLM_MID, app=cfe_es},
   *     CFE_ES_SHELL_TLM_MID={msgID=2075, type=TELEMETRY, macro=CFE_ES_SHELL_TLM_MID, app=cfe_es}}
   *     } }
   * @return A new map in the form of {@code {6148=CFE_ES_CMD_MID, 6149=CFE_ES_SEND_HK_MID,
   *     2063=CFE_ES_HK_TLM_MID, 2071=CFE_ES_APP_TLM_MID, 2076=CFE_ES_MEMSTATS_TLM_MID,
   *     2075=CFE_ES_SHELL_TLM_MID} }
   */
  public static LinkedHashMap<Object, Object> getMsgIdMapToMacro(
      LinkedHashMap<Object, Object> messages) {
    LinkedHashMap<Object, Object> outMap = new LinkedHashMap<Object, Object>();

    for (Entry<?, ?> mEntry : messages.entrySet()) {
      LinkedHashMap<Object, Object> messageMap =
          (LinkedHashMap<Object, Object>) messages.get(mEntry.getKey());
      outMap.put(messageMap.get("msgID"), mEntry.getKey());
    }

    return outMap;
  }
}
