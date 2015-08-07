/*
 * Copyright (c) 2012. The Genome Analysis Centre, Norwich, UK
 * MISO project contacts: Robert Davey, Mario Caccamo @ TGAC
 * *********************************************************************
 *
 * This file is part of MISO.
 *
 * MISO is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MISO is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MISO.  If not, see <http://www.gnu.org/licenses/>.
 *
 * *********************************************************************
 */

package uk.ac.bbsrc.tgac.miso.notification.service;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import nki.core.MetrixContainer;
import nki.decorators.MetrixContainerDecorator;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameter;
import org.springframework.integration.Message;
import org.w3c.dom.*;

import uk.ac.bbsrc.tgac.miso.core.util.LimsUtils;
import uk.ac.bbsrc.tgac.miso.core.util.SubmissionUtils;
import uk.ac.bbsrc.tgac.miso.notification.util.NotificationUtils;
import uk.ac.bbsrc.tgac.miso.tools.run.util.FileSetTransformer;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * uk.ac.bbsrc.tgac.miso.notification.util
 * <p/>
 * Transforms relevant Illumina metadata files into a Map to form the payload of a Message
 *
 * @author Rob Davey
 * @date 10-Dec-2010
 * @since 0.1.6
 */
public class IlluminaTransformer implements FileSetTransformer<String, String, File> {
  protected static final Logger log = LoggerFactory.getLogger(IlluminaTransformer.class);
  
  private static final String JSON_RUN_NAME = "runName";
  private static final String JSON_FULL_PATH = "fullPath";
  private static final String JSON_RUN_INFO = "runinfo";
  private static final String JSON_RUN_PARAMS = "runparams";
  private static final String JSON_STATUS = "status";
  private static final String JSON_SEQUENCER_NAME = "sequencerName";
  private static final String JSON_CONTAINER_ID = "containerId";
  private static final String JSON_LANE_COUNT = "laneCount";
  private static final String JSON_NUM_CYCLES = "numCycles";
  private static final String JSON_START_DATE = "startDate";
  private static final String JSON_COMPLETE_DATE = "completionDate";
  
  private static final String STATUS_COMPLETE = "Completed";
  private static final String STATUS_RUNNING = "Running";
  private static final String STATUS_UNKNOWN = "Unknown";
  private static final String STATUS_FAILED = "Failed";

  private Map<String, String> finishedCache = new HashMap<>();

  private final Pattern runCompleteLogPattern = Pattern.compile(
      "(\\d{1,2}\\/\\d{1,2}\\/\\d{4},\\d{2}:\\d{2}:\\d{2})\\.\\d{3},\\d+,\\d+,\\d+,Proce[s||e]sing\\s+completed\\.\\s+Run\\s+has\\s+finished\\."
  );

  private final Pattern lastDateEntryLogPattern = Pattern.compile(
      "(\\d{1,2}\\/\\d{1,2}\\/\\d{4},\\d{2}:\\d{2}:\\d{2})\\.\\d{3},\\d+,\\d+,\\d+,.*"
  );

  private final DateFormat logDateFormat = new SimpleDateFormat("MM'/'dd'/'yyyy','HH:mm:ss");

  public Map<String, String> transform(Message<Set<File>> message) {
    return transform(message.getPayload());
  }

  @Override
  public Map<String, String> transform(Set<File> files) {
    log.info("Processing " + files.size() + " Illumina run directories...");

    int count = 0;

    //TODO modify this to use a JSONObject instead of a Map
    Map<String, JSONArray> map = new HashMap<>();

    map.put(STATUS_RUNNING, new JSONArray());
    map.put(STATUS_COMPLETE, new JSONArray());
    map.put(STATUS_UNKNOWN, new JSONArray());
    map.put(STATUS_FAILED, new JSONArray());

    for (File rootFile : files) {
      count++;
      String countStr = "[#" + count + "/" + files.size() + "] ";
      if (rootFile.isDirectory()) {
        if (rootFile.canRead()) {
          JSONObject run = new JSONObject();
          final File oldStatusFile = new File(rootFile, "/Data/Status.xml");
          final File newStatusFile = new File(rootFile, "/Data/reports/Status.xml");
          final File runInfo = new File(rootFile, "/RunInfo.xml");
          final File runParameters = new File(rootFile, "/runParameters.xml");
          final File completeFile = new File(rootFile, "/Run.completed");

          try {
            boolean readCompleteFilesFound = true;
            boolean failed = false;

            String runName = rootFile.getName();
            log.debug(countStr + "Processing run " + runName);

            if (!finishedCache.keySet().contains(runName)) {
              run.put(JSON_RUN_NAME, runName);
              run.put(JSON_FULL_PATH, rootFile.getCanonicalPath()); //follow symlinks!

              if (!oldStatusFile.exists() && !newStatusFile.exists()) {
                //probably MiSeq/NextSeq
                Boolean lastCycleLogFileExists = false;
                File lastCycleLogFile = null;
                
                Document runInfoDoc = getDocument(runInfo);
                int numReads = 0;
                if (runInfoDoc != null) {
                  run.put(JSON_RUN_INFO, SubmissionUtils.transform(runInfo));
                  checkRunInfo(runInfoDoc, run);
                  if (numReads == 0) {
                    numReads = runInfoDoc.getElementsByTagName("Read").getLength();
                  }

                  int sumCycles = 0;
                  if (run.has(JSON_NUM_CYCLES)) {
                    sumCycles = run.getInt(JSON_NUM_CYCLES);
                  }
                  
                  lastCycleLogFile = new File(rootFile, "/Logs/" + runName + "_Cycle" + sumCycles + "_Log.00.log");
                  if (lastCycleLogFile.exists()) {
                    lastCycleLogFileExists = true;
                  }
                  else {
                    File dir = new File(rootFile, "/Logs/");
                    FileFilter fileFilter = new WildcardFileFilter("*Post Run Step.log");
                    File[] filterFiles = dir.listFiles(fileFilter);
                    if (filterFiles != null && filterFiles.length > 0) {
                      lastCycleLogFileExists = true;
                    }
                    else {
                      File cycleTimeLog = new File(rootFile, "/Logs/CycleTimes.txt");
                      if (cycleTimeLog.exists() && cycleTimeLog.canRead()) {
                        //check last line of CycleTimes.txt
                        Pattern p = Pattern.compile(
                          "(\\d{1,2}\\/\\d{1,2}\\/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+[A-z0-9]+\\s+" + sumCycles + "\\s+End\\s{1}Imaging"
                        );

                        Matcher m = LimsUtils.tailGrep(cycleTimeLog, p, 10);
                        if (m != null && m.groupCount() > 0) {
                          lastCycleLogFileExists = true;
                        }
                      }
                    }
                  }
                }

                Document runParamDoc = getDocument(runParameters);
                if (runParamDoc != null) {
                  run.put(JSON_RUN_PARAMS, SubmissionUtils.transform(runParameters));
                  checkRunParams(runParamDoc, run);
                }
                else if (checkRunParametersXFile(rootFile)) {
                  failed = true;
                }

                checkDates(rootFile, run);

                if (!failed) {
                  failed = checkLogs(rootFile);
                }
                
                readCompleteFilesFound = checkReadCompleteFiles(rootFile, numReads);

                if (readCompleteFilesFound) {
                  if (!new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() && !lastCycleLogFileExists) {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete_ReadX.txt exist but Basecalling_Netcopy_complete.txt doesn't exist and last cycle log file doesn't exist.");
                    if (failed) {
                      log.debug("Run has likely failed.");
                      map.get(STATUS_FAILED).add(run);
                    }
                    else {
                      log.debug("Run is unknown.");
                      map.get(STATUS_UNKNOWN).add(run);
                    }
                  }
                  else if (new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() && !lastCycleLogFileExists) {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete_ReadX.txt exist and Basecalling_Netcopy_complete.txt exists but last cycle log file doesn't exist.");
                    if (failed) {
                      log.debug("Run has likely failed.");
                      map.get(STATUS_FAILED).add(run);
                    }
                    else {
                      log.debug("Run is unknown.");
                      map.get(STATUS_UNKNOWN).add(run);
                    }
                  }
                  else {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete*.txt exist and last cycle log file exists. Run is complete");
                    map.get(STATUS_COMPLETE).add(run);
                  }
                }
                else {
                  if (!completeFile.exists()) {
                    if (!new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() &&
                        (lastCycleLogFile != null && !lastCycleLogFileExists)) {
                      log.debug(runName + " :: A Basecalling_Netcopy_complete_ReadX.txt doesn't exist and last cycle log file doesn't exist.");
                      if (failed) {
                        log.debug("Run has likely failed.");
                        map.get(STATUS_FAILED).add(run);
                      }
                      else {
                        log.debug("Run is not complete.");
                        map.get(STATUS_RUNNING).add(run);
                      }
                    }
                    else {
                      log.debug(runName + " :: Basecalling_Netcopy_complete*.txt don't exist and last cycle log file doesn't exist.");
                      if (failed) {
                        log.debug("Run has likely failed.");
                        map.get(STATUS_FAILED).add(run);
                      }
                      else {
                        log.debug("Run is unknown.");
                        map.get(STATUS_UNKNOWN).add(run);
                      }
                    }
                  }
                  else {
                    log.debug(runName + " :: Basecalling_Netcopy_complete*.txt don't exist and last cycle log file doesn't exist, but RTAComplete.txt exists. Run is complete");
                    map.get(STATUS_COMPLETE).add(run);
                  }
                }
              }
              else if (oldStatusFile.exists()) {
                int numReads = 0;
                Document statusDoc = getDocument(oldStatusFile); 
                if (statusDoc != null) {
                  run.put(JSON_STATUS, SubmissionUtils.transform(oldStatusFile));
                  runName = statusDoc.getElementsByTagName("RunName").item(0).getTextContent();
                  run.put(JSON_RUN_NAME, runName);
                }
                else {
                  run.put(JSON_STATUS, "<error><RunName>" + runName + "</RunName><ErrorMessage>Cannot read status file</ErrorMessage></error>");
                }
                Document runInfoDoc = getDocument(runInfo);
                if (runInfoDoc != null) {
                  run.put(JSON_RUN_INFO, SubmissionUtils.transform(runInfo));
                  checkRunInfo(runInfoDoc, run);
                  if (numReads == 0) {
                    numReads = runInfoDoc.getElementsByTagName("Read").getLength();
                  }
                }
                
                Document runParamDoc = getDocument(runParameters);
                if (runParamDoc != null) {
                  run.put(JSON_RUN_PARAMS, SubmissionUtils.transform(runParameters));
                  checkRunParams(runParamDoc, run);
                }
                else if (checkRunParametersXFile(rootFile)) {
                  failed = true;
                }

                checkDates(rootFile, run);

                if (!failed) {
                  failed = checkLogs(rootFile);
                }

                readCompleteFilesFound = checkReadCompleteFiles(rootFile, numReads);
                
                if (!completeFile.exists()) {
                  if (run.has(JSON_COMPLETE_DATE)) {
                    log.debug(runName + " :: Completed");
                    map.get(STATUS_COMPLETE).add(run);
                  }
                  else {
                    if (failed) {
                      log.debug("Run has likely failed.");
                      map.get(STATUS_FAILED).add(run);
                    }
                    else {
                      log.debug(runName + " :: Running");
                      map.get(STATUS_RUNNING).add(run);
                    }
                  }
                }
                else {
                  log.debug(runName + " :: Completed");
                  map.get(STATUS_COMPLETE).add(run);
                }
              }
              else if (newStatusFile.exists()) {
                int numReads = 0;
                boolean someChecks = false; // TODO: rename this // TODO: default?
                Document statusDoc = getDocument(newStatusFile); 
                if (statusDoc != null) {
                  run.put(JSON_STATUS, SubmissionUtils.transform(newStatusFile));
                  runName = statusDoc.getElementsByTagName("RunName").item(0).getTextContent();
                  run.put(JSON_RUN_NAME, runName);
                  
                  if (statusDoc.getElementsByTagName("NumberOfReads").getLength() != 0) {
                    numReads = new Integer(statusDoc.getElementsByTagName("NumberOfReads").item(0).getTextContent());
                  }
                  
                  if (statusDoc.getElementsByTagName("NumberOfReads").getLength() != 0) {
                    int numCycles = new Integer(statusDoc.getElementsByTagName("NumCycles").item(0).getTextContent());
                    run.put(JSON_NUM_CYCLES, numCycles);
                    
                    if (statusDoc.getElementsByTagName("ImgCycle").getLength() != 0
                        && statusDoc.getElementsByTagName("ScoreCycle").getLength() != 0
                        && statusDoc.getElementsByTagName("CallCycle").getLength() != 0) {
                      int imgCycle = new Integer(statusDoc.getElementsByTagName("ImgCycle").item(0).getTextContent());
                      int scoreCycle = new Integer(statusDoc.getElementsByTagName("ScoreCycle").item(0).getTextContent());
                      int callCycle = new Integer(statusDoc.getElementsByTagName("CallCycle").item(0).getTextContent());
                      someChecks = numCycles != imgCycle || numCycles != scoreCycle || numCycles != callCycle;
                    }
                  }
                }
                else {
                  run.put(JSON_STATUS, "<error><RunName>" + runName + "</RunName><ErrorMessage>Cannot read status file</ErrorMessage></error>");
                }
                
                Document runInfoDoc = getDocument(runInfo);
                if (runInfoDoc != null) {
                  run.put(JSON_RUN_INFO, SubmissionUtils.transform(runInfo));
                  checkRunInfo(runInfoDoc, run);
                  if (numReads == 0) {
                    numReads = runInfoDoc.getElementsByTagName("Read").getLength();
                  }
                }
                
                Document runParamDoc = getDocument(runParameters);
                if (runParamDoc != null) {
                  run.put(JSON_RUN_PARAMS, SubmissionUtils.transform(runParameters));
                  checkRunParams(runParamDoc, run);
                }
                else if (checkRunParametersXFile(rootFile)) {
                  failed = true;
                }

                checkDates(rootFile, run);

                if (!failed) {
                  failed = checkLogs(rootFile);
                }
                
                readCompleteFilesFound = checkReadCompleteFiles(rootFile, numReads);

                if (readCompleteFilesFound) {
                  if (!new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() && someChecks) {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete_ReadX.txt exist but Basecalling_Netcopy_complete.txt doesn't exist and cycles don't match.");
                    if (failed) {
                      log.debug("Run has likely failed.");
                      map.get(STATUS_FAILED).add(run);
                    }
                    else {
                      log.debug("Run is unknown.");
                      map.get(STATUS_UNKNOWN).add(run);
                    }
                  }
                  else if (new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() && someChecks) {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete_ReadX.txt exist and Basecalling_Netcopy_complete.txt exists but cycles don't match.");
                    if (failed) {
                      log.debug("Run has likely failed.");
                      map.get(STATUS_FAILED).add(run);
                    }
                    else {
                      log.debug("Run is unknown.");
                      map.get(STATUS_UNKNOWN).add(run);
                    }
                  }
                  else {
                    log.debug(runName + " :: All Basecalling_Netcopy_complete*.txt exist and cycles match. Run is complete");
                    map.get(STATUS_COMPLETE).add(run);
                  }
                }
                else {
                  if (!completeFile.exists()) {
                    if (!new File(rootFile, "/Basecalling_Netcopy_complete.txt").exists() && someChecks) {
                      log.debug(runName + " :: A Basecalling_Netcopy_complete_ReadX.txt doesn't exist and cycles don't match.");
                      if (failed) {
                        log.debug("Run has likely failed.");
                        map.get(STATUS_FAILED).add(run);
                      }
                      else {
                        log.debug("Run is not complete.");
                        map.get(STATUS_RUNNING).add(run);
                      }
                    }
                    else {
                      log.debug(runName + " :: Basecalling_Netcopy_complete*.txt don't exist and cycles don't match.");
                      if (failed) {
                        log.debug("Run has likely failed.");
                        map.get(STATUS_FAILED).add(run);
                      }
                      else {
                        log.debug("Run is unknown.");
                        map.get(STATUS_UNKNOWN).add(run);
                      }
                    }
                  }
                  else {
                    log.debug(runName + " :: Basecalling_Netcopy_complete*.txt don't exist and cycles don't match, but Run.completed exists. Run is complete");
                    map.get(STATUS_COMPLETE).add(run);
                  }
                }
              }
              else {
                // Should be unreachable
                log.error("Unexpected condition reached examining run "+runName);
              }
            }
            else {
              log.info("Run already scanned. Getting cached version " + runName);

              //if a completed run has been moved (e.g. archived), update the new path
              JSONObject json = JSONObject.fromObject(finishedCache.get(runName));
              if (json.has("fullPath") && !rootFile.getCanonicalPath().equals(json.getString("fullPath"))) {
                log.info("Cached path changed. Updating " + runName);
                json.put("fullPath", rootFile.getCanonicalPath());
                finishedCache.put(runName, json.toString());
              }
              map.get(STATUS_COMPLETE).add(finishedCache.get(runName));
            }
          }
          catch (ParserConfigurationException e) {
            log.error("Error configuring parser: " + e.getMessage());
            e.printStackTrace();
          }
          catch (TransformerException e) {
            log.error("Error transforming XML: " + e.getMessage());
            e.printStackTrace();
          }
          catch (IOException e) {
            log.error("Error with file IO: " + e.getMessage());
            e.printStackTrace();
          }
        }
        else {
          log.error(rootFile.getName() + " :: Permission denied");
        }
      }
    }

    HashMap<String, String> smap = new HashMap<>();
    for (String key : map.keySet()) {
      smap.put(key, map.get(key).toString());

      if (STATUS_COMPLETE.equals(key)) {
        for (JSONObject run : (Iterable<JSONObject>)map.get(key)) {
          if (!finishedCache.keySet().contains(run.getString("runName"))) {
            log.info("Caching completed run " + run.getString("runName"));
            finishedCache.put(run.getString("runName"), run.toString());
          }
        }
      }
    }

    return smap;
  }
  
  /**
   * Checks if an XML file is readable and if so, creates a Document for reading it
   * 
   * @param file The file to read
   * @return The Document if the file is readable; null otherwise
   * @throws ParserConfigurationException
   * @throws TransformerException
   * @throws IOException
   */
  private static Document getDocument(File file) throws ParserConfigurationException, TransformerException, IOException {
    if (!file.exists() || !file.canRead()) return null;
    
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    SubmissionUtils.transform(file, doc);
    return doc;
  }
  
  /**
   * Reads a RunInfo document, looks for total number of cycles, container ID, and lane count, and adds to the run any of these that are 
   * not already included
   * 
   * @param runInfoDoc the RunInfo.xml Document
   * @param run JSON representation of the sequencer run
   * @throws TransformerException
   * @throws IOException
   * @throws ParserConfigurationException
   */
  private void checkRunInfo(Document runInfoDoc, JSONObject run) {
    if (!run.has(JSON_NUM_CYCLES)) {
      int sumCycles = 0;
      NodeList nl = runInfoDoc.getElementsByTagName("Read");
      if (nl.getLength() > 0) {
        for (int i = 0; i < nl.getLength(); i++) {
          Element e = (Element) nl.item(i);
          if (!"".equals(e.getAttributeNS(null, "NumCycles"))) {
            sumCycles += Integer.parseInt(e.getAttributeNS(null, "NumCycles"));
          }
        }
        run.put(JSON_NUM_CYCLES, sumCycles);
      }
    }

    if (!run.has(JSON_SEQUENCER_NAME) && runInfoDoc.getElementsByTagName("Instrument").getLength() != 0) {
      run.put(JSON_SEQUENCER_NAME, runInfoDoc.getElementsByTagName("Instrument").item(0).getTextContent());
    }

    if (runInfoDoc.getElementsByTagName("FlowcellId").getLength() != 0) {
      run.put(JSON_CONTAINER_ID, runInfoDoc.getElementsByTagName("FlowcellId").item(0).getTextContent());
    }
    else if (runInfoDoc.getElementsByTagName("Flowcell").getLength() != 0) {
      run.put(JSON_CONTAINER_ID, runInfoDoc.getElementsByTagName("Flowcell").item(0).getTextContent());
    }

    if (runInfoDoc.getElementsByTagName("FlowcellLayout").getLength() != 0) {
      NamedNodeMap n = runInfoDoc.getElementsByTagName("FlowcellLayout").item(0).getAttributes();
      if (n.getLength() != 0) {
        Node attr = n.getNamedItem("LaneCount");
        if (attr != null) {
          run.put(JSON_LANE_COUNT, attr.getTextContent());
        }
      }
    }
  }
  
  /**
   * Reads the runParameters.xml document, looks for the sequencer name and container ID, and adds to the run any of these that are not 
   * already included
   * 
   * @param runParamDoc the runParameters.xml Document
   * @param run JSON representation of the sequencer run
   * @return true if runParameters.xml is missing, but runParameters.xml* is found, which indicates run failure; false otherwise
   * @throws TransformerException
   * @throws IOException
   * @throws ParserConfigurationException
   */
  private void checkRunParams(Document runParamDoc, JSONObject run) {
    if (!run.has(JSON_SEQUENCER_NAME) && runParamDoc.getElementsByTagName("ScannerID").getLength() != 0) {
      run.put(JSON_SEQUENCER_NAME, runParamDoc.getElementsByTagName("ScannerID").item(0).getTextContent());
    }

    if (!run.has(JSON_CONTAINER_ID) && runParamDoc.getElementsByTagName("Barcode").getLength() != 0) {
      run.put(JSON_CONTAINER_ID, runParamDoc.getElementsByTagName("Barcode").item(0).getTextContent());
    }
  }
  
  /**
   * Looks for a file in the run directory that begins with "runParameters.xml"
   * 
   * @param rootFile run directory
   * @return true if any such files are found; false otherwise
   */
  private boolean checkRunParametersXFile(File rootFile) {
    FileFilter fileFilter = new WildcardFileFilter("runParameters.xml*"); // TODO: what is this looking for?
    File[] filterFiles = rootFile.listFiles(fileFilter);
    if (rootFile.listFiles(fileFilter) != null && filterFiles.length > 0) {
      return true;
    }
    return false;
  }
  
  /**
   * Checks for existance the expected Basecalling_Netcopy_complete_X files for a completed run
   * 
   * @param rootFile run directory
   * @param numReads number of reads
   * @return true if the expected files exist; false otherwise
   */
  private boolean checkReadCompleteFiles(File rootFile, int numReads) {
    if (!new File(rootFile, "/Basecalling_Netcopy_complete_SINGLEREAD.txt").exists()) {
      if (numReads < 1) return false;
      for (int i = 1; i <= numReads; i++) {
        if (!new File(rootFile, "/Basecalling_Netcopy_complete_Read" + (i) + ".txt").exists()
        && !new File(rootFile, "/Basecalling_Netcopy_complete_READ" + (i) + ".txt").exists()) {
          log.debug(rootFile.getName() + " :: No Basecalling_Netcopy_complete_Read" + (i) + ".txt / Basecalling_Netcopy_complete_READ" + (i) + ".txt!");
          return false;
        }
      }
    }
    return true;
  }

  private void checkDates(File rootFile, JSONObject run) throws IOException {
    String runName = run.getString("runName");

    String runDirRegex = "(\\d{6})_[A-z0-9]+_\\d+_[A-z0-9_\\+\\-]*";
    Matcher startMatcher = Pattern.compile(runDirRegex).matcher(runName);
    if (startMatcher.matches()) {
      log.debug(runName + " :: Got start date -> " + startMatcher.group(1));
      run.put(JSON_START_DATE, startMatcher.group(1));
    }

    File cycleTimeLog = new File(rootFile, "/Logs/CycleTimes.txt");
    File rtaLog = new File(rootFile, "/Data/RTALogs/Log.txt");
    File rtaLog2 = new File(rootFile, "/Data/Log.txt");
    File eventsLog = new File(rootFile, "/Events.log");
    File rtaComplete = new File(rootFile, "/RTAComplete.txt");

    if (rtaLog.exists() && rtaLog.canRead()) {
      Matcher m = LimsUtils.tailGrep(rtaLog, runCompleteLogPattern, 10);
      if (m != null && m.groupCount() > 0) {
        log.debug(runName + " :: Got RTALogs Log.txt completion date -> " + m.group(1));
        run.put(JSON_COMPLETE_DATE, m.group(1));
      }
    }
    else if (rtaLog2.exists() && rtaLog2.canRead()) {
      Matcher m = LimsUtils.tailGrep(rtaLog2, runCompleteLogPattern, 10);
      if (m != null && m.groupCount() > 0) {
        log.debug(runName + " :: Got Log.txt completion date -> " + m.group(1));
        run.put(JSON_COMPLETE_DATE, m.group(1));
      }
    }

    if (run.has(JSON_NUM_CYCLES) && cycleTimeLog.exists() && cycleTimeLog.canRead()) {
      int numCycles = run.getInt(JSON_NUM_CYCLES);
      Pattern p = Pattern.compile(
          "(\\d{1,2}\\/\\d{1,2}\\/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+[A-z0-9]+\\s+" + numCycles + "\\s+End\\s{1}Imaging"
      );

      Matcher m = LimsUtils.tailGrep(cycleTimeLog, p, 10);
      if (m != null && m.groupCount() > 0) {
        String cycleDateStr = m.group(1) + "," + m.group(2);
        if (run.has(JSON_COMPLETE_DATE)) {
          log.debug(runName + " :: Checking " + cycleDateStr + " vs. " + run.getString("completionDate"));
          try {
            Date cycleDate = logDateFormat.parse(cycleDateStr);
            Date cDate = logDateFormat.parse(run.getString(JSON_COMPLETE_DATE));

            if (cycleDate.after(cDate)) {
              log.debug(runName + " :: Cycletimes completion date is newer -> " + cycleDateStr);
              run.put(JSON_COMPLETE_DATE, cycleDateStr);
            }
          }
          catch (ParseException e) {
            log.debug(runName + " :: Oops. Can't parse dates. Falling back!");
          }
        }
      }
    }

    if (!run.has(JSON_COMPLETE_DATE)) {
      //attempt to get latest log file entry date
      if (rtaLog.exists() && rtaLog.canRead()) {
        Matcher m = LimsUtils.tailGrep(rtaLog, lastDateEntryLogPattern, 1);
        if (m != null && m.groupCount() > 0) {
          log.debug(runName + " :: Got RTALogs Log.txt last entry date -> " + m.group(1));
          run.put(JSON_COMPLETE_DATE, m.group(1));
        }
      }
      else if (rtaLog2.exists() && rtaLog2.canRead()) {
        Matcher m = LimsUtils.tailGrep(rtaLog2, lastDateEntryLogPattern, 1);
        if (m != null && m.groupCount() > 0) {
          log.debug(runName + " :: Got Log.txt last entry date -> " + m.group(1));
          run.put(JSON_COMPLETE_DATE, m.group(1));
        }
      }

      if (run.has(JSON_NUM_CYCLES) && cycleTimeLog.exists() && cycleTimeLog.canRead()) {
        int numCycles = run.getInt(JSON_NUM_CYCLES);
        Pattern p = Pattern.compile(
            "(\\d{1,2}\\/\\d{1,2}\\/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+[A-z0-9]+\\s+" + numCycles + "\\s+End\\s{1}Imaging"
        );

        Matcher m = LimsUtils.tailGrep(cycleTimeLog, p, 10);
        if (m != null && m.groupCount() > 0) {
          log.debug(runName + " :: Got cycletimes last entry date -> " + m.group(1) + "," + m.group(2));
          String cycleDateStr = m.group(1) + "," + m.group(2);
          if (run.has(JSON_COMPLETE_DATE)) {
            log.debug(runName + " :: Checking " + cycleDateStr + " vs. " + run.getString(JSON_COMPLETE_DATE));
            try {
              Date cycleDate = logDateFormat.parse(cycleDateStr);
              Date cDate = logDateFormat.parse(run.getString(JSON_COMPLETE_DATE));

              if (cycleDate.after(cDate)) {
                log.debug(runName + " :: Cycletimes completion date is newer -> " + cycleDateStr);
                run.put(JSON_COMPLETE_DATE, cycleDateStr);
              }
            }
            catch (ParseException e) {
              log.debug(runName + " :: Oops. Can't parse dates. Falling back!");
            }
          }
        }
      }
    }

    //still nothing? attempt with Events.log
    if (!run.has(JSON_COMPLETE_DATE)) {
      //attempt to get latest log file entry date
      if (eventsLog.exists() && eventsLog.canRead()) {
        log.debug(runName + " :: Checking events log...");
        Pattern p = Pattern.compile(
            "\\.*\\s+(\\d{1,2}\\/\\d{2}\\/\\d{4})\\s+(\\d{1,2}:\\d{2}:\\d{2}).\\d+.*"
        );

        Matcher m = LimsUtils.tailGrep(eventsLog, p, 50);
        if (m != null && m.groupCount() > 0) {
          log.debug(runName + " :: Got last log event date -> " + m.group(1) + "," + m.group(2));
          run.put(JSON_COMPLETE_DATE, m.group(1) + "," + m.group(2));
        }
      }
    }

    // last ditch attempt with RTAComplete.txt
    if (!run.has(JSON_COMPLETE_DATE)) {
      if (rtaComplete.exists() && rtaComplete.canRead()) {
        log.debug(runName + " :: Last ditch attempt. Checking RTAComplete log...");
        Pattern p = Pattern.compile(
            "\\.*(\\d{1,2}\\/\\d{1,2}\\/\\d{4}),(\\d{1,2}:\\d{1,2}:\\d{1,2}).\\d+.*"
        );

        Matcher m = LimsUtils.tailGrep(rtaComplete, p, 2);
        if (m != null && m.groupCount() > 0) {
          log.debug(runName + " :: Got RTAComplete date -> " + m.group(1) + "," + m.group(2));
          run.put(JSON_COMPLETE_DATE, m.group(1) + "," + m.group(2));
        }
      }
    }

    if (!run.has(JSON_COMPLETE_DATE)) {
      run.put(JSON_COMPLETE_DATE, "null");
    }
  }

  private Boolean checkLogs(File rootFile) throws IOException {
    File rtaLogDir = new File(rootFile, "/Data/RTALogs/");
    boolean failed = false;
    if (rtaLogDir.exists()) {
      Pattern p = Pattern.compile(".*(Application\\s{1}exited\\s{1}before\\s{1}completion).*");

      for (File f : rtaLogDir.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return (name.endsWith("Log_00.txt") || name.equals("Log.txt"));
        }
      })) {
        Matcher m = LimsUtils.tailGrep(f, p, 5);
        if (m != null && m.groupCount() > 0) {
          failed = true;
        }
      }
    }
    return failed;
  }

  private JSONObject parseInterOp(File rootFile) throws IOException {
    MetrixContainer mc = new MetrixContainer(rootFile.getAbsolutePath());
    return JSONObject.fromObject(new MetrixContainerDecorator(mc).toJSON().toJSONString());
  }

  public JSONArray transformInterOpOnly(Set<File> files) {
    log.info("Processing InterOp files for " + files.size() + " Illumina run directories...");
    int count = 0;

    JSONArray map = new JSONArray();
    for (File rootFile : files) {
      count++;
      String countStr = "[#" + count + "/" + files.size() + "] ";
      log.info("Processing " + countStr + rootFile.getName());

      JSONObject run = new JSONObject();
      if (rootFile.isDirectory()) {
        if (rootFile.canRead()) {
          try {
            String runName = rootFile.getName();
            if (!finishedCache.keySet().contains(runName)) {
              //parse interop if completed cache doesn't hold this run
              JSONObject metrix = parseInterOp(rootFile);
              if (metrix != null) {
                run.put("metrix", metrix);
              }
              else {
                run.put("error", "Cannot provide metrics - parsing failed.");
              }
              run.put("runName", runName);
              map.add(run);
            }
            else {
              JSONObject cachedRun = JSONObject.fromObject(finishedCache.get(runName));
              if (!cachedRun.has("metrix")) {
                JSONObject metrix = parseInterOp(rootFile);
                if (metrix != null) {
                  run.put("metrix", metrix);
                  cachedRun.put("metrix", metrix);
                  finishedCache.put(runName, cachedRun.toString());
                }
                else {
                  run.put("error", "Cannot provide metrics - parsing failed.");
                }
              }
              else {
                run.put("metrix", cachedRun.get("metrix"));
              }
              run.put("runName", runName);
              map.add(run);
            }
          }
          catch (IOException e) {
            log.error("Error with file IO: " + e.getMessage());
            e.printStackTrace();
          }
        }
        else {
          log.error(rootFile.getName() + " :: Permission denied");
          run.put("runName", rootFile.getName());
          run.put("error", "Cannot read into run directory. Permission denied.");
          map.add(run);
        }
      }
    }

    return map;
  }

  public byte[] transformToJson(Set<File> files) {
    Map<String, String> smap = transform(files);
    JSONObject json = new JSONObject();
    for (String key : smap.keySet()) {
      json.put(key, JSONArray.fromObject(smap.get(key)));
    }
    return (json.toString() + "\r\n").getBytes();
  }

  public Message<Set<String>> runStatusFilesToStringSetMessage(Message<Set<File>> message) {
    Set<File> files = message.getPayload();
    Set<String> xmls = new HashSet<String>();
    for (File f : files) {
      try {
        xmls.add(SubmissionUtils.transform(f));
      }
      catch (TransformerException e) {
        //e.printStackTrace();
        log.error("Error transforming XML: " + e.getMessage());
      }
      catch (IOException e) {
        //e.printStackTrace();
        log.error("Error with file IO: " + e.getMessage());
      }
    }
    return NotificationUtils.buildSimpleMessage(xmls);
  }

  public Message<Set<String>> runCompletedFilesToStringSetMessage(Message<Set<File>> message) {
    Set<File> files = message.getPayload();
    Set<String> runNames = new HashSet<String>();
    String regex = ".*/([\\d]+_[A-z0-9]+_[\\d]+_[A-z0-9_]*)[/]{0,1}.*";
    Pattern p = Pattern.compile(regex);
    for (File f : files) {
      Matcher m = p.matcher(f.getAbsolutePath());
      if (m.matches()) {
        runNames.add(m.group(1));
      }
    }
    return NotificationUtils.buildSimpleMessage(runNames);
  }

  public Set<String> runStatusJobToStringSet(JobExecution exec) {
    Set<String> files = new HashSet<String>();
    for (Map.Entry<String, JobParameter> params : exec.getJobInstance().getJobParameters().getParameters().entrySet()) {
      File f = new File(params.getValue().toString());
      try {
        files.add(SubmissionUtils.transform(f));
      }
      catch (TransformerException e) {
        //e.printStackTrace();
        log.error("Error transforming XML: " + e.getMessage());
      }
      catch (IOException e) {
        //e.printStackTrace();
        log.error("Error with file IO: " + e.getMessage());
      }
    }
    return files;
  }

  public Set<String> runCompletedJobToStringSet(JobExecution exec) {
    Set<String> runNames = new HashSet<String>();
    for (Map.Entry<String, JobParameter> params : exec.getJobInstance().getJobParameters().getParameters().entrySet()) {
      runNames.add(params.getKey());
    }
    return runNames;
  }
}
