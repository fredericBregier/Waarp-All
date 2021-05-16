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

package org.waarp.ftp.client.transaction;

import org.waarp.common.digest.FilesystemBasedDigest.DigestAlgo;
import org.waarp.ftp.client.NullOutputStream;
import org.waarp.ftp.client.WaarpFtpClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * FTP Apache Commons-Net client transaction test
 */
public class FtpApacheClientTransactionTest extends WaarpFtpClient {
  /**
   * @param server
   * @param port
   * @param username
   * @param passwd
   * @param account
   */
  public FtpApacheClientTransactionTest(String server, int port,
                                        String username, String passwd,
                                        String account, int isSsl,
                                        boolean trace) {
    super(server, port, username, passwd, account, false, isSsl, 0, 10000,
          trace);
    final File dir = new File("/tmp/GGFTP/" + username + '/' + account);
    dir.mkdirs();
  }

  /**
   * @param server
   * @param port
   * @param username
   * @param passwd
   * @param account
   */
  public FtpApacheClientTransactionTest(String server, int port,
                                        String username, String passwd,
                                        String account, int isSsl) {
    super(server, port, username, passwd, account, false, isSsl, 0, 10000);
    final File dir = new File("/tmp/GGFTP/" + username + '/' + account);
    dir.mkdirs();
  }

  /**
   * Ask to transfer a file
   *
   * @param local
   * @param remote
   * @param store
   *
   * @return True if the file is correctly transfered
   */
  public boolean transferFile(String local, String remote, boolean store) {
    boolean status = false;
    OutputStream output = null;
    final FileInputStream fileInputStream = null;
    try {
      if (store) {
        status = store(local, remote);
        if (!status) {
          logger.error("Cannot finalize store like operation");
          return false;
        }
        /*
         * if (!this.ftpClient.completePendingCommand()) { System.err.println("Cannot finalize store like operation");
         * return false; }
         */
        String[] results = executeSiteCommand("XCRC " + remote);
        for (final String string : results) {
          logger.info("XCRC: " + string);
        }
        results = executeSiteCommand("XMD5 " + remote);
        for (final String string : results) {
          logger.info("XMD5: " + string);
        }
        results = executeSiteCommand("XSHA1 " + remote);
        for (final String string : results) {
          logger.info("XSHA1: " + string);
        }
        for (DigestAlgo algo : DigestAlgo.values()) {
          results =
              executeSiteCommand("XDIGEST " + algo.algoName + " " + remote);
          for (final String string : results) {
            logger.info("XDIGEST " + algo.algoName + ": " + string);
          }
          results = executeSiteCommand("XDIGEST " + algo.name() + " " + remote);
          for (final String string : results) {
            logger.info("XDIGEST " + algo.algoName + ": " + string);
          }
        }
        results = listFiles();
        for (final String string : results) {
          logger.info("LIST: " + string);
        }
        return true;
      } else {
        output = new NullOutputStream();
        status = retrieve(output, remote);
        output.flush();
        output.close();
        return status;
      }
    } catch (final IOException e) {
      if (output != null) {
        try {
          output.close();
        } catch (final IOException ignored) {
        }
      }
      if (fileInputStream != null) {
        try {
          fileInputStream.close();
        } catch (final IOException ignored) {
        }
      }
      e.printStackTrace();
      return false;
    }
  }

  public boolean deleteFile(String remote) {
    try {
      return ftpClient.deleteFile(remote);
    } catch (final IOException e) {
      e.printStackTrace();
      return false;
    }
  }
}
