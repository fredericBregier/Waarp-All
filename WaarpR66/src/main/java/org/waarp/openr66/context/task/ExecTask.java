/*
 * This file is part of Waarp Project (named also Waarp or GG).
 *
 *  Copyright (c) 2019, Waarp SAS, and individual contributors by the @author
 *  tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 *  All Waarp Project is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 * Waarp . If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.context.task;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.openr66.context.R66Session;
import org.waarp.openr66.context.task.exception.OpenR66RunnerErrorException;
import org.waarp.openr66.context.task.localexec.LocalExecClient;
import org.waarp.openr66.protocol.configuration.Configuration;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

/**
 * Execute an external command
 *
 *
 */
public class ExecTask extends AbstractExecTask {

  /**
   * Internal Logger
   */
  private static final WaarpLogger logger =
      WaarpLoggerFactory.getLogger(ExecTask.class);

  /**
   * @param argRule
   * @param delay
   * @param argTransfer
   * @param session
   */
  public ExecTask(String argRule, int delay, String argTransfer,
                  R66Session session) {
    super(TaskType.EXEC, delay, argRule, argTransfer, session);
  }

  @Override
  public void run() {
    /*
     * First apply all replacements and format to argRule from context and argTransfer. Will call exec (from first
     * element of resulting string) with arguments as the following value from the replacements. Return 0 if OK,
     * else 1 for a warning else as an error. No change should be done in the FILENAME
     */
    logger
        .debug("Exec with " + argRule + ":" + argTransfer + " and {}", session);
    final String finalname = applyTransferSubstitutions(argRule);

    // Check if the execution will be done through LocalExec daemon
    if (Configuration.configuration.isUseLocalExec() && useLocalExec) {
      final LocalExecClient localExecClient = new LocalExecClient();
      if (localExecClient.connect()) {
        localExecClient.runOneCommand(finalname, delay, waitForValidation,
                                      futureCompletion);
        localExecClient.disconnect();
        return;
      } // else continue
    }

    PrepareCommandExec prepareCommandExec =
        new PrepareCommandExec(finalname, true, waitForValidation).invoke();
    if (prepareCommandExec.isError()) {
      return;
    }
    CommandLine commandLine = prepareCommandExec.getCommandLine();
    DefaultExecutor defaultExecutor = prepareCommandExec.getDefaultExecutor();
    PipedInputStream inputStream = prepareCommandExec.getInputStream();
    PipedOutputStream outputStream = prepareCommandExec.getOutputStream();
    PumpStreamHandler pumpStreamHandler =
        prepareCommandExec.getPumpStreamHandler();
    ExecuteWatchdog watchdog = prepareCommandExec.getWatchdog();

    if (!waitForValidation) {
      // Do not wait for validation
      futureCompletion.setSuccess();
      logger.info("Exec will start but no WAIT with {}", commandLine);
    }

    ExecuteCommand executeCommand =
        new ExecuteCommand(commandLine, defaultExecutor, inputStream,
                           outputStream, pumpStreamHandler, null).invoke();
    if (executeCommand.isError()) {
      return;
    }
    int status = executeCommand.getStatus();

    if (defaultExecutor.isFailure(status) && watchdog != null &&
        watchdog.killedProcess()) {
      // kill by the watchdoc (time out)
      logger.error("Exec is in Time Out");
      status = -1;
    }
    if (status == 0) {
      if (waitForValidation) {
        futureCompletion.setSuccess();
      }
      logger.info("Exec OK with {}", commandLine);
    } else if (status == 1) {
      logger.warn("Exec in warning with " + commandLine.toString());
      if (waitForValidation) {
        futureCompletion.setSuccess();
      }
    } else {
      logger.error("Status: " + status + " Exec in error with " +
                   commandLine.toString());
      if (waitForValidation) {
        futureCompletion.cancel();
      }
    }
  }

  @Override
  void finalizeFromError(Runnable threadReader, int status,
                         CommandLine commandLine, Exception e) {
    try {
      Thread.sleep(Configuration.RETRYINMS);
    } catch (final InterruptedException e2) {
    }
    logger.error("Status: " + status + " Exec in error with " + commandLine +
                 " returns " + e.getMessage());
    final OpenR66RunnerErrorException exc = new OpenR66RunnerErrorException(
        "<STATUS>" + status + "</STATUS><ERROR>" + e.getMessage() + "</ERROR>");
    if (waitForValidation) {
      futureCompletion.setFailure(e);
    }
  }
}
