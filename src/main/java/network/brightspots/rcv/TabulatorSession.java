/*
 * Universal RCV Tabulator
 * Copyright (c) 2017-2020 Bright Spots Developers.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Purpose:
 * TabulatorSession contains the high-level flow for tabulation execution:
 * parse config file
 * parse cast vote records
 * tabulate contest
 * output results
 *
 * TabulatorSession also stores state metadata which exists outside tabulation results including:
 * config object, resolved output, and logging paths, tabulation object, and CVR data including
 * precinct codes discovered while parsing CVR files.
 */

package network.brightspots.rcv;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import network.brightspots.rcv.CastVoteRecord.CvrParseException;
import network.brightspots.rcv.ContestConfig.Provider;
import network.brightspots.rcv.ContestConfig.UnrecognizedProviderException;
import network.brightspots.rcv.FileUtils.UnableToCreateDirectoryException;
import network.brightspots.rcv.ResultsWriter.RoundSnapshotDataMissingException;
import network.brightspots.rcv.StreamingCvrReader.CvrDataFormatException;
import network.brightspots.rcv.Tabulator.TabulationCancelledException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.xml.sax.SAXException;

@SuppressWarnings("RedundantSuppression")
class TabulatorSession {

  private final String configPath;
  // precinct IDs discovered during CVR parsing to support testing
  private final Set<String> precinctIds = new HashSet<>();
  private final String timestampString;
  private String outputPath;
  private List<String> convertedFilesWritten;

  TabulatorSession(String configPath) {
    this.configPath = configPath;
    // current date-time formatted as a string used for creating unique output files names
    timestampString = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
  }

  // validation will catch a mismatch and abort anyway, but let's log helpful errors for the CLI
  // here also
  private static void checkConfigVersionMatchesApp(ContestConfig config) {
    String version = config.getRawConfig().tabulatorVersion;

    if (!version.equals(ContestConfig.AUTOMATED_TEST_VERSION)) {
      //noinspection StatementWithEmptyBody
      if (ContestConfigMigration.isConfigVersionNewerThanAppVersion(version)) {
        // It will log a severe message already, so no need to add one here.
      } else if (ContestConfigMigration.isConfigVersionOlderThanAppVersion(version)) {
        Logger.severe(
            "Can't use a config with older version %s in newer version %s of the app! To "
                + "automatically migrate the config to the newer version, load it in the graphical "
                + "version of the app (i.e. don't use the -cli flag when starting the tabulator).",
            version, Main.APP_VERSION);
      }
    }
  }

  // Visible for testing
  @SuppressWarnings("unused")
  String getOutputPath() {
    return outputPath;
  }

  // Visible for testing
  @SuppressWarnings("unused")
  String getTimestampString() {
    return timestampString;
  }

  // Visible for testing
  @SuppressWarnings("unused")
  List<String> getConvertedFilesWritten() {
    return convertedFilesWritten;
  }

  // special mode to just export the CVR as CDF JSON instead of tabulating
  void convertToCdf() {
    ContestConfig config = ContestConfig.loadContestConfig(configPath);
    if (config != null && config.validate()) {
      checkConfigVersionMatchesApp(config);
      try {
        FileUtils.createOutputDirectory(config.getOutputDirectory());
        List<CastVoteRecord> castVoteRecords = parseCastVoteRecords(config, precinctIds);
        if (castVoteRecords == null) {
          Logger.severe("Aborting conversion due to cast vote record errors!");
        } else {
          ResultsWriter writer =
              new ResultsWriter()
                  .setNumRounds(0)
                  .setContestConfig(config)
                  .setTimestampString(timestampString)
                  .setPrecinctIds(precinctIds);
          try {
            writer.generateCdfJson(castVoteRecords);
          } catch (RoundSnapshotDataMissingException exception) {
            // This will never actually happen because no snapshots are involved when you're just
            // translating the input to CDF, not the tabulation results.
          }
        }
      } catch (IOException | UnableToCreateDirectoryException exception) {
        Logger.severe("CDF JSON generation failed.");
      }
    } else {
      Logger.severe("Failed to load config.");
    }
  }

  void tabulate() {
    Logger.info("Starting tabulation session...");
    ContestConfig config = ContestConfig.loadContestConfig(configPath);
    checkConfigVersionMatchesApp(config);
    boolean tabulationSuccess = false;
    //noinspection ConstantConditions
    if (config != null && config.validate() && setUpLogging(config)) {
      Logger.info("Computer name: %s", Utils.getComputerName());
      Logger.info("User name: %s", Utils.getUserName());
      Logger.info("Config file: %s", configPath);
      try {
        Logger.fine("Begin config file contents:");
        BufferedReader reader = new BufferedReader(new FileReader(configPath));
        String line = reader.readLine();
        while (line != null) {
          Logger.fine(line);
          line = reader.readLine();
        }
        Logger.fine("End config file contents.");
        reader.close();
      } catch (IOException exception) {
        Logger.severe("Error logging config file: %s\n%s", configPath, exception);
      }
      Logger.info("Tabulating '%s'...", config.getContestName());
      if (config.isMultiSeatSequentialWinnerTakesAllEnabled()) {
        Logger.info("This is a multi-pass IRV contest.");
        int numWinners = config.getNumberOfWinners();
        // temporarily set config to single-seat so we can run sequential elections
        config.setNumberOfWinners(1);
        while (config.getSequentialWinners().size() < numWinners) {
          Logger.info(
              "Beginning tabulation for seat #%d...", config.getSequentialWinners().size() + 1);
          // Read cast vote records and precinct IDs from CVR files
          List<CastVoteRecord> castVoteRecords = parseCastVoteRecords(config, precinctIds);
          if (castVoteRecords == null) {
            Logger.severe("Aborting tabulation due to cast vote record errors!");
            break;
          }
          Set<String> newWinnerSet;
          try {
            newWinnerSet = runTabulationForConfig(config, castVoteRecords);
          } catch (TabulationCancelledException exception) {
            Logger.severe("Tabulation was cancelled by the user!");
            break;
          }
          assert newWinnerSet.size() == 1;
          String newWinner = (String) newWinnerSet.toArray()[0];
          config.setCandidateExclusionStatus(newWinner, true);
          config.addSequentialWinner(newWinner);
          Logger.info("Tabulation for seat #%d completed.", config.getSequentialWinners().size());
          if (config.getSequentialWinners().size() < numWinners) {
            Logger.info("Excluding %s from the remaining tabulations.", newWinner);
          }
        }
        // revert config to original state
        config.setNumberOfWinners(numWinners);
        for (String winner : config.getSequentialWinners()) {
          config.setCandidateExclusionStatus(winner, false);
        }
        tabulationSuccess = true;
      } else {
        // normal operation (not multi-pass IRV, a.k.a. sequential multi-seat)
        // Read cast vote records and precinct IDs from CVR files
        List<CastVoteRecord> castVoteRecords = parseCastVoteRecords(config, precinctIds);
        if (castVoteRecords == null) {
          Logger.severe("Aborting tabulation due to cast vote record errors!");
        } else {
          try {
            runTabulationForConfig(config, castVoteRecords);
            tabulationSuccess = true;
          } catch (TabulationCancelledException exception) {
            Logger.severe("Tabulation was cancelled by the user!");
          }
        }
      }
      Logger.info("Tabulation session completed.");
      if (tabulationSuccess) {
        Logger.info("Results written to: %s", outputPath);
      }
      Logger.removeTabulationFileLogging();
    }
  }

  private boolean setUpLogging(ContestConfig config) {
    boolean success = false;

    // %g format is for log file naming
    String tabulationLogPath =
        Paths.get(config.getOutputDirectory(), String.format("%s_audit_%%g.log", timestampString))
            .toAbsolutePath()
            .toString();

    // cache outputPath for testing
    outputPath = config.getOutputDirectory();
    try {
      FileUtils.createOutputDirectory(config.getOutputDirectory());
      Logger.addTabulationFileLogging(tabulationLogPath);
      success = true;
    } catch (UnableToCreateDirectoryException | IOException exception) {
      Logger.severe("Failed to configure tabulation logger!\n%s", exception);
    }
    if (!success) {
      Logger.severe("Failed to configure logger!");
    }
    return success;
  }

  // execute tabulation for given ContestConfig (a Session may comprise multiple tabulations)
  // returns: set of winners from tabulation
  private Set<String> runTabulationForConfig(
      ContestConfig config, List<CastVoteRecord> castVoteRecords)
      throws TabulationCancelledException {
    Set<String> winners;
    Tabulator tabulator = new Tabulator(castVoteRecords, config, precinctIds);
    winners = tabulator.tabulate();
    try {
      tabulator.generateSummaryFiles(timestampString);
    } catch (IOException exception) {
      Logger.severe("Error writing summary files:\n%s", exception);
    }
    return winners;
  }

  // parse CVR files referenced in the ContestConfig object into a list of CastVoteRecords
  // param: config object containing CVR file paths to parse
  // param: precinctIds a set of precinct IDs which will be populated during cvr parsing
  // returns: list of parsed CVRs or null if an error was encountered
  private List<CastVoteRecord> parseCastVoteRecords(ContestConfig config, Set<String> precinctIds) {
    Logger.info("Parsing cast vote records...");
    List<CastVoteRecord> castVoteRecords = new ArrayList<>();
    boolean encounteredSourceProblem = false;

    // At each iteration of the following loop, we add records from another source file.
    for (RawContestConfig.CvrSource source : config.rawConfig.cvrFileSources) {
      String cvrPath = config.resolveConfigPath(source.getFilePath());
      Provider provider = ContestConfig.getProvider(source);
      try {
        if (ContestConfig.isCdf(source)) {
          Logger.info("Reading CDF cast vote record file: %s...", cvrPath);
          new CommonDataFormatReader(
              cvrPath, config, source.getContestId(), source.getOvervoteLabel())
              .parseCvrFile(castVoteRecords);
          continue;
        } else if (ContestConfig.getProvider(source) == Provider.CLEAR_BALLOT) {
          Logger.info("Reading Clear Ballot cast vote records from file: %s...", cvrPath);
          new ClearBallotCvrReader(cvrPath, config, source.getUndeclaredWriteInLabel())
              .readCastVoteRecords(castVoteRecords, source.getContestId());
          continue;
        } else if (provider == Provider.DOMINION) {
          Logger.info("Reading Dominion cast vote records from folder: %s...", cvrPath);
          DominionCvrReader reader =
              new DominionCvrReader(config, cvrPath, source.getUndeclaredWriteInLabel());
          reader.readCastVoteRecords(castVoteRecords, source.getContestId());
          // Before we tabulate, we output a converted generic CSV for the CVRs.
          try {
            ResultsWriter writer = new ResultsWriter().setTimestampString(timestampString);
            this.convertedFilesWritten =
                writer.writeGenericCvrCsv(
                    castVoteRecords,
                    reader.getContests().values(),
                    config.getOutputDirectory(),
                    source.getContestId(),
                    source.getUndeclaredWriteInLabel());
          } catch (IOException exception) {
            // error already logged in ResultsWriter
          }
          continue;
        } else if (provider == Provider.ESS) {
          Logger.info("Reading ES&S cast vote record file: %s...", cvrPath);
          new StreamingCvrReader(config, source).parseCvrFile(castVoteRecords, precinctIds);
          continue;
        } else if (provider == Provider.HART) {
          Logger.info("Reading Hart cast vote records from folder: %s...", cvrPath);
          new HartCvrReader(
              cvrPath, source.getContestId(), config, source.getUndeclaredWriteInLabel())
              .readCastVoteRecordsFromFolder(castVoteRecords);
          continue;
        }
        throw new UnrecognizedProviderException();
      } catch (UnrecognizedCandidatesException exception) {
        Logger.severe("Source file contains unrecognized candidate(s): %s", cvrPath);
        // map from name to number of times encountered
        for (String candidate : exception.candidateCounts.keySet()) {
          Logger.severe(
              "Unrecognized candidate \"%s\" appears %d time(s)!",
              candidate, exception.candidateCounts.get(candidate));
        }
        // various incorrect settings can lead to UnrecognizedCandidatesException so it's hard
        // to know exactly what the problem is
        Logger.info(
            "Check config settings for candidate names, firstVoteRowIndex, "
                + "firstVoteColumnIndex, and precinctColumnIndex to make sure they are correct!");
        Logger.info("See config_file_documentation.txt for more details.");
        encounteredSourceProblem = true;
      } catch (IOException exception) {
        Logger.severe("Error opening cast vote record file: %s", cvrPath);
        Logger.info("Check file path and permissions and make sure they are correct!");
        encounteredSourceProblem = true;
      } catch (ParserConfigurationException
          | SAXException
          | OpenXML4JException
          | POIXMLException exception) {
        Logger.severe("Error parsing source file %s", cvrPath);
        Logger.info(
            "ES&S cast vote record files must be Microsoft Excel Workbook "
                + "format.\nStrict Open XML and Open Office are not supported.");
        encounteredSourceProblem = true;
      } catch (CvrDataFormatException exception) {
        Logger.severe("Data format error while parsing source file: %s", cvrPath);
        Logger.info("See the log for details.");
        encounteredSourceProblem = true;
      } catch (UnrecognizedProviderException exception) {
        Logger.severe(
            "Unrecognized provider \"%s\" in source file: %s", source.getProvider(), cvrPath);
        encounteredSourceProblem = true;
      } catch (CvrParseException exception) {
        encounteredSourceProblem = true;
      } catch (Exception exception) {
        Logger.severe("Unexpected error parsing source file: %s\n%s", cvrPath, exception);
        encounteredSourceProblem = true;
      }
    }

    if (encounteredSourceProblem) {
      Logger.severe("Parsing cast vote records failed!");
      castVoteRecords = null;
    } else if (castVoteRecords.isEmpty()) {
      Logger.severe("No cast vote records found!");
      castVoteRecords = null;
    } else {
      Logger.info("Parsed %d cast vote records successfully.", castVoteRecords.size());
    }
    return castVoteRecords;
  }

  static class UnrecognizedCandidatesException extends Exception {

    // count of how many times each unrecognized candidate was encountered during CVR parsing
    final Map<String, Integer> candidateCounts;

    UnrecognizedCandidatesException(Map<String, Integer> candidateCounts) {
      this.candidateCounts = candidateCounts;
    }
  }
}
