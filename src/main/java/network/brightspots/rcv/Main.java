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
 * Main entry point for the RCV module.
 * Parse command line and launch GUI or create and run a tabulation session.
 */

package network.brightspots.rcv;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("WeakerAccess")
public class Main extends GuiApplication {

  public static final String APP_NAME = "Universal RCV Tabulator";
  public static final String APP_VERSION = "1.2.0";

  /**
   * Main entry point to the RCV tabulator program.
   *
   * @param args command-line args
   */
  public static void main(String[] args) {
    System.out.printf("%s version %s%n", APP_NAME, APP_VERSION);
    Logger.setup();
    logSystemInfo();

    // Determine if user intends to use the command-line interface, and gather args if so
    boolean useCli = false;
    List<String> argsCli = new ArrayList<>();
    for (String arg : args) {
      if (!useCli && arg.equals("-cli")) {
        useCli = true;
      } else if (useCli) {
        argsCli.add(arg);
      }
    }

    if (!useCli) {
      // Launch the GUI
      launch(args);
    } else {
      Logger.info("Tabulator is being used via the CLI.");
      // Check for unexpected input
      if (argsCli.size() == 0) {
        Logger.severe(
            "No config file path provided on command line!\n"
                + "Please provide a path to the config file!\n"
                + "See README.md for more details.");
        System.exit(1);
      } else if (argsCli.size() > 2) {
        Logger.severe(
            "Too many arguments! Max is 2 but got: %d\n" + "See README.md for more details.",
            argsCli.size());
        System.exit(2);
      }
      // Path to either: config file for configuring the tabulator, or Dominion JSONs
      String providedPath = argsCli.get(0);
      // Session object will manage the tabulation process
      TabulatorSession session = new TabulatorSession(providedPath);
      if (argsCli.size() == 2 && argsCli.get(1).equals("convert-to-cdf")) {
        session.convertToCdf();
      } else {
        session.tabulate();
      }
    }

    System.exit(0);
  }

  private static void logSystemInfo() {
    Logger.info("Launching %s version %s...", APP_NAME, APP_VERSION);
    Logger.info(
        "Host system: %s version %s",
        System.getProperty("os.name"), System.getProperty("os.version"));
  }
}
